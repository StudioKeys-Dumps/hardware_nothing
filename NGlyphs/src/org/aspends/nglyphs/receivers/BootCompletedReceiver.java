package org.aspends.nglyphs.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.services.*;

/**
 * Ensures Glyph services are started automatically after device reboot.
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "Starting Glyph services after boot: " + action);
            RingtoneSyncObserver.register(context.getApplicationContext());

            SharedPreferences prefs = context.getSharedPreferences(
                    context.getString(R.string.pref_file), Context.MODE_PRIVATE);

            if (!prefs.getBoolean("master_allow", false)) {
                Log.d(TAG, "Master toggle is OFF, skipping service start.");
                return;
            }

            try {
                context.startService(new Intent(context, CameraRecordingService.class));
            } catch (Exception e) {
                Log.e(TAG, "Failed to start CameraRecordingService", e);
            }

            // Start enabled services
            startServiceIfEnabled(
                    context, prefs, "flip_to_glyph_enabled", FlipToGlyphService.class);
            startServiceIfEnabled(
                    context, prefs, "battery_glyph_enabled", BatteryGlyphService.class);
            startServiceIfEnabled(
                    context, prefs, "powershare_glyph_enabled", PowershareService.class);
            startServiceIfEnabled(
                    context, prefs, "volume_bar_enabled", VolumeObserverService.class);
            startServiceIfEnabled(context, prefs, "shake_enabled", ShakeToGlyphService.class);
            startServiceIfEnabled(
                    context, prefs, "auto_brightness_enabled", AutoBrightnessService.class);

            startServiceIfEnabled(context, prefs, "assistant_animations_enabled",
                    AssistantInteractionService.class);

            if (prefs.getBoolean("music_visualizer_enabled", false)) {
                context.startService(new Intent(context, AudioVisualizerService.class));
            }
        }
    }

    private void startServiceIfEnabled(
            Context context, SharedPreferences prefs, String key, Class<?> serviceClass) {
        boolean enabled = prefs.getBoolean(key, true); // Default to true for volume/flip if not set
        if (enabled) {
            Log.d(TAG, "Starting " + serviceClass.getSimpleName());
            try {
                context.startService(new Intent(context, serviceClass));
            } catch (Exception e) {
                Log.e(TAG, "Failed to start " + serviceClass.getSimpleName(), e);
            }
        }
    }
}
