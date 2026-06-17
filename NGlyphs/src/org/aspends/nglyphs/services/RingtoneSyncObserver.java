package org.aspends.nglyphs.services;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.util.AssetStyleUtils;
import org.aspends.nglyphs.util.OggGlyphEncoder;
import org.aspends.nglyphs.util.OggMetadataParser;

/**
 * Observes system ringtone and notification sound changes, then auto-selects
 * or auto-generates a matching Glyph CSV pattern and saves it to SharedPreferences.
 *
 * <p>Register once at app startup via {@link #register(Context)}. Use
 * {@link #setSelfUpdating(int, boolean)} before any programmatic tone change to suppress
 * the resulting observer callback.
 */
public final class RingtoneSyncObserver {
    private static final String TAG = "RingtoneSyncObserver";

    /** Broadcast action fired after a ringtone sync completes. */
    public static final String ACTION_RINGTONE_SYNCED = "org.aspends.nglyphs.ACTION_RINGTONE_SYNCED";

    /** Extra key indicating which tone type was synced (RingtoneManager.TYPE_*). */
    public static final String EXTRA_TONE_TYPE = "tone_type";

    private static final long DEBOUNCE_MS = 150L;
    private static final String CUSTOM_RINGTONES_DIR = "custom_ringtones";

    // Loop guards — set these before any programmatic ringtone change so the observer
    // ignores the resulting Settings.System notification.
    private static final AtomicBoolean sSelfUpdatingRingtone = new AtomicBoolean(false);
    private static final AtomicBoolean sSelfUpdatingNotification = new AtomicBoolean(false);

    private static boolean sRegistered = false;

    // Tracks whether a glyph preview launched by handleToneChanged / handleStockMatch
    // is still on the LEDs. Used by sExternalRingtoneCallback to bail out of a
    // stale preview the moment the user starts previewing a different tone in the
    // system Sound picker.
    private static volatile boolean sPreviewActive = false;
    private static volatile long sPreviewArmTime = 0L;
    private static AudioManager.AudioPlaybackCallback sExternalRingtoneCallback;

    /** Window after a preview starts during which the callback ignores external
     *  ringtone audio — that audio IS the system playing the tone whose change
     *  triggered the preview, and shouldn't be treated as "external". */
    private static final long PREVIEW_ARM_DEBOUNCE_MS = 750L;

    // -------------------------------------------------------------------------
    // Stock tone maps  (lowercase key → exact CSV base name without extension)
    // -------------------------------------------------------------------------

    private static final Map<String, String> STOCK_RINGTONES;
    private static final Map<String, String> STOCK_NOTIFICATIONS;

