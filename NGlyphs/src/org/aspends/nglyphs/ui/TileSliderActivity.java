package org.aspends.nglyphs.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.quicksettings.TileService;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.services.AutoBrightnessService;
import org.aspends.nglyphs.services.GlyphTileService;

public class TileSliderActivity extends AppCompatActivity {
    private static final int NUM_LEVELS = 10;
    private static final int MAX_BRIGHTNESS = 4095;

    private SharedPreferences prefs;
    private Vibrator vibrator;
    private Slider slider;
    private AlertDialog dialog;
    private int lastLevel = -1;

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        org.aspends.nglyphs.core.AnimationManager.init(this);
        org.aspends.nglyphs.core.GlyphManagerV2.getInstance().init(this);

        ComponentName component =
                getIntent().getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName.class);
        if (component != null
                && !component.getClassName().equals(GlyphTileService.class.getName())) {
            // Long-press on master tile → open the app
            Intent launch = new Intent(this, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            finish();
            return;
        }

        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        vibrator = getSystemService(Vibrator.class);

        if (!prefs.getBoolean("master_allow", false)) {
            android.widget.Toast
                    .makeText(this, "Enable Glyphs first!", android.widget.Toast.LENGTH_SHORT)
                    .show();
            finish();
            return;
        }

        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        showStrengthDialog();
    }

    private void showStrengthDialog() {
        Context themed = new ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight);

        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        FrameLayout container = new FrameLayout(themed);
        container.setPadding(pad, pad, pad, 0);

        slider = new Slider(themed);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        slider.setLayoutParams(lp);
        slider.setValueFrom(1f);
        slider.setValueTo((float) NUM_LEVELS);
        slider.setStepSize(1f);
        slider.setLabelFormatter(value -> Math.round((value / NUM_LEVELS) * 100f) + "%");
        container.addView(slider);

        int currentBrightness = prefs.getInt("torch_brightness", 2048);
        boolean isOn = prefs.getBoolean("is_light_on", false);
        int currentLevel = brightnessToLevel(currentBrightness);

        slider.setValue(currentLevel);
        slider.setEnabled(isOn);
        lastLevel = currentLevel;

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (!fromUser) return;
            if (prefs.getBoolean("auto_brightness_enabled", false)) {
                prefs.edit().putBoolean("auto_brightness_enabled", false).apply();
                stopService(new Intent(this, AutoBrightnessService.class));
            }
            int level = (int) value;
            if (level != lastLevel) {
                vibrateForLevel(level);
                lastLevel = level;
            }
            int brightness = levelToBrightness(level);
            prefs.edit().putInt("torch_brightness", brightness).apply();
            if (prefs.getBoolean("is_light_on", false)) {
                org.aspends.nglyphs.core.AnimationManager.refreshBackgroundState();
            }
        });

        dialog = new MaterialAlertDialogBuilder(themed, R.style.ThemeOverlay_GlyphDialog)
                         .setTitle(R.string.flashlight_strength_title)
                         .setView(container)
                         .setPositiveButton(R.string.quick_settings_done, null)
                         .setNeutralButton(isOn ? R.string.flashlight_strength_turn_off
                                                : R.string.flashlight_strength_turn_on,
                                 null)
                         .setOnDismissListener(d -> {
                             try {
                                 TileService.requestListeningState(
                                         this, new ComponentName(this, GlyphTileService.class));
                             } catch (Exception ignored) {
                             }
                             finish();
                         })
                         .create();

        dialog.setOnShowListener(d -> {
            Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setOnClickListener(v -> toggleTorch(neutral));
            }
        });

        dialog.show();
    }

    private void toggleTorch(Button neutral) {
        boolean wasOn = prefs.getBoolean("is_light_on", false);
        boolean newState = !wasOn;
        prefs.edit().putBoolean("is_light_on", newState).apply();
        org.aspends.nglyphs.core.AnimationManager.refreshBackgroundState();

        slider.setEnabled(newState);
        neutral.setText(newState ? R.string.flashlight_strength_turn_off
                                 : R.string.flashlight_strength_turn_on);

        try {
            TileService.requestListeningState(
                    this, new ComponentName(this, GlyphTileService.class));
        } catch (Exception e) {
            android.util.Log.e("TileSliderActivity", "Failed to request tile listening state", e);
        }
        quickTick(20, 100);
    }

    private int brightnessToLevel(int brightness) {
        if (brightness <= 0) return 1;
        int level = Math.round((brightness / (float) MAX_BRIGHTNESS) * NUM_LEVELS);
        return Math.max(1, Math.min(NUM_LEVELS, level));
    }

    private int levelToBrightness(int level) {
        if (level <= 0) return 0;
        if (level >= NUM_LEVELS) return MAX_BRIGHTNESS;
        return Math.round((level / (float) NUM_LEVELS) * MAX_BRIGHTNESS);
    }

    private void vibrateForLevel(int level) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        int safeLevel = Math.max(1, Math.min(NUM_LEVELS, level));
        int denom = Math.max(1, NUM_LEVELS - 1);
        float t = Math.max(0f, Math.min(1f, (safeLevel - 1) / (float) denom));
        float minScale = 0.12f;
        float scale = Math.max(0f, Math.min(1f, minScale + (1.0f - minScale) * t));

        try {
            if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                VibrationEffect effect =
                        VibrationEffect.startComposition()
                                .addPrimitive(
                                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
                                .compose();
                vibrator.vibrate(effect);
                return;
            }
        } catch (Throwable ignored) {
        }
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
    }

    private void quickTick(int d, int a) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(d, a));
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (Exception ignored) {
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        super.onDestroy();
    }
}
