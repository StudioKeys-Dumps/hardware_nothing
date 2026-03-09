/*
 * Copyright (C) 2020-2024 Paranoid Android
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

package co.aospa.glyph.Services;

import android.app.AppOpsManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.annotation.Nullable;

import co.aospa.glyph.Utils.FileUtils;
import co.aospa.glyph.Constants.Constants;

public class CameraRecordingService extends Service {

    private static final String TAG = "GlyphCameraRecording";
    private static final boolean DEBUG = true;

    private static final String VIDEO_LED_PATH =
            "/sys/class/leds/aw210xx_led/video_leds_effect";

    private AppOpsManager appOps;
    private TelecomManager telecomManager;
    private AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean isRecordingLedOn = false;
    private volatile String cameraPackage = null;
    private volatile String micPackage = null;

    private final AppOpsManager.OnOpActiveChangedListener cameraListener =
            (op, uid, packageName, active) -> {
                cameraPackage = active ? packageName : null;
                handler.post(this::evaluateRecordingState);
            };

    private final AppOpsManager.OnOpActiveChangedListener micListener =
            (op, uid, packageName, active) -> {
                micPackage = active ? packageName : null;
                handler.post(this::evaluateRecordingState);
            };

    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) Log.d(TAG, "onCreate");
        appOps = getSystemService(AppOpsManager.class);
        telecomManager = getSystemService(TelecomManager.class);
        audioManager = getSystemService(AudioManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "onStartCommand");
        appOps.startWatchingActive(
                new String[]{AppOpsManager.OPSTR_CAMERA},
                getMainExecutor(), cameraListener);
        appOps.startWatchingActive(
                new String[]{AppOpsManager.OPSTR_RECORD_AUDIO},
                getMainExecutor(), micListener);
        return START_STICKY;
    }

    private void evaluateRecordingState() {
        boolean isRecording = cameraPackage != null
                && micPackage != null
                && cameraPackage.equals(micPackage)
                && !isCallActive()
                && isCameraApp(cameraPackage);

        if (DEBUG) Log.d(TAG, "evaluateRecordingState: isRecording=" + isRecording
                + " cameraPackage=" + cameraPackage
                + " micPackage=" + micPackage);

        if (isRecording && !isRecordingLedOn) {
            isRecordingLedOn = true;
            if (DEBUG) Log.d(TAG, "Starting recording LED");
            FileUtils.writeLine(VIDEO_LED_PATH, 1);
        } else if (!isRecording && isRecordingLedOn) {
            isRecordingLedOn = false;
            if (DEBUG) Log.d(TAG, "Stopping recording LED");
            FileUtils.writeLine(VIDEO_LED_PATH, 0);
        }
    }

    private boolean isCallActive() {
        if (telecomManager != null && telecomManager.isInCall()) {
            return true;
        }
        int mode = audioManager.getMode();
        return mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_COMMUNICATION_REDIRECT;
    }

    private boolean isCameraApp(String packageName) {
        try {
            PackageManager pm = getPackageManager();

            Intent connService = new Intent("android.telecom.ConnectionService");
            connService.setPackage(packageName);
            if (!pm.queryIntentServices(connService, 0).isEmpty()) {
                return false;
            }

            Intent inCallService = new Intent("android.telecom.InCallService");
            inCallService.setPackage(packageName);
            if (!pm.queryIntentServices(inCallService, 0).isEmpty()) {
                return false;
            }

            Intent captureIntent = new Intent(
                    android.provider.MediaStore.ACTION_VIDEO_CAPTURE);
            captureIntent.setPackage(packageName);
            if (!pm.queryIntentActivities(captureIntent, 0).isEmpty()) {
                return true;
            }

            Intent imageIntent = new Intent(
                    android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            imageIntent.setPackage(packageName);
            if (!pm.queryIntentActivities(imageIntent, 0).isEmpty()) {
                return true;
            }

            return audioManager.getMode() == AudioManager.MODE_NORMAL;
        } catch (Exception e) {
            Log.e(TAG, "isCameraApp error", e);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy");
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        appOps.stopWatchingActive(cameraListener);
        appOps.stopWatchingActive(micListener);
        if (isRecordingLedOn) {
            isRecordingLedOn = false;
            FileUtils.writeLine(VIDEO_LED_PATH, 0);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
