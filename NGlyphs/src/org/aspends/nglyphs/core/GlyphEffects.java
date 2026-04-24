package org.aspends.nglyphs.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.services.FlipToGlyphService;
import org.aspends.nglyphs.util.CustomRingtoneManager;
import org.aspends.nglyphs.util.OggMetadataParser;
import org.aspends.nglyphs.util.ShellUtils;

public class GlyphEffects {
    private static Future<?> activeEffectFuture;
    private static final ExecutorService effectExecutor = Executors.newSingleThreadExecutor();
    private static final AtomicLong sessionCounter = new AtomicLong(0);
    private static volatile int activeStreamType = -1;
    private static volatile boolean activeIsRingtone = false;
    private static android.media.MediaPlayer previewPlayer;

    private static final int FRAME_DURATION = 20;
    private static final GlyphManagerV2.Glyph[] GLYPH_ORDER = {GlyphManagerV2.Glyph.CAMERA,
            GlyphManagerV2.Glyph.DIAGONAL, GlyphManagerV2.Glyph.MAIN, GlyphManagerV2.Glyph.LINE,
            GlyphManagerV2.Glyph.DOT};

    private static class EffectInterruptedException extends Exception {}

    private static void safeSleep(long ms) throws EffectInterruptedException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new EffectInterruptedException();
        }
    }

    /**
     * Executes a specific lighting effect style on the device's glyph LEDs.
     * Optionally triggers synchronized vibrations.
     *
     * @param style           The name of the effect style to run (e.g., "breath",
     *                        "blink", "squirrels").
     * @param brightness      The maximum brightness level for the effects (0 to
     *                        MAX_BRIGHTNESS).
     * @param vibrator        A Vibrator instance to produce haptic feedback; can be
     *                        null.
     * @param context         Application or Service context, required for playing
     *                        custom audio.
     * @param audioStreamType The AudioManager stream type (e.g., STREAM_RING,
     *                        STREAM_NOTIFICATION).
     */
    public static void run(String style, int brightness, Vibrator vibrator,
            android.content.Context context, int audioStreamType) {
        run(style, brightness, vibrator, context, audioStreamType, false);
    }

    public static void run(String style, int brightness, Vibrator vibrator,
            android.content.Context context, int audioStreamType, boolean forceAudio) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
            if (audioStreamType != android.media.AudioManager.STREAM_RING
                    && prefs.getBoolean("is_on_call", false)
                    && prefs.getBoolean("stop_glyphs_during_call", false)) {
                return;
            }

            if (prefs.getBoolean("screen_off_only", false)
                    && audioStreamType != android.media.AudioManager.STREAM_RING && !forceAudio) {
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(
                        android.content.Context.POWER_SERVICE);
                if (pm != null && pm.isInteractive()) {
                    return;
                }
            }
        }
        // Removed is_light_on guard to allow merging patterns with Torch

        final android.content.Context finalCtx =
                context != null ? context.getApplicationContext() : null;

        // Strict Priority: Never allow any non-ringtone to stop a ringtone
        boolean isNewRequestRingtone = (audioStreamType == android.media.AudioManager.STREAM_RING);
        if (activeIsRingtone && !isNewRequestRingtone && activeEffectFuture != null
                && !activeEffectFuture.isDone()) {
            return;
        }

        stopCustomRingtone(); // Stop any currently playing effect
        activeStreamType = audioStreamType;
        activeIsRingtone = isNewRequestRingtone;
        final long sessionId = sessionCounter.incrementAndGet();

        activeEffectFuture = effectExecutor.submit(() -> {
            try {
                java.io.File customOgg =
                        CustomRingtoneManager.getCustomRingtoneFile(context, style);
                if (customOgg != null) {
                    AnimationManager.setHighPriorityActive(true);
                    executeCustomRingtone(customOgg, brightness, vibrator, context, audioStreamType,
                            sessionId, forceAudio);
                    return;
                }

                AnimationManager.setHighPriorityActive(true);

                if (style.startsWith("native_")) {
                    GlyphManagerV2 nativeManager = GlyphManagerV2.getInstance();
                    GlyphManagerV2.NativeEffect effect = null;
                    switch (style) {
                        case "native_all_white":
                            effect = GlyphManagerV2.NativeEffect.ALL_WHITE;
                            break;
                        case "native_frame":
                            effect = GlyphManagerV2.NativeEffect.FRAME;
                            break;
                        case "native_boot":
                            effect = GlyphManagerV2.NativeEffect.BOOT;
                            break;
                        case "native_breath":
                            effect = GlyphManagerV2.NativeEffect.BREATH;
                            break;
                        case "native_ringtone":
                            effect = GlyphManagerV2.NativeEffect.RINGTONE;
                            break;
                        case "native_assistant":
                            effect = GlyphManagerV2.NativeEffect.ASSISTANT;
                            break;
                        case "native_flip":
                            effect = GlyphManagerV2.NativeEffect.FLIP;
                            break;
                        case "native_music":
                            effect = GlyphManagerV2.NativeEffect.MUSIC;
                            break;
                        case "native_random":
                            effect = GlyphManagerV2.NativeEffect.RANDOM;
                            break;
                        case "native_video":
                            effect = GlyphManagerV2.NativeEffect.VIDEO;
                            break;
                        case "native_keyboard":
                            effect = GlyphManagerV2.NativeEffect.KEYBOARD;
                            break;
                        case "native_all":
                            effect = GlyphManagerV2.NativeEffect.ALL_EFFECT;
                            break;
                        case "native_horse_race":
                            effect = GlyphManagerV2.NativeEffect.HORSE_RACE;
                            break;
                        case "native_nf":
                            effect = GlyphManagerV2.NativeEffect.NF_EFFECT;
                            break;
                        case "native_exclamation":
                            effect = GlyphManagerV2.NativeEffect.EXCLAMATION;
                            break;
                    }
                    if (effect != null) {
                        if (forceAudio && finalCtx != null) {
                            finalCtx.sendBroadcast(new android.content
                                            .Intent("org.aspends.nglyphs.ACTION_AUDIO_PLAY")
                                            .setPackage(finalCtx.getPackageName())
                                            .putExtra("style", style));
                        }
                        nativeManager.setNativeEffect(effect, 1);
                        int duration = (audioStreamType == android.media.AudioManager.STREAM_RING)
                                ? 8000
                                : 1500;
                        try {
                            safeSleep(duration);
                        } finally {
                            nativeManager.setNativeEffect(effect, 0);
                            nativeManager.setFrame(
                                    new int[15]); // Force-clear potential ghosted frames
                            if (finalCtx != null) {
                                finalCtx.sendBroadcast(new android.content
                                                .Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL)
                                                .setPackage(finalCtx.getPackageName()));
                            }
                        }
                    }
                    return;
                }

                switch (style) {
                    // styles from 1.0
                    case "static":
                        vibrate(vibrator, 30, 0, context, audioStreamType);
                        updateAll(brightness, context, audioStreamType);
                        safeSleep(400);
                        updateAll(0, context, audioStreamType);
                        break;

                    case "breath":
                        for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
                            flash(g, brightness, 100, vibrator, context, audioStreamType);
                        }
                        break;

                    case "blink":
                        for (int i = 0; i < 2; i++) {
                            vibrate(vibrator, 25, 0, context, audioStreamType);
                            updateAll(brightness, context, audioStreamType);
                            safeSleep(100);
                            updateAll(0, context, audioStreamType);
                            safeSleep(100);
                        }
                        break;

                    case "stock":
                        int speed = 80;
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.DOT, brightness);
                        safeSleep(speed);

                        vibrate(vibrator, 30, 0, context, audioStreamType);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.LINE, brightness);
                        GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.DOT, 0);
                        safeSleep(speed);

                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.MAIN, brightness);
                        GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.LINE, 0);
                        safeSleep(speed);

                        vibrate(vibrator, 30, 0, context, audioStreamType);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.DIAGONAL, brightness);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.CAMERA, brightness);
                        GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.MAIN, 0);
                        safeSleep(speed + 20);

                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.DIAGONAL, 0);
                        GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.CAMERA, 0);
                        safeSleep(speed);
                        break;

                    // calls
                    // ported from nos
                    case "pneumatic":
                        for (int i = 0; i < 10; i++)
                            flash(GlyphManagerV2.Glyph.LINE, brightness, 40, vibrator, context,
                                    audioStreamType);
                        break;

                    case "abra":
                        for (int cycle = 0; cycle < 2; cycle++) {
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 80, vibrator, context,
                                    audioStreamType);
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 80, vibrator, context,
                                    audioStreamType);
                            flash(GlyphManagerV2.Glyph.CAMERA, brightness, 100, vibrator, context,
                                    audioStreamType);
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 80, vibrator, context,
                                    audioStreamType);
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 80, vibrator, context,
                                    audioStreamType);
                        }
                        for (int i = 0; i < 4; i++)
                            flash(GlyphManagerV2.Glyph.LINE, brightness, 80, vibrator, context,
                                    audioStreamType);
                        break;

                    case "squirrels":
                        for (int i = 0; i < 3; i++)
                            flash(GlyphManagerV2.Glyph.CAMERA, brightness, 50, vibrator, context,
                                    audioStreamType);
                        flash(GlyphManagerV2.Glyph.MAIN, brightness, 100, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.LINE, brightness, 100, vibrator, context,
                                audioStreamType);
                        break;

                    case "snaps":
                        for (int i = 0; i < 2; i++)
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 150, vibrator, context,
                                    audioStreamType);
                        break;

                    case "radiate":
                        flash(GlyphManagerV2.Glyph.LINE, brightness, 60, vibrator, context,
                                audioStreamType);
                        for (int i = 0; i < 7; i++)
                            flash(GlyphManagerV2.Glyph.MAIN, brightness, 50, vibrator, context,
                                    audioStreamType);
                        break;

                    case "tennis":
                        for (int i = 0; i < 3; i++)
                            flash(GlyphManagerV2.Glyph.LINE, brightness, 60, vibrator, context,
                                    audioStreamType);
                        flash(GlyphManagerV2.Glyph.DOT, brightness, 80, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.LINE, brightness, 80, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.DOT, brightness, 80, vibrator, context,
                                audioStreamType);
                        break;

                    case "plot":
                        flash(GlyphManagerV2.Glyph.LINE, brightness, 150, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.CAMERA, brightness, 150, vibrator, context,
                                audioStreamType);

                        for (int i = 0; i < 2; i++)
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 80, vibrator, context,
                                    audioStreamType);

                        flash(GlyphManagerV2.Glyph.LINE, brightness, 150, vibrator, context,
                                audioStreamType);
                        for (int i = 0; i < 5; i++)
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 60, vibrator, context,
                                    audioStreamType);

                        flash(GlyphManagerV2.Glyph.LINE, brightness, 300, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.CAMERA, brightness, 300, vibrator, context,
                                audioStreamType);

                        for (int i = 0; i < 9; i++) {
                            vibrate(vibrator, 25, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.MAIN, brightness);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.LINE, brightness);
                            safeSleep(60);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.MAIN, 0);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.LINE, 0);
                            safeSleep(60);
                        }
                        break;

                    case "scribble":
                        flash(GlyphManagerV2.Glyph.CAMERA, brightness, 70, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.LINE, brightness, 70, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.CAMERA, brightness, 70, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.LINE, brightness, 70, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.DOT, brightness, 70, vibrator, context,
                                audioStreamType);

                        for (int i = 0; i < 3; i++)
                            flash(GlyphManagerV2.Glyph.LINE, brightness, 70, vibrator, context,
                                    audioStreamType);

                        for (int i = 0; i < 2; i++)
                            flash(GlyphManagerV2.Glyph.LINE, brightness, 120, vibrator, context,
                                    audioStreamType);
                        break;

                    // notifications
                    // from nos
                    // some of them suck
                    case "oi":
                        for (int i = 0; i < 3; i++)
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 80, vibrator, context,
                                    audioStreamType);
                        break;

                    case "nope":
                        for (int i = 0; i < 3; i++) {
                            vibrate(vibrator, 20, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.CAMERA, brightness);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.MAIN, brightness);
                            safeSleep(100);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.CAMERA, 0);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.MAIN, 0);
                            safeSleep(100);
                        }
                        break;

                    case "why":
                        flash(GlyphManagerV2.Glyph.DIAGONAL, brightness, 100, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.CAMERA, brightness, 100, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.MAIN, brightness, 100, vibrator, context,
                                audioStreamType);
                        break;

                    case "bulb_one":
                        flash(GlyphManagerV2.Glyph.MAIN, brightness, 120, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.LINE, brightness, 120, vibrator, context,
                                audioStreamType);
                        flash(GlyphManagerV2.Glyph.DOT, brightness, 300, vibrator, context,
                                audioStreamType);
                        break;

                    case "bulb_two":
                        vibrate(vibrator, 50, 0, context, audioStreamType);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.CAMERA, brightness);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.MAIN, brightness);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.LINE, brightness);
                        safeSleep(300);
                        updateAll(0, context, audioStreamType);
                        safeSleep(100);
                        flash(GlyphManagerV2.Glyph.DIAGONAL, brightness, 300, vibrator, context,
                                audioStreamType);
                        break;

                    case "guiro":
                        GlyphManagerV2.Glyph[] order = {GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.MAIN, GlyphManagerV2.Glyph.LINE,
                                GlyphManagerV2.Glyph.DOT};
                        for (GlyphManagerV2.Glyph g : order)
                            flash(g, brightness, 60, vibrator, context, audioStreamType);
                        break;

                    case "squiggle":
                        for (int i = 0; i < 2; i++) {
                            vibrate(vibrator, 30, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.CAMERA, brightness);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.LINE, brightness);
                            safeSleep(70);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.CAMERA, 0);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.LINE, 0);
                            safeSleep(70);
                        }

                        flash(GlyphManagerV2.Glyph.LINE, brightness, 70, vibrator, context,
                                audioStreamType);

                        for (int i = 0; i < 2; i++) {
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 70, vibrator, context,
                                    audioStreamType);
                        }
                        break;

                    // new patterns
                    case "wave":
                        GlyphManagerV2.Glyph[] waveOrder = {GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.MAIN, GlyphManagerV2.Glyph.LINE,
                                GlyphManagerV2.Glyph.DOT, GlyphManagerV2.Glyph.DIAGONAL};
                        for (GlyphManagerV2.Glyph g : waveOrder) {
                            vibrate(vibrator, 30, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(g, brightness);
                            safeSleep(50);
                        }
                        safeSleep(50);
                        for (int i = waveOrder.length - 1; i >= 0; i--) {
                            GlyphManagerV2.getInstance().setBrightness(waveOrder[i], 0);
                            safeSleep(50);
                        }
                        break;

                    case "heartbeat":
                        flash(GlyphManagerV2.Glyph.MAIN, brightness, 60, vibrator, context,
                                audioStreamType);
                        safeSleep(60);
                        flash(GlyphManagerV2.Glyph.MAIN, brightness, 100, vibrator, context,
                                audioStreamType);
                        safeSleep(300);
                        break;

                    case "strobe":
                        for (int i = 0; i < 5; i++) {
                            vibrate(vibrator, 25, 0, context, audioStreamType);
                            updateAll(brightness, context, audioStreamType);
                            safeSleep(40);
                            updateAll(0, context, audioStreamType);
                            safeSleep(40);
                        }
                        break;
                    case "bounce":
                        GlyphManagerV2.Glyph[] bounceOrder = {GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.MAIN, GlyphManagerV2.Glyph.LINE,
                                GlyphManagerV2.Glyph.DOT};
                        for (int i = 0; i < bounceOrder.length; i++) {
                            vibrate(vibrator, 30, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(bounceOrder[i], brightness);
                            safeSleep(60);
                            GlyphManagerV2.getInstance().setBrightness(bounceOrder[i], 0);
                        }
                        for (int i = bounceOrder.length - 2; i >= 1; i--) {
                            vibrate(vibrator, 25, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(bounceOrder[i], brightness);
                            safeSleep(60);
                            GlyphManagerV2.getInstance().setBrightness(bounceOrder[i], 0);
                        }
                        break;

                    case "sparkle":
                        GlyphManagerV2.Glyph[] allGlyphs = GlyphManagerV2.Glyph.getBasicGlyphs();
                        for (int i = 0; i < 15; i++) {
                            GlyphManagerV2.Glyph target =
                                    allGlyphs[(int) (Math.random() * allGlyphs.length)];
                            vibrate(vibrator, 5, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(target, brightness);
                            safeSleep(30);
                            GlyphManagerV2.getInstance().setBrightness(target, 0);
                            safeSleep(30);
                        }
                        break;

                    case "zigzag":
                        GlyphManagerV2.Glyph[] zigzagOrder = {GlyphManagerV2.Glyph.DIAGONAL,
                                GlyphManagerV2.Glyph.MAIN, GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.LINE, GlyphManagerV2.Glyph.DOT};
                        for (int i = 0; i < 2; i++) {
                            for (GlyphManagerV2.Glyph g : zigzagOrder) {
                                vibrate(vibrator, 30, 0, context, audioStreamType);
                                GlyphManagerV2.getInstance().setBrightness(g, brightness);
                                safeSleep(50);
                                GlyphManagerV2.getInstance().setBrightness(g, 0);
                            }
                        }
                        break;
                    case "drop":
                        flash(GlyphManagerV2.Glyph.DOT, brightness, 50, vibrator, context,
                                audioStreamType);
                        break;

                    case "tap":
                        for (int i = 0; i < 2; i++) {
                            flash(GlyphManagerV2.Glyph.CAMERA, brightness, 30, vibrator, context,
                                    audioStreamType);
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 30, vibrator, context,
                                    audioStreamType);
                            safeSleep(30);
                        }
                        break;

                    case "slide":
                        GlyphManagerV2.Glyph[] slideOrder = {GlyphManagerV2.Glyph.DIAGONAL,
                                GlyphManagerV2.Glyph.LINE, GlyphManagerV2.Glyph.DOT};
                        for (GlyphManagerV2.Glyph g : slideOrder) {
                            vibrate(vibrator, 25, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(g, brightness);
                            safeSleep(40);
                            GlyphManagerV2.getInstance().setBrightness(g, 0);
                        }
                        break;

                    case "symphony":
                        for (int m = 0; m < 3; m++) { // 3 measures
                            // Beat 1: Strong thud
                            flash(GlyphManagerV2.Glyph.MAIN, brightness, 150, vibrator, context,
                                    audioStreamType);
                            safeSleep(100);
                            // Beat 2: Quick accents
                            for (int i = 0; i < 2; i++) {
                                flash(GlyphManagerV2.Glyph.CAMERA, brightness, 40, vibrator,
                                        context, audioStreamType);
                                flash(GlyphManagerV2.Glyph.DOT, brightness, 40, vibrator, context,
                                        audioStreamType);
                            }
                            safeSleep(100);
                            // Beat 3: Swell
                            vibrate(vibrator, 80, 0, context, audioStreamType);
                            updateAll(brightness, context, audioStreamType);
                            safeSleep(200);
                            updateAll(0, context, audioStreamType);
                            safeSleep(300);
                        }
                        break;

                    case "crescendo":
                        for (int duration = 150; duration >= 30; duration -= 30) {
                            vibrate(vibrator, duration / 2, 0, context, audioStreamType);
                            updateAll(brightness, context, audioStreamType);
                            safeSleep(duration);
                            updateAll(0, context, audioStreamType);
                            safeSleep(duration);
                        }
                        for (int i = 0; i < 6; i++) {
                            flash(GlyphManagerV2.Glyph.MAIN, brightness, 20, vibrator, context,
                                    audioStreamType);
                        }
                        safeSleep(500);
                        break;

                    case "marimba":
                        GlyphManagerV2.Glyph[] marimbaNodes = {GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.LINE, GlyphManagerV2.Glyph.DOT,
                                GlyphManagerV2.Glyph.DIAGONAL};
                        for (int cycle = 0; cycle < 5; cycle++) {
                            for (GlyphManagerV2.Glyph g : marimbaNodes) {
                                vibrate(vibrator, 30, 0, context, audioStreamType);
                                GlyphManagerV2.getInstance().setBrightness(g, brightness);
                                safeSleep(30);
                                GlyphManagerV2.getInstance().setBrightness(g, 0);
                                safeSleep(20);
                            }
                        }
                        break;

                    case "sweep":
                        vibrate(vibrator, 20, 0, context, audioStreamType);
                        GlyphManagerV2.Glyph[] sweepOrder = {GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.DIAGONAL, GlyphManagerV2.Glyph.MAIN,
                                GlyphManagerV2.Glyph.LINE, GlyphManagerV2.Glyph.DOT};
                        for (GlyphManagerV2.Glyph g : sweepOrder) {
                            GlyphManagerV2.getInstance().setBrightness(g, brightness);
                            safeSleep(100);
                            GlyphManagerV2.getInstance().setBrightness(g, 0);
                        }
                        break;

                    case "metronome":
                        for (int i = 0; i < 4; i++) {
                            vibrate(vibrator, 30, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.DOT, brightness);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.MAIN, brightness);
                            safeSleep(60);
                            GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.DOT, 0);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.MAIN, 0);
                            safeSleep(400);
                        }
                        break;

                    case "sizzle":
                        GlyphManagerV2.Glyph[] sz = GlyphManagerV2.Glyph.getBasicGlyphs();
                        for (int i = 0; i < 20; i++) {
                            GlyphManagerV2.Glyph random = sz[(int) (Math.random() * sz.length)];
                            vibrate(vibrator, 5, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(
                                    random, (int) (brightness * 0.5));
                            safeSleep(20);
                            GlyphManagerV2.getInstance().setBrightness(random, 0);
                            safeSleep(20);
                        }
                        updateAll(brightness, context, audioStreamType);
                        vibrate(vibrator, 50, 0, context, audioStreamType);
                        safeSleep(100);
                        updateAll(0, context, audioStreamType);
                        break;

                    case "ping":
                        for (int i = 0; i < 3; i++) {
                            vibrate(vibrator, 20, 0, context, audioStreamType);
                            GlyphManagerV2.getInstance().setBrightness(
                                    GlyphManagerV2.Glyph.DOT, brightness);
                            safeSleep(30);
                            GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.DOT, 0);
                            safeSleep(100);
                            vibrate(vibrator, 30, 0, context, audioStreamType);
                            for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
                                if (g != GlyphManagerV2.Glyph.DOT) {
                                    GlyphManagerV2.getInstance().setBrightness(
                                            g, (int) (brightness * 0.3));
                                }
                            }
                            safeSleep(80);
                            updateAll(0, context, audioStreamType);
                            safeSleep(300);
                        }
                        break;

                    case "fade":
                        vibrate(vibrator, 50, 0, context, audioStreamType);
                        for (int i = 0; i <= brightness; i += Math.max(1, brightness / 10)) {
                            updateAll(i, context, audioStreamType);
                            safeSleep(30);
                        }
                        for (int i = brightness; i >= 0; i -= Math.max(1, brightness / 10)) {
                            updateAll(i, context, audioStreamType);
                            safeSleep(30);
                        }
                        updateAll(0, context, audioStreamType);
                        break;

                    case "knight_rider":
                        vibrate(vibrator, 30, 0, context, audioStreamType);
                        GlyphManagerV2.Glyph[] riderOrder = {GlyphManagerV2.Glyph.DIAGONAL,
                                GlyphManagerV2.Glyph.LINE, GlyphManagerV2.Glyph.DOT,
                                GlyphManagerV2.Glyph.LINE, GlyphManagerV2.Glyph.DIAGONAL};
                        for (GlyphManagerV2.Glyph g : riderOrder) {
                            GlyphManagerV2.getInstance().setBrightness(g, brightness);
                            safeSleep(80);
                            GlyphManagerV2.getInstance().setBrightness(g, 0);
                        }
                        break;

                    case "twinkle":
                        for (int m = 0; m < 6; m++) {
                            GlyphManagerV2.Glyph randomGlyph =
                                    GlyphManagerV2.Glyph.getBasicGlyphs()[(int) (Math.random()
                                            * GlyphManagerV2.Glyph.getBasicGlyphs().length)];
                            GlyphManagerV2.getInstance().setBrightness(randomGlyph, brightness);
                            if (m % 2 == 0)
                                vibrate(vibrator, 30, 0, context, audioStreamType);
                            safeSleep(70 + (long) (Math.random() * 50));
                            GlyphManagerV2.getInstance().setBrightness(randomGlyph, 0);
                            safeSleep(50);
                        }
                        break;

                    case "radar":
                        vibrate(vibrator, 40, 0, context, audioStreamType);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.DOT, brightness);
                        safeSleep(100);
                        GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.DOT, 0);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.LINE, brightness);
                        safeSleep(100);
                        GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.LINE, 0);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.MAIN, brightness);
                        safeSleep(200);
                        GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.MAIN, 0);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.CAMERA, brightness);
                        GlyphManagerV2.getInstance().setBrightness(
                                GlyphManagerV2.Glyph.DIAGONAL, brightness);
                        safeSleep(150);
                        updateAll(0, context, audioStreamType);
                        break;

                    case "fireflies":
                        for (int i = 0; i < 20; i++) {
                            GlyphManagerV2.Glyph g =
                                    GlyphManagerV2.Glyph
                                            .getBasicGlyphs()[(int) (Math.random() * 5)];
                            GlyphManagerV2.getInstance().setBrightness(g, (int) (brightness * 0.4));
                            vibrate(vibrator, 20, 0, context, audioStreamType);
                            safeSleep(40);
                            GlyphManagerV2.getInstance().setBrightness(g, 0);
                            safeSleep(20);
                        }
                        break;

                    case "spotlight":
                        for (int i = 0; i < 3; i++) {
                            vibrate(vibrator, 40, 0, context, audioStreamType);
                            for (int b = 0; b <= brightness; b += brightness / 5) {
                                GlyphManagerV2.getInstance().setBrightness(
                                        GlyphManagerV2.Glyph.MAIN, b);
                                safeSleep(30);
                            }
                            for (int b = brightness; b >= 0; b -= brightness / 5) {
                                GlyphManagerV2.getInstance().setBrightness(
                                        GlyphManagerV2.Glyph.MAIN, b);
                                safeSleep(30);
                            }
                        }
                        break;

                    case "scanline":
                        GlyphManagerV2.Glyph[] scanOrder = {GlyphManagerV2.Glyph.LINE,
                                GlyphManagerV2.Glyph.DIAGONAL, GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.MAIN};
                        for (int i = 0; i < 3; i++) {
                            for (GlyphManagerV2.Glyph g : scanOrder) {
                                vibrate(vibrator, 25, 0, context, audioStreamType);
                                GlyphManagerV2.getInstance().setBrightness(g, brightness);
                                safeSleep(60);
                                GlyphManagerV2.getInstance().setBrightness(g, 0);
                            }
                        }
                        break;

                    case "spiral":
                        GlyphManagerV2.Glyph[] spiralOrder = {GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.DIAGONAL, GlyphManagerV2.Glyph.MAIN,
                                GlyphManagerV2.Glyph.LINE, GlyphManagerV2.Glyph.DOT};
                        for (int i = 0; i < 3; i++) {
                            for (GlyphManagerV2.Glyph g : spiralOrder) {
                                vibrate(vibrator, 30, 0, context, audioStreamType);
                                GlyphManagerV2.getInstance().setBrightness(g, brightness);
                                safeSleep(80);
                                GlyphManagerV2.getInstance().setBrightness(g, 0);
                            }
                        }
                        break;

                    case "raindrops":
                        for (int i = 0; i < 5; i++) {
                            flash(GlyphManagerV2.Glyph.DOT, brightness, 50, vibrator, context,
                                    audioStreamType);
                            flash(GlyphManagerV2.Glyph.LINE, (int) (brightness * 0.7), 70, vibrator,
                                    context, audioStreamType);
                            safeSleep(100);
                        }
                        break;

                    case "heartbeat_rapid":
                        for (int i = 0; i < 4; i++) {
                            flash(GlyphManagerV2.Glyph.MAIN, brightness, 40, vibrator, context,
                                    audioStreamType);
                            safeSleep(40);
                            flash(GlyphManagerV2.Glyph.MAIN, brightness, 40, vibrator, context,
                                    audioStreamType);
                            safeSleep(250);
                        }
                        break;

                    case "blink_sync":
                        for (int i = 0; i < 3; i++) {
                            vibrate(vibrator, 50, 0, context, audioStreamType);
                            updateAll(brightness, context, audioStreamType);
                            safeSleep(100);
                            updateAll(0, context, audioStreamType);
                            safeSleep(100);
                        }
                        break;

                    case "sequence_run":
                        GlyphManagerV2.Glyph[] runAll = {GlyphManagerV2.Glyph.CAMERA,
                                GlyphManagerV2.Glyph.DIAGONAL, GlyphManagerV2.Glyph.MAIN,
                                GlyphManagerV2.Glyph.LINE, GlyphManagerV2.Glyph.DOT};
                        for (int cycl = 0; cycl < 4; cycl++) {
                            for (GlyphManagerV2.Glyph g : runAll) {
                                vibrate(vibrator, 25, 0, context, audioStreamType);
                                GlyphManagerV2.getInstance().setBrightness(g, brightness);
                                safeSleep(50);
                                GlyphManagerV2.getInstance().setBrightness(g, 0);
                            }
                        }
                        break;

                    case "chaos":
                        for (int i = 0; i < 40; i++) {
                            GlyphManagerV2.Glyph g =
                                    GlyphManagerV2.Glyph
                                            .getBasicGlyphs()[(int) (Math.random() * 5)];
                            GlyphManagerV2.getInstance().setBrightness(
                                    g, (int) (Math.random() * brightness));
                            if (i % 4 == 0)
                                vibrate(vibrator, 25, 0, context, audioStreamType);
                            safeSleep(30);
                            GlyphManagerV2.getInstance().setBrightness(g, 0);
                        }
                        break;

                    case "aurora":
                        for (int i = 0; i < 2; i++) {
                            for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
                                for (int b = 0; b <= brightness; b += brightness / 10) {
                                    GlyphManagerV2.getInstance().setBrightness(g, b);
                                    safeSleep(20);
                                }
                                vibrate(vibrator, 30, 0, context, audioStreamType);
                                for (int b = brightness; b >= 0; b -= brightness / 10) {
                                    GlyphManagerV2.getInstance().setBrightness(g, b);
                                    safeSleep(20);
                                }
                            }
                        }
                        break;
                }
            } catch (EffectInterruptedException e) {
                // Abort the effect and turn off LEDs
                updateAll(0, context, audioStreamType);
            } finally {
                if (sessionCounter.get() == sessionId) {
                    AnimationManager.setHighPriorityActive(false);
                    updateAll(0, context, audioStreamType);
                    if (finalCtx != null) {
                        finalCtx.sendBroadcast(new android.content
                                        .Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL)
                                        .setPackage(finalCtx.getPackageName()));
                    }
                }
            }
        });
    }

    /**
     * Briefly flashes a single glyph LED and triggers a short vibration.
     *
     * @param g The specific glyph to flash.
     * @param b The brightness level.
     * @param d The duration in milliseconds for the flash state.
     * @param v A Vibrator instance for haptics.
     */
    private static void flash(GlyphManagerV2.Glyph g, int b, int d, android.os.Vibrator v,
            android.content.Context context, int audioStreamType)
            throws EffectInterruptedException {
        vibrate(v, 30, 0, context, audioStreamType);
        GlyphManagerV2.getInstance().setBrightness(g, b);
        safeSleep(d);
        GlyphManagerV2.getInstance().setBrightness(g, 0);
        safeSleep(d);
    }

    private static void updateAll(int val, android.content.Context context, int audioStreamType) {
        for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
            GlyphManagerV2.getInstance().setBrightness(g, val);
        }
        if (val == 0 && context != null) {
            context.sendBroadcast(
                    new android.content.Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL)
                            .setPackage(context.getPackageName()));
        }
    }

    /**
     * Triggers a simple one-shot vibration on the device.
     * Uses USAGE_ALARM to ensure background execution through doze states.
     *
     * @param v  The Vibrator instance.
     * @param ms The duration of the vibration in milliseconds.
     */
    private static void vibrate(Vibrator v, int ms, int amplitude, android.content.Context context,
            int audioStreamType) {
        if (v != null && v.hasVibrator() && context != null) {
            String prefFile = context.getString(org.aspends.nglyphs.R.string.pref_file);
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(prefFile, android.content.Context.MODE_PRIVATE);
            if (!prefs.getBoolean("ring_notif_haptics_enabled", true))
                return;

            int hapticStrength = prefs.getInt("ring_notif_haptic_strength", 100);
            vibrateDirect(v, ms, amplitude, hapticStrength, audioStreamType);
        }
    }

    private static void vibrateDirect(
            Vibrator v, int ms, int amplitude, int hapticStrength, int audioStreamType) {
        if (v == null || !v.hasVibrator())
            return;

        // Final amplitude scaled by user strength (0-100)
        int baseAmplitude = (amplitude > 0) ? amplitude : 255;
        int finalAmplitude = (baseAmplitude * hapticStrength) / 100;
        finalAmplitude = Math.max(1, Math.min(finalAmplitude, 255));

        // Scale duration as requested
        int finalMs = (int) (ms * (hapticStrength / 100f));
        finalMs = Math.max(5, finalMs);

        int usage = android.os.VibrationAttributes.USAGE_NOTIFICATION;
        if (audioStreamType == android.media.AudioManager.STREAM_RING) {
            usage = android.os.VibrationAttributes.USAGE_RINGTONE;
        }

        android.os.VibrationAttributes attrs =
                new android.os.VibrationAttributes.Builder().setUsage(usage).build();

        try {
            v.vibrate(android.os.VibrationEffect.createOneShot(finalMs, finalAmplitude), attrs);
        } catch (Exception ignored) {
        }
    }

    public static boolean isActive() {
        return activeEffectFuture != null && !activeEffectFuture.isDone();
    }

    public static void muteActiveAudio() {
        // Audio now handled by system RingtoneManager
    }

    public static void stopCustomRingtone() {
        activeStreamType = -1;
        activeIsRingtone = false;
        if (activeEffectFuture != null) {
            activeEffectFuture.cancel(true);
            activeEffectFuture = null;
        }
        if (previewPlayer != null) {
            try {
                if (previewPlayer.isPlaying())
                    previewPlayer.stop();
                previewPlayer.release();
            } catch (Exception ignored) {
            }
            previewPlayer = null;
        }
        AnimationManager.setHighPriorityActive(false);
        GlyphManagerV2.getInstance().toggleAll(false);
        AnimationManager.refreshBackgroundState();
    }

    /**
     * Stage an app-internal file into external cache so mediaserver (s0)
     * can read it. Internal paths carry per-app MLS categories that block
     * mediaserver; external cache is labelled media_rw_data_file.
     */
    private static java.io.File stageForMediaserver(
            android.content.Context context, java.io.File src) throws java.io.IOException {
        if (context == null || src == null || !src.exists()) {
            return src;
        }
        java.io.File extCache = context.getExternalCacheDir();
        if (extCache == null) {
            return src;
        }
        java.io.File staged = new java.io.File(extCache, "preview_" + src.getName());
        if (staged.exists() && staged.length() == src.length()
                && staged.lastModified() >= src.lastModified()) {
            return staged;
        }
        try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(staged)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
        return staged;
    }

    private static void executeCustomRingtone(java.io.File oggFile, int brightness,
            Vibrator vibrator, android.content.Context context, int audioStreamType, long sessionId,
            boolean forceAudio) {
        final android.content.Context finalCtx =
                context != null ? context.getApplicationContext() : null;
        boolean isRingtone = (audioStreamType == android.media.AudioManager.STREAM_RING);
        String timeline;
        String fileName = oggFile.getName();
        java.io.File customCsv = new java.io.File(
                context.getFilesDir(), "custom_ringtones/" + fileName.replace(".ogg", ".csv"));
        if (customCsv.exists()) {
            timeline = org.aspends.nglyphs.util.CustomRingtoneManager.loadCSV(customCsv);
        } else {
            timeline = OggMetadataParser.extractGlyphTimeline(oggFile);
        }
        // Audio playback: ONLY during user preview (forceAudio = true)
        if (forceAudio) {
            try {
                // Audio Play Broadcast for selection screen synchronization
                context.sendBroadcast(
                        new android.content.Intent("org.aspends.nglyphs.ACTION_AUDIO_PLAY")
                                .setPackage(context.getPackageName())
                                .putExtra("style", oggFile.getName()));

                android.util.Log.d(
                        "GlyphEffects", "Playing preview for: " + oggFile.getAbsolutePath());
                if (!oggFile.exists()) {
                    android.util.Log.e(
                            "GlyphEffects", "File does not exist: " + oggFile.getAbsolutePath());
                }

                java.io.File playable = stageForMediaserver(context, oggFile);
                previewPlayer = new android.media.MediaPlayer();
                previewPlayer.setDataSource(playable.getAbsolutePath());

                int usage = android.media.AudioAttributes.USAGE_NOTIFICATION;
                if (audioStreamType == android.media.AudioManager.STREAM_RING) {
                    usage = android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
                }

                previewPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(usage)
                                .setContentType(
                                        android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());

                previewPlayer.setLooping(isRingtone);

                previewPlayer.setOnErrorListener((mp, what, extra) -> {
                    android.util.Log.e(
                            "GlyphEffects", "MediaPlayer Error: what=" + what + " extra=" + extra);
                    return false;
                });

                previewPlayer.prepare();

                ShellUtils.clearQueue(); // Ensure clean start
                previewPlayer.start();
                android.util.Log.d("GlyphEffects", "MediaPlayer started successfully.");
            } catch (Exception e) {
                android.util.Log.e(
                        "GlyphEffects", "MediaPlayer failure for " + oggFile.getName(), e);
            }
        } else {
            ShellUtils.clearQueue();
        }

        try {
            if (timeline != null && !timeline.isEmpty()) {
                String[] frames = timeline.split("\\r?\\n");
                long lastHapticTime = 0;
                int lastIntensity = 0;
                float scale = brightness / 4095f;
                android.content.SharedPreferences prefs =
                        context.getSharedPreferences(context.getString(R.string.pref_file),
                                android.content.Context.MODE_PRIVATE);
                int hapticStrength = prefs.getInt("ring_notif_haptic_strength", 100);
                boolean hapticsEnabled = prefs.getBoolean("ring_notif_haptics_enabled", true);
                boolean isLightOn = prefs.getBoolean("is_light_on", false);
                int torchBrightness = prefs.getInt("torch_brightness", 2048);

                boolean isRingtoneLoop = isRingtone; // Use separate flag for loop
                long startTime = android.os.SystemClock.elapsedRealtime();
                int totalFrameOffset = 0;

                do {
                    for (int frameIdx = 0; frameIdx < frames.length; frameIdx++) {
                        if (Thread.currentThread().isInterrupted())
                            break;

                        String line = frames[frameIdx].trim();
                        if (line.isEmpty())
                            continue;

                        String[] cols = line.split(",");
                        if (cols.length >= 5) {
                            try {
                                int torchVal = isLightOn ? torchBrightness : 0;
                                int[] frame = new int[15];
                                int peakIntensity = 0;

                                if (cols.length >= 15) {
                                    for (int i = 0; i < 15; i++) {
                                        int val = (int) (Integer.parseInt(cols[i]) * scale);
                                        val = Math.max(val, torchVal);
                                        frame[i] = val;
                                        peakIntensity = Math.max(peakIntensity, val);
                                    }
                                } else {
                                    int vCam = Math.max(
                                            (int) (Integer.parseInt(cols[0]) * scale), torchVal);
                                    int vDiag = Math.max(
                                            (int) (Integer.parseInt(cols[1]) * scale), torchVal);
                                    int vBattery = Math.max(
                                            (int) (Integer.parseInt(cols[2]) * scale), torchVal);
                                    int vLine = Math.max(
                                            (int) (Integer.parseInt(cols[3]) * scale), torchVal);
                                    int vDot = Math.max(
                                            (int) (Integer.parseInt(cols[4]) * scale), torchVal);

                                    frame[0] = vCam;
                                    frame[1] = vDiag;
                                    frame[2] = frame[3] = frame[4] = frame[5] = vBattery;
                                    frame[6] = vDot;
                                    for (int i = 7; i <= 14; i++) frame[i] = vLine;
                                    peakIntensity = Math.max(vCam,
                                            Math.max(vDiag,
                                                    Math.max(vBattery, Math.max(vLine, vDot))));
                                }

                                GlyphManagerV2.getInstance().setFrame(frame);

                                // Haptic Sync: Trigger pulse following peak intensity with better
                                // delta detection for "perfect" sync
                                long now = android.os.SystemClock.elapsedRealtime();
                                // Detect a significant jump (>10% of max) or a peak to feel crisp
                                int deltaThreshold = 450;
                                if (hapticsEnabled
                                        && (peakIntensity > lastIntensity + deltaThreshold
                                                || (peakIntensity > 3500 && lastIntensity < 3000))
                                        && (now - lastHapticTime > 65)) {
                                    // Power-weighted scaling makes haptics feel more responsive to
                                    // brightness Using a steeper curve for even more "crisp" feel
                                    // at high intensities
                                    int intensityAmplitude =
                                            (int) (Math.pow(peakIntensity / 4095.0, 0.6) * 255);
                                    vibrateDirect(vibrator, 20, intensityAmplitude, hapticStrength,
                                            audioStreamType);
                                    lastHapticTime = now;
                                }
                                lastIntensity = peakIntensity;

                            } catch (Exception e) {
                            }
                        }

                        // Sync to 16.6ms (60Hz) using wall-clock compensation
                        long expectedTime =
                                startTime + (long) ((totalFrameOffset + frameIdx + 1) * 16.666);
                        long now = android.os.SystemClock.elapsedRealtime();
                        long sleepTime = expectedTime - now;

                        if (sleepTime > 0) {
                            safeSleep(sleepTime);
                        } else if (sleepTime < -33) {
                            // CATCH-UP LOGIC: We are more than 2 frames behind.
                            // Skip frames to match the current wall-clock time.
                            int skipCount = (int) (Math.abs(sleepTime) / 16.666);
                            if (frameIdx + skipCount < frames.length) {
                                frameIdx += skipCount;
                            }
                        }
                    }
                    totalFrameOffset += frames.length;
                } while (isRingtoneLoop && !Thread.currentThread().isInterrupted());
            }

            // sync loop finishes
        } catch (EffectInterruptedException e) {
            // Stop requested
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (sessionCounter.get() == sessionId) {
                updateAll(0, context, audioStreamType);
                GlyphManagerV2.getInstance().setFrame(new int[15]); // Clear any meter data
                if (finalCtx != null) {
                    finalCtx.sendBroadcast(
                            new android.content.Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL)
                                    .setPackage(finalCtx.getPackageName()));
                }
            }
        }
    }

    public static void play(android.content.Context context, String folder, String fileName,
            Vibrator vibrator, int brightness) {
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);

            if (prefs.getBoolean("is_on_call", false)
                    && prefs.getBoolean("stop_glyphs_during_call", false)) {
                return;
            }
        }

        // Notifications (which use play) cannot interrupt a ringtone
        if (activeStreamType == android.media.AudioManager.STREAM_RING && activeEffectFuture != null
                && !activeEffectFuture.isDone()) {
            return;
        }

        stopCustomRingtone();
        final android.content.Context appCtx =
                context != null ? context.getApplicationContext() : null;
        float scale = (float) brightness / 4095f;

        final long sessionId = sessionCounter.incrementAndGet();

        activeEffectFuture = effectExecutor.submit(() -> {
            AnimationManager.setHighPriorityActive(true);
            try (java.io.InputStream is = appCtx.getAssets().open(folder + "/" + fileName + ".csv");
                    java.io.BufferedReader reader =
                            new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                android.content.SharedPreferences prefs = appCtx.getSharedPreferences(
                        appCtx.getString(R.string.pref_file), android.content.Context.MODE_PRIVATE);
                boolean isLightOn = prefs.getBoolean("is_light_on", false);
                int torchBrightness = prefs.getInt("torch_brightness", 2048);
                int torchVal = isLightOn ? torchBrightness : 0;

                final double FRAME_MS = 16.666;
                final long startTime = android.os.SystemClock.elapsedRealtime();
                long frameIdx = 0;
                boolean vibratedThisCycle = false;
                String line;
                while ((line = reader.readLine()) != null
                        && !Thread.currentThread().isInterrupted()) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty())
                        continue;

                    String[] vals = trimmed.split("[,\\t ]+");
                    if (vals.length < GLYPH_ORDER.length)
                        continue;

                    try {
                        int[] bright = new int[GLYPH_ORDER.length];
                        boolean anyNonZero = false;
                        for (int i = 0; i < GLYPH_ORDER.length; i++) {
                            int val = Math.round(Integer.parseInt(vals[i]) * scale);
                            bright[i] = Math.max(val, torchVal);
                            if (bright[i] > 0)
                                anyNonZero = true;
                        }

                        if (anyNonZero && !vibratedThisCycle) {
                            vibrate(vibrator, 25, 100, appCtx, -1);
                            vibratedThisCycle = true;
                        }
                        if (!anyNonZero)
                            vibratedThisCycle = false;

                        for (int i = 0; i < GLYPH_ORDER.length; i++) {
                            GlyphManagerV2.getInstance().setBrightness(GLYPH_ORDER[i], bright[i]);
                        }
                    } catch (NumberFormatException ignored) {
                    }

                    frameIdx++;
                    long expectedTime = startTime + (long) (frameIdx * FRAME_MS);
                    long sleepTime = expectedTime - android.os.SystemClock.elapsedRealtime();
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else if (sleepTime < -33) {
                        long skip = (long) (Math.abs(sleepTime) / FRAME_MS);
                        for (long s = 0; s < skip && reader.readLine() != null; s++) {
                            frameIdx++;
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (sessionCounter.get() == sessionId) {
                    GlyphManagerV2.getInstance().toggleAll(false);
                    AnimationManager.setHighPriorityActive(false);
                    AnimationManager.refreshBackgroundState();
                    if (appCtx != null) {
                        appCtx.sendBroadcast(new android.content
                                        .Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL)
                                        .setPackage(appCtx.getPackageName()));
                    }
                }
            }
        });
    }
}