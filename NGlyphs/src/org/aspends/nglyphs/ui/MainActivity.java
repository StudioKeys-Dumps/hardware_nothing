package org.aspends.nglyphs.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log; // Added for Log.i
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphEffects;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.services.*;
import org.aspends.nglyphs.util.*;
import org.aspends.nglyphs.util.RootNotificationHelper;
import org.aspends.nglyphs.util.ShellUtils;

public class MainActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private boolean isMasterAllowed;
    private boolean isUpdatingUI = false;
    private int currentBrightness;

    private MaterialCardView cardNotifications, cardRingtones, cardFlipStyle, cardSleepTime,
            cardBrightness, cardEssentialLights, cardRingNotifHaptics, cardVolumeBar,
            cardShakeToGlyph, cardImport, cardBattery, cardTurnOff, cardGlyphProgress,
            cardGlyphConverter, cardTorchBrightness, cardStopDuringCall, cardAssistant,
            cardMusicVisualizer, cardNotifCooldown;

    private TextView textCurrentRingtone, textCurrentNotifSound, textCurrentFlipStyle,
            textSleepTime, textImportWarning, textCurrentMusic;
    private MaterialSwitch switchMaster, switchFlip, switchLockscreenOnly, switchSleepMode,
            switchShake, switchVolumeBar, switchVolumeFlipOnly, switchRingNotifHaptics,
            switchShakeWhileOn, switchAutoBrightness, switchAssistantMic, switchGlyphProgress,
            switchGlyphProgressFlippedOnly, switchNotifCooldown, switchStopDuringCall, switchTorch,
            switchMusicVisualizer, switchAssistant;
    private LinearLayout layoutRingNotifHapticStrength, layoutVolumeFlipOnly,
            layoutGlyphProgressFlippedOnly, layoutGlyphProgressBrightness;
    private Slider slider, sliderShakeSensitivity, sliderHapticStrength,
            sliderRingNotifHapticStrength, sliderProgressBrightness, sliderTorch;

    private RadioGroup rgShakeCount;
    private ImageView spacewar;
    private ActivityResultLauncher<Intent> importRingtoneLauncher;
    private final android.os.Handler previewHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable previewRunnable;
    private android.content.BroadcastReceiver brightnessReceiver;

    public static final String PREF_BLINK_STYLE = "glyph_blink_style";

    // if u gonna add extra styles, begin from here
    private String[] notifStyleValues;
    private String[] callStyleValues;
    private String[] flipStyleValues;

    private static final String NATIVE_FLIP_VALUE = "native_flip";

    /**
     * Called when the activity is starting.
     * Initializes the views, retrieves shared preferences, and sets up root shell
     * access.
     * Also requests necessary permissions if root access is granted.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *                           previously being
     *                           shut down then this Bundle contains the data it
     *                           most recently
     *                           supplied in onSaveInstanceState(Bundle).
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();

                java.io.File extDir = getExternalFilesDir(null);
                if (extDir == null)
                    extDir = getFilesDir();
                File logFile = new File(extDir, "crash_log.txt");
                FileWriter writer = new FileWriter(logFile, true);
                writer.append("\n\n--- Crash Report ---\n");
                writer.append("Time: ").append(new java.util.Date().toString()).append("\n");
                writer.append("Thread: ").append(thread.getName()).append("\n");
                writer.append("Stack Trace:\n").append(stackTrace);
                writer.close();
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to log crash", e);
            }
            // Let the app crash normally after logging
            System.exit(1);
        });

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        importRingtoneLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        try {
                            android.net.Uri uri = result.getData().getData();
                            if (uri != null) {
                                String originalName = org.aspends.nglyphs.util.CustomRingtoneManager
                                                              .getFileNameFromUri(this, uri);
                                if (originalName == null)
                                    originalName = "unknown";

                                String mime = getContentResolver().getType(uri);
                                String nameLower = originalName.toLowerCase();
                                boolean isOgg =
                                        (mime != null
                                                && (mime.contains("ogg")
                                                        || mime.contains("application/ogg")))
                                        || nameLower.endsWith(".ogg");
                                boolean isCsv = (mime != null && mime.contains("text/csv"))
                                        || (uri.getPath() != null
                                                && uri.getPath().endsWith(".csv"));

                                if (!isOgg && !isCsv) {
                                    Toast.makeText(this,
                                                 "Only .ogg patterns supported. Use the Converter "
                                                 + "for other formats.",
                                                 Toast.LENGTH_LONG)
                                            .show();
                                    return;
                                }

                                // Strip extension from originalName if present
                                if (nameLower.endsWith(".ogg"))
                                    originalName =
                                            originalName.substring(0, originalName.length() - 4);
                                if (nameLower.endsWith(".csv"))
                                    originalName =
                                            originalName.substring(0, originalName.length() - 4);

                                String ext = isCsv ? ".csv" : ".ogg";
                                String fileName = "imported_" + System.currentTimeMillis() + "_"
                                        + originalName + ext;

                                java.io.File destFile =
                                        org.aspends.nglyphs.util.CustomRingtoneManager.importFile(
                                                this, uri, fileName);
                                if (destFile == null)
                                    throw new Exception("Import failed");

                                Toast.makeText(this, "Imported " + (isCsv ? "Pattern" : "Audio"),
                                             Toast.LENGTH_SHORT)
                                        .show();
                                if (!isCsv
                                        && android.os.Build.VERSION.SDK_INT
                                                >= android.os.Build.VERSION_CODES.M
                                        && !android.provider.Settings.System.canWrite(this)) {
                                    new com.google.android.material.dialog
                                            .MaterialAlertDialogBuilder(this)
                                            .setTitle("Permission Required")
                                            .setMessage("To set this audio as a ringtone, Glyph "
                                                        + "Manager needs permission to modify "
                                                        + "system settings. Grant it now?")
                                            .setPositiveButton("Grant",
                                                    (d, w)
                                                            -> org.aspends.nglyphs.util
                                                                    .RingtoneHelper
                                                                    .checkAndRequestPermissions(
                                                                            this))
                                            .setNegativeButton("Later", null)
                                            .show();
                                }

                                // Refresh lists
                                notifStyleValues = loadStyleNames("notification");
                                callStyleValues = loadStyleNames("call");
                                updateStyleLabels();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        setContentView(R.layout.activity_main);
        GlyphManagerV2.getInstance().init(this);
        AnimationManager.init(this);
        RingtoneSyncObserver.register(getApplicationContext());
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.nestedScroll), (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                            insets.bottom);
                    return windowInsets;
                });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        if (!prefs.contains("master_allow")) {
            prefs.edit()
                    .putBoolean("master_allow", true)
                    .putBoolean("flip_to_glyph_enabled", true)
                    .putBoolean("battery_glyph_enabled", true)
                    .putBoolean("volume_bar_enabled", true)
                    .apply();
        }
        vibrator = getSystemService(Vibrator.class);

        notifStyleValues = loadStyleNames("notification");
        callStyleValues = loadStyleNames("call");

        // Include Native Flip as an option for Flip to Glyph
        String[] loadedFlipValues = loadStyleNames("notification");
        flipStyleValues = new String[loadedFlipValues.length + 1];
        flipStyleValues[0] = NATIVE_FLIP_VALUE;
        System.arraycopy(loadedFlipValues, 0, flipStyleValues, 1, loadedFlipValues.length);

        initViews();

        AppListCache.loadAsync(MainActivity.this);
        isMasterAllowed = prefs.getBoolean("master_allow", false);
        currentBrightness = prefs.getInt("brightness", 2048);
        setupUI();
        setupListeners();
        checkAllPermissions();
        setupSmoothCollapse();

        brightnessReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if ("org.aspends.nglyphs.ACTION_BRIGHTNESS_UPDATED".equals(intent.getAction())) {
                    int newBrightness = prefs.getInt("brightness", 2048);
                    float pos = mapBrightnessToPosition(newBrightness);
                    slider.setValue(pos);
                    updateOutlineAlpha(pos);
                }
            }
        };
        registerReceiver(brightnessReceiver,
                new android.content.IntentFilter("org.aspends.nglyphs.ACTION_BRIGHTNESS_UPDATED"),
                android.content.Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                notifStyleValues = loadStyleNames("notification");
                callStyleValues = loadStyleNames("call");
                updateStyleLabels();
            }
        }, new android.content.IntentFilter("org.aspends.nglyphs.ACTION_RINGTONE_SYNCED"),
                android.content.Context.RECEIVER_NOT_EXPORTED);

        // UI Refresh Loop for Sleep Mode transitions
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshUIState();
                handler.postDelayed(this, 60000); // Pulse every 1 minute
            }
        }, 60000);

        prefs.registerOnSharedPreferenceChangeListener(
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(
                            SharedPreferences sharedPreferences, String key) {
                        if ("master_allow".equals(key)) {
                            boolean newVal = sharedPreferences.getBoolean(key, false);
                            if (switchMaster != null && switchMaster.isChecked() != newVal) {
                                runOnUiThread(() -> switchMaster.setChecked(newVal));
                            }
                        } else if ("is_light_on".equals(key)) {
                            boolean newVal = sharedPreferences.getBoolean(key, false);
                            if (switchTorch != null && switchTorch.isChecked() != newVal) {
                                runOnUiThread(() -> switchTorch.setChecked(newVal));
                            }
                        }
                    }
                });
    }

    private String[] loadStyleNames(String folder) {
        try {
            String[] files = getAssets().list(folder);
            if (files == null || files.length == 0)
                return new String[] {};
            List<String> names = new ArrayList<>();
            for (String f : files) {
                if (f.endsWith(".csv")) {
                    names.add(f.replace(".csv", ""));
                }
            }
            java.util.Collections.sort(names);
            return names.toArray(new String[0]);
        } catch (Exception e) {
            return new String[] {};
        }
    }

    /**
     * Initializes all UI component references by finding them by their ID.
     */
    private void initViews() {
        cardNotifications = findViewById(R.id.cardNotifications);
        cardRingtones = findViewById(R.id.cardRingtones);
        cardFlipStyle = findViewById(R.id.cardFlipStyle);
        cardSleepTime = findViewById(R.id.cardSleepTime);
        cardBrightness = findViewById(R.id.cardBrightness);
        cardEssentialLights = findViewById(R.id.cardEssentialLights);
        cardImport = findViewById(R.id.cardImport);
        cardBattery = findViewById(R.id.cardBattery);
        cardTurnOff = findViewById(R.id.cardTurnOff);
        cardShakeToGlyph = findViewById(R.id.cardShakeToGlyph);
        cardVolumeBar = findViewById(R.id.cardVolumeBar);
        cardRingNotifHaptics = findViewById(R.id.cardRingNotifHaptics);
        cardGlyphProgress = findViewById(R.id.cardGlyphProgress);
        cardGlyphConverter = findViewById(R.id.cardGlyphConverter);
        cardTorchBrightness = findViewById(R.id.cardTorchBrightness);
        cardAssistant = findViewById(R.id.cardAssistant);
        cardMusicVisualizer = findViewById(R.id.cardMusicVisualizer);
        cardNotifCooldown = findViewById(R.id.cardNotifCooldown);
        sliderTorch = findViewById(R.id.sliderTorch);

        layoutGlyphProgressBrightness = findViewById(R.id.layoutGlyphProgressBrightness);
        sliderProgressBrightness = findViewById(R.id.sliderProgressBrightness);

        textCurrentRingtone = findViewById(R.id.textCurrentRingtone);
        textCurrentNotifSound = findViewById(R.id.textCurrentNotifSound);
        textCurrentFlipStyle = findViewById(R.id.textCurrentFlipStyle);
        textSleepTime = findViewById(R.id.textSleepTime);
        textCurrentMusic = findViewById(R.id.textCurrentMusic);

        slider = findViewById(R.id.sliderMain);
        switchMaster = findViewById(R.id.switchAll);
        switchAutoBrightness = findViewById(R.id.switchAutoBrightness);
        switchFlip = findViewById(R.id.switchFlip);
        switchLockscreenOnly = findViewById(R.id.switchLockscreenOnly);
        switchSleepMode = findViewById(R.id.switchSleepMode);
        switchShake = findViewById(R.id.switchShake);
        switchVolumeBar = findViewById(R.id.switchVolumeBar);
        switchVolumeFlipOnly = findViewById(R.id.switchVolumeFlipOnly);
        switchRingNotifHaptics = findViewById(R.id.switchRingNotifHaptics);
        switchTorch = findViewById(R.id.switchTorch);
        switchMusicVisualizer = findViewById(R.id.switchMusicVisualizer);
        switchAssistant = findViewById(R.id.switchAssistant);

        sliderShakeSensitivity = findViewById(R.id.seekBar_sensitivity);
        switchShakeWhileOn = findViewById(R.id.switchShakeWhileOn);
        rgShakeCount = findViewById(R.id.rg_shake_count);
        sliderRingNotifHapticStrength = findViewById(R.id.sliderRingNotifHapticStrength);
        layoutVolumeFlipOnly = findViewById(R.id.layoutVolumeFlipOnly);
        layoutRingNotifHapticStrength = findViewById(R.id.layoutRingNotifHapticStrength);
        layoutGlyphProgressFlippedOnly = findViewById(R.id.layoutGlyphProgressFlippedOnly);
        switchGlyphProgress = findViewById(R.id.switchGlyphProgress);
        switchGlyphProgressFlippedOnly = findViewById(R.id.switchGlyphProgressFlippedOnly);
        spacewar = findViewById(R.id.spacewar);
        switchNotifCooldown = findViewById(R.id.switchNotifCooldown);
        switchStopDuringCall = findViewById(R.id.switchStopDuringCall);
        cardStopDuringCall = findViewById(R.id.cardStopDuringCall);
    }

    /**
     * Sets the initial state of the UI components based on stored preferences
     * and master toggle permission.
     */
    private void setupUI() {
        isUpdatingUI = true;
        switchMaster.setChecked(isMasterAllowed);
        slider.setValue(mapBrightnessToPosition(prefs.getInt("brightness", 2048)));
        if (sliderTorch != null) {
            sliderTorch.setValue(mapBrightnessToPosition(prefs.getInt("torch_brightness", 2048)));
        }

        updateOutlineAlpha(slider.getValue());

        switchSleepMode.setChecked(prefs.getBoolean("sleep_mode_enabled", false));
        switchLockscreenOnly.setChecked(prefs.getBoolean("screen_off_only", false));
        switchShake.setChecked(prefs.getBoolean("shake_enabled", false));
        switchVolumeBar.setChecked(prefs.getBoolean("volume_bar_enabled", true));
        switchVolumeFlipOnly.setChecked(prefs.getBoolean("volume_flip_only", false));
        switchRingNotifHaptics.setChecked(prefs.getBoolean("ring_notif_haptics_enabled", true));
        switchAutoBrightness.setChecked(prefs.getBoolean("auto_brightness_enabled", false));
        switchShakeWhileOn.setChecked(prefs.getBoolean("shake_while_screen_on", false));
        switchGlyphProgress.setChecked(prefs.getBoolean("glyph_progress_enabled", false));
        switchGlyphProgressFlippedOnly.setChecked(
                prefs.getBoolean("glyph_progress_flipped_only", false));
        switchNotifCooldown.setChecked(prefs.getBoolean("notif_cooldown_enabled", false));
        if (switchMusicVisualizer != null)
            switchMusicVisualizer.setChecked(prefs.getBoolean("music_visualizer_enabled", false));
        sliderRingNotifHapticStrength.setValue(prefs.getInt("ring_notif_haptic_strength", 100));
        sliderProgressBrightness.setValue(prefs.getInt("glyph_progress_brightness_factor", 70));
        sliderShakeSensitivity.setValue(prefs.getInt("shake_sensitivity", 50));

        // Initialize default styles if missing to ensure immediate functionality on
        // fresh install
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains("glyph_blink_style")) {
            editor.putString("glyph_blink_style", "static");
        }
        if (!prefs.contains("call_style_value")) {
            editor.putString("call_style_value", "static");
        }
        if (!prefs.contains("flip_style_value")) {
            editor.putString("flip_style_value", NATIVE_FLIP_VALUE);
        }
        if (!prefs.contains("shake_count")) {
            editor.putInt("shake_count", 3);
        }
        if (!prefs.contains("shake_sensitivity")) {
            editor.putInt("shake_sensitivity", 50);
        }
        if (!prefs.contains("torch_brightness")) {
            editor.putInt("torch_brightness", prefs.getInt("brightness", 2048));
        }
        editor.apply();
        int shakes = prefs.getInt("shake_count", 3);
        if (shakes == 1)
            rgShakeCount.check(R.id.rb_one);
        else if (shakes == 2)
            rgShakeCount.check(R.id.rb_two);
        else
            rgShakeCount.check(R.id.rb_three);

        updateStyleLabels();
        updateSleepTimeLabel();
        layoutVolumeFlipOnly.setVisibility(switchVolumeBar.isChecked() ? View.VISIBLE : View.GONE);
        layoutRingNotifHapticStrength.setVisibility(
                switchRingNotifHaptics.isChecked() ? View.VISIBLE : View.GONE);
        layoutGlyphProgressFlippedOnly.setVisibility(
                switchGlyphProgress.isChecked() ? View.VISIBLE : View.GONE);
        layoutGlyphProgressBrightness.setVisibility(
                switchGlyphProgress.isChecked() ? View.VISIBLE : View.GONE);

        refreshUIState();

        // Start services if enabled
        Intent shakeIntent = new Intent(this, ShakeToGlyphService.class);
        if (prefs.getBoolean("shake_enabled", false)) {
            startService(shakeIntent);
        }

        if (isMasterAllowed) {
            boolean vizEnabled = prefs.getBoolean("music_visualizer_enabled", false);
            if (vizEnabled) {
                startService(new Intent(this, AudioVisualizerService.class));
            } else {
                Intent flipIntent = new Intent(this, FlipToGlyphService.class);
                Intent batteryIntent = new Intent(this, BatteryGlyphService.class);
                Intent volumeIntent = new Intent(this, VolumeObserverService.class);

                startService(flipIntent);

                if (prefs.getBoolean("battery_glyph_enabled", false)) {
                    startService(batteryIntent);
                }
                if (prefs.getBoolean("powershare_glyph_enabled", false)) {
                    startService(new Intent(this, PowershareService.class));
                }
                if (prefs.getBoolean("volume_bar_enabled", true)) {
                    startService(volumeIntent);
                }
            }
            if (prefs.getBoolean("auto_brightness_enabled", false)) {
                startService(new Intent(this, AutoBrightnessService.class));
            }
            if (prefs.getBoolean("assistant_animations_enabled", false)) {
                startService(new Intent(this, AssistantInteractionService.class));
            }
            startService(new Intent(this, CameraRecordingService.class));
        }
        isUpdatingUI = false;
    }

    private void setupListeners() {
        cardNotifications.setOnClickListener(v
                -> showStyleDialog(R.string.card_notifications, "glyph_blink_style_idx",
                        PREF_BLINK_STYLE, "notification", notifStyleValues));
        cardRingtones.setOnClickListener(v
                -> showStyleDialog(R.string.card_ringtones, "call_style_idx", "call_style_value",
                        "call", callStyleValues));
        cardFlipStyle.setOnClickListener(v
                -> showStyleDialog(R.string.flip_to_glyph_label, "flip_style_idx",
                        "flip_style_value", "notification", flipStyleValues));

        cardSleepTime.setOnClickListener(
                v -> startActivity(new Intent(this, SleepModeActivity.class)));
        cardEssentialLights.setOnClickListener(
                v -> startActivity(new Intent(this, EssentialLightsActivity.class)));
        cardBattery.setOnClickListener(
                v -> startActivity(new Intent(this, BatterySettingsActivity.class)));
        cardImport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/ogg");
            String[] mimeTypes = {"audio/ogg", "application/ogg"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            importRingtoneLauncher.launch(intent);
        });
        cardGlyphConverter.setOnClickListener(
                v -> startActivity(new Intent(this, GlyphConverterActivity.class)));

        switchMaster.setOnCheckedChangeListener((v, isChecked) -> {
            if (isUpdatingUI)
                return;
            quickTick(25, 120);
            isMasterAllowed = isChecked;
            prefs.edit().putBoolean("master_allow", isChecked).apply();

            Intent flipIntent = new Intent(this, FlipToGlyphService.class);
            Intent batteryIntent = new Intent(this, BatteryGlyphService.class);
            Intent volumeIntent = new Intent(this, VolumeObserverService.class);
            Intent shakeIntent = new Intent(this, ShakeToGlyphService.class);
            Intent autoBrightIntent = new Intent(this, AutoBrightnessService.class);

            if (isChecked) {
                startService(flipIntent);
                if (prefs.getBoolean("battery_glyph_enabled", false))
                    startService(batteryIntent);
                if (prefs.getBoolean("powershare_glyph_enabled", false))
                    startService(new Intent(this, PowershareService.class));
                if (prefs.getBoolean("volume_bar_enabled", true))
                    startService(volumeIntent);
                if (prefs.getBoolean("auto_brightness_enabled", false))
                    startService(autoBrightIntent);
                if (prefs.getBoolean("music_visualizer_enabled", false))
                    startService(new Intent(this, AudioVisualizerService.class));
                if (prefs.getBoolean("assistant_animations_enabled", false))
                    startService(new Intent(this, AssistantInteractionService.class));
                startService(new Intent(this, CameraRecordingService.class));
            } else {
                stopService(flipIntent);
                stopService(batteryIntent);
                stopService(new Intent(this, PowershareService.class));
                stopService(volumeIntent);
                stopService(autoBrightIntent);
                stopService(new Intent(this, AudioVisualizerService.class));
                stopService(new Intent(this, AssistantInteractionService.class));
                stopService(new Intent(this, CameraRecordingService.class));
                // ShakeToGlyphService remains running for independent Torch toggles,
                // but the LEDs themselves will be cleared below.

                AnimationManager.cancelAnimation();
                GlyphManagerV2.getInstance().toggleAll(false);
            }
            sendBroadcast(new Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL")
                            .setPackage(getPackageName()));
            refreshUIState();
        });

        switchFlip.setChecked(prefs.getBoolean("flip_to_glyph_enabled", false));
        switchFlip.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(20, 100);
            prefs.edit().putBoolean("flip_to_glyph_enabled", isChecked).apply();
            // Do not stop FlipToGlyphService here! It also processes notifications and call
            // ringtones!
            // The service now handles ignoring flip sensor events internally when this is
            // false.
        });

        switchAutoBrightness.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("auto_brightness_enabled", ic).apply();
            Intent intent = new Intent(this, AutoBrightnessService.class);
            if (ic && isMasterAllowed)
                startService(intent);
            else
                stopService(intent);
        });

        switchShakeWhileOn.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(25, 120);
            prefs.edit().putBoolean("shake_while_screen_on", isChecked).apply();
        });

        switchLockscreenOnly.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("screen_off_only", isChecked).apply();
        });

        switchSleepMode.setOnCheckedChangeListener((v, isChecked) -> {
            quickTick(20, 100);
            prefs.edit().putBoolean("sleep_mode_enabled", isChecked).apply();
            refreshUIState();
        });

        switchShake.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("shake_enabled", ic).apply();
            Intent intent = new Intent(this, ShakeToGlyphService.class);
            if (ic)
                startService(intent);
            else
                stopService(intent);
            refreshUIState();
        });

        switchVolumeBar.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("volume_bar_enabled", ic).apply();
            layoutVolumeFlipOnly.setVisibility(ic ? View.VISIBLE : View.GONE);
            Intent intent = new Intent(this, VolumeObserverService.class);
            if (ic && isMasterAllowed)
                startService(intent);
            else
                stopService(intent);
            refreshUIState();
        });

        switchVolumeFlipOnly.setOnCheckedChangeListener(
                (v, ic) -> prefs.edit().putBoolean("volume_flip_only", ic).apply());

        switchRingNotifHaptics.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("ring_notif_haptics_enabled", ic).apply();
            layoutRingNotifHapticStrength.setVisibility(ic ? View.VISIBLE : View.GONE);
        });

        switchGlyphProgress.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("glyph_progress_enabled", ic).apply();
            layoutGlyphProgressFlippedOnly.setVisibility(ic ? View.VISIBLE : View.GONE);
            layoutGlyphProgressBrightness.setVisibility(ic ? View.VISIBLE : View.GONE);
            sendBroadcast(new Intent(FlipToGlyphService.ACTION_REFRESH_ESSENTIAL)
                            .setPackage(getPackageName()));
            refreshUIState();
        });

        if (switchMusicVisualizer != null) {
            switchMusicVisualizer.setOnCheckedChangeListener((v, ic) -> {
                if (isUpdatingUI)
                    return;
                quickTick(15, 100);
                prefs.edit().putBoolean("music_visualizer_enabled", ic).apply();
                Intent vizIntent =
                        new Intent(this, org.aspends.nglyphs.services.AudioVisualizerService.class);
                Intent flipIntent = new Intent(this, FlipToGlyphService.class);
                Intent volumeIntent = new Intent(this, VolumeObserverService.class);
                Intent batteryIntent = new Intent(this, BatteryGlyphService.class);
                Intent powershareIntent = new Intent(this, PowershareService.class);
                if (ic && isMasterAllowed) {
                    stopService(flipIntent);
                    stopService(volumeIntent);
                    stopService(batteryIntent);
                    stopService(powershareIntent);
                    AnimationManager.cancelAnimation();
                    startService(vizIntent);
                } else {
                    stopService(vizIntent);
                    AnimationManager.cancelAnimation();
                    if (isMasterAllowed) {
                        startService(flipIntent);
                        if (prefs.getBoolean("volume_bar_enabled", true))
                            startService(volumeIntent);
                        if (prefs.getBoolean("battery_glyph_enabled", false))
                            startService(batteryIntent);
                        if (prefs.getBoolean("powershare_glyph_enabled", false))
                            startService(powershareIntent);
                    }
                }
                refreshUIState();
            });
        }

        updateMusicZoneLabel();

        if (cardMusicVisualizer != null) {
            cardMusicVisualizer.setOnClickListener(v -> {
                String[] modes = {"Beat detection", "5-zone LED Visualizer",
                        "15-zone LED Visualizer"};
                int current = prefs.getInt("visualizer_mode",
                        org.aspends.nglyphs.services.AudioVisualizerService.MODE_15ZONE);
                if (current < 0 || current >= modes.length) {
                    current = org.aspends.nglyphs.services.AudioVisualizerService.MODE_15ZONE;
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Visualizer Mode")
                        .setSingleChoiceItems(modes, current,
                                (dialog, which) -> {
                                    prefs.edit().putInt("visualizer_mode", which).apply();
                                    updateMusicZoneLabel();
                                    if (switchMusicVisualizer != null
                                            && switchMusicVisualizer.isChecked()
                                            && isMasterAllowed) {
                                        Intent vizIntent = new Intent(this,
                                                org.aspends.nglyphs.services.AudioVisualizerService
                                                        .class);
                                        stopService(vizIntent);
                                        startService(vizIntent);
                                    }
                                    dialog.dismiss();
                                })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        if (switchAssistant != null) {
            switchAssistant.setOnCheckedChangeListener((v, ic) -> {
                quickTick(15, 100);
                prefs.edit().putBoolean("assistant_animations_enabled", ic).apply();
                Intent assistantIntent = new Intent(this, AssistantInteractionService.class);
                if (ic && isMasterAllowed) {
                    startService(assistantIntent);
                } else {
                    stopService(assistantIntent);
                }
                refreshUIState();
            });
            switchAssistant.setChecked(prefs.getBoolean("assistant_animations_enabled", false));
        }

        if (switchTorch != null) {
            switchTorch.setChecked(prefs.getBoolean("is_light_on", false));
            switchTorch.setOnCheckedChangeListener((v, ic) -> {
                quickTick(15, 100);
                prefs.edit().putBoolean("is_light_on", ic).apply();
                AnimationManager.refreshBackgroundState();
                if (ic) {
                    for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
                        GlyphManagerV2.getInstance().setBrightness(
                                g, prefs.getInt("torch_brightness", 2048));
                    }
                } else {
                    GlyphManagerV2.getInstance().toggleAll(false);
                }
                try {
                    TileService.requestListeningState(
                            this, new ComponentName(this, GlyphTileService.class));
                } catch (Exception ignored) {
                }
            });
        }

        switchGlyphProgressFlippedOnly.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("glyph_progress_flipped_only", ic).apply();
            refreshUIState();
        });

        switchNotifCooldown.setChecked(prefs.getBoolean("notif_cooldown_enabled", false));
        switchNotifCooldown.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("notif_cooldown_enabled", ic).apply();
            refreshUIState();
        });

        switchStopDuringCall.setChecked(prefs.getBoolean("stop_glyphs_during_call", false));
        switchStopDuringCall.setOnCheckedChangeListener((v, ic) -> {
            quickTick(15, 100);
            prefs.edit().putBoolean("stop_glyphs_during_call", ic).apply();
            refreshUIState();
        });

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                if (switchAutoBrightness.isChecked()) {
                    switchAutoBrightness.setChecked(false); // Manually sliding overrides auto
                }

                // Haptic feedback tick for slider
                quickTick(10, VibrationEffect.DEFAULT_AMPLITUDE);

                int brightness = mapPositionToBrightness(value);
                prefs.edit().putInt("brightness", brightness).apply();
                updateOutlineAlpha(value);

                if (isMasterAllowed) {
                    updateHardware(brightness);
                    previewHandler.removeCallbacksAndMessages(null);
                    previewHandler.postDelayed(() -> updateHardware(0), 1000);
                }
            }
        });

        if (sliderTorch != null) {
            sliderTorch.addOnChangeListener((s, value, fromUser) -> {
                if (fromUser) {
                    quickTick(10, VibrationEffect.DEFAULT_AMPLITUDE);
                    int torchBrightness = mapPositionToBrightness(value);
                    prefs.edit().putInt("torch_brightness", torchBrightness).apply();
                    if (isMasterAllowed && prefs.getBoolean("is_light_on", false)) {
                        updateHardware(torchBrightness);
                    }
                }
            });
        }

        sliderShakeSensitivity.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                int strength = (int) value;
                quickTick(20, strength);
                prefs.edit().putInt("shake_sensitivity", (int) value).apply();
            }
        });

        sliderRingNotifHapticStrength.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                int strength = (int) value;
                quickTick(20, strength);
                prefs.edit().putInt("ring_notif_haptic_strength", strength).apply();
            }
        });

        sliderProgressBrightness.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                quickTick(10, 50);
                prefs.edit().putInt("glyph_progress_brightness_factor", (int) value).apply();
            }
        });

        rgShakeCount.setOnCheckedChangeListener((group, checkedId) -> {
            int shakes = 2;
            if (checkedId == R.id.rb_one)
                shakes = 1;
            else if (checkedId == R.id.rb_three)
                shakes = 3;
            prefs.edit().putInt("shake_count", shakes).apply();
        });
    }

    private void showStyleDialog(
            int titleRes, String idxKey, String valKey, String folderName, String[] values) {
        if (values == null || values.length == 0)
            return;

        Intent intent = new Intent(this, StyleSelectionActivity.class);
        intent.putExtra("titleRes", titleRes);
        intent.putExtra("idxKey", idxKey);
        intent.putExtra("valKey", valKey);
        intent.putExtra("folderName", folderName);
        intent.putExtra("values", values);
        startActivity(intent);
    }

    private void updateMusicZoneLabel() {
        if (textCurrentMusic == null)
            return;
        int mode = prefs.getInt("visualizer_mode",
                org.aspends.nglyphs.services.AudioVisualizerService.MODE_15ZONE);
        switch (mode) {
            case org.aspends.nglyphs.services.AudioVisualizerService.MODE_BEAT:
                textCurrentMusic.setText("Beat detection \u00b7 Pulse on beats");
                break;
            case org.aspends.nglyphs.services.AudioVisualizerService.MODE_5ZONE:
                textCurrentMusic.setText("5-zone \u00b7 Sync to audio");
                break;
            default:
                textCurrentMusic.setText("15-zone \u00b7 Full LED array");
                break;
        }
    }

    private void updateStyleLabels() {
        int nIdx = prefs.getInt("glyph_blink_style_idx", 0);
        int cIdx = prefs.getInt("call_style_idx", 0);
        int fIdx = prefs.getInt("flip_style_idx", 0);

        List<java.io.File> customs = CustomRingtoneManager.getImportedRingtones(this);

        if (notifStyleValues != null && nIdx < notifStyleValues.length) {
            textCurrentNotifSound.setText(
                    CustomRingtoneManager.cleanStyleName(notifStyleValues[nIdx]));
        } else if (notifStyleValues != null && nIdx - notifStyleValues.length < customs.size()) {
            textCurrentNotifSound.setText(CustomRingtoneManager.cleanStyleName(
                    customs.get(nIdx - notifStyleValues.length).getName()));
        }

        if (callStyleValues != null && cIdx < callStyleValues.length) {
            textCurrentRingtone.setText(
                    CustomRingtoneManager.cleanStyleName(callStyleValues[cIdx]));
        } else if (callStyleValues != null && cIdx - callStyleValues.length < customs.size()) {
            textCurrentRingtone.setText(CustomRingtoneManager.cleanStyleName(
                    customs.get(cIdx - callStyleValues.length).getName()));
        }

        if (flipStyleValues != null && fIdx < flipStyleValues.length) {
            String val = flipStyleValues[fIdx];
            if (NATIVE_FLIP_VALUE.equals(val)) {
                textCurrentFlipStyle.setText("Stock");
            } else {
                textCurrentFlipStyle.setText(CustomRingtoneManager.cleanStyleName(val));
            }
        } else if (flipStyleValues != null && fIdx - flipStyleValues.length < customs.size()) {
            textCurrentFlipStyle.setText(CustomRingtoneManager.cleanStyleName(
                    customs.get(fIdx - flipStyleValues.length).getName()));
        }

        checkCustomAudioWarning((notifStyleValues != null && nIdx >= notifStyleValues.length)
                || (callStyleValues != null && cIdx >= callStyleValues.length)
                || (flipStyleValues != null && fIdx >= flipStyleValues.length));
    }

    private void checkCustomAudioWarning(boolean hasCustom) {
        if (textImportWarning != null) {
            textImportWarning.setVisibility(
                    hasCustom ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }
    private void handleImportResult(Intent data) {
        if (data != null && data.getData() != null) {
            Uri uri = data.getData();
            String originalName = CustomRingtoneManager.getFileNameFromUri(this, uri);
            if (originalName.toLowerCase().endsWith(".ogg"))
                originalName = originalName.substring(0, originalName.length() - 4);
            String fileName =
                    "imported_" + System.currentTimeMillis() + "_" + originalName + ".ogg";

            java.io.File oggFile = CustomRingtoneManager.importFile(this, uri, fileName);
            if (oggFile != null) {
                String timeline = null;
                java.io.File customCsv = new java.io.File(getFilesDir(),
                        "custom_ringtones/" + oggFile.getName().replace(".ogg", ".csv"));
                if (customCsv.exists()) {
                    timeline = CustomRingtoneManager.loadCSV(customCsv);
                } else {
                    timeline = OggMetadataParser.extractGlyphTimeline(oggFile);
                }
                if (timeline != null) {
                    Toast.makeText(this, "Success: Extracted Timeline & Saved!", Toast.LENGTH_SHORT)
                            .show();
                } else {
                    Toast.makeText(this,
                                 "Audio saved. You can now set it as system ringtone from the "
                                 + "style picker.",
                                 Toast.LENGTH_LONG)
                            .show();
                }
            } else {
                Toast.makeText(this, "Failed to import ringtone.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Checks and silently grants required permissions for the system app.
     */
    private void checkAllPermissions() {
        org.aspends.nglyphs.receivers.BootCompletedReceiver.grantNotificationListener(this);

        List<String> permissionsToRequest = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (prefs.getBoolean("assistant_mic_visualizer", false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!permissionsToRequest.isEmpty()) {
            requestPermissions(permissionsToRequest.toArray(new String[0]), 101);
        }
    }

    /**
     * Refreshes the visual state (enabled/alpha) of the UI elements based on
     * sleep mode and master toggle permissions.
     */
    private void refreshUIState() {
        boolean sleepActive = isSleepTimeActive();
        boolean generalEnabled = isMasterAllowed && !sleepActive;

        // Master switch and Sleep card are special
        switchMaster.setEnabled(!sleepActive);
        cardSleepTime.setEnabled(
                isMasterAllowed); // Always allow adjusting sleep time if master is on
        switchSleepMode.setEnabled(
                isMasterAllowed); // Always allow toggling sleep mode if master is on

        MaterialCardView[] cards = {cardNotifications, cardRingtones, cardBrightness, cardFlipStyle,
                cardEssentialLights, cardVolumeBar, cardRingNotifHaptics, cardBattery, cardTurnOff,
                cardShakeToGlyph, cardImport, cardGlyphProgress, cardSleepTime, cardStopDuringCall,
                cardTorchBrightness, cardAssistant, cardMusicVisualizer, cardGlyphConverter,
                cardNotifCooldown};

        for (MaterialCardView c : cards) {
            if (c == null)
                continue;
            boolean cardEnabled = generalEnabled;
            // Mutually exclusive logic: if Music Visualizer is ON, disable conflicting card modules
            if (switchMusicVisualizer != null && switchMusicVisualizer.isChecked()) {
                if (c == cardShakeToGlyph || c == cardGlyphProgress || c == cardAssistant) {
                    cardEnabled = false;
                }
            }

            // Exceptions for Sleep/TurnOff/Brightness (allow interaction if master is on)
            if (c == cardSleepTime || c == cardBrightness || c == cardTorchBrightness)
                cardEnabled = isMasterAllowed;

            c.setEnabled(cardEnabled);
            c.setAlpha(cardEnabled ? 1.0f : 0.5f);

            // Material 3 Adaptive States: Border and Background color
            if (c != cardBrightness && c != cardTorchBrightness) {
                c.setStrokeWidth(cardEnabled ? 0 : 1);
                c.setStrokeColor(android.graphics.Color.parseColor("#1F000000"));
                // Slightly dim the background if disabled
                if (!cardEnabled) {
                    c.setCardBackgroundColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                    c, com.google.android.material.R.attr.colorSurfaceVariant));
                } else {
                    c.setCardBackgroundColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                    c, com.google.android.material.R.attr.colorSurfaceContainer));
                }
            }
        }

        // Specifically handle all switches/sliders/pickers with hierarchical disabling
        MaterialSwitch[] switches = {switchFlip, switchLockscreenOnly, switchShake, switchVolumeBar,
                switchVolumeFlipOnly, switchRingNotifHaptics, switchShakeWhileOn,
                switchAutoBrightness, switchAssistantMic, switchGlyphProgress,
                switchGlyphProgressFlippedOnly, switchNotifCooldown, switchStopDuringCall,
                switchAssistant, switchMusicVisualizer, switchTorch};

        for (MaterialSwitch s : switches) {
            if (s != null) {
                boolean parentEnabled = generalEnabled;
                // Conflict Logic: Music Visualizer takes total precedence
                if (switchMusicVisualizer != null && switchMusicVisualizer.isChecked()) {
                    if (s == switchShake || s == switchGlyphProgress || s == switchAssistant) {
                        parentEnabled = false;
                    }
                }

                // Sub-feature logic: only enable if parent feature is ALSO checked
                if (s == switchVolumeFlipOnly)
                    parentEnabled &= switchVolumeBar.isChecked();
                if (s == switchShakeWhileOn)
                    parentEnabled &= switchShake.isChecked();
                if (s == switchGlyphProgressFlippedOnly)
                    parentEnabled &= switchGlyphProgress.isChecked();

                s.setEnabled(parentEnabled);
                s.setAlpha(parentEnabled ? 1.0f : 0.6f);
            }
        }

        Slider[] sliders = {slider, sliderShakeSensitivity, sliderRingNotifHapticStrength,
                sliderTorch, sliderProgressBrightness};

        for (Slider sl : sliders) {
            if (sl != null) {
                boolean parentEnabled = generalEnabled;
                if (sl == sliderShakeSensitivity)
                    parentEnabled &= switchShake.isChecked();
                if (sl == sliderProgressBrightness)
                    parentEnabled &= switchGlyphProgress.isChecked();

                sl.setEnabled(parentEnabled);
                sl.setAlpha(parentEnabled ? 1.0f : 0.6f);
            }
        }

        if (rgShakeCount != null) {
            boolean shakeParentEnabled = generalEnabled && switchShake.isChecked();
            for (int i = 0; i < rgShakeCount.getChildCount(); i++) {
                View child = rgShakeCount.getChildAt(i);
                child.setEnabled(shakeParentEnabled);
                child.setAlpha(shakeParentEnabled ? 1.0f : 0.6f);
            }
        }
    }

    /**
     * Triggers a brief, quick haptic vibration for UI feedback.
     *
     * @param d Duration in milliseconds.
     * @param a Amplitude (1-255).
     */
    private void quickTick(int d, int a) {
        if (!prefs.getBoolean("ring_notif_haptics_enabled", true))
            return;

        // Scale 0-100 to 1-255 to prevent crash and follow 0-100 slider
        int scaledA = (a * 255) / 100;
        scaledA = Math.max(1, Math.min(scaledA, 255));

        if (vibrator != null && vibrator.hasVibrator()) {
            android.os.VibrationAttributes attrs =
                    new android.os.VibrationAttributes.Builder()
                            .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                            .build();
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(d, scaledA), attrs);
        }
    }

    /**
     * Directly updates the brightness of all glyph LEDs via the GlyphManagerV2.
     *
     * @param val The brightness level to apply.
     */
    private void updateHardware(int val) {
        for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs())
            GlyphManagerV2.getInstance().setBrightness(g, val);

        if (val == 0) {
            GlyphManagerV2.getInstance().setBrightness(
                    GlyphManagerV2.Glyph.SINGLE_LED, 0); // Explicit 0 allows natural
                                                         // toggles off
            sendBroadcast(new Intent("org.aspends.nglyphs.ACTION_REFRESH_ESSENTIAL")
                            .setPackage(getPackageName()));
        }
    }

    /**
     * Requests the system to update the state of the Quick Settings tile
     * associated with the master toggle.
     */
    private void updateTile() {
        try {
            TileService.requestListeningState(
                    this, new ComponentName(this, MasterTileService.class));
        } catch (Exception ignored) {
        }
    }

    /**
     * Maps the UI slider's position (1 to 4) to actual brightness values.
     *
     * @param p The slider position.
     * @return The corresponding brightness level.
     */
    private int mapPositionToBrightness(float p) {
        // Steps of ~570 gap: 100, 671, 1242, 1813, 2384, 2955, 3526, 4095
        if (p <= 1)
            return 100;
        if (p <= 2)
            return 671;
        if (p <= 3)
            return 1242;
        if (p <= 4)
            return 1813;
        if (p <= 5)
            return 2384;
        if (p <= 6)
            return 2955;
        if (p <= 7)
            return 3526;
        return 4095;
    }

    /**
     * Maps actual brightness values back to the UI slider's position (1 to 8).
     *
     * @param b The brightness level.
     * @return The corresponding slider position.
     */
    private float mapBrightnessToPosition(int b) {
        if (b <= 100)
            return 1f;
        if (b <= 671)
            return 2f;
        if (b <= 1242)
            return 3f;
        if (b <= 1813)
            return 4f;
        if (b <= 2384)
            return 5f;
        if (b <= 2955)
            return 6f;
        if (b <= 3526)
            return 7f;
        return 8f;
    }

    /**
     * Updates the label showing the start and end times for Sleep Mode.
     */
    private void updateSleepTimeLabel() {
        textSleepTime.setText(prefs.getString("sleep_start", "23:00") + " - "
                + prefs.getString("sleep_end", "07:00"));
    }

    private boolean isSleepTimeActive() {
        if (!prefs.getBoolean("sleep_mode_enabled", false))
            return false;
        try {
            String[] s = prefs.getString("sleep_start", "23:00").split(":");
            String[] e = prefs.getString("sleep_end", "07:00").split(":");
            int startMin = Integer.parseInt(s[0]) * 60 + Integer.parseInt(s[1]);
            int endMin = Integer.parseInt(e[0]) * 60 + Integer.parseInt(e[1]);
            int nowMin = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) * 60
                    + java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE);
            return startMin < endMin ? (nowMin >= startMin && nowMin <= endMin)
                                     : (nowMin >= startMin || nowMin <= endMin);
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Called when the activity resumes from a paused state.
     * Re-initializes the UI to ensure changes from other activities
     * (like SleepModeActivity) are reflected.
     */
    @Override
    protected void onResume() {
        super.onResume();
        setupUI();
        org.aspends.nglyphs.core.AnimationManager.refreshBackgroundState();
    }

    private void setupInitialState() { setupUI(); }

    @Override
    protected void onPause() {
        super.onPause();
        GlyphEffects.stopCustomRingtone();
    }

    private void setupSmoothCollapse() {
        AppBarLayout appBar = findViewById(R.id.appBarLayout);
        CollapsingToolbarLayout ctl = findViewById(R.id.collapsingToolbar);
        if (appBar == null || ctl == null)
            return;

        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, typedValue, true);
        int colorOnSurface = typedValue.data;

        appBar.addOnOffsetChangedListener((bar, verticalOffset) -> {
            int totalScrollRange = bar.getTotalScrollRange();
            if (totalScrollRange == 0)
                return;

            float fraction = Math.abs((float) verticalOffset / totalScrollRange);
            float smooth = fraction * fraction * (3f - 2f * fraction);
            int alpha = (int) (255 * (1f - smooth));
            int color = (alpha << 24) | (colorOnSurface & 0x00FFFFFF);
            ctl.setExpandedTitleColor(color);
        });
    }

    private void updateOutlineAlpha(float sliderValue) {
        if (spacewar != null) {
            float alpha = 0.2f + ((sliderValue - 1) / 4f) * 0.8f;
            spacewar.setAlpha(alpha);
        }
    }
}