/*
 * Copyright (C) 2024-2025 LunarisAOSP
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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.preference.PreferenceManager;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Manager.StatusManager;
import co.aospa.glyph.Utils.FileUtils;
import co.aospa.glyph.Utils.ResourceUtils;

public class ShakeDetectorService extends Service implements SensorEventListener {

    private static final String TAG = "GlyphShakeDetector";
    private static final boolean DEBUG = false;
    
    private static final int SHAKE_TIME_WINDOW = 500; 
    private static final int SHAKE_COUNT_THRESHOLD = 2;
    
    private static final int DEFAULT_SENSITIVITY = 35;
    
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private SharedPreferences mSharedPrefs;
    private PowerManager.WakeLock mWakeLock;
    private Vibrator mVibrator;
    
    private long mLastShakeTime = 0;
    private int mShakeCount = 0;
    private float mCurrentThreshold = DEFAULT_SENSITIVITY;

    @Override
    public void onCreate() {
        super.onCreate();
        
        if (DEBUG) Log.d(TAG, "Service created");
        
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":ShakeWakeLock");
        
        loadSensitivity();
        
        registerSensorListener();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Service started");
        
        loadSensitivity();
        
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loadSensitivity() {
        int sensitivity = mSharedPrefs.getInt(Constants.GLYPH_SHAKE_SENSITIVITY, DEFAULT_SENSITIVITY);
        mCurrentThreshold = (float) sensitivity;
        if (DEBUG) Log.d(TAG, "Shake sensitivity loaded: " + mCurrentThreshold);
    }

    private void registerSensorListener() {
        if (mAccelerometer != null && isShakeEnabled() && SettingsManager.isGlyphEnabled()) {
            mSensorManager.registerListener(this, mAccelerometer, 
                SensorManager.SENSOR_DELAY_GAME);
            if (DEBUG) Log.d(TAG, "Accelerometer listener registered with threshold: " + mCurrentThreshold);
        } else {
            if (DEBUG) Log.d(TAG, "Shake disabled or Glyph disabled, listener not registered");
        }
    }

    private void unregisterSensorListener() {
        mSensorManager.unregisterListener(this);
        if (DEBUG) Log.d(TAG, "Accelerometer listener unregistered");
    }

    private boolean isShakeEnabled() {
        return mSharedPrefs.getBoolean(Constants.GLYPH_SHAKE_TORCH_ENABLE, false);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        if (!isShakeEnabled() || !SettingsManager.isGlyphEnabled()) {
            return;
        }

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float acceleration = (float) Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;

        if (acceleration > mCurrentThreshold) {
            long currentTime = SystemClock.elapsedRealtime();
            
            if (currentTime - mLastShakeTime < SHAKE_TIME_WINDOW) {
                mShakeCount++;
                if (DEBUG) Log.d(TAG, "Shake detected, count: " + mShakeCount + ", accel: " + acceleration);
                
                if (mShakeCount >= SHAKE_COUNT_THRESHOLD) {
                    onShakeDetected();
                    mShakeCount = 0;
                }
            } else {
                mShakeCount = 1;
                if (DEBUG) Log.d(TAG, "First shake detected, accel: " + acceleration);
            }
            
            mLastShakeTime = currentTime;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void onShakeDetected() {
        if (DEBUG) Log.d(TAG, "Shake gesture triggered, toggling torch");
        
        performHapticFeedback();
        
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            mWakeLock.acquire(3000);
        }
        
        try {
            boolean currentState = StatusManager.isAllLedActive();
            boolean newState = !currentState;
            
            StatusManager.setAllLedsActive(newState);
            FileUtils.writeAllLed(newState ? Constants.getMaxBrightness() : 0);

            if (StatusManager.isEssentialLedActive() && !newState) {
                FileUtils.writeSingleLed(
                    ResourceUtils.getInteger("glyph_settings_notifs_essential_led"),
                    Constants.getMaxBrightness() / 100 * 7);
            }
            
            if (DEBUG) Log.d(TAG, "Torch toggled to: " + (newState ? "ON" : "OFF"));
            
        } catch (Exception e) {
            Log.e(TAG, "Error toggling torch", e);
        } finally {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    private void performHapticFeedback() {
        if (mVibrator != null && mVibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                mVibrator.vibrate(50);
            }
            if (DEBUG) Log.d(TAG, "Haptic feedback triggered");
        }
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Service destroyed");
        
        unregisterSensorListener();
        
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        
        super.onDestroy();
    }
}
