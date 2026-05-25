package org.aspends.nglyphs.util;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import org.aspends.nglyphs.core.GlyphManagerV2;

/**
 * Encodes an arbitrary input audio file into a real Ogg/Opus container with
 * a Vorbis "AUTHOR=" comment carrying the glyph timeline (base64 of zlib of
 * the per-frame CSV). The output is playable by Android's Ringtone/MediaPlayer
 * stack and self-describing — OggMetadataParser reads the same AUTHOR tag back.
 */
public class OggGlyphEncoder {
    private static final String TAG = "OggGlyphEncoder";

    private static final int FFT_SIZE = 1024;
    private static final int OPUS_OUTPUT_RATE = 48_000;
    private static final int OPUS_BITRATE = 96_000;
    private static final int OPUS_FRAME_SAMPLES = 960; // 20 ms at 48 kHz
    private static final String OPUS_MIME = "audio/opus";
    private static final long CODEC_TIMEOUT_US = 10_000L;

    private final Context context;

    public interface ProgressListener {
        void onProgress(String status);
        void onFinished(File result);
        void onError(String error);
    }

    public OggGlyphEncoder(Context context) {
        this.context = context;
    }

    public void convert(File input, File output, boolean isExtended, ProgressListener listener) {
        try {
            listener.onProgress("Decoding audio...");
            AudioSource source = decodeToPcm(input);

            listener.onProgress("Analyzing waveform...");
            List<int[]> frames = analyseFrames(source.pcm, isExtended);

            listener.onProgress("Encoding Opus stream...");
            short[] resampled = resampleTo48k(source.pcm, source.sampleRate, source.channelCount);
            transcodeToOpus(resampled, source.channelCount, output);

            listener.onProgress("Embedding glyph timeline...");
            String csv = framesToCsv(frames);
            String authorTag = Base64.encodeToString(
                    deflate(csv.getBytes("UTF-8")), Base64.NO_WRAP);
            if (!OggOpusWriter.addVorbisComment(output, "AUTHOR", authorTag)) {
                listener.onError("Failed to embed glyph timeline into OGG");
                return;
            }

            listener.onFinished(output);
        } catch (Exception e) {
            Log.e(TAG, "Conversion failed", e);
            listener.onError(e.getMessage());
        }
    }

    private static final class AudioSource {
        final short[] pcm;
        final int sampleRate;
        final int channelCount;

