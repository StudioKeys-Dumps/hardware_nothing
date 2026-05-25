package org.aspends.nglyphs.util;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Ogg/Opus comment editor. Patches the OpusTags page of an existing
 * Ogg/Opus file in place to append a Vorbis comment of the form KEY=value
 * (matching the format Nothing's stock ringtones use to carry glyph timelines).
 *
 * The transcoder (MediaMuxer with MUXER_OUTPUT_OGG) writes a valid Ogg/Opus
 * file with an empty OpusTags page; this writer inserts the AUTHOR= comment
 * after the fact, recalculates the page segment table and Ogg CRC32, and
 * splices the page back into the file.
 *
 * The Ogg CRC32 uses polynomial 0x04c11db7 (non-reflected, no XOR-out), which
 * is different from the standard java.util.zip.CRC32.
 */
public final class OggOpusWriter {
    private static final String TAG = "OggOpusWriter";

    private static final byte[] OGG_CAPTURE = {'O', 'g', 'g', 'S'};
    private static final byte[] OPUS_TAGS_MAGIC = {'O', 'p', 'u', 's', 'T', 'a', 'g', 's'};

    private static final int OGG_HEADER_FIXED = 27;
    private static final int CRC_OFFSET = 22;
    private static final int MAX_PAGE_PAYLOAD = 255 * 255;
    private static final byte HEADER_TYPE_CONTINUED = 0x01;
    private static final long GRANULE_CONTINUED = -1L;

    private static final int[] CRC_TABLE = buildCrcTable();

    private OggOpusWriter() {}

    /**
     * Inject a Vorbis comment into the OpusTags page of an Ogg/Opus file.
     * Returns true on success; logs the precise failure mode and returns
     * false on any parse/IO error.
     *
     * Locates the OpusTags packet by searching for its 8-byte magic and
     * walking backwards to the containing page's OggS capture. This is
     * tolerant of layout variation (extra padding pages, multi-segment
     * OpusHead, etc.) — anything the MediaMuxer OGG writer might produce.
     */
    public static boolean addVorbisComment(File oggFile, String key, String value) {
        try {
            byte[] data = readAll(oggFile);

            int magicOff = indexOf(data, OPUS_TAGS_MAGIC);
            if (magicOff < 0) {
                Log.e(TAG, "OpusTags magic not found anywhere in " + oggFile.getName()
                        + " (size=" + data.length + ")");
                return false;
            }
            int tagsPageStart = findContainingPage(data, magicOff);
            if (tagsPageStart < 0) {
                Log.e(TAG, "No OggS capture found before OpusTags magic at " + magicOff);
                return false;
            }

            int hSize;
            int tagsEnd;
            try {
                hSize = headerSize(data, tagsPageStart);
                tagsEnd = pageEnd(data, tagsPageStart);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Malformed OpusTags page at " + tagsPageStart + ": " + e.getMessage());
                return false;
            }
            int payloadOff = tagsPageStart + hSize;
            int payloadLen = tagsEnd - payloadOff;
            if (payloadLen < OPUS_TAGS_MAGIC.length
                    || !startsWith(data, payloadOff, OPUS_TAGS_MAGIC)) {
                Log.e(TAG, "OpusTags magic not at payload start of containing page");
                return false;
            }

            ByteBuffer in = ByteBuffer.wrap(data, payloadOff, payloadLen)
                    .order(ByteOrder.LITTLE_ENDIAN);
            in.position(in.position() + OPUS_TAGS_MAGIC.length);
            byte[] vendor = readLengthPrefixed(in);
            int commentCount = in.getInt();
            List<byte[]> comments = new ArrayList<>(commentCount + 1);
            for (int i = 0; i < commentCount; i++) {
                comments.add(readLengthPrefixed(in));
            }

            comments.add((key + "=" + value).getBytes(StandardCharsets.UTF_8));

            byte[] newPayload = encodeOpusTags(vendor, comments);

            byte[] newPages = buildOpusTagsPages(data, tagsPageStart, newPayload);
            int newPageCount = countPages(newPages);
            int origPageCount = 1;
            int seqShift = newPageCount - origPageCount;

            byte[] suffix = renumberSubsequentPages(data, tagsEnd, seqShift);

            try (RandomAccessFile raf = new RandomAccessFile(oggFile, "rw")) {
                raf.setLength(tagsPageStart + newPages.length + suffix.length);
                raf.seek(tagsPageStart);
                raf.write(newPages);
                raf.write(suffix);
            }
            Log.i(TAG, "Embedded " + key + " into " + oggFile.getName()
                    + " (payload " + newPayload.length + " bytes across "
                    + newPageCount + " page(s), +" + seqShift + " seq shift)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "addVorbisComment failed: " + e.getMessage(), e);
            return false;
        }
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * Given a byte offset somewhere inside an Ogg page payload, walk backwards
     * to find the byte offset of that page's "OggS" capture.
     */
    private static int findContainingPage(byte[] data, int payloadOffset) {
        for (int i = Math.min(payloadOffset, data.length - 4); i >= 0; i--) {
            if (data[i] == OGG_CAPTURE[0]
                    && data[i + 1] == OGG_CAPTURE[1]
                    && data[i + 2] == OGG_CAPTURE[2]
                    && data[i + 3] == OGG_CAPTURE[3]) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] readLengthPrefixed(ByteBuffer in) {
        int len = in.getInt();
        byte[] out = new byte[len];
        in.get(out);
        return out;
    }

    private static byte[] encodeOpusTags(byte[] vendor, List<byte[]> comments) {
        int size = OPUS_TAGS_MAGIC.length + 4 + vendor.length + 4;
        for (byte[] c : comments) size += 4 + c.length;
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.put(OPUS_TAGS_MAGIC);
        out.putInt(vendor.length);
        out.put(vendor);
        out.putInt(comments.size());
        for (byte[] c : comments) {
            out.putInt(c.length);
            out.put(c);
        }
        return out.array();
    }

    /**
     * Build one or more Ogg pages for the rewritten OpusTags packet.
     *
     * The first page reuses the original page's header_type, granule, serial
     * and sequence so OpusHead → OpusTags page-0 numbering is preserved. Any
     * payload spilling past {@link #MAX_PAGE_PAYLOAD} (255 segments × 255
     * bytes) is emitted as a continuation page per Ogg framing spec: the
     * {@code header_type} bit {@code 0x01} marks "continued packet", granule
     * position is -1, and the sequence number increments for each new page.
     */
    private static byte[] buildOpusTagsPages(byte[] data, int oldPageOffset, byte[] payload) {
        byte origHeaderType = data[oldPageOffset + 5];
        long origGranule = readLE64(data, oldPageOffset + 6);
        int serial = readLE32(data, oldPageOffset + 14);
        int startSeq = readLE32(data, oldPageOffset + 18);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        int seq = startSeq;
        boolean isFirst = true;
        int totalLen = payload.length;

        while (offset < totalLen || isFirst) {
            int chunk = Math.min(MAX_PAGE_PAYLOAD, totalLen - offset);
            boolean isLast = (offset + chunk == totalLen);
            int segCount;
            boolean needTerminator;
            if (isLast && chunk % 255 == 0) {
                int candidate = chunk / 255 + 1;
                if (candidate > 255) {
                    chunk -= 255;
                    isLast = false;
                    segCount = chunk / 255;
                    needTerminator = false;
                } else {
                    segCount = candidate;
                    needTerminator = true;
                }
            } else if (chunk % 255 == 0) {
                segCount = chunk / 255;
                needTerminator = false;
            } else {
                segCount = (chunk + 254) / 255;
                needTerminator = false;
            }
            if (segCount > 255) {
                throw new IllegalStateException("segment count overflow: " + segCount);
            }

            int headerLen = OGG_HEADER_FIXED + segCount;
            byte[] page = new byte[headerLen + chunk];
            page[0] = 'O'; page[1] = 'g'; page[2] = 'g'; page[3] = 'S';
            page[4] = 0;
            page[5] = isFirst ? origHeaderType : HEADER_TYPE_CONTINUED;
            writeLE64(page, 6, isFirst ? origGranule : GRANULE_CONTINUED);
            writeLE32(page, 14, serial);
            writeLE32(page, 18, seq);
            writeLE32(page, CRC_OFFSET, 0);
            page[26] = (byte) segCount;

            int dataSegs = needTerminator ? segCount - 1 : segCount;
            int remaining = chunk;
            for (int i = 0; i < dataSegs; i++) {
                int seg = Math.min(255, remaining);
                page[OGG_HEADER_FIXED + i] = (byte) seg;
                remaining -= seg;
            }
            if (needTerminator) {
                page[OGG_HEADER_FIXED + segCount - 1] = 0;
            }

            System.arraycopy(payload, offset, page, headerLen, chunk);

            int crc = oggCrc(page, 0, page.length);
            writeLE32(page, CRC_OFFSET, crc);

            out.write(page, 0, page.length);
            offset += chunk;
            seq++;
            isFirst = false;
        }
        return out.toByteArray();
    }

    /**
     * Copy {@code data[from..end)} and, for every Ogg page in the copy,
     * increment its sequence number by {@code seqShift} and recompute the
     * page CRC. Returns the rewritten suffix.
     */
    private static byte[] renumberSubsequentPages(byte[] data, int from, int seqShift) {
        int len = data.length - from;
        byte[] out = new byte[len];
        System.arraycopy(data, from, out, 0, len);
        if (seqShift == 0) return out;

        int off = 0;
        while (off + OGG_HEADER_FIXED <= out.length) {
            if (!startsWith(out, off, OGG_CAPTURE)) {
                throw new IllegalStateException("not at Ogg page boundary at suffix off " + off);
            }
            int segCount = out[off + 26] & 0xFF;
            if (off + OGG_HEADER_FIXED + segCount > out.length) {
                throw new IllegalStateException("segment table truncated at suffix off " + off);
            }
            int payloadLen = 0;
            for (int i = 0; i < segCount; i++) {
                payloadLen += out[off + OGG_HEADER_FIXED + i] & 0xFF;
            }
            int pageLen = OGG_HEADER_FIXED + segCount + payloadLen;
            if (off + pageLen > out.length) {
                throw new IllegalStateException("page payload truncated at suffix off " + off);
            }

            int seq = readLE32(out, off + 18);
            writeLE32(out, off + 18, seq + seqShift);
            writeLE32(out, off + CRC_OFFSET, 0);
            int crc = oggCrc(out, off, pageLen);
            writeLE32(out, off + CRC_OFFSET, crc);

            off += pageLen;
        }
        return out;
    }

    private static int countPages(byte[] pages) {
        int count = 0;
        int off = 0;
        while (off + OGG_HEADER_FIXED <= pages.length
                && startsWith(pages, off, OGG_CAPTURE)) {
            int segCount = pages[off + 26] & 0xFF;
            int payloadLen = 0;
            for (int i = 0; i < segCount; i++) {
                payloadLen += pages[off + OGG_HEADER_FIXED + i] & 0xFF;
            }
            off += OGG_HEADER_FIXED + segCount + payloadLen;
            count++;
        }
        return count;
    }

    private static int readLE32(byte[] data, int off) {
        return (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }

    private static long readLE64(byte[] data, int off) {
        long lo = readLE32(data, off) & 0xFFFFFFFFL;
        long hi = readLE32(data, off + 4) & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    private static void writeLE32(byte[] data, int off, int v) {
        data[off]     = (byte) (v & 0xFF);
        data[off + 1] = (byte) ((v >>> 8) & 0xFF);
        data[off + 2] = (byte) ((v >>> 16) & 0xFF);
        data[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    private static void writeLE64(byte[] data, int off, long v) {
        for (int i = 0; i < 8; i++) {
            data[off + i] = (byte) ((v >>> (i * 8)) & 0xFF);
        }
    }

    private static int pageEnd(byte[] data, int offset) {
        if (offset + OGG_HEADER_FIXED > data.length) {
            throw new IllegalStateException("page header truncated at " + offset);
        }
        if (!startsWith(data, offset, OGG_CAPTURE)) {
            throw new IllegalStateException("missing OggS capture at " + offset);
        }
        int segCount = data[offset + 26] & 0xFF;
        if (offset + OGG_HEADER_FIXED + segCount > data.length) {
            throw new IllegalStateException("segment table truncated at " + offset);
        }
        int payloadSize = 0;
        for (int i = 0; i < segCount; i++) {
            payloadSize += data[offset + OGG_HEADER_FIXED + i] & 0xFF;
        }
        int end = offset + OGG_HEADER_FIXED + segCount + payloadSize;
        if (end > data.length) {
            throw new IllegalStateException("page payload truncated at " + offset);
        }
        return end;
    }

    private static int headerSize(byte[] data, int offset) {
        return OGG_HEADER_FIXED + (data[offset + 26] & 0xFF);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] pattern) {
        if (offset + pattern.length > data.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) return false;
        }
        return true;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
                ByteArrayOutputStream baos = new ByteArrayOutputStream((int) f.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private static int oggCrc(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
        }
        return crc;
    }

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                if ((r & 0x80000000) != 0) {
                    r = (r << 1) ^ 0x04c11db7;
                } else {
                    r = r << 1;
                }
            }
            table[i] = r;
        }
        return table;
    }
}
