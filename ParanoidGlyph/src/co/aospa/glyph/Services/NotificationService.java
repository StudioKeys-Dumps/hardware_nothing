/*
 * Copyright (C) 2022-2024 Paranoid Android
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Manager.AnimationManager;
import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Manager.StatusManager;

public class NotificationService extends NotificationListenerService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "GlyphNotification";
    private static final boolean DEBUG = true;

    private NotificationManager mNotificationManager;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;

    private ContentResolver mContentResolver;
    private SettingObserver mSettingObserver;

    private SharedPreferences mSharedPreferences;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "Creating service");
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":WakeLock");
        mContentResolver = getContentResolver();
        mSettingObserver = new SettingObserver();
        mSettingObserver.register(mContentResolver);
        mSharedPreferences = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting service");
        onNotificationUpdated();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service");
        mHandler.removeCallbacksAndMessages(null);
        AnimationManager.stopEssential();
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        mSettingObserver.unregister(mContentResolver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (Constants.CONTEXT == null) return;
        if (!SettingsManager.isGlyphNotifsEnabled()) return;

        String packageName = sbn.getPackageName();
        String channelID = sbn.getNotification().getChannelId();
        int importance = -1;
        boolean canBypassDnd = false;
        int interruptionFilter = mNotificationManager.getCurrentInterruptionFilter();

        try {
            Context pkgContext = createPackageContext(packageName, 0);
            NotificationManager pkgNm = (NotificationManager) pkgContext.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = pkgNm.getNotificationChannel(channelID);
            if (channel != null) {
                importance = channel.getImportance();
                canBypassDnd = channel.canBypassDnd();
            }
        } catch (Exception ignored) {}

        if (DEBUG) Log.d(TAG, "Notification posted: " + packageName + " | channel: " + channelID);

        if (SettingsManager.isGlyphNotifsAppEnabled(packageName)
                && !sbn.isOngoing()
                && !ArrayUtils.contains(Constants.APPS_TO_IGNORE, packageName)
                && !ArrayUtils.contains(Constants.NOTIFS_TO_IGNORE, packageName + ":" + channelID)
                && (importance >= NotificationManager.IMPORTANCE_DEFAULT || importance == -1)
                && (interruptionFilter <= NotificationManager.INTERRUPTION_FILTER_ALL || canBypassDnd)) {

            mWakeLock.acquire(2500);

            final String animName = SettingsManager.getGlyphNotifsAnimation();

            mHandler.postDelayed(() -> {
                AnimationManager.playCsv(animName);
            }, 180);
        }

        if (SettingsManager.isGlyphNotifsAppEssential(packageName)
                && !sbn.isOngoing()
                && !ArrayUtils.contains(Constants.APPS_TO_IGNORE, packageName)
                && !ArrayUtils.contains(Constants.NOTIFS_TO_IGNORE, packageName + ":" + channelID)
                && (importance >= NotificationManager.IMPORTANCE_DEFAULT || importance == -1)
                && (interruptionFilter <= NotificationManager.INTERRUPTION_FILTER_ALL || canBypassDnd)
                && mNotificationManager.isNotificationPolicyAccessGranted()) {

            AnimationManager.playEssential();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (DEBUG) Log.d(TAG, "Notification removed: " + sbn.getPackageName());
        onNotificationUpdated();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key.equals("glyph_settings_notifs_sub_essential")) {
            onNotificationUpdated();
        }
    }

    private void onNotificationUpdated() {
        if (!SettingsManager.isGlyphNotifsEnabled()) {
            AnimationManager.stopEssential();
            return;
        }

        if (!mNotificationManager.isNotificationPolicyAccessGranted()) return;

        boolean shouldPlayEssential = false;
        StatusBarNotification[] active = getActiveNotifications();

        for (StatusBarNotification sbn : active) {
            if (sbn.isOngoing()) continue;

            String pkg = sbn.getPackageName();
            String ch = sbn.getNotification().getChannelId();

            if (SettingsManager.isGlyphNotifsAppEssential(pkg)
                    && !ArrayUtils.contains(Constants.APPS_TO_IGNORE, pkg)
                    && !ArrayUtils.contains(Constants.NOTIFS_TO_IGNORE, pkg + ":" + ch)) {
                shouldPlayEssential = true;
                break;
            }
        }

        if (shouldPlayEssential) {
            AnimationManager.playEssential();
        } else {
            AnimationManager.stopEssential();
        }
    }

    private class SettingObserver extends ContentObserver {
        public SettingObserver() {
            super(new Handler(Looper.getMainLooper()));
        }

        public void register(ContentResolver cr) {
            cr.registerContentObserver(Settings.Secure.getUriFor(Constants.GLYPH_ENABLE), false, this);
            cr.registerContentObserver(Settings.Secure.getUriFor(Constants.GLYPH_NOTIFS_ENABLE), false, this);
        }

        public void unregister(ContentResolver cr) {
            cr.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            onNotificationUpdated();
            super.onChange(selfChange);
        }
    }
}