        AudioSource(short[] pcm, int sampleRate, int channelCount) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }
    }

    private static AudioSource decodeToPcm(File input) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(input.getAbsolutePath());

            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0) throw new IllegalStateException("No audio track");
            extractor.selectTrack(trackIndex);

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = format.getString(MediaFormat.KEY_MIME);

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            try {
                while (true) {
                    if (!inputEos) {
                        int inIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                        if (inIdx >= 0) {
                            ByteBuffer ib = codec.getInputBuffer(inIdx);
                            int n = extractor.readSampleData(ib, 0);
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEos = true;
                            } else {
                                codec.queueInputBuffer(
                                        inIdx, 0, n, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }

                    int outIdx = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (outIdx >= 0) {
                        if (info.size > 0) {
                            ByteBuffer ob = codec.getOutputBuffer(outIdx);
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            byte[] chunk = new byte[info.size];
                            ob.get(chunk);
                            pcm.write(chunk);
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    }
                }
            } finally {
                codec.stop();
                codec.release();
            }

            byte[] pcmBytes = pcm.toByteArray();
            short[] pcmShorts = new short[pcmBytes.length / 2];
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmShorts);
            return new AudioSource(pcmShorts, sampleRate, channelCount);
        } finally {
            extractor.release();
        }
    }

    private static List<int[]> analyseFrames(short[] pcm, boolean isExtended) {
        int numZones = isExtended ? 15 : 5;
        double[] buffer = new double[FFT_SIZE];
        List<double[]> raw = new ArrayList<>();
        int ptr = 0;
        for (int i = 0; i < pcm.length; i++) {
            buffer[ptr++] = pcm[i] / 32768.0;
            if (ptr == FFT_SIZE) {
                raw.add(bandMagnitudes(buffer, numZones));
                ptr = 0;
            }
        }

        // Per-clip peak normalisation: scale so the loudest band in the whole
        // recording maps to MAX_BRIGHTNESS. Without this, the existing *500
        // multiplier saturated every loud frame to the same value and the LEDs
        // showed up as a uniform dim glow regardless of audio content.
        double peak = 0;
        for (double[] frame : raw) {
            for (double v : frame) {
                if (v > peak) peak = v;
            }
        }
        double scale =
                peak > 0 ? (double) GlyphManagerV2.MAX_BRIGHTNESS / peak : 0;

        List<int[]> result = new ArrayList<>(raw.size());
        for (double[] frame : raw) {
            int[] out = new int[numZones];
            for (int j = 0; j < numZones; j++) {
                int v = (int) Math.round(frame[j] * scale);
                if (v < 0) v = 0;
                if (v > GlyphManagerV2.MAX_BRIGHTNESS) v = GlyphManagerV2.MAX_BRIGHTNESS;
                out[j] = v;
            }
            result.add(out);
        }
        return result;
    }

    private static double[] bandMagnitudes(double[] buffer, int numZones) {
        double[] re = buffer.clone();
        double[] im = new double[buffer.length];
        FFT.fft(re, im);

        int half = buffer.length / 2;
        double[] intensities = new double[numZones];
        for (int i = 0; i < numZones; i++) {
            int startIdx = i * half / numZones;
            int endIdx = (i + 1) * half / numZones;
            double max = 0;
            for (int k = startIdx; k < endIdx; k++) {
                double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                if (mag > max) max = mag;
            }
            intensities[i] = max;
        }
        return intensities;
    }

    private static String framesToCsv(List<int[]> frames) {
        StringBuilder sb = new StringBuilder();
        for (int[] frame : frames) {
            for (int i = 0; i < frame.length; i++) {
                sb.append(frame[i]);
                if (i < frame.length - 1) sb.append(",");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static byte[] deflate(byte[] src) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(src);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length / 4);
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }

    /**
     * Linear-interpolation resampler to {@value #OPUS_OUTPUT_RATE} Hz. Quality
     * is adequate for ringtones; the Opus encoder smooths most remaining
     * harshness.
     */
    private static short[] resampleTo48k(short[] in, int inRate, int channels) {
        if (inRate == OPUS_OUTPUT_RATE || inRate <= 0) return in;
        int inFrames = in.length / channels;
        if (inFrames < 2) return in;
        double ratio = (double) OPUS_OUTPUT_RATE / inRate;
        int outFrames = (int) (inFrames * ratio);
        short[] out = new short[outFrames * channels];
        for (int i = 0; i < outFrames; i++) {
            double srcPos = i / ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;
            int next = Math.min(srcIdx + 1, inFrames - 1);
            for (int ch = 0; ch < channels; ch++) {
                short s0 = in[srcIdx * channels + ch];
                short s1 = in[next * channels + ch];
                out[i * channels + ch] = (short) (s0 + (s1 - s0) * frac);
            }
        }
        return out;
    }

    private void transcodeToOpus(short[] pcm, int channels, File output) throws Exception {
        MediaFormat opusFormat = MediaFormat.createAudioFormat(
                OPUS_MIME, OPUS_OUTPUT_RATE, channels);
        opusFormat.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE);
        opusFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,
                android.media.AudioFormat.ENCODING_PCM_16BIT);

        MediaCodec encoder = MediaCodec.createEncoderByType(OPUS_MIME);
        encoder.configure(opusFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaMuxer muxer = new MediaMuxer(
                output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
        int muxerTrack = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int pcmPos = 0;
        boolean inputDone = false;
        long ptsUs = 0;

        try {
            while (true) {
                if (!inputDone) {
                    int inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer ib = encoder.getInputBuffer(inIdx);
                        ib.clear();
                        int remainingFrames = (pcm.length / channels) - pcmPos;
                        int chunkFrames = Math.min(remainingFrames, OPUS_FRAME_SAMPLES);
                        if (chunkFrames <= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            ShortBuffer sb = ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            sb.put(pcm, pcmPos * channels, chunkFrames * channels);
                            int byteCount = chunkFrames * channels * 2;
                            encoder.queueInputBuffer(inIdx, 0, byteCount, ptsUs, 0);
                            pcmPos += chunkFrames;
                            ptsUs += (long) chunkFrames * 1_000_000L / OPUS_OUTPUT_RATE;
                        }
                    }
                }

                int outIdx = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IllegalStateException("encoder format changed twice");
                    }
                    muxerTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outIdx >= 0) {
                    ByteBuffer ob = encoder.getOutputBuffer(outIdx);
                    if (info.size > 0 && muxerStarted) {
                        muxer.writeSampleData(muxerTrack, ob, info);
                    }
                    encoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            try { encoder.stop(); } catch (Exception ignored) {}
            encoder.release();
            if (muxerStarted) {
                try { muxer.stop(); } catch (Exception ignored) {}
            }
            muxer.release();
        }
    }
}
