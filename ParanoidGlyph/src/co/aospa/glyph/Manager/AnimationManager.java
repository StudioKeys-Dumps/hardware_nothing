/*
* Copyright (C) 2022-2024 Paranoid Android
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package co.aospa.glyph.Manager;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.GlyphApplication;
import co.aospa.glyph.Utils.FileUtils;
import co.aospa.glyph.Utils.ResourceUtils;

public final class AnimationManager {

    private static final String TAG = "GlyphAnimationManager";
    private static final boolean DEBUG = true;
    private static final long RINGTONE_START_OFFSET_MS = 201L;
    private static Vibrator mVibrator;
    private static boolean vibratedThisCycle = false;

    private static final Map<String, Double> RINGTONE_DURATION_MS = new HashMap<String, Double>() {{
        put("Abra",       6993.521);
        put("Beetle",     8732.875);
        put("Bug",        9425.042);
        put("Burrow",     7598.375);
        put("Flutter",    8312.500);
        put("Forever",   10388.729);
        put("Karha",      3303.292);
        put("Latency",    9801.042);
        put("Molitor",    8000.000);
        put("Pepelu",    11169.063);
        put("Pet",        4497.375);
        put("Plot",       5875.188);
        put("Pneumatic",  5500.000);
        put("Radiate",   10607.021);
        put("Scribble",   4313.000);
        put("Snaps",      7929.958);
        put("Squirrels",  4770.604);
        put("Sticks",     5033.750);
        put("Tennis",     5848.208);
        put("Wings",      7752.500);
        put("WooYeh",     9933.188);
        put("Wow",        7563.333);
    }};

    private static Future<?> submit(Runnable runnable) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        return executorService.submit(runnable);
    }

    private static void initVibrator() {
        if (mVibrator == null) {
            Context context = GlyphApplication.getContext();
            if (context != null) {
                mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
        }
    }

    private static void playHaptic(float brightness) {
        initVibrator();
        if (mVibrator == null || !mVibrator.hasVibrator()) return;

        if (!SettingsManager.isHapticEnabled()) return;

        if (brightness > 0 && !vibratedThisCycle) {
            int amplitude = (int) Math.max(1, Math.min(brightness * 255 / Constants.getMaxBrightness(), 255));
            mVibrator.vibrate(VibrationEffect.createOneShot(20, amplitude));
            vibratedThisCycle = true;
        } else if (brightness <= 0) {
            vibratedThisCycle = false;
        }
    }

    private static boolean check(String name, boolean wait) {
        if (DEBUG) Log.d(TAG, "Playing animation | name: " + name + " | waiting: " + Boolean.toString(wait));

        if (StatusManager.isAllLedActive()) {
            if (DEBUG) Log.d(TAG, "All LEDs are active, exiting animation | name: " + name);
            return false;
        }

        if (StatusManager.isCallLedActive()) {
            if (DEBUG) Log.d(TAG, "Call animation is currently active, exiting animation | name: " + name);
            return false;
        }

        if (StatusManager.isAnimationActive()) {
            long start = System.currentTimeMillis();
            if (name == "volume" && StatusManager.isVolumeLedActive()) {
                if (DEBUG) Log.d(TAG, "There is already a volume animation playing, update");
                StatusManager.setVolumeLedUpdate(true);
                while (StatusManager.isVolumeLedUpdate()) {
                    if (System.currentTimeMillis() - start >= 2500) return false;
                }
            } else if (wait) {
                if (DEBUG) Log.d(TAG, "There is already an animation playing, wait | name: " + name);
                while (StatusManager.isAnimationActive()) {
                    if (System.currentTimeMillis() - start >= 2500) return false;
                }
            } else {
                if (DEBUG) Log.d(TAG, "There is already an animation playing, exiting | name: " + name);
                return false;
            }
        }

        return true;
    }

    private static boolean checkInterruption(String name) {
        if (StatusManager.isAllLedActive()
                || (name != "call" && StatusManager.isCallLedEnabled())
                || (name == "call" && !StatusManager.isCallLedEnabled())
                || (name == "volume" && StatusManager.isVolumeLedUpdate())) {
            return true;
        }
        return false;
    }

    private static double getRingtoneDurationMs(String name) {
        Double mapped = RINGTONE_DURATION_MS.get(name);
        if (mapped != null) {
            if (DEBUG) Log.d(TAG, "Ringtone duration from map | name: " + name + " | ms: " + mapped);
            return mapped;
        }

        String[] paths = {
            "/product/media/audio/ringtones/" + name + ".ogg",
            "/system/media/audio/ringtones/" + name + ".ogg",
        };
        for (String path : paths) {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(path);
                String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                retriever.release();
                if (dur != null) {
                    double ms = Double.parseDouble(dur);
                    if (DEBUG) Log.d(TAG, "Ringtone duration measured | name: " + name + " | ms: " + ms);
                    return ms;
                }
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "Could not read duration from: " + path);
            }
        }

        Log.w(TAG, "Could not determine ringtone duration for: " + name);
        return -1.0;
    }

    private static long playCallCycle(String name) {
        long cycleStart = System.currentTimeMillis();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ResourceUtils.getCallAnimation(name)))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!StatusManager.isCallLedEnabled() || checkInterruption("call")) break;

                long frameStart = System.currentTimeMillis();

                String cleanLine = line.replace(" ", "");
                cleanLine = cleanLine.endsWith(",")
                        ? cleanLine.substring(0, cleanLine.length() - 1) : cleanLine;
                String[] pattern = cleanLine.split(",");

                if (ArrayUtils.contains(
                        Constants.getSupportedAnimationPatternLengths(), pattern.length)) {
                    updateLedFrame(pattern);
                } else {
                    if (DEBUG) Log.d(TAG, "Line length mismatch | " + name);
                    break;
                }

                long delay = 16L - (System.currentTimeMillis() - frameStart);
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception in call animation cycle | " + name, e);
        }

        return System.currentTimeMillis() - cycleStart;
    }

    private static void sleepInterruptible(long ms) {
        if (ms <= 0) return;
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (!StatusManager.isCallLedEnabled()) break;
            try {
                Thread.sleep(Math.min(20L, end - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public static void playCsv(String name) {
        playCsv(name, false);
    }

    public static void playCsv(String name, boolean wait) {
        submit(() -> {
            if (!check(name, wait))
                return;

            StatusManager.setAnimationActive(true);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ResourceUtils.getAnimation(name)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (checkInterruption("csv")) throw new InterruptedException();
                    long frameStart = SystemClock.uptimeMillis();
                    line = line.replace(" ", "");
                    line = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
                    String[] pattern = line.split(",");
                    if (ArrayUtils.contains(Constants.getSupportedAnimationPatternLengths(), pattern.length)) {
                        updateLedFrame(pattern);
                    } else {
                        if (DEBUG) Log.d(TAG, "Animation line length mismatch | name: " + name + " | line: " + line);
                        throw new InterruptedException();
                    }
                    long elapsed = SystemClock.uptimeMillis() - frameStart;
                    SystemClock.sleep(Math.max(1L, 16L - elapsed));
                }
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "Exception while playing animation | name: " + name + " | exception: " + e);
            } finally {
                updateLedFrame(new float[5]);
                StatusManager.setAnimationActive(false);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: " + name);
            }
        });
    }

    public static void playNotification(String name) {
        playNotification(name, false);
    }

    public static void playNotification(String name, boolean wait) {
        submit(() -> {
            if (!check(name, wait))
                return;

            StatusManager.setAnimationActive(true);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ResourceUtils.getNotificationAnimation(name)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (checkInterruption("csv")) throw new InterruptedException();
                    long frameStart = SystemClock.uptimeMillis();
                    line = line.replace(" ", "");
                    line = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
                    String[] pattern = line.split(",");
                    if (ArrayUtils.contains(Constants.getSupportedAnimationPatternLengths(), pattern.length)) {
                        updateLedFrame(pattern);
                    } else {
                        if (DEBUG) Log.d(TAG, "Animation line length mismatch | name: " + name + " | line: " + line);
                        throw new InterruptedException();
                    }
                    long elapsed = SystemClock.uptimeMillis() - frameStart;
                    SystemClock.sleep(Math.max(1L, 16L - elapsed));
                }
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "Exception while playing notification animation | name: " + name + " | exception: " + e);
            } finally {
                updateLedFrame(new float[5]);
                StatusManager.setAnimationActive(false);
                if (DEBUG) Log.d(TAG, "Done playing notification animation | name: " + name);
            }
        });
    }

    public static void playCharging(int batteryLevel, boolean wait) {
        submit(() -> {
            if (!check("charging", wait))
                return;

            StatusManager.setAnimationActive(true);

            boolean batteryDot = ResourceUtils.getBoolean("glyph_settings_battery_dot");
            int[] batteryArray = new int[ResourceUtils.getInteger("glyph_settings_battery_levels_num")];
            int amount = (int) (Math.floor((batteryLevel / 100.0) * (batteryArray.length - (batteryDot ? 2 : 1))) + (batteryDot ? 2 : 1));

            try {
                for (int i = 0; i < batteryArray.length; i++) {
                    if (checkInterruption("charging")) throw new InterruptedException();
                    batteryArray[i] = Constants.getBrightness();
                    if (batteryDot && i == 0) continue;
                    updateLedFrame(batteryArray);
                    Thread.sleep(15);
                }
                for (int i = batteryArray.length - 1; i > amount - 1; i--) {
                    if (checkInterruption("charging")) throw new InterruptedException();
                    batteryArray[i] = 0;
                    updateLedFrame(batteryArray);
                    Thread.sleep(5);
                }
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start <= 2000) {
                    if (checkInterruption("charging")) throw new InterruptedException();
                }
                for (int i = amount - 1; i >= 0; i--) {
                    if (checkInterruption("charging")) throw new InterruptedException();
                    batteryArray[i] = 0;
                    updateLedFrame(batteryArray);
                    Thread.sleep(11);
                }
                long start2 = System.currentTimeMillis();
                while (System.currentTimeMillis() - start2 <= 730) {
                    if (checkInterruption("charging")) throw new InterruptedException();
                }
            } catch (InterruptedException e) {
                if (DEBUG) Log.d(TAG, "Exception while playing animation, interrupted | name: charging");
                if (!StatusManager.isAllLedActive()) {
                    updateLedFrame(new int[batteryArray.length]);
                }
            } finally {
                StatusManager.setAnimationActive(false);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: charging");
            }
        });
    }

    public static void playVolume(int volumeLevel, boolean wait) {
        submit(() -> {
            if (!check("volume", wait))
                return;

            StatusManager.setVolumeLedActive(true);
            StatusManager.setAnimationActive(true);

            int[] volumeArray = new int[ResourceUtils.getInteger("glyph_settings_volume_levels_num")];
            int amount = (int) (Math.floor((volumeLevel / 100D) * (volumeArray.length - 1)) + 1);
            int last = StatusManager.getVolumeLedLast();

            try {
                for (int i = 0; i < volumeArray.length; i++) {
                    if (volumeLevel == 0) {
                        if (checkInterruption("volume")) throw new InterruptedException();
                        StatusManager.setVolumeLedLast(0);
                        updateLedFrame(new int[volumeArray.length]);
                        break;
                    } else if (i <= amount - 1 && volumeLevel > 0) {
                        if (checkInterruption("volume")) throw new InterruptedException();
                        StatusManager.setVolumeLedLast(i);
                        volumeArray[i] = Constants.getBrightness();
                        if (last == 0) {
                            updateLedFrame(volumeArray);
                            Thread.sleep(15);
                        }
                    }
                }
                if (last != 0) {
                    if (checkInterruption("volume")) throw new InterruptedException();
                    updateLedFrame(volumeArray);
                }
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start <= 1800) {
                    if (checkInterruption("volume")) throw new InterruptedException();
                }
                for (int i = volumeArray.length - 1; i >= 0; i--) {
                    if (checkInterruption("volume")) throw new InterruptedException();
                    if (volumeArray[i] != 0) {
                        StatusManager.setVolumeLedLast(i);
                        volumeArray[i] = 0;
                        updateLedFrame(volumeArray);
                        Thread.sleep(15);
                    }
                }
                long start2 = System.currentTimeMillis();
                while (System.currentTimeMillis() - start2 <= 730) {
                    if (checkInterruption("volume")) throw new InterruptedException();
                }
            } catch (InterruptedException e) {
                if (DEBUG) Log.d(TAG, "Exception while playing animation, interrupted | name: volume");
                if (!StatusManager.isAllLedActive() && !StatusManager.isVolumeLedUpdate()) {
                    updateLedFrame(new int[volumeArray.length]);
                }
            } finally {
                if (!StatusManager.isVolumeLedUpdate()) {
                    StatusManager.setVolumeLedLast(0);
                    StatusManager.setAnimationActive(false);
                    StatusManager.setVolumeLedActive(false);
                }
                StatusManager.setVolumeLedUpdate(false);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: volume");
            }
        });
    }

    public static void playCall(String name) {
        submit(() -> {
            StatusManager.setCallLedEnabled(true);

            if (!check("call: " + name, true)) return;

            StatusManager.setCallLedActive(true);
            Log.d(TAG, "Starting call animation loop | name: " + name);

            if (RINGTONE_START_OFFSET_MS > 0) {
                sleepInterruptible(RINGTONE_START_OFFSET_MS);
            }

            if (!StatusManager.isCallLedEnabled()) {
                updateLedFrame(new float[5]);
                StatusManager.setCallLedActive(false);
                return;
            }

            final double ringtoneDurationMs = getRingtoneDurationMs(name);
            final long anchorStart = System.currentTimeMillis();
            int cycle = 0;

            while (StatusManager.isCallLedEnabled()) {
                long cycleDuration = playCallCycle(name);

                if (!StatusManager.isCallLedEnabled()) break;

                cycle++;
                double nextCycleTargetExact = anchorStart + ((double) cycle * ringtoneDurationMs);
                long nextCycleTarget = Math.round(nextCycleTargetExact);
                long now = System.currentTimeMillis();
                long gap = nextCycleTarget - now;

                if (DEBUG) Log.d(TAG, "Cycle " + cycle + " sync"
                        + " | csvDuration: " + cycleDuration + "ms"
                        + " | gap: " + gap + "ms"
                        + " | ringtone: " + ringtoneDurationMs + "ms");

                if (gap > 0) {
                    updateLedFrame(new float[5]);
                    sleepInterruptible(gap);
                } else {
                    if (DEBUG) Log.d(TAG, "Cycle " + cycle + " overran by " + (-gap) + "ms");
                }
            }

            updateLedFrame(new float[5]);
            StatusManager.setCallLedActive(false);
            Log.d(TAG, "Call animation loop stopped | " + name);
        });
    }

    public static void stopCall() {
        if (DEBUG) Log.d(TAG, "Disabling Call Animation");
        StatusManager.setCallLedEnabled(false);
    }

    public static void playEssential() {
        if (DEBUG) Log.d(TAG, "Playing Essential Animation");
        int led = ResourceUtils.getInteger("glyph_settings_notifs_essential_led");
        if (!StatusManager.isEssentialLedActive()) {
            submit(() -> {
                if (!check("essential", true))
                    return;

                StatusManager.setAnimationActive(true);

                try {
                    if (checkInterruption("essential")) throw new InterruptedException();
                    int[] steps = {1, 2, 4, 7};
                    for (int i : steps) {
                        if (checkInterruption("essential")) throw new InterruptedException();
                        updateLedSingle(led, Constants.getMaxBrightness() / 100 * i);
                        Thread.sleep(25);
                    }
                    Thread.sleep(250);
                } catch (InterruptedException e) {}

                StatusManager.setAnimationActive(false);
                StatusManager.setEssentialLedActive(true);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: essential");
            });
        } else {
            updateLedSingle(led, (float) Constants.getMaxBrightness() / 100 * 7);
            return;
        }
    }

    public static void stopEssential() {
        if (DEBUG) Log.d(TAG, "Disabling Essential Animation");
        StatusManager.setEssentialLedActive(false);
        if (!StatusManager.isAnimationActive() && !StatusManager.isAllLedActive()) {
            int led = ResourceUtils.getInteger("glyph_settings_notifs_essential_led");
            updateLedSingle(led, 0);
        }
    }

    public static void playMusic(String name) {
        submit(() -> {
            float maxBrightness = (float) Constants.getMaxBrightness();
            float[] pattern = new float[5];

            switch (name) {
                case "low":
                    pattern[4] = maxBrightness;
                    break;
                case "mid_low":
                    pattern[3] = maxBrightness;
                    break;
                case "mid":
                    pattern[2] = maxBrightness;
                    break;
                case "mid_high":
                    pattern[0] = maxBrightness;
                    break;
                case "high":
                    pattern[1] = maxBrightness;
                    break;
                default:
                    if (DEBUG) Log.d(TAG, "Name doesn't match any zone, returning | name: " + name);
                    return;
            }

            try {
                updateLedFrame(pattern, true);
                Thread.sleep(90);
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "Exception while playing animation | name: music: " + name + " | exception: " + e);
            } finally {
                updateLedFrame(new float[5], true);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: " + name);
            }
        });
    }

    private static void updateLedFrame(String[] pattern) {
        updateLedFrame(Arrays.stream(pattern)
                .mapToInt(Integer::parseInt)
                .toArray());
    }

    public static void updateLedFrame(int[] pattern) {
        float[] floatPattern = new float[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            floatPattern[i] = (float) pattern[i];
        }
        updateLedFrame(floatPattern);
    }

    private static void updateLedFrame(float[] pattern) {
        updateLedFrame(pattern, false);
    }

    private static void updateLedFrame(float[] pattern, boolean skipHaptic) {
        float maxFrameBrightness = 0;
        for (float b : pattern) {
            if (b > maxFrameBrightness) maxFrameBrightness = b;
        }
        if (!skipHaptic) playHaptic(maxFrameBrightness);

        float maxBrightness = (float) Constants.getMaxBrightness();
        int essentialLed = ResourceUtils.getInteger("glyph_settings_notifs_essential_led");
        if (StatusManager.isEssentialLedActive()) {
            if (pattern.length == 5) {
                if (pattern[1] < (maxBrightness / 100 * 7)) {
                    pattern[1] = maxBrightness / 100 * 7;
                }
            }
        }
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = pattern[i] / maxBrightness * Constants.getBrightness();
        }
        FileUtils.writeFrameLed(pattern);
    }

    private static void updateLedSingle(int led, String brightness) {
        updateLedSingle(led, Float.parseFloat(brightness));
    }

    private static void updateLedSingle(int led, int brightness) {
        updateLedSingle(led, (float) brightness);
    }

    private static void updateLedSingle(int led, float brightness) {
        playHaptic(brightness);
        float maxBrightness = (float) Constants.getMaxBrightness();
        int essentialLed = ResourceUtils.getInteger("glyph_settings_notifs_essential_led");
        if (StatusManager.isEssentialLedActive()
                && led == essentialLed
                && brightness < (maxBrightness / 100 * 7)) {
            brightness = maxBrightness / 100 * 7;
        }
        FileUtils.writeSingleLed(led, brightness / maxBrightness * Constants.getBrightness());
    }
}
