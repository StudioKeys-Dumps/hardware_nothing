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
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.util.OggGlyphEncoder;

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

    private static AudioManager.AudioPlaybackCallback sPlaybackCallback;
    private static boolean sPreviewActive = false;

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

        registerPlaybackWatcher(appContext, handler);
    }

    /**
     * Watch system-wide audio playback configs and react when any other app
     * plays ringtone / notification audio (e.g. the SoundPicker preview).
     * Soundpicker does not touch Settings.System until the user confirms,
     * so the ContentObserver alone cannot cover the preview step.
     */
    private static void registerPlaybackWatcher(Context appContext, Handler handler) {
        if (sPlaybackCallback != null) return;

        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        int ownUid = android.os.Process.myUid();

        sPlaybackCallback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                boolean preview = false;
                if (configs != null) {
                    for (AudioPlaybackConfiguration c : configs) {
                        AudioAttributes attr = c.getAudioAttributes();
                        if (attr == null) continue;
                        int usage = attr.getUsage();
                        if (usage != AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                                && usage != AudioAttributes.USAGE_NOTIFICATION) {
                            continue;
                        }
                        int clientUid = safeClientUid(c);
                        if (clientUid == ownUid) {
                            continue;
                        }
                        preview = true;
                        break;
                    }
                }
                Log.i(TAG, "onPlaybackConfigChanged preview=" + preview);
                if (preview) {
                    sPreviewActive = true;
                    activateTemporaryVisualizer(appContext);
                } else if (sPreviewActive) {
                    sPreviewActive = false;
                    stopTemporaryVisualizer(appContext);
                }
            }
        };
        am.registerAudioPlaybackCallback(sPlaybackCallback, handler);
        Log.i(TAG, "AudioPlaybackCallback registered for ringtone preview detection.");
    }

    /**
     * AudioPlaybackConfiguration.getClientUid is @SystemApi; fall back to
     * reflection when the linker cannot resolve it directly.
     */
    private static int safeClientUid(AudioPlaybackConfiguration c) {
        try {
            return (int) AudioPlaybackConfiguration.class
                    .getMethod("getClientUid")
                    .invoke(c);
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
    private static Runnable sVisualizerStopRunnable;
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

                org.aspends.nglyphs.core.GlyphEffects.run(
                        oggName, brightness, v, context, streamType, false);

                if (sGlyphPreviewStopRunnable != null) {
                    sVisualizerHandler.removeCallbacks(sGlyphPreviewStopRunnable);
                }
                long timeout = (toneType == RingtoneManager.TYPE_RINGTONE)
                        ? GLYPH_PREVIEW_RING_MS
                        : GLYPH_PREVIEW_NOTIF_MS;
                sGlyphPreviewStopRunnable = () -> {
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

    /**
     * Temporarily start the AudioVisualizerService so glyphs react to ringtone
     * preview audio playing in Settings or other apps.
     */
    private static void activateTemporaryVisualizer(Context context) {
        // Start the visualizer service
        try {
            context.startService(new Intent(context, AudioVisualizerService.class));
        } catch (Exception e) {
            Log.w(TAG, "Could not start AudioVisualizerService", e);
            return;
        }
        AudioVisualizerService.setRingtonePreviewActive(true);

        // Cancel any pending stop
        if (sVisualizerStopRunnable != null) {
            sVisualizerHandler.removeCallbacks(sVisualizerStopRunnable);
        }

        // Schedule stop after 30 seconds
        sVisualizerStopRunnable = () -> {
            AudioVisualizerService.setRingtonePreviewActive(false);
            // Only stop the service if the user hasn't enabled it manually
            SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), Context.MODE_PRIVATE);
            if (!prefs.getBoolean("music_visualizer_enabled", false)) {
                context.stopService(new Intent(context, AudioVisualizerService.class));
            }
            Log.i(TAG, "Temporary visualizer deactivated");
        };
        sVisualizerHandler.postDelayed(sVisualizerStopRunnable, 30_000L);
        Log.i(TAG, "Temporary visualizer activated for ringtone preview");
    }

    /** Tear the temporary visualizer down immediately. */
    private static void stopTemporaryVisualizer(Context context) {
        if (sVisualizerStopRunnable != null) {
            sVisualizerHandler.removeCallbacks(sVisualizerStopRunnable);
            sVisualizerStopRunnable = null;
        }
        AudioVisualizerService.setRingtonePreviewActive(false);
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), Context.MODE_PRIVATE);
            if (!prefs.getBoolean("music_visualizer_enabled", false)) {
                context.stopService(new Intent(context, AudioVisualizerService.class));
            }
        } catch (Exception e) {
            Log.w(TAG, "stopTemporaryVisualizer failed", e);
        }
        Log.i(TAG, "Temporary visualizer stopped (preview ended)");
    }

    private static void handleToneChanged(Context context, int toneType) {
        // Activate visualizer so glyphs react to the ringtone preview audio
        activateTemporaryVisualizer(context);

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
        String csvFileName = csvName + ".csv";
        int idx = findAssetIndex(context, assetFolder, csvFileName);

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
                org.aspends.nglyphs.core.GlyphEffects.play(
                        context, assetFolder, csvName, v, brightness);

                if (sGlyphPreviewStopRunnable != null) {
                    sVisualizerHandler.removeCallbacks(sGlyphPreviewStopRunnable);
                }
                long timeout = (toneType == RingtoneManager.TYPE_RINGTONE)
                        ? GLYPH_PREVIEW_RING_MS
                        : GLYPH_PREVIEW_NOTIF_MS;
                sGlyphPreviewStopRunnable = () -> {
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
     * No stock match — copy the URI to a temp OGG, run FFT encoding, copy the generated
     * CSV to {@code custom_ringtones/}, then save preferences.
     */
    private static void handleCustomTone(
            Context context,
            Uri toneUri,
            String baseName,
            String valKey,
            String idxKey,
            int toneType) {
        new Thread(() -> {
            try {
                // 1. Copy URI content to a temp file, preserving original format
                File cacheDir = context.getCacheDir();
                long ts = System.currentTimeMillis();
                File tempInput = new File(cacheDir, "ringtone_sync_input_" + ts);
                copyUriToFile(context, toneUri, tempInput);

                if (!tempInput.exists() || tempInput.length() == 0) {
                    Log.e(TAG, "Temp input file is empty or missing after copy");
                    cleanupFile(tempInput);
                    return;
                }
                Log.i(TAG, "Copied ringtone to temp: " + tempInput.length() + " bytes");

                // 2. Define output OGG alongside a generated CSV
                File tempOutput = new File(cacheDir, "ringtone_sync_output_" + ts + ".ogg");

                // 3. Run OggGlyphEncoder to produce the companion .csv
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

                // Wait for encoding to finish (it runs synchronously in convert() but future-proof)
                synchronized (lock) {
                    if (!success[0]) {
                        lock.wait(30_000L); // 30-second safety timeout
                    }
                }

                if (!success[0]) {
                    Log.e(TAG, "Encoding did not succeed; skipping prefs update.");
                    cleanupFile(tempInput);
                    cleanupFile(tempOutput);
                    return;
                }

                // 4. The encoder writes a .csv alongside tempOutput
                String outputCsvName = tempOutput.getName().replace(".ogg", ".csv");
                File generatedCsv = new File(tempOutput.getParent(), outputCsvName);

                if (!generatedCsv.exists()) {
                    Log.e(TAG, "Generated CSV not found: " + generatedCsv);
                    cleanupFile(tempInput);
                    cleanupFile(tempOutput);
                    return;
                }

                // 5. Persist both OGG and CSV so GlyphEffects can resolve the style
                //    via CustomRingtoneManager.getCustomRingtoneFile (filters by .ogg).
                String safeBase = sanitizeFileName(baseName);
                String destOggName = safeBase + ".ogg";
                String destCsvName = safeBase + ".csv";
                File destDir = new File(context.getFilesDir(), CUSTOM_RINGTONES_DIR);
                if (!destDir.exists()) {
                    destDir.mkdirs();
                }
                File destOgg = new File(destDir, destOggName);
                File destCsv = new File(destDir, destCsvName);
                copyFile(tempOutput, destOgg);
                copyFile(generatedCsv, destCsv);

                SharedPreferences prefs =
                        context.getSharedPreferences(context.getString(R.string.pref_file),
                                Context.MODE_PRIVATE);
                prefs.edit().putString(valKey, destOggName).apply();

                Log.i(TAG, "Custom tone encoded and saved as: " + destOggName);
                broadcastSynced(context, toneType);

                // 6. Light the freshly generated pattern so the user can see it
                //    (Settings already plays the audio).
                playGlyphPreview(context, destOggName, toneType);

                cleanupFile(tempInput);
                cleanupFile(tempOutput);
                cleanupFile(generatedCsv);

            } catch (Exception e) {
                Log.e(TAG, "Custom tone handling failed", e);
            }
        }, "RingtoneSyncEncoder").start();
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

    /**
     * Finds the index of {@code csvFileName} (e.g. "Abra.csv") in the sorted list of
     * {@code *.csv} files under the given assets sub-folder.
     *
     * @return zero-based index, or -1 if not found
     */
    private static int findAssetIndex(Context context, String assetFolder, String csvFileName) {
        try {
            String[] files = context.getAssets().list(assetFolder);
            if (files == null) {
                return -1;
            }
            // Collect only CSV entries, sort them to match the order used by StyleSelectionActivity
            java.util.List<String> csvFiles = new java.util.ArrayList<>();
            for (String f : files) {
                if (f.endsWith(".csv")) {
                    csvFiles.add(f);
                }
            }
            Collections.sort(csvFiles);

            for (int i = 0; i < csvFiles.size(); i++) {
                if (csvFiles.get(i).equalsIgnoreCase(csvFileName)) {
                    return i;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to list assets/" + assetFolder, e);
        }
        return -1;
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
