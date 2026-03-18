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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Calendar;

import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Utils.ServiceUtils;

public class GlyphScheduleService extends Service {

    private static final String TAG = "GlyphScheduleService";
    private static final boolean DEBUG = true;

    private static final String ACTION_SCHEDULE_START = "co.aospa.glyph.SCHEDULE_START";
    private static final String ACTION_SCHEDULE_END   = "co.aospa.glyph.SCHEDULE_END";

    private AlarmManager mAlarmManager;

    private final BroadcastReceiver mScheduleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            final String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "Received action: " + action);

            if (ACTION_SCHEDULE_START.equals(action)) {
                if (DEBUG) Log.d(TAG, "Schedule: stopping services (quiet hours begin)");
                ServiceUtils.stopOtherServices();
                LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(new Intent("co.aospa.glyph.SCHEDULE_STATE_CHANGED"));
                scheduleNextAlarms();
            } else if (ACTION_SCHEDULE_END.equals(action)) {
                if (DEBUG) Log.d(TAG, "Schedule: resuming services (quiet hours end)");
                ServiceUtils.startOtherServices();
                LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(new Intent("co.aospa.glyph.SCHEDULE_STATE_CHANGED"));
                scheduleNextAlarms();
            }
        }
    };

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "Creating GlyphScheduleService");
        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SCHEDULE_START);
        filter.addAction(ACTION_SCHEDULE_END);
        registerReceiver(mScheduleReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        applyCurrentScheduleState();
        scheduleNextAlarms();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting GlyphScheduleService");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying GlyphScheduleService");
        cancelAlarms();
        unregisterReceiver(mScheduleReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void applyCurrentScheduleState() {
        boolean shouldBeOff = SettingsManager.isWithinScheduledOffWindow();
        if (DEBUG) Log.d(TAG, "applyCurrentScheduleState: shouldBeOff=" + shouldBeOff);

        if (shouldBeOff) {
            ServiceUtils.stopOtherServices();
        } else {
            ServiceUtils.startOtherServices();
        }
    }

    private void applyGlyphState() {
        boolean enabled = SettingsManager.isGlyphEnabled();
        if (DEBUG) Log.d(TAG, "applyGlyphState: enabled=" + enabled);
        if (enabled) {
            ServiceUtils.startOtherServices();
        } else {
            ServiceUtils.stopOtherServices();
        }
    }

    private void scheduleNextAlarms() {
        cancelAlarms();

        int startHour   = SettingsManager.getGlyphScheduleStartHour();
        int startMinute = SettingsManager.getGlyphScheduleStartMinute();
        int endHour     = SettingsManager.getGlyphScheduleEndHour();
        int endMinute   = SettingsManager.getGlyphScheduleEndMinute();

        scheduleAlarm(ACTION_SCHEDULE_START, startHour, startMinute);
        scheduleAlarm(ACTION_SCHEDULE_END,   endHour,   endMinute);

        if (DEBUG) Log.d(TAG, "Alarms scheduled: start=" + startHour + ":" + startMinute
                + " end=" + endHour + ":" + endMinute);
    }

    private void scheduleAlarm(String action, int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        PendingIntent pi = buildPendingIntent(action);
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                cal.getTimeInMillis(), pi);
    }

    private void cancelAlarms() {
        mAlarmManager.cancel(buildPendingIntent(ACTION_SCHEDULE_START));
        mAlarmManager.cancel(buildPendingIntent(ACTION_SCHEDULE_END));
    }

    private PendingIntent buildPendingIntent(String action) {
        Intent intent = new Intent(action).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
