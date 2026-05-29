package org.aspends.nglyphs.core;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.services.FlipToGlyphService;

public class AnimationManager {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final android.os.Handler timeoutHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable volumeTimeoutRunnable;
    private static Runnable batteryTimeoutRunnable;
    public static final String ACTION_GLYPH_FREE = "org.aspends.nglyphs.ACTION_GLYPH_FREE";
    private static volatile boolean isAnimationRunning = false;
    private static android.content.Context appContext;

    public interface GlyphFrameListener {
        void onFrameUpdated(int[] frame);
    }
    private static GlyphFrameListener activeFrameListener = null;

    public static void setFrameListener(GlyphFrameListener listener) {
        activeFrameListener = listener;
    }

    public static void notifyFrame(int[] frame) {
        if (activeFrameListener != null) {
            activeFrameListener.onFrameUpdated(frame);
        }
    }

    public static void init(android.content.Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    // New Priority Hierarchy (High to Low)
    public static final int PRIORITY_NONE = 0;
    public static final int PRIORITY_VISUALIZER = 1; // LOWEST (Background Music Sync)
    public static final int PRIORITY_BATTERY = 2; // Plug-in / Nudge pulses
    public static final int PRIORITY_PROGRESS = 3; // Music and Download bars (Higher than battery)
    public static final int PRIORITY_VOLUME = 4; // Volume bar
    public static final int PRIORITY_ASSISTANT = 5; // Voice pulsing
    public static final int PRIORITY_HIGH = 6; // Patterns / Ringtone / Notifications
    public static final int PRIORITY_TORCH = 7; // MAX (Constant Torch)

    private static volatile int activePriority = PRIORITY_NONE;
    private static volatile int runningLoopPriority = PRIORITY_NONE;

    public static boolean isBusy() { return isAnimationRunning && activePriority != PRIORITY_NONE; }

    public static int getActivePriority() { return activePriority; }

    // Animation state for smooth transitions
    private static float targetLevel = 0f;
    private static float currentLevel = 0f;
    private static final float INTERPOLATION_SPEED = 0.25f; // Balanced for smoothness (was 0.4f)
    private static final int REFRESH_RATE_MS = 16; // 60fps

    /**
     * Attempts to acquire the animation lock for a specific priority.
     * Returns true if successful (can override lower or equal priority if allowed).
     */
    private static synchronized boolean requestLock(int priority) {
        if (appContext != null) {
            android.content.SharedPreferences prefs = appContext.getSharedPreferences(
                    appContext.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);

            // Hard kill switch check
            if (!prefs.getBoolean("master_allow", false))
                return false;

            if (prefs.getBoolean("is_light_on", false))
                return false;

            // Block all non-critical animations if user is on an active call and setting is
            // enabled
            if (priority < PRIORITY_HIGH && prefs.getBoolean("is_on_call", false)
                    && prefs.getBoolean("stop_glyphs_during_call", false)) {
                return false;
            }
        }

        if (priority >= activePriority
                || (activePriority == PRIORITY_PROGRESS && targetLevel < 0.01f)) {
            if (activePriority != priority) {
                isAnimationRunning = false; // Interrupt lower priority
            }
            activePriority = priority;
            return true;
        }
        return false;
    }

    private static synchronized void releaseLock(int priority) {
        if (activePriority == priority) {
            activePriority = PRIORITY_NONE;
            isAnimationRunning = false;
            timeoutHandler.removeCallbacks(watchdogRunnable);
            if (appContext != null) {
                appContext.sendBroadcast(
                        new android.content.Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL)
                                .setPackage(appContext.getPackageName()));

                if (priority > PRIORITY_PROGRESS) {
                    appContext.sendBroadcast(new android.content.Intent(ACTION_GLYPH_FREE)
                                    .setPackage(appContext.getPackageName()));
                }
            }
        }
    }

    public static synchronized boolean requestLockExternal(int priority) {
        return requestLock(priority);
    }

    public static synchronized void releaseLockExternal(int priority) { releaseLock(priority); }

    public static void setAnimationRunning(boolean running) { isAnimationRunning = running; }

    /**
     * External hook for patterns/notifications which are managed outside this
     * class.
     */
    public static void setHighPriorityActive(boolean active) {
        if (active) {
            requestLock(PRIORITY_HIGH);
        } else {
            releaseLock(PRIORITY_HIGH);
        }
    }

