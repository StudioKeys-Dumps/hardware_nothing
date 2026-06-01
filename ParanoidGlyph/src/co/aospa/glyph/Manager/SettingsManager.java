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

package co.aospa.glyph.Manager;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.android.internal.util.ArrayUtils;

import java.util.HashSet;
import java.util.Set;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Utils.ResourceUtils;

public final class SettingsManager {

    private static final String TAG = "GlyphSettingsManager";
    private static final boolean DEBUG = true;

    private static Context context = Constants.CONTEXT;

    public static boolean enableGlyph(boolean enable) {
        return Settings.Secure.putInt(context.getContentResolver(),
                Constants.GLYPH_ENABLE, enable ? 1 : 0);
    }

    public static boolean isGlyphEnabled() {
        return Settings.Secure.getInt(context.getContentResolver(),
                Constants.GLYPH_ENABLE, 1) != 0;
    }

    public static boolean isGlyphFlipEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.GLYPH_FLIP_ENABLE, false) && isGlyphEnabled();
    }

    public static int getGlyphBrightness() {
        int[] levels = Constants.getBrightnessLevels();
        int brightnessSetting = getGlyphBrightnessSetting();
        return levels[brightnessSetting - 1];
    }

    public static int getGlyphBrightnessSetting() {
        int d = 3;
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(Constants.GLYPH_BRIGHTNESS, d);
    }

    public static boolean isGlyphChargingEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.GLYPH_CHARGING_LEVEL_ENABLE, false) && isGlyphEnabled();
    }

    public static boolean isGlyphPowershareEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.GLYPH_CHARGING_POWERSHARE_ENABLE, false) && isGlyphEnabled();
    }

    public static boolean isGlyphCallEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("glyph_settings_call_toggle", true) && isGlyphEnabled();
    }

    public static boolean setGlyphCallEnabled(boolean enable) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean("glyph_settings_call_toggle", enable)
                .commit();
    }

    public static String getGlyphCallAnimation() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.GLYPH_CALL_SUB_ANIMATIONS,
                        ResourceUtils.getString("glyph_settings_call_animations_default"));
    }

    public static String getGlyphCallCustomRingtoneUri() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.GLYPH_CALL_SUB_CUSTOM_RINGTONE_URI, "");
    }

    public static void setGlyphCallCustomRingtoneUri(String uri) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(Constants.GLYPH_CALL_SUB_CUSTOM_RINGTONE_URI, uri)
                .apply();
    }

    public static boolean isGlyphMusicVisualizerEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.GLYPH_MUSIC_VISUALIZER_ENABLE, false) && isGlyphEnabled();
    }

    public static boolean isGlyphVolumeLevelEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.GLYPH_VOLUME_LEVEL_ENABLE, false) && isGlyphEnabled();
    }

    public static boolean isGlyphNotifsEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("glyph_settings_notifs_toggle", true) && isGlyphEnabled();
    }

    public static boolean setGlyphNotifsEnabled(boolean enable) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean("glyph_settings_notifs_toggle", enable)
                .commit();
    }

    public static String getGlyphNotifsAnimation() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.GLYPH_NOTIFS_SUB_ANIMATIONS,
                        ResourceUtils.getString("glyph_settings_notifs_animations_default"));
    }

    public static String getGlyphNotifsCustomSoundUri() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.GLYPH_NOTIFS_SUB_ANIMATIONS, "");
    }

    public static void setGlyphNotifsCustomSoundUri(String uri) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(Constants.GLYPH_NOTIFS_SUB_CUSTOM_SOUND_URI, uri)
                .apply();
    }

    public static boolean isGlyphNotifsAppEnabled(String app) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(app, true) && isGlyphNotifsEnabled();
    }

    public static boolean isGlyphNotifsAppEssential(String app) {
        Set<String> selectedValues = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(Constants.GLYPH_NOTIFS_SUB_ESSENTIAL, new HashSet<String>());
        return selectedValues.contains(app) && isGlyphNotifsEnabled();
    }

    public static boolean isGlyphShakeTorchEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.GLYPH_SHAKE_TORCH_ENABLE, false) && isGlyphEnabled();
    }

    public static boolean isHapticEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.GLYPH_HAPTIC_ENABLE, false) && isGlyphEnabled();
    }

    public static int getGlyphScheduleMode() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(Constants.GLYPH_SCHEDULE_MODE, 0);
    }

    public static void setGlyphScheduleMode(int mode) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putInt(Constants.GLYPH_SCHEDULE_MODE, mode)
                .apply();
    }

    public static int getGlyphScheduleStartHour() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(Constants.GLYPH_SCHEDULE_START_HOUR, 22);
    }

    public static int getGlyphScheduleStartMinute() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(Constants.GLYPH_SCHEDULE_START_MINUTE, 0);
    }

    public static void setGlyphScheduleStart(int hour, int minute) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putInt(Constants.GLYPH_SCHEDULE_START_HOUR, hour)
                .putInt(Constants.GLYPH_SCHEDULE_START_MINUTE, minute)
                .apply();
    }

    public static int getGlyphScheduleEndHour() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(Constants.GLYPH_SCHEDULE_END_HOUR, 7);
    }

    public static int getGlyphScheduleEndMinute() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(Constants.GLYPH_SCHEDULE_END_MINUTE, 0);
    }

    public static void setGlyphScheduleEnd(int hour, int minute) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putInt(Constants.GLYPH_SCHEDULE_END_HOUR, hour)
                .putInt(Constants.GLYPH_SCHEDULE_END_MINUTE, minute)
                .apply();
    }

    public static boolean isGlyphScheduleEnabled() {
        return getGlyphScheduleMode() == 1 && isGlyphEnabled();
    }

    public static boolean isWithinScheduledOffWindow() {
        if (!isGlyphScheduleEnabled()) return false;

        java.util.Calendar now = java.util.Calendar.getInstance();
        int nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + now.get(java.util.Calendar.MINUTE);

        int startMinutes = getGlyphScheduleStartHour() * 60 + getGlyphScheduleStartMinute();
        int endMinutes   = getGlyphScheduleEndHour()   * 60 + getGlyphScheduleEndMinute();

        if (startMinutes == endMinutes) return false;

        if (startMinutes > endMinutes) {
            return nowMinutes >= startMinutes || nowMinutes < endMinutes;
        }
        return nowMinutes >= startMinutes && nowMinutes < endMinutes;
    }

    public static void setGlyphCameraRecordingLedEnabled(boolean enabled) {
        Settings.Secure.putInt(context.getContentResolver(),
            Constants.GLYPH_CAMERA_RECORDING_LED_ENABLE, enabled ? 1 : 0);
    }

    public static boolean isGlyphCameraRecordingLedEnabled() {
        return Settings.Secure.getInt(context.getContentResolver(),
            Constants.GLYPH_CAMERA_RECORDING_LED_ENABLE, 1) == 1;
    }

}