    static {
        Map<String, String> calls = new HashMap<>();
        calls.put("abra", "Abra");
        calls.put("beetle", "Beetle");
        calls.put("bug", "Bug");
        calls.put("burrow", "Burrow");
        calls.put("flutter", "Flutter");
        calls.put("forever", "Forever");
        calls.put("karha", "Karha");
        calls.put("latency", "Latency");
        calls.put("molitor", "Molitor");
        calls.put("pepelu", "Pepelu");
        calls.put("pet", "Pet");
        calls.put("plot", "Plot");
        calls.put("pneumatic", "Pneumatic");
        calls.put("radiate", "Radiate");
        calls.put("scribble", "Scribble");
        calls.put("snaps", "Snaps");
        calls.put("squirrels", "Squirrels");
        calls.put("tennis", "Tennis");
        calls.put("woo_yeh", "Woo_Yeh");
        calls.put("wow", "Wow");
        STOCK_RINGTONES = Collections.unmodifiableMap(calls);

        Map<String, String> notifs = new HashMap<>();
        notifs.put("beak", "Beak");
        notifs.put("bulb_one", "Bulb_One");
        notifs.put("bulb_two", "Bulb_Two");
        notifs.put("cough", "Cough");
        notifs.put("fox", "Fox");
        notifs.put("gamma", "Gamma");
        notifs.put("gargle", "Gargle");
        notifs.put("guiro", "Guiro");
        notifs.put("nope", "Nope");
        notifs.put("oi", "Oi");
        notifs.put("pep", "Pep");
        notifs.put("simmer", "Simmer");
        notifs.put("skim", "Skim");
        notifs.put("squiggle", "Squiggle");
        notifs.put("volley", "Volley");
        notifs.put("why", "Why");
        notifs.put("woo", "Woo");
        notifs.put("yeh", "Yeh");
        notifs.put("zip", "Zip");
        STOCK_NOTIFICATIONS = Collections.unmodifiableMap(notifs);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    private RingtoneSyncObserver() {}

    /**
     * Sets the self-update guard for the given ringtone type.
     *
     * <p>Call this <em>before</em> any programmatic ringtone change so the resulting
     * Settings.System change notification is silently ignored.
     *
     * @param type      {@link RingtoneManager#TYPE_RINGTONE} or
     *                  {@link RingtoneManager#TYPE_NOTIFICATION}
     * @param updating  {@code true} to arm the guard, {@code false} to clear it
     */
    public static void setSelfUpdating(int type, boolean updating) {
        if (type == RingtoneManager.TYPE_RINGTONE) {
            sSelfUpdatingRingtone.set(updating);
        } else if (type == RingtoneManager.TYPE_NOTIFICATION) {
            sSelfUpdatingNotification.set(updating);
        }
    }

    /**
     * Registers ContentObservers for the system ringtone and notification sound.
     * Safe to call multiple times — subsequent calls are no-ops.
     *
     * @param appContext application-level Context (must not be an Activity context)
     */
    public static void register(Context appContext) {
        if (sRegistered) {
            return;
        }
        sRegistered = true;

        ContentResolver resolver = appContext.getContentResolver();
        Handler handler = new Handler(Looper.getMainLooper());

        Uri ringtoneUri = Settings.System.getUriFor(Settings.System.RINGTONE);
        Uri notifUri = Settings.System.getUriFor(Settings.System.NOTIFICATION_SOUND);

        Log.i(TAG, "Registering observers: ringtone=" + ringtoneUri + " notif=" + notifUri);

        resolver.registerContentObserver(
                ringtoneUri,
                true,
                new ToneObserver(appContext, handler, RingtoneManager.TYPE_RINGTONE));

        resolver.registerContentObserver(
                notifUri,
                true,
                new ToneObserver(appContext, handler, RingtoneManager.TYPE_NOTIFICATION));

        // Also observe the broader Settings.System content URI as fallback
        resolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (uri == null) return;
                        String uriStr = uri.toString();
                        Log.i(TAG, "Settings.System change detected: " + uri);
                        if (uriStr.contains("ringtone")) {
                            new ToneObserver(appContext, handler, RingtoneManager.TYPE_RINGTONE)
                                    .onChange(selfChange);
                        } else if (uriStr.contains("notification_sound")) {
                            new ToneObserver(appContext, handler, RingtoneManager.TYPE_NOTIFICATION)
                                    .onChange(selfChange);
                        }
                    }
                });

        Log.i(TAG, "ContentObservers registered for ringtone and notification sound.");

        registerExternalRingtoneStop(appContext, handler);
    }

    /**
     * Registers an AudioPlaybackCallback whose only job is to stop a stale
     * NGlyphs glyph preview when the system Sound picker (or anything else)
     * starts playing another ringtone/notification audio. Crucially this
     * callback never STARTS anything — it does not drive the visualizer or
     * launch a new preview. The arm-debounce keeps it from killing a preview
     * still ramping up off the same tone change that triggered it.
     */
    private static void registerExternalRingtoneStop(Context appContext, Handler handler) {
        if (sExternalRingtoneCallback != null) return;
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int ownUid = android.os.Process.myUid();
        sExternalRingtoneCallback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                if (!sPreviewActive || configs == null) return;
                if (SystemClock.elapsedRealtime() - sPreviewArmTime < PREVIEW_ARM_DEBOUNCE_MS) {
                    return;
                }
                for (AudioPlaybackConfiguration c : configs) {
                    AudioAttributes attr = c.getAudioAttributes();
                    if (attr == null) continue;
                    int usage = attr.getUsage();
                    if (usage != AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                            && usage != AudioAttributes.USAGE_NOTIFICATION) {
                        continue;
                    }
                    if (safeClientUid(c) == ownUid) continue;
                    sPreviewActive = false;
                    if (sGlyphPreviewStopRunnable != null) {
                        sVisualizerHandler.removeCallbacks(sGlyphPreviewStopRunnable);
                        sGlyphPreviewStopRunnable = null;
                    }
                    try {
                        org.aspends.nglyphs.core.GlyphEffects.stopCustomRingtone();
                    } catch (Exception ignored) {
                    }
                    Log.i(TAG, "Preview cancelled — external ringtone audio detected");
                    return;
                }
            }
        };
        try {
            am.registerAudioPlaybackCallback(sExternalRingtoneCallback, handler);
        } catch (Exception e) {
            Log.w(TAG, "registerAudioPlaybackCallback failed", e);
            sExternalRingtoneCallback = null;
        }
    }

    private static int safeClientUid(AudioPlaybackConfiguration c) {
        try {
            return (int) AudioPlaybackConfiguration.class.getMethod("getClientUid").invoke(c);
        } catch (Throwable t) {
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Internal observer
    // -------------------------------------------------------------------------

    private static final class ToneObserver extends ContentObserver {
        private final Context mContext;
        private final Handler mHandler;
        private final int mToneType;
        private Runnable mPendingRunnable;

        ToneObserver(Context context, Handler handler, int toneType) {
            super(handler);
            mContext = context.getApplicationContext();
            mHandler = handler;
            mToneType = toneType;
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            // Check loop guard
            if (mToneType == RingtoneManager.TYPE_RINGTONE) {
                if (sSelfUpdatingRingtone.compareAndSet(true, false)) {
                    Log.i(TAG, "Ringtone: skipping self-triggered change.");
                    return;
                }
            } else {
                if (sSelfUpdatingNotification.compareAndSet(true, false)) {
                    Log.i(TAG, "Notification: skipping self-triggered change.");
                    return;
                }
            }

            // Debounce: cancel any pending work and schedule fresh
            if (mPendingRunnable != null) {
                mHandler.removeCallbacks(mPendingRunnable);
            }
            mPendingRunnable = () -> handleToneChanged(mContext, mToneType);
            mHandler.postDelayed(mPendingRunnable, DEBOUNCE_MS);
        }
    }

    // -------------------------------------------------------------------------
    // Core sync logic
    // -------------------------------------------------------------------------

    private static final Handler sVisualizerHandler = new Handler(Looper.getMainLooper());
    private static Runnable sGlyphPreviewStopRunnable;
    private static final long GLYPH_PREVIEW_RING_MS = 10_000L;
    private static final long GLYPH_PREVIEW_NOTIF_MS = 3_000L;

    /** Play the just-generated glyph timeline on the lights (no audio). */
    private static void playGlyphPreview(Context context, String oggName, int toneType) {
        sVisualizerHandler.post(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(
                        context.getString(R.string.pref_file), Context.MODE_PRIVATE);
                int brightness = prefs.getInt("brightness", 2048);
                android.os.Vibrator v =
                        (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                int streamType = (toneType == RingtoneManager.TYPE_RINGTONE)
                        ? android.media.AudioManager.STREAM_RING
                        : android.media.AudioManager.STREAM_NOTIFICATION;

                // Cancel any preview from a previous tone pick so a new selection
                // replaces it cleanly instead of overlapping.
                org.aspends.nglyphs.core.GlyphEffects.stopCustomRingtone();
                sPreviewActive = true;
                sPreviewArmTime = SystemClock.elapsedRealtime();
                org.aspends.nglyphs.core.GlyphEffects.run(
                        oggName, brightness, v, context, streamType, false);

                if (sGlyphPreviewStopRunnable != null) {
                    sVisualizerHandler.removeCallbacks(sGlyphPreviewStopRunnable);
                }
                long timeout = (toneType == RingtoneManager.TYPE_RINGTONE)
                        ? GLYPH_PREVIEW_RING_MS
                        : GLYPH_PREVIEW_NOTIF_MS;
                sGlyphPreviewStopRunnable = () -> {
                    sPreviewActive = false;
                    try {
                        org.aspends.nglyphs.core.GlyphEffects.stopCustomRingtone();
                    } catch (Exception ignored) {}
                };
                sVisualizerHandler.postDelayed(sGlyphPreviewStopRunnable, timeout);
            } catch (Exception e) {
                Log.w(TAG, "playGlyphPreview failed", e);
            }
        });
    }

    private static void handleToneChanged(Context context, int toneType) {
        Uri toneUri = RingtoneManager.getActualDefaultRingtoneUri(context, toneType);
        if (toneUri == null) {
            Log.w(TAG, "Could not resolve URI for tone type " + toneType);
            return;
        }

        String baseName = extractBaseName(context, toneUri);
        if (baseName == null || baseName.isEmpty()) {
            Log.w(TAG, "Could not determine tone file name from URI: " + toneUri);
            return;
        }

        boolean isRingtone = (toneType == RingtoneManager.TYPE_RINGTONE);
        Map<String, String> stockMap = isRingtone ? STOCK_RINGTONES : STOCK_NOTIFICATIONS;
        String assetFolder = isRingtone ? "call" : "notification";
        String valKey = isRingtone ? "call_style_value" : "glyph_blink_style";
        String idxKey = isRingtone ? "call_style_idx" : "glyph_blink_style_idx";

        String lookupKey = baseName.toLowerCase();
        String matchedCsvName = stockMap.get(lookupKey);

        if (matchedCsvName != null) {
            handleStockMatch(context, matchedCsvName, assetFolder, valKey, idxKey, toneType);
        } else {
            handleCustomTone(context, toneUri, baseName, valKey, idxKey, toneType);
        }
    }

    /** Stock tone matched — resolve index in sorted asset list and save preferences. */
    private static void handleStockMatch(
            Context context,
            String csvName,
            String assetFolder,
            String valKey,
            String idxKey,
            int toneType) {
        // loadStyleNames() stores values without extension, so save just the base name
        int idx = AssetStyleUtils.findStyleIndex(context, assetFolder, csvName);

        SharedPreferences prefs =
                context.getSharedPreferences(context.getString(R.string.pref_file),
                        Context.MODE_PRIVATE);
        prefs.edit().putString(valKey, csvName).putInt(idxKey, Math.max(0, idx)).apply();

        Log.i(TAG, "Synced stock tone: " + csvName + " at index " + idx);
        broadcastSynced(context, toneType);
        playStockGlyphPreview(context, assetFolder, csvName, toneType);
    }

    /** Light a stock CSV pattern on the glyphs for a brief preview. */
    private static void playStockGlyphPreview(
            Context context, String assetFolder, String csvName, int toneType) {
        sVisualizerHandler.post(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(
                        context.getString(R.string.pref_file), Context.MODE_PRIVATE);
                int brightness = prefs.getInt("brightness", 2048);
                android.os.Vibrator v =
                        (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                // Force-cancel any in-flight preview. play() refuses to interrupt an
                // active STREAM_RING future on its own, which leaves the previous
                // custom ringtone's glyph looping when the user picks a stock tone.
                org.aspends.nglyphs.core.GlyphEffects.stopCustomRingtone();
                sPreviewActive = true;
                sPreviewArmTime = SystemClock.elapsedRealtime();
                org.aspends.nglyphs.core.GlyphEffects.play(
                        context, assetFolder, csvName, v, brightness);

                if (sGlyphPreviewStopRunnable != null) {
                    sVisualizerHandler.removeCallbacks(sGlyphPreviewStopRunnable);
                }
                long timeout = (toneType == RingtoneManager.TYPE_RINGTONE)
                        ? GLYPH_PREVIEW_RING_MS
                        : GLYPH_PREVIEW_NOTIF_MS;
                sGlyphPreviewStopRunnable = () -> {
                    sPreviewActive = false;
                    try {
                        org.aspends.nglyphs.core.GlyphEffects.stopCustomRingtone();
                    } catch (Exception ignored) {}
                };
                sVisualizerHandler.postDelayed(sGlyphPreviewStopRunnable, timeout);
            } catch (Exception e) {
                Log.w(TAG, "playStockGlyphPreview failed", e);
            }
        });
    }

    /**
     * No stock-asset match. Resolve the URI, then prefer an authored glyph timeline
     * embedded in the OGG's Vorbis comments (AUTHOR=/CUSTOM1=) over a generic FFT
     * re-encoding. Only fall back to {@link OggGlyphEncoder} when the source carries
     * no embedded timeline.
     */
    private static void handleCustomTone(
            Context context,
            Uri toneUri,
            String baseName,
            String valKey,
            String idxKey,
            int toneType) {
        new Thread(() -> {
            File tempInput = null;
            File tempOutput = null;
            try {
                File cacheDir = context.getCacheDir();
                long ts = System.currentTimeMillis();
                tempInput = new File(cacheDir, "ringtone_sync_input_" + ts);
                copyUriToFile(context, toneUri, tempInput);

                if (!tempInput.exists() || tempInput.length() == 0) {
                    Log.e(TAG, "Temp input file is empty or missing after copy");
                    return;
                }
                Log.i(TAG, "Copied ringtone to temp: " + tempInput.length() + " bytes");

                String safeBase = sanitizeFileName(baseName);
                String destOggName = safeBase + ".ogg";
                File destDir = new File(context.getFilesDir(), CUSTOM_RINGTONES_DIR);
                if (!destDir.exists()) {
                    destDir.mkdirs();
                }
                File destOgg = new File(destDir, destOggName);

                // Prefer an authored timeline embedded in the source OGG.
                String embedded = OggMetadataParser.extractGlyphTimeline(tempInput);
                if (embedded != null && !embedded.isEmpty()) {
                    copyFile(tempInput, destOgg);
                    persistAndPreview(context, valKey, destOggName, toneType,
                            "Authored timeline imported");
                    return;
                }

                // No embedded timeline — fall back to FFT encoding. The encoder produces
                // a real Ogg/Opus with the timeline written as a Vorbis AUTHOR= comment,
                // so executeCustomRingtone can read it back the same way it does for
                // authored stock tones.
                tempOutput = new File(cacheDir, "ringtone_sync_output_" + ts + ".ogg");

                final boolean[] success = {false};
                final Object lock = new Object();
                OggGlyphEncoder encoder = new OggGlyphEncoder(context);
                encoder.convert(tempInput, tempOutput, false, new OggGlyphEncoder.ProgressListener() {
                    @Override
                    public void onProgress(String status) {
                        Log.i(TAG, "Encoding: " + status);
                    }

                    @Override
                    public void onFinished(File result) {
                        success[0] = true;
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Encoding error: " + error);
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                });

                synchronized (lock) {
                    if (!success[0]) {
                        lock.wait(30_000L);
                    }
                }

                if (!success[0]) {
                    Log.e(TAG, "Encoding did not succeed; skipping prefs update.");
                    return;
                }

                copyFile(tempOutput, destOgg);
                persistAndPreview(context, valKey, destOggName, toneType,
                        "Custom tone encoded");
            } catch (Exception e) {
                Log.e(TAG, "Custom tone handling failed", e);
            } finally {
                cleanupFile(tempInput);
                cleanupFile(tempOutput);
            }
        }, "RingtoneSyncEncoder").start();
    }

    private static void persistAndPreview(
            Context context, String valKey, String destOggName, int toneType, String reason) {
        SharedPreferences prefs = context.getSharedPreferences(
                context.getString(R.string.pref_file), Context.MODE_PRIVATE);
        prefs.edit().putString(valKey, destOggName).apply();
        Log.i(TAG, reason + ": " + destOggName);
        broadcastSynced(context, toneType);
        playGlyphPreview(context, destOggName, toneType);
    }


    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts a clean base name (no extension) from a ringtone URI.
     * Tries MediaStore DISPLAY_NAME cursor first; falls back to path segment.
     */
    private static String extractBaseName(Context context, Uri uri) {
        // Try cursor with DISPLAY_NAME
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int colIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (colIdx != -1) {
                    String displayName = cursor.getString(colIdx);
                    if (displayName != null && !displayName.isEmpty()) {
                        return stripExtension(displayName);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "DISPLAY_NAME cursor failed, falling back to path.", e);
        }

        // Fallback: last path segment
        String path = uri.getLastPathSegment();
        if (path != null && !path.isEmpty()) {
            // Strip any leading directory components
            int slashIdx = path.lastIndexOf('/');
            if (slashIdx >= 0) {
                path = path.substring(slashIdx + 1);
            }
            return stripExtension(path);
        }

        return null;
    }

    private static String stripExtension(String name) {
        int dotIdx = name.lastIndexOf('.');
        return (dotIdx > 0) ? name.substring(0, dotIdx) : name;
    }

    private static void copyUriToFile(Context context, Uri uri, File dest) throws Exception {
        try (InputStream is = context.getContentResolver().openInputStream(uri);
                OutputStream os = new FileOutputStream(dest)) {
            if (is == null) {
                throw new IllegalStateException("Cannot open input stream for URI: " + uri);
            }
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
        }
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (InputStream is = new java.io.FileInputStream(src);
                OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
        }
    }

    private static void cleanupFile(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Replaces characters that are unsafe in filenames with underscores. */
    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static void broadcastSynced(Context context, int toneType) {
        Intent intent = new Intent(ACTION_RINGTONE_SYNCED);
        intent.putExtra(EXTRA_TONE_TYPE, toneType);
        context.sendBroadcast(intent);
    }
}