    private static final java.util.Map<GlyphManagerV2.Glyph, Integer> backgroundState =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void setBackgroundGlyph(GlyphManagerV2.Glyph glyph, int brightness) {
        if (activePriority == PRIORITY_HIGH)
            return; // Don't interrupt patterns

        if (appContext != null) {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) appContext.getSystemService(
                            android.content.Context.KEYGUARD_SERVICE);
            android.os.PowerManager pm = (android.os.PowerManager) appContext.getSystemService(
                    android.content.Context.POWER_SERVICE);
            if (pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked()) {
                brightness = 0;
            }
        }

        if (brightness > 0) {
            backgroundState.put(glyph, brightness);
        } else {
            backgroundState.remove(glyph);
        }

        // Push background state immediately
        refreshBackgroundState();
    }

    public static void refreshBackgroundState() {
        if (activePriority == PRIORITY_HIGH)
            return;
        if (!isAnimationRunning) {
            updateMeter(currentLevel, runningLoopPriority == PRIORITY_PROGRESS ? 8 : 9);
        }
    }

    private static float animationBrightnessMultiplier = 1.0f;

    private static void updateMeter(float level, int maxZones) {
        if (activePriority == PRIORITY_HIGH && level < 0.01f && isAnimationRunning)
            return;

        int[] frame = new int[15];
        int torchBrightness = 0;

        if (appContext != null) {
            android.content.SharedPreferences prefs = appContext.getSharedPreferences(
                    appContext.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false)) {
                torchBrightness = prefs.getInt("torch_brightness", 2048);
                for (int i = 0; i < 15; i++) frame[i] = torchBrightness;
            }
        }

        for (java.util.Map.Entry<GlyphManagerV2.Glyph, Integer> entry :
                backgroundState.entrySet()) {
            GlyphManagerV2.Glyph g = entry.getKey();
            int b = entry.getValue();
            switch (g) {
                case CAMERA:
                    frame[0] = b;
                    break;
                case DIAGONAL:
                    frame[1] = Math.max(frame[1], b);
                    break;
                case MAIN:
                    frame[2] = Math.max(frame[2], b);
                    frame[3] = Math.max(frame[3], b);
                    frame[4] = Math.max(frame[4], b);
                    frame[5] = Math.max(frame[5], b);
                    break;
                case DOT:
                    frame[6] = Math.max(frame[6], b);
                    break;
                case LINE:
                    for (int i = 7; i <= 14; i++) frame[i] = Math.max(frame[i], b);
                    break;
            }
        }

        if (level > 0.01f || isAnimationRunning) {
            int fullLeds = (int) Math.floor(level);
            float fraction = level - fullLeds;
            int startIdx = (maxZones == 15) ? 0 : (maxZones == 9 ? 6 : 7);

            int brightness = (int) (GlyphManagerV2.MAX_BRIGHTNESS * animationBrightnessMultiplier);

            for (int i = 0; i < fullLeds && i < maxZones; i++) {
                frame[startIdx + i] = brightness;
            }

            if (fullLeds < maxZones && fraction > 0.01f) {
                frame[startIdx + fullLeds] = (int) (brightness * fraction);
            }
        }

        // 3. FINAL PUSH: Decide between fast Frame (Animation) or bright Individual
        // (Static)
        if (level > 0.01f || isAnimationRunning) {
            GlyphManagerV2.getInstance().setFrame(frame);
        } else {
            // STATIC MODE: Use individual nodes for higher brightness peaks
            // (Torch/Essentials)
            java.util.Map<GlyphManagerV2.Glyph, Integer> updates = new java.util.HashMap<>();
            updates.put(GlyphManagerV2.Glyph.CAMERA, frame[0]);
            updates.put(GlyphManagerV2.Glyph.DIAGONAL, frame[1]);
            updates.put(GlyphManagerV2.Glyph.MAIN, frame[2]);
            updates.put(GlyphManagerV2.Glyph.DOT, frame[6]);
            updates.put(GlyphManagerV2.Glyph.LINE, frame[7]);

            GlyphManagerV2.getInstance().setGlyphBrightness(updates);
        }
    }

    public static void showVolumeLevel(int percentage, android.content.Context context) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false))
                return;
        }

        if (!requestLock(PRIORITY_VOLUME))
            return;

        animationBrightnessMultiplier = 1.0f;
        targetLevel = (percentage / 100f) * 9.0f;

        if (volumeTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(volumeTimeoutRunnable);
        }

        if (runningLoopPriority != PRIORITY_VOLUME || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_VOLUME, 9);
        }

        volumeTimeoutRunnable = () -> {
            targetLevel = 0f;
        };
        timeoutHandler.postDelayed(volumeTimeoutRunnable, 2500);
    }

    public static void showVisualizer(int[] zoneIntensities, android.content.Context context) {
        showVisualizer(zoneIntensities, context, false);
    }

    public static void showVisualizer(
            int[] zoneIntensities, android.content.Context context, boolean beatLayout) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false))
                return;
        }
        if (!requestLock(PRIORITY_VISUALIZER))
            return;

        isAnimationRunning = true;
        runningLoopPriority = PRIORITY_VISUALIZER;

        int[] frame = new int[15];

        if (zoneIntensities.length == 15) {
            // Each frame index maps to an individual LED — pass through directly
            // but apply a noise floor so LEDs only light when their band has real energy
            int threshold = (int) (GlyphManagerV2.MAX_BRIGHTNESS * 0.08f);
            for (int i = 0; i < 15; i++) {
                frame[i] = zoneIntensities[i] < threshold ? 0 : zoneIntensities[i];
            }
        } else if (beatLayout && zoneIntensities.length >= 5) {
            // Beat Detection only — stock "Music Visualisation" layout: the 8-LED
            // center line pulses with the bass/kick; mids light the charging circle
            // and rear-cam arc; treble flicks the dot. Frame indices map to physical
            // LEDs via frame_leds_effect (kernel leds_aw210xx.c).
            int lineVal = zoneIntensities[0]; // bass -> center line (8 LEDs)
            for (int i = 7; i <= 14; i++) frame[i] = lineVal;

            int roundVal = zoneIntensities[1]; // low-mid -> charging circle
            frame[2] = frame[3] = frame[4] = frame[5] = roundVal;

            frame[0] = zoneIntensities[2]; // mid -> rear-cam arc
            frame[1] = zoneIntensities[3]; // mid-high -> diagonal strip
            frame[6] = zoneIntensities[4]; // treble -> dot
        } else if (zoneIntensities.length >= 5) {
            int bass = zoneIntensities[0];
            frame[2] = frame[3] = frame[4] = frame[5] = bass;

            frame[1] = zoneIntensities[1];
            frame[0] = zoneIntensities[2];

            int lineVal = zoneIntensities[3];
            for (int i = 7; i <= 14; i++) frame[i] = lineVal;

            frame[6] = zoneIntensities[4];
        }

        GlyphManagerV2.getInstance().setFrame(frame);

        if (runningLoopPriority != PRIORITY_VISUALIZER) {
            runningLoopPriority = PRIORITY_VISUALIZER;
        }
    }

    // Clears the glyphs and releases the lock when music stops.
    public static void stopVisualizer(android.content.Context context) {
        if (activePriority != PRIORITY_VISUALIZER && runningLoopPriority != PRIORITY_VISUALIZER) {
            return;
        }
        isAnimationRunning = false;
        runningLoopPriority = PRIORITY_NONE;
        GlyphManagerV2.getInstance().setFrame(new int[15]);
        releaseLock(PRIORITY_VISUALIZER);
    }

    public static void showProgressLevel(int percentage, android.content.Context context) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false))
                return;

            if (!prefs.getBoolean("glyph_progress_enabled", false)) {
                releaseLock(PRIORITY_PROGRESS);
                return;
            }
            if (prefs.getBoolean("glyph_progress_flipped_only", false)
                    && !prefs.getBoolean("device_is_flipped", false)) {
                releaseLock(PRIORITY_PROGRESS);
                return;
            }
        }

        if (percentage <= 0 && activePriority != PRIORITY_PROGRESS) {
            return;
        }

        if (!requestLock(PRIORITY_PROGRESS))
            return;

        float factor = 0.7f;
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            factor = prefs.getInt("glyph_progress_brightness_factor", 70) / 100f;
        }
        animationBrightnessMultiplier = factor;
        targetLevel = (percentage / 100f) * 8.0f;

        if (runningLoopPriority != PRIORITY_PROGRESS || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_PROGRESS, 8);
        }
    }

    public static void showBluetoothBattery(
            int batteryLevel, android.content.Context context, int holdMs) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false))
                return;
        }

        android.util.Log.i("AnimationManager",
                "showBluetoothBattery: level=" + batteryLevel + ", holdMs=" + holdMs);

        if (!requestLock(PRIORITY_VOLUME))
            return;

        int maxZones = 9;
        targetLevel = (float) ((batteryLevel / 100.0) * (maxZones - 1)) + 1;

        if (runningLoopPriority != PRIORITY_VOLUME || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_VOLUME, maxZones);
        }

        batteryTimeoutRunnable = () -> {
            targetLevel = 0f;
        };
        timeoutHandler.postDelayed(batteryTimeoutRunnable, holdMs);
    }

    public static void playBatteryAnimation(
            int batteryLevel, android.content.Context context, int holdMs) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            if (prefs.getBoolean("is_light_on", false))
                return;
        }

        android.util.Log.i("AnimationManager",
                "playBatteryAnimation: level=" + batteryLevel + ", holdMs=" + holdMs);

        if (!requestLock(PRIORITY_BATTERY))
            return;

        animationBrightnessMultiplier = 1.0f;
        int maxZones = 9;
        targetLevel = (float) ((batteryLevel / 100.0) * (maxZones - 1)) + 1;

        if (batteryTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(batteryTimeoutRunnable);
        }

        if (runningLoopPriority != PRIORITY_BATTERY || !isAnimationRunning) {
            startAnimationLoop(PRIORITY_BATTERY, maxZones);
        }

        batteryTimeoutRunnable = () -> {
            targetLevel = 0f;
        };
        timeoutHandler.postDelayed(batteryTimeoutRunnable, holdMs);
    }

    private static final Runnable watchdogRunnable = () -> {
        android.util.Log.w("AnimationManager", "Watchdog triggered! Forcing glyph clear.");
        cancelAnimation();
    };

    private static void startAnimationLoop(final int priority, final int maxZones) {
        isAnimationRunning = true;
        runningLoopPriority = priority;

        timeoutHandler.removeCallbacks(watchdogRunnable);
        if (priority == PRIORITY_BATTERY || priority == PRIORITY_VOLUME) {
            timeoutHandler.postDelayed(watchdogRunnable, 15000);
        }

        executor.submit(() -> {
            currentLevel = 0f;
            boolean forcedFirstFrame = true;

            int loopInterval = (priority == PRIORITY_VOLUME) ? 16 : 32;

            try {
                while (isAnimationRunning && activePriority == priority
                        && runningLoopPriority == priority) {
                    long now = SystemClock.uptimeMillis();
                    float diff = targetLevel - currentLevel;

                    if (forcedFirstFrame || Math.abs(diff) > 0.01f) {
                        float speed = INTERPOLATION_SPEED;
                        if (priority == PRIORITY_BATTERY) {
                            speed = 0.03f;
                        } else if (priority == PRIORITY_PROGRESS) {
                            speed = 0.1f;
                        }
                        currentLevel += diff * speed;
                        updateMeter(currentLevel, maxZones);
                        forcedFirstFrame = false;
                    } else if (targetLevel < 0.01f) {
                        currentLevel = 0f;
                        updateMeter(0f, maxZones);
                        break;
                    } else {
                        updateMeter(targetLevel, maxZones);
                        SystemClock.sleep(200);
                        continue;
                    }

                    long elapsed = SystemClock.uptimeMillis() - now;
                    long sleep = Math.max(1, loopInterval - elapsed);
                    SystemClock.sleep(sleep);
                }
            } catch (Exception e) {
                android.util.Log.e("AnimationManager", "Animation Loop Error", e);
            } finally {
                updateMeter(0f, maxZones);
                GlyphManagerV2.getInstance().setFrame(new int[15]);

                if (activePriority == priority) {
                    releaseLock(priority);
                }
                isAnimationRunning = false;
                runningLoopPriority = PRIORITY_NONE;

                if (appContext != null) {
                    Intent intent = new Intent(ACTION_GLYPH_FREE);
                    intent.setPackage(appContext.getPackageName());
                    appContext.sendBroadcast(intent);
                }
            }
        });
    }

    public static void cancelAnimation() {
        isAnimationRunning = false;
        activePriority = PRIORITY_NONE;
        runningLoopPriority = PRIORITY_NONE;
        targetLevel = 0f;
        currentLevel = 0f;
        timeoutHandler.removeCallbacksAndMessages(null);
        executor.submit(() -> GlyphManagerV2.getInstance().setFrame(new int[15]));
    }

    public static void cancelPriority(int priority) {
        if (activePriority == priority) {
            targetLevel = 0f;
            currentLevel = 0f;
            isAnimationRunning = false;
        }
    }
}
