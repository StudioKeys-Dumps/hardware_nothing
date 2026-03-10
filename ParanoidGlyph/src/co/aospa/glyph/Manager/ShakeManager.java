/*
 * Copyright (C) 2024-2025 LunarisAOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.aospa.glyph.Manager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Services.ShakeDetectorService;

public class ShakeManager {

    private static final String TAG = "GlyphShakeManager";
    private static final boolean DEBUG = false;

    /**
     * Start the shake detector service if enabled in settings
     * @param context Application context
     */
    public static void startShakeService(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot start service");
            return;
        }
        
        if (isShakeEnabled(context) && SettingsManager.isGlyphEnabled()) {
            try {
                Intent serviceIntent = new Intent(context, ShakeDetectorService.class);
                context.startService(serviceIntent);
                if (DEBUG) Log.d(TAG, "Shake service start requested");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start shake service", e);
            }
        } else {
            if (DEBUG) Log.d(TAG, "Shake or Glyph disabled, not starting service");
        }
    }

    /**
     * Stop the shake detector service
     * @param context Application context
     */
    public static void stopShakeService(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot stop service");
            return;
        }
        
        try {
            Intent serviceIntent = new Intent(context, ShakeDetectorService.class);
            context.stopService(serviceIntent);
            if (DEBUG) Log.d(TAG, "Shake service stop requested");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop shake service", e);
        }
    }

    /**
     * Restart the shake detector service (useful when settings change)
     * @param context Application context
     */
    public static void restartShakeService(Context context) {
        if (DEBUG) Log.d(TAG, "Restarting shake service");
        stopShakeService(context);
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }
        
        startShakeService(context);
    }

    /**
     * Check if shake gesture is enabled in preferences
     * @param context Application context
     * @return true if shake is enabled, false otherwise
     */
    public static boolean isShakeEnabled(Context context) {
        if (context == null) {
            return false;
        }
        
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            return prefs.getBoolean(Constants.GLYPH_SHAKE_TORCH_ENABLE, false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read shake preference", e);
            return false;
        }
    }

    /**
     * Enable or disable shake gesture
     * @param context Application context
     * @param enabled true to enable, false to disable
     */
    public static void setShakeEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().putBoolean(Constants.GLYPH_SHAKE_TORCH_ENABLE, enabled).apply();
            
            if (enabled) {
                startShakeService(context);
            } else {
                stopShakeService(context);
            }
            
            if (DEBUG) Log.d(TAG, "Shake gesture " + (enabled ? "enabled" : "disabled"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to set shake preference", e);
        }
    }
}