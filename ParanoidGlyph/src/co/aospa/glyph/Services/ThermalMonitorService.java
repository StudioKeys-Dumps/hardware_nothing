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

package co.aospa.glyph.Services;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Utils.FileUtils;

public class ThermalMonitorService extends Service {

    private static final String TAG = "GlyphThermalMonitorService";
    private static final boolean DEBUG = true;

    private static final int POLLING_INTERVAL_MS = 2000;

    private static final String CPU_TEMP_PATH_0 = "/sys/class/thermal/thermal_zone44/temp";
    private static final String CPU_TEMP_PATH_1 = "/sys/class/thermal/thermal_zone45/temp";

    private static final String DOT_LED_PATH = "/sys/class/leds/aw210xx_led/dot_led_br";

    private Handler mHandler;
    private Runnable mPollingRunnable;

    private boolean mCpuAlertActive = false;

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "Creating ThermalMonitorService");
        mHandler = new Handler();
        mPollingRunnable = new Runnable() {
            @Override
            public void run() {
                checkTemperatures();
                mHandler.postDelayed(this, POLLING_INTERVAL_MS);
            }
        };
        mHandler.post(mPollingRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting ThermalMonitorService");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying ThermalMonitorService");
        mHandler.removeCallbacks(mPollingRunnable);
        clearAlert();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void checkTemperatures() {
        if (SettingsManager.isGlyphMusicVisualizerEnabled()) {
            if (mCpuAlertActive) clearAlert();
            return;
        }
        checkCpu();
    }

    private void checkCpu() {
        if (!SettingsManager.isGlyphThermalCpuEnabled()) {
            if (mCpuAlertActive) clearAlert();
            return;
        }

        int threshold = SettingsManager.getGlyphThermalCpuThreshold() * 1000;
        int temp0 = FileUtils.readLineInt(CPU_TEMP_PATH_0);
        int temp1 = FileUtils.readLineInt(CPU_TEMP_PATH_1);
        int maxTemp = Math.max(temp0, temp1);

        if (DEBUG) Log.d(TAG, "CPU temp: " + (maxTemp / 1000) + "°C threshold: " + (threshold / 1000) + "°C");

        if (maxTemp >= threshold) {
            if (!mCpuAlertActive) {
                if (DEBUG) Log.d(TAG, "CPU threshold exceeded, activating alert");
                mCpuAlertActive = true;
                activateAlert();
            }
        } else {
            if (mCpuAlertActive) {
                if (DEBUG) Log.d(TAG, "CPU temp back to normal, clearing alert");
                clearAlert();
            }
        }
    }

    private void activateAlert() {
        FileUtils.writeLine(DOT_LED_PATH, Constants.getBrightness());
    }

    private void clearAlert() {
        mCpuAlertActive = false;
        FileUtils.writeLine(DOT_LED_PATH, 0);
    }
}
