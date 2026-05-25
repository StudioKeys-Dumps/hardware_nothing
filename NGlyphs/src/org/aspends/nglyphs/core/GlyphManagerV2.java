package org.aspends.nglyphs.core;

import android.content.Context;
import android.os.PowerManager;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.util.ShellUtils;

public class GlyphManagerV2 {
    public static final int MAX_BRIGHTNESS = 4095;
    private static final String PATH_ROOT = "/sys/class/leds/aw210xx_led";

    private static volatile GlyphManagerV2 instance;
    private PowerManager.WakeLock wakeLock;
    private Context context;
    private final int[] currentFrame = new int[15];

    public static synchronized GlyphManagerV2 getInstance() {
        if (instance == null) {
            instance = new GlyphManagerV2();
        }
        return instance;
    }

    private GlyphManagerV2() {}

    public void init(Context context) {
        this.context = context.getApplicationContext();
        PowerManager pm = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock =
                    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphManager:HardwareExecutor");
        }
    }

    public enum Glyph {
        CAMERA(PATH_ROOT + "/rear_cam_led_br"),
        DIAGONAL(PATH_ROOT + "/front_cam_led_br"),
        MAIN(PATH_ROOT + "/round_leds_br"),
        LINE(PATH_ROOT + "/vline_leds_br"),
        DOT(PATH_ROOT + "/dot_led_br"),
        SINGLE_LED(PATH_ROOT + "/video_leds_br");

        public final String path;

        Glyph(String path) { this.path = path; }

        public static Glyph[] getBasicGlyphs() {
            return new Glyph[] {CAMERA, DIAGONAL, MAIN, LINE, DOT};
        }
    }

    public enum NativeEffect {
        ALL_WHITE(PATH_ROOT + "/all_white_leds_br"),
        FRAME(PATH_ROOT + "/frame_leds_effect"),
        BOOT(PATH_ROOT + "/bootan_leds_effect"),
        BREATH(PATH_ROOT + "/leds_breath_set"),
        RINGTONE(PATH_ROOT + "/ringtone_leds_effect"),
        ASSISTANT(PATH_ROOT + "/ga_leds_effect"),
        FLIP(PATH_ROOT + "/flip_leds_effect"),
        MUSIC(PATH_ROOT + "/music_leds_effect"),
        RANDOM(PATH_ROOT + "/random_leds_effect"),
        VIDEO(PATH_ROOT + "/video_leds_effect"),
        KEYBOARD(PATH_ROOT + "/keybd_leds_effect"),
        ALL_EFFECT(PATH_ROOT + "/all_leds_effect"),
        HORSE_RACE(PATH_ROOT + "/horse_race_leds_br"),
        NF_EFFECT(PATH_ROOT + "/nf_leds_effect"),
        EXCLAMATION(PATH_ROOT + "/exclamation_leds_effect");

        public final String path;

        NativeEffect(String path) { this.path = path; }
    }

    public void setBrightness(Glyph glyph, int brightness) {
        if (glyph == Glyph.SINGLE_LED) {
            int toggle = brightness > 0 ? 1 : 0;
            String effectPath = PATH_ROOT + "/video_leds_effect";
            writeSysfs(effectPath, String.valueOf(toggle));
            return;
        }

        int safeBrightness = Math.max(0, Math.min(brightness, MAX_BRIGHTNESS));
        writeSysfs(glyph.path, String.valueOf(safeBrightness));

        // Update current frame state for UI matching
        updateInternalFrame(glyph, safeBrightness);
        AnimationManager.notifyFrame(currentFrame);
    }

    private void updateInternalFrame(Glyph g, int b) {
        switch (g) {
            case CAMERA:
                currentFrame[0] = b;
                break;
            case DIAGONAL:
                currentFrame[1] = b;
                break;
            case MAIN:
                currentFrame[2] = b;
                currentFrame[3] = b;
                currentFrame[4] = b;
                currentFrame[5] = b;
                break;
            case DOT:
                currentFrame[6] = b;
                break;
            case LINE:
                for (int i = 7; i <= 14; i++) currentFrame[i] = b;
                break;
        }
    }

    private void withWakeLock(long timeout, Runnable action) {
        boolean acquired = false;
        try {
            if (wakeLock != null) {
                wakeLock.acquire(timeout);
                acquired = true;
            }
            action.run();
        } finally {
            if (acquired && wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void setGlyphBrightness(java.util.Map<Glyph, Integer> updates) {
        if (updates == null || updates.isEmpty())
            return;

        java.util.Map<String, String> pathUpdates = new java.util.HashMap<>();
        for (java.util.Map.Entry<Glyph, Integer> entry : updates.entrySet()) {
            Glyph g = entry.getKey();
            int b = Math.max(0, Math.min(entry.getValue(), MAX_BRIGHTNESS));
            if (g == Glyph.SINGLE_LED) {
                pathUpdates.put(PATH_ROOT + "/video_leds_effect", String.valueOf(b > 0 ? 1 : 0));
            } else {
                pathUpdates.put(g.path, String.valueOf(b));
                updateInternalFrame(g, b);
            }
        }

        withWakeLock(3000, () -> ShellUtils.fastWriteBatch(pathUpdates));
    }

    public void setFrame(int[] values) {
        if (values == null || values.length == 0 || values.length > 33)
            return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            int b = Math.max(0, Math.min(values[i], MAX_BRIGHTNESS));
            sb.append(b);
            if (i < 15)
                currentFrame[i] = b; // Update internal buffer
            if (i < values.length - 1)
                sb.append(" ");
        }

        writeSysfs(PATH_ROOT + "/frame_leds_effect", sb.toString());

        // Notify UI listener via AnimationManager
        AnimationManager.notifyFrame(currentFrame);
    }

    public void setBrightnessSingle(int index, int brightness) {
        writeSysfs(PATH_ROOT + "/single_led_br",
                index + " " + Math.max(0, Math.min(brightness, MAX_BRIGHTNESS)));
    }

    private void writeSysfs(final String path, final String value) {
        withWakeLock(1000, () -> ShellUtils.fastWrite(path, value));
    }

    public void setNativeEffect(NativeEffect effect, int value) {
        writeSysfs(effect.path, String.valueOf(value));
    }

    public void resetAll() { toggleAll(false); }

    public void toggleAll(boolean turnOn) {
        int val = turnOn ? MAX_BRIGHTNESS : 0;
        int[] frame = new int[15];
        java.util.Arrays.fill(frame, val);
        setFrame(frame);

        if (!turnOn) {
            setBrightness(Glyph.SINGLE_LED, 0);
        }
    }
}
