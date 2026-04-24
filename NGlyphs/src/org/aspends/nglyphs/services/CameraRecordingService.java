package org.aspends.nglyphs.services;

import android.app.AppOpsManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.util.Log;
import androidx.annotation.Nullable;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.GlyphManagerV2;

public class CameraRecordingService extends Service {
    private static final String TAG = "CameraRecordingService";
    private static final String PREF_MASTER_ALLOW = "master_allow";

    private AppOpsManager appOps;
    private TelecomManager telecomManager;
    private AudioManager audioManager;
    private SharedPreferences prefs;
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

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sp, key) -> {
                if (PREF_MASTER_ALLOW.equals(key)) {
                    handler.post(this::evaluateRecordingState);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        appOps = getSystemService(AppOpsManager.class);
        telecomManager = getSystemService(TelecomManager.class);
        audioManager = getSystemService(AudioManager.class);
        prefs = getSharedPreferences(getString(R.string.pref_file), Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        appOps.startWatchingActive(
                new String[]{AppOpsManager.OPSTR_CAMERA},
                getMainExecutor(), cameraListener);
        appOps.startWatchingActive(
                new String[]{AppOpsManager.OPSTR_RECORD_AUDIO},
                getMainExecutor(), micListener);
        return START_STICKY;
    }

    private void evaluateRecordingState() {
        boolean masterAllowed = prefs.getBoolean(PREF_MASTER_ALLOW, false);
        boolean isRecording = masterAllowed
                && cameraPackage != null
                && micPackage != null
                && cameraPackage.equals(micPackage)
                && !isCallActive()
                && isCameraApp(cameraPackage);

        if (isRecording && !isRecordingLedOn) {
            isRecordingLedOn = true;
            GlyphManagerV2.getInstance().setNativeEffect(
                    GlyphManagerV2.NativeEffect.VIDEO, 1);
        } else if (!isRecording && isRecordingLedOn) {
            isRecordingLedOn = false;
            GlyphManagerV2.getInstance().setNativeEffect(
                    GlyphManagerV2.NativeEffect.VIDEO, 0);
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
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        appOps.stopWatchingActive(cameraListener);
        appOps.stopWatchingActive(micListener);
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        if (isRecordingLedOn) {
            isRecordingLedOn = false;
            GlyphManagerV2.getInstance().setNativeEffect(
                    GlyphManagerV2.NativeEffect.VIDEO, 0);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
