package org.aspends.nglyphs.services;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphEffects;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.util.AppListCache;
import org.aspends.nglyphs.util.SleepGuard;

public class GlyphNotificationListener
        extends NotificationListenerService implements SensorEventListener {
    public static GlyphNotificationListener instance;

    private final Set<String> ignoredEssentialKeys = new HashSet<>();
    private final ConcurrentHashMap<String, StatusBarNotification> localActiveEssentials =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastBlinkTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPackageTriggerTimes =
            new ConcurrentHashMap<>();
    private BroadcastReceiver unlockReceiver;
    private SharedPreferences prefs;
    private Runnable pendingScreenOffUpdate;
    private android.app.KeyguardManager.KeyguardLockedStateListener keyguardListener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = this::runEssentialHeartbeat;
    private final ConcurrentHashMap<String, Integer> activeProgressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> activeRawProgressMap = new ConcurrentHashMap<>();

    private MediaSessionManager mediaSessionManager;
    private final Map<MediaSession.Token, MediaController.Callback> mediaCallbacks =
            new ConcurrentHashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::updateMediaSessions;
    private final Runnable smoothProgressRunnable = this::updateSmoothProgress;
    private MediaController activeController = null;
    private boolean isMusicActive = false;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long lastMovementTime = SystemClock.elapsedRealtime();
    private boolean isBatterySaverActive = false;
    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes
    private float[] lastGravity = new float[3];
    private static final float MOVEMENT_THRESHOLD = 0.5f;

    private int watchdogLastValue = -1;
    private final Map<String, Long> sourceLastChangeMap = new ConcurrentHashMap<>();
    private static final long WATCHDOG_TIMEOUT_MS = 5000;
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            boolean removedAny = false;

            for (Map.Entry<String, Long> entry : sourceLastChangeMap.entrySet()) {
                if (now - entry.getValue() > WATCHDOG_TIMEOUT_MS) {
                    activeProgressMap.remove(entry.getKey());
                    sourceLastChangeMap.remove(entry.getKey());
                    removedAny = true;
                }
            }

            if (removedAny) {
                triggerHighestProgress();
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable essentialDebounceRunnable = new Runnable() {
        @Override
        public void run() {
            performEssentialLightUpdate();
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications != null) {
                localActiveEssentials.clear();
                activeProgressMap.clear();
                for (StatusBarNotification sbn : activeNotifications) {
                    localActiveEssentials.put(sbn.getKey(), sbn);
                }
            }
        } catch (Exception e) {
        }
        updateEssentialLightState();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        loadIgnoredKeys();

        // Register ringtone sync observer early — this service starts automatically
        RingtoneSyncObserver.register(getApplicationContext());

        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(action)
                        || "android.intent.action.USER_UNLOCKED".equals(action)) {
                    lastMovementTime =
                            SystemClock.elapsedRealtime(); // Reset idle timer for battery saver
                    if (pendingScreenOffUpdate != null) {
                        handler.removeCallbacks(pendingScreenOffUpdate);
                        pendingScreenOffUpdate = null;
                    }
                    refreshLocalActiveEssentials();
                    if (prefs.getBoolean("essential_lights_unlock", false)) {
                        performIgnoreLogic();
                    }
                    updateEssentialLightState();
                    handler.postDelayed(() -> updateEssentialLightState(), 400);
                    handler.postDelayed(() -> updateEssentialLightState(), 1000);
                    handler.postDelayed(() -> updateEssentialLightState(), 2000);
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    if (pendingScreenOffUpdate != null) {
                        handler.removeCallbacks(pendingScreenOffUpdate);
                    }
                    pendingScreenOffUpdate = () -> {
                        pendingScreenOffUpdate = null;
                        updateEssentialLightState();
                    };
                    handler.postDelayed(pendingScreenOffUpdate, 1000);
                    startHeartbeat();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    stopHeartbeat();
                    updateEssentialLightState();
                } else if (FlipToGlyphService.ACTION_REFRESH_ESSENTIAL.equals(intent.getAction())) {
                    loadIgnoredKeys();
                    updateEssentialLightState();
                    triggerHighestProgress();
                } else if (AnimationManager.ACTION_GLYPH_FREE.equals(intent.getAction())) {
                    // System signaled that a high-priority animation finished.
                    // Attempt to restore any pending progress bar.
                    triggerHighestProgress();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction("android.intent.action.USER_UNLOCKED");
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL);
        filter.addAction(AnimationManager.ACTION_GLYPH_FREE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(unlockReceiver, filter);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                keyguardListener = locked -> {
                    if (!locked) {
                        refreshLocalActiveEssentials();
                        updateEssentialLightState();
                    }
                };
                try {
                    km.addKeyguardLockedStateListener(handler::post, keyguardListener);
                } catch (Exception e) {
                    keyguardListener = null;
                }
            }
        }

        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (mediaSessionManager != null) {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener,
                    new ComponentName(this, GlyphNotificationListener.class), handler);
            updateMediaSessions(mediaSessionManager.getActiveSessions(
                    new ComponentName(this, GlyphNotificationListener.class)));
        }

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        handler.post(watchdogRunnable);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterSensor();
        if (unlockReceiver != null)
            unregisterReceiver(unlockReceiver);
        if (keyguardListener != null) {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                try {
                    km.removeKeyguardLockedStateListener(keyguardListener);
                } catch (Exception ignored) {
                }
            }
            keyguardListener = null;
        }
        if (mediaSessionManager != null)
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        clearMediaCallbacks();
        handler.removeCallbacksAndMessages(null);
        if (instance == this)
            instance = null;
    }

    private void refreshLocalActiveEssentials() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            localActiveEssentials.clear();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    localActiveEssentials.put(sbn.getKey(), sbn);
                }
            }
            trimIgnoredEssentialKeys();
        } catch (Exception ignored) {
        }
    }

    private synchronized void trimIgnoredEssentialKeys() {
        if (ignoredEssentialKeys.isEmpty()) return;
        boolean changed = ignoredEssentialKeys.retainAll(localActiveEssentials.keySet());
        if (changed) saveIgnoredKeys();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null)
            return;
        Notification n = sbn.getNotification();
        if (n == null)
            return;

        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        if (!prefs.getBoolean("master_allow", false))
            return;

        // Auto-discover and persist channel IDs as they arrive
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            String cid = n.getChannelId();
            if (cid != null) {
                org.aspends.nglyphs.util.RootNotificationHelper.persistDiscovery(
                        this, sbn.getPackageName(), cid);
            }
        }

        Set<String> essentialApps = prefs.getStringSet("essential_apps", new HashSet<>());
        String pkgName = sbn.getPackageName();
        boolean isPackageEssential = essentialApps.contains(pkgName);

        boolean isEssentialApp = false;
        if (isPackageEssential) {
            Set<String> selectedChannels =
                    prefs.getStringSet("essential_channels_" + pkgName, new HashSet<>());
            // If specific channels are selected, only those are essential.
            // If the set IS EMPTY, it means ALL channels for this app are essential.
            if (selectedChannels.isEmpty()
                    || selectedChannels.contains(n != null ? n.getChannelId() : "")) {
                isEssentialApp = true;
            }
        }

        if (n != null && Notification.CATEGORY_MISSED_CALL.equals(n.category)
                && prefs.getBoolean("essential_missed_call", false)) {
            isEssentialApp = true;
        }

        if (n != null
                && (Notification.CATEGORY_CALL.equals(n.category)
                        || "call".equalsIgnoreCase(n.category))) {
            return; // Dialer/Telecom events are handled by the service/receiver
        }

        // Explicitly ignore known dialer apps (except missed calls)
        boolean isMissedCallCategory =
                n != null && Notification.CATEGORY_MISSED_CALL.equals(n.category);
        if (!isMissedCallCategory
                && (pkgName.contains("dialer") || pkgName.contains("telecom")
                        || pkgName.contains("phone"))) {
            return;
        }

        // BLOCK notification glyphs if a call is currently active (using our internal
        // flag)
        if (FlipToGlyphService.ringingActive) {
            return;
        }

        if (n != null) {
            Bundle extras = n.extras;
            int progress = extras.getInt(Notification.EXTRA_PROGRESS, -1);
            int progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1);
            boolean hasProgress = progressMax > 0 && progress >= 0;

            boolean isOngoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0
                    || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
            boolean isProgressOrMedia = Notification.CATEGORY_PROGRESS.equals(n.category)
                    || Notification.CATEGORY_SERVICE.equals(n.category)
                    || Notification.CATEGORY_TRANSPORT.equals(n.category)
                    || Notification.CATEGORY_STATUS.equals(n.category)
                    || Notification.CATEGORY_SYSTEM.equals(n.category);

            if (isOngoing || isProgressOrMedia || hasProgress) {
                String template = extras.getString(Notification.EXTRA_TEMPLATE);

                // --- ENHANCED DOWNLOAD DETECTION (Part 1) ---
                if (!hasProgress) {
                    // Fallback to text parsing (REGEX) if extras are zero or missing
                    String text = "";
                    if (extras.getCharSequence(Notification.EXTRA_TEXT) != null)
                        text += extras.getCharSequence(Notification.EXTRA_TEXT).toString();
                    if (extras.getCharSequence(Notification.EXTRA_TITLE) != null)
                        text += " " + extras.getCharSequence(Notification.EXTRA_TITLE).toString();

                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d{1,3})%");
                    java.util.regex.Matcher m = p.matcher(text);
                    if (m.find()) {
                        progress = Integer.parseInt(m.group(1));
                        progressMax = 100;
                        hasProgress = true;
                        android.util.Log.d(
                                "GlyphNotification", "Regex Progress: " + progress + "%");
                    }
                }

                boolean indeterminate =
                        extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
                boolean isProgressEvent = hasProgress || indeterminate;
                // -----------------------------------

                if (isProgressEvent && prefs.getBoolean("glyph_progress_enabled", false)) {
                    int percent = 0;
                    if (hasProgress && progressMax > 0) {
                        percent = (int) ((progress / (float) progressMax) * 100);
                    } else if (indeterminate) {
                        percent = 50; // Visual placeholder for indeterminate
                    }

                    onProgressDetected("NOTIFICATION", sbn.getPackageName(), percent, progress,
                            progressMax, sbn.getKey());
                }
                if (!isEssentialApp)
                    return;
            }
        }

        localActiveEssentials.put(sbn.getKey(), sbn);
        updateEssentialLightState();

        handler.postDelayed(this::updateEssentialLightState, 1000);
        handler.postDelayed(this::updateEssentialLightState, 2500);

        if (prefs.getBoolean("master_allow", false)) {
            // Essential apps respect "turn off when unlocked": skip blink while unlocked
            if (isEssentialApp && prefs.getBoolean("essential_lights_unlock", false)) {
                android.app.KeyguardManager kmBlink =
                        (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                android.os.PowerManager pmBlink =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pmBlink != null && pmBlink.isInteractive()
                        && kmBlink != null && !kmBlink.isKeyguardLocked())
                    return;
            }

            long now = System.currentTimeMillis();
            Long lastBlink = lastBlinkTimes.get(sbn.getKey());
            if (lastBlink != null && (now - lastBlink < 10000))
                return;
            lastBlinkTimes.put(sbn.getKey(), now);

            if (prefs.getBoolean("notif_cooldown_enabled", false)) {
                Long lastPkgTrigger = lastPackageTriggerTimes.get(pkgName);
                if (lastPkgTrigger != null && (now - lastPkgTrigger < 60000))
                    return;
                lastPackageTriggerTimes.put(pkgName, now);
            }

            if (prefs.getBoolean("screen_off_only", false)) {
                android.os.PowerManager pm2 =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm2 != null && pm2.isInteractive())
                    return;
            }

            boolean hasSound = false;
            if (n != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    String channelId = n.getChannelId();
                    android.app.NotificationManager manager =
                            getSystemService(android.app.NotificationManager.class);
                    if (manager != null && channelId != null) {
                        android.app.NotificationChannel channel =
                                manager.getNotificationChannel(channelId);
                        if (channel != null && channel.getSound() != null) {
                            hasSound = true;
                        }
                    }
                }

                // Fallback for older devices or if no channel sound found (only if not already
                // true)
                if (!hasSound) {
                    try {
                        // Use reflection or standard access but guarded against direct warning if
                        // possible
                        // Actually, standard access is fine if we accept it's for older devices,
                        // but to satisfy linter/compiler we can check SDK
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                            hasSound = (n.sound != null)
                                    || ((n.defaults & android.app.Notification.DEFAULT_SOUND) != 0);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            Intent intent = new Intent(this, FlipToGlyphService.class);
            intent.setAction(FlipToGlyphService.ACTION_NOTIFICATION_GLYPH);
            intent.putExtra("suppress_audio", hasSound);
            startService(intent);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (ignoredEssentialKeys.contains(sbn.getKey())) {
            ignoredEssentialKeys.remove(sbn.getKey());
            saveIgnoredKeys();
        }
        localActiveEssentials.remove(sbn.getKey());
        lastBlinkTimes.remove(sbn.getKey());
        if (activeProgressMap.containsKey(sbn.getKey())) {
            activeProgressMap.remove(sbn.getKey());
            activeRawProgressMap.remove(sbn.getKey());
            sourceLastChangeMap.remove(sbn.getKey());
            if (activeProgressMap.isEmpty())
                watchdogLastValue = -1;
            triggerHighestProgress();
        }
        updateEssentialLightState();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        super.onNotificationRankingUpdate(rankingMap);
        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        if (!prefs.getBoolean("master_allow", false))
            return;

        if (!prefs.getBoolean("master_allow", false))
            return;
        if (!prefs.getBoolean("glyph_progress_enabled", false))
            return;

        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null)
                return;
            for (StatusBarNotification sbn : active) {
                Notification n = sbn.getNotification();
                if (n == null)
                    continue;
                Bundle extras = n.extras;
                int progress = extras.getInt(Notification.EXTRA_PROGRESS, -1);
                int progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1);
                if (progressMax > 0 && progress >= 0) {
                    int percent = (int) ((progress / (float) progressMax) * 100);
                    onProgressDetected("NOTIFICATION", sbn.getPackageName(), percent, progress,
                            progressMax, sbn.getKey());
                }
            }
        } catch (Exception e) {
        }
    }

    private void triggerHighestProgress() {
        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        if (!prefs.getBoolean("master_allow", false)
                || !prefs.getBoolean("glyph_progress_enabled", false)) {
            AnimationManager.cancelPriority(AnimationManager.PRIORITY_PROGRESS);
            return;
        }

        if (prefs.getBoolean("glyph_progress_flipped_only", false)
                && !prefs.getBoolean("device_is_flipped", false)) {
            AnimationManager.cancelPriority(AnimationManager.PRIORITY_PROGRESS);
            return;
        }

        // Priority 1: Notifications (Downloads, Installs)
        int highestNotif = -1;
        for (Map.Entry<String, Integer> entry : activeProgressMap.entrySet()) {
            if (entry.getKey().startsWith("MEDIA_") || entry.getKey().startsWith("BT_"))
                continue;
            if (entry.getValue() > highestNotif)
                highestNotif = entry.getValue();
        }

        if (highestNotif != -1) {
            AnimationManager.showProgressLevel(highestNotif, this);
            return;
        }

        // Priority 2: Bluetooth Battery
        int highestBT = -1;
        for (Map.Entry<String, Integer> entry : activeProgressMap.entrySet()) {
            if (entry.getKey().startsWith("BT_") && entry.getValue() > highestBT) {
                highestBT = entry.getValue();
            }
        }
        if (highestBT != -1) {
            AnimationManager.showProgressLevel(highestBT, this);
            return;
        }

        // Priority 3: Media
        findActiveMediaController();
        if (activeController != null) {
            PlaybackState state = activeController.getPlaybackState();
            boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;

            String key = "MEDIA_" + activeController.getPackageName();
            Integer mediaPercent = activeProgressMap.get(key);

            if (isPlaying && mediaPercent != null) {
                AnimationManager.showProgressLevel(mediaPercent, this);
                return;
            }
        }

        // Nothing to show, clear
        AnimationManager.cancelPriority(AnimationManager.PRIORITY_PROGRESS);
        handler.removeCallbacks(smoothProgressRunnable);
    }

    private void updateMediaSessions(List<MediaController> controllers) {
        Set<MediaSession.Token> newTokens = new HashSet<>();
        for (MediaController mc : controllers) {
            MediaSession.Token token = mc.getSessionToken();
            newTokens.add(token);
            if (!mediaCallbacks.containsKey(token)) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        String key = "MEDIA_" + mc.getPackageName();
                        sourceLastChangeMap.put(key, android.os.SystemClock.elapsedRealtime());
                        triggerSmoothProgress();
                    }

                    @Override
                    public void onMetadataChanged(MediaMetadata metadata) {
                        String key = "MEDIA_" + mc.getPackageName();
                        activeProgressMap.remove(key);
                        sourceLastChangeMap.put(key, android.os.SystemClock.elapsedRealtime());
                        triggerSmoothProgress();
                    }
                };
                mc.registerCallback(cb, handler);
                mediaCallbacks.put(token, cb);
            }
        }
        mediaCallbacks.keySet().removeIf(token -> !newTokens.contains(token));
        triggerSmoothProgress();
    }

    private void clearMediaCallbacks() { mediaCallbacks.clear(); }

    private void triggerSmoothProgress() {
        handler.removeCallbacks(smoothProgressRunnable);

        // PRIORITY: If a download is active, don't start music animation
        boolean hasHigherPriority = false;
        for (String key : activeProgressMap.keySet()) {
            if (!key.startsWith("MEDIA_") && !key.startsWith("BT_")) {
                hasHigherPriority = true;
                break;
            }
        }
        if (hasHigherPriority)
            return;

        findActiveMediaController();
        if (activeController != null) {
            PlaybackState state = activeController.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                isMusicActive = true;
                handler.post(smoothProgressRunnable);
                return;
            }
        }
        isMusicActive = false;
        updateSmoothProgress();
    }

    private void findActiveMediaController() {
        if (mediaSessionManager == null)
            return;
        List<MediaController> controllers = mediaSessionManager.getActiveSessions(
                new ComponentName(this, GlyphNotificationListener.class));
        activeController = null;

        // Strategy: First find ANY playing session.
        // If multiple are playing, pick the one with most recent interaction/update.
        long mostRecent = -1;
        for (MediaController mc : controllers) {
            PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                String key = "MEDIA_" + mc.getPackageName();
                long lastChange =
                        sourceLastChangeMap.containsKey(key) ? sourceLastChangeMap.get(key) : 0;
                if (lastChange > mostRecent) {
                    mostRecent = lastChange;
                    activeController = mc;
                }
            }
        }

        // Fallback: If nothing is playing, just pick the first one (most recently used
        // by Android system)
        if (activeController == null && !controllers.isEmpty()) {
            activeController = controllers.get(0);
        }
    }

    private void updateSmoothProgress() {
        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        if (!prefs.getBoolean("glyph_progress_enabled", false))
            return;
        if (activeController != null) {
            PlaybackState state = activeController.getPlaybackState();
            MediaMetadata meta = activeController.getMetadata();
            if (state != null && meta != null) {
                long duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
                int playbackState = state.getState();

                // Part 2: Handle stopped/none/paused
                String key = "MEDIA_" + activeController.getPackageName();
                if (playbackState == PlaybackState.STATE_STOPPED
                        || playbackState == PlaybackState.STATE_NONE
                        || playbackState == PlaybackState.STATE_PAUSED || duration <= 0) {
                    onProgressDetected("MEDIA", activeController.getPackageName(), 0, 0,
                            Math.max(0, duration), key);
                    return;
                }

                long pos = state.getPosition();
                if (playbackState == PlaybackState.STATE_PLAYING) {
                    long timeDiff =
                            SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
                    pos += (long) (timeDiff * state.getPlaybackSpeed());
                }

                int percent = (int) Math.min(100, Math.max(0, (pos / (float) duration) * 100));
                onProgressDetected(
                        "MEDIA", activeController.getPackageName(), percent, pos, duration, key);
            }
        } else {
            triggerHighestProgress();
        }
        if (isMusicActive)
            handler.postDelayed(smoothProgressRunnable, 500);
    }

    private void updateEssentialLightState() {
        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        boolean batterySaverEnabled = prefs.getBoolean("essential_battery_saver", false);
        if (batterySaverEnabled) {
            registerSensor();
        } else {
            unregisterSensor();
            isBatterySaverActive = false;
        }

        handler.removeCallbacks(essentialDebounceRunnable);
        handler.postDelayed(essentialDebounceRunnable, 150);
    }

    private void registerSensor() {
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void unregisterSensor() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float deltaX = Math.abs(lastGravity[0] - x);
            float deltaY = Math.abs(lastGravity[1] - y);
            float deltaZ = Math.abs(lastGravity[2] - z);

            if (deltaX > MOVEMENT_THRESHOLD || deltaY > MOVEMENT_THRESHOLD
                    || deltaZ > MOVEMENT_THRESHOLD) {
                lastMovementTime = SystemClock.elapsedRealtime();
                if (isBatterySaverActive) {
                    isBatterySaverActive = false;
                    updateEssentialLightState();
                }
            }

            lastGravity[0] = x;
            lastGravity[1] = y;
            lastGravity[2] = z;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void performEssentialLightUpdate() {
        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        if (prefs.getBoolean("essential_battery_saver", false)) {
            if (SystemClock.elapsedRealtime() - lastMovementTime > IDLE_TIMEOUT_MS) {
                if (!isBatterySaverActive) {
                    isBatterySaverActive = true;
                    android.util.Log.d(
                            "GlyphNotification", "Battery Saver Activated (30 min idle)");
                }
            }
        } else {
            isBatterySaverActive = false;
        }

        if (prefs.getBoolean("essential_lights_unlock", false)) {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked()) {
                performIgnoreLogic();
            }
        }

        // Immediate Suppression: If device is active and unlocked, no essential light
        // can fire
        android.app.KeyguardManager kmCheck =
                (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        android.os.PowerManager pmCheck =
                (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isDeviceActiveAndUnlocked = pmCheck != null && pmCheck.isInteractive()
                && kmCheck != null && !kmCheck.isKeyguardLocked();

        android.util.Log.d("GlyphNotification",
                "performEssentialLightUpdate: activeAndUnlocked=" + isDeviceActiveAndUnlocked);

        Set<String> essentialApps = prefs.getStringSet("essential_apps", new HashSet<>());
        boolean hasActiveEssential = false;
        Set<GlyphManagerV2.Glyph> activeGlyphs = new HashSet<>();

        if (isDeviceActiveAndUnlocked) {
            // Force clear background glyphs in AnimationManager immediately
            for (GlyphManagerV2.Glyph glyph : GlyphManagerV2.Glyph.values()) {
                AnimationManager.setBackgroundGlyph(glyph, 0);
            }
            return; // Exit early, nothing else to do if unlocked
        }
        try {
            for (StatusBarNotification sbn : localActiveEssentials.values()) {
                Notification n = sbn.getNotification();
                boolean isMissedCall = n != null
                        && Notification.CATEGORY_MISSED_CALL.equals(n.category)
                        && prefs.getBoolean("essential_missed_call", false);
                String pkg = sbn.getPackageName();
                if (essentialApps.contains(pkg) || isMissedCall) {
                    if (!ignoredEssentialKeys.contains(sbn.getKey())) {
                        Set<String> selectedChannels =
                                prefs.getStringSet("essential_channels_" + pkg, null);
                        if (selectedChannels != null && !selectedChannels.isEmpty()) {
                            if (!selectedChannels.contains(sbn.getNotification().getChannelId()))
                                continue;
                        }
                        hasActiveEssential = true;
                        String customGlyphName = isMissedCall && !essentialApps.contains(pkg)
                                ? prefs.getString("glyph_missed_call", "DEFAULT")
                                : prefs.getString("glyph_app_" + pkg, "DEFAULT");
                        if ("DEFAULT".equals(customGlyphName)) {
                            activeGlyphs.add(prefs.getBoolean("essential_lights_red", false)
                                            ? GlyphManagerV2.Glyph.SINGLE_LED
                                            : GlyphManagerV2.Glyph.DIAGONAL);
                        } else {
                            try {
                                activeGlyphs.add(GlyphManagerV2.Glyph.valueOf(customGlyphName));
                            } catch (Exception e) {
                                activeGlyphs.add(prefs.getBoolean("essential_lights_red", false)
                                                ? GlyphManagerV2.Glyph.SINGLE_LED
                                                : GlyphManagerV2.Glyph.DIAGONAL);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }

        if (prefs.getBoolean("is_on_call", false)) {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null
                    && tm.getCallState() == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                prefs.edit().putBoolean("is_on_call", false).apply();
            }
        }
        if (prefs.getBoolean("is_on_call", false)
                && prefs.getBoolean("stop_glyphs_during_call", false)) {
            hasActiveEssential = false;
        }

        int brightness = prefs.getInt("brightness", 2048);
        boolean masterAllow = prefs.getBoolean("master_allow", false);

        // Push state to AnimationManager for unified layer merging
        for (GlyphManagerV2.Glyph glyph : GlyphManagerV2.Glyph.values()) {
            boolean shouldShow = !isDeviceActiveAndUnlocked && masterAllow && hasActiveEssential
                    && activeGlyphs.contains(glyph) && !isBatterySaverActive;
            int val = shouldShow ? brightness : 0;
            AnimationManager.setBackgroundGlyph(glyph, val);
        }

        android.util.Log.d("GlyphNotification",
                "Essentials Updated: hasActive=" + hasActiveEssential + " (master=" + masterAllow
                        + ")");
    }

    private void runEssentialHeartbeat() {
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            updateEssentialLightState();
            handler.postDelayed(heartbeatRunnable, 10000);
        }
    }

    private void startHeartbeat() {
        handler.removeCallbacks(heartbeatRunnable);
        handler.postDelayed(heartbeatRunnable, 5000);
    }

    private void stopHeartbeat() { handler.removeCallbacks(heartbeatRunnable); }

    private void performIgnoreLogic() {
        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        // Aggressively refresh local map from system state to avoid ghosts/stale
        // notifications
        try {
            StatusBarNotification[] active = getActiveNotifications();
            localActiveEssentials.clear();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    localActiveEssentials.put(sbn.getKey(), sbn);
                }
            }
        } catch (Exception ignored) {
        }

        boolean changed = false;
        // Simplified: Ignore EVERYTHING currently in the tray. This ensures a clean
        // slate after unlock, as per user expectation.
        for (String key : localActiveEssentials.keySet()) {
            if (!ignoredEssentialKeys.contains(key)) {
                ignoredEssentialKeys.add(key);
                changed = true;
            }
        }
        if (changed)
            saveIgnoredKeys();
    }

    private synchronized void loadIgnoredKeys() {
        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("ignored_essential_keys", new HashSet<>());
        ignoredEssentialKeys.clear();
        ignoredEssentialKeys.addAll(saved);
    }

    private synchronized void saveIgnoredKeys() {
        if (prefs == null)
            prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        prefs.edit()
                .putStringSet("ignored_essential_keys", new HashSet<>(ignoredEssentialKeys))
                .apply();
    }

    // --- Part 4: Unified Progress Output ---
    public void onProgressDetected(
            String sourceType, String pkg, int percent, long progress, long max, String key) {
        // Prevent duplicate progress events via map (check RAW progress)
        Long lastRaw = activeRawProgressMap.get(key);
        if (lastRaw != null && lastRaw == progress) {
            // Truly hasn't changed (e.g. stalled download or paused music)
            return;
        }

        activeRawProgressMap.put(key, progress);
        activeProgressMap.put(key, percent);
        sourceLastChangeMap.put(key, android.os.SystemClock.elapsedRealtime());

        triggerHighestProgress();
    }

    // --- Part 5: Glyph Update Trigger ---
    public static void updateGlyphLine(int percent) {
        if (instance != null) {
            AnimationManager.showProgressLevel(percent, instance);
        }
    }
}