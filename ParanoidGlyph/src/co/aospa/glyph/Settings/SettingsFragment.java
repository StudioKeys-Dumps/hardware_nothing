/*
 * Copyright (C) 2015 The CyanogenMod Project
 * 2017-2019 The LineageOS Project
 * 2020-2024 Paranoid Android
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

package co.aospa.glyph.Settings;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.PrimarySwitchPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SliderPreference;

import co.aospa.glyph.R;
import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Manager.ShakeManager;
import co.aospa.glyph.Utils.ServiceUtils;

public class SettingsFragment extends SettingsBasePreferenceFragment implements OnPreferenceChangeListener {

    private SwitchPreferenceCompat mFlipPreference;
    private SliderPreference mBrightnessPreference;
    private PrimarySwitchPreference mNotifsPreference;
    private PrimarySwitchPreference mCallPreference;
    private SwitchPreferenceCompat mChargingLevelPreference;
    private SwitchPreferenceCompat mChargingPowersharePreference;
    private SwitchPreferenceCompat mVolumeLevelPreference;
    private SwitchPreferenceCompat mShakeTorchPreference;
    private SeekBarPreference mShakeSensitivityPreference;
    private SwitchPreferenceCompat mMusicVisualizerPreference;

    private ContentResolver mContentResolver;
    private SettingObserver mSettingObserver;

    private Handler mHandler = new Handler();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.glyph_settings);

        mContentResolver = getActivity().getContentResolver();
        mSettingObserver = new SettingObserver();
        mSettingObserver.register(mContentResolver);

        boolean glyphEnabled = SettingsManager.isGlyphEnabled();
        boolean musicEnabled = SettingsManager.isGlyphMusicVisualizerEnabled();

        MainSwitchPreference switchBar = findPreference(Constants.GLYPH_ENABLE);
        switchBar.setOnPreferenceChangeListener(this);
        switchBar.setChecked(glyphEnabled);

        mFlipPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_FLIP_ENABLE);
        mFlipPreference.setOnPreferenceChangeListener(this);

        mBrightnessPreference = (SliderPreference) findPreference(Constants.GLYPH_BRIGHTNESS);
        mBrightnessPreference.setMin(1);
        mBrightnessPreference.setMax(Constants.getBrightnessLevels().length);
        mBrightnessPreference.setSliderIncrement(1);
        mBrightnessPreference.setHapticFeedbackMode(SliderPreference.HAPTIC_FEEDBACK_MODE_ON_TICKS);
        mBrightnessPreference.setValue(SettingsManager.getGlyphBrightnessSetting());
        mBrightnessPreference.setTickVisible(true);
        mBrightnessPreference.setUpdatesContinuously(true);
        mBrightnessPreference.setOnPreferenceChangeListener(this);

        mNotifsPreference = (PrimarySwitchPreference) findPreference(Constants.GLYPH_NOTIFS_ENABLE);
        mNotifsPreference.setChecked(SettingsManager.isGlyphNotifsEnabled());
        mNotifsPreference.setOnPreferenceChangeListener(this);

        mCallPreference = (PrimarySwitchPreference) findPreference(Constants.GLYPH_CALL_ENABLE);
        mCallPreference.setChecked(SettingsManager.isGlyphCallEnabled());
        mCallPreference.setOnPreferenceChangeListener(this);

        mChargingLevelPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_CHARGING_LEVEL_ENABLE);
        mChargingLevelPreference.setOnPreferenceChangeListener(this);

        mChargingPowersharePreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_CHARGING_POWERSHARE_ENABLE);
        mChargingPowersharePreference.setOnPreferenceChangeListener(this);

        mVolumeLevelPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_VOLUME_LEVEL_ENABLE);
        mVolumeLevelPreference.setOnPreferenceChangeListener(this);

        mShakeTorchPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_SHAKE_TORCH_ENABLE);
        mShakeTorchPreference.setOnPreferenceChangeListener(this);

        mShakeSensitivityPreference = (SeekBarPreference) findPreference(Constants.GLYPH_SHAKE_SENSITIVITY);
        mShakeSensitivityPreference.setUpdatesContinuously(false);
        mShakeSensitivityPreference.setOnPreferenceChangeListener(this);

        mMusicVisualizerPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_MUSIC_VISUALIZER_ENABLE);
        mMusicVisualizerPreference.setOnPreferenceChangeListener(this);

        updateDependencies(glyphEnabled, mMusicVisualizerPreference.isChecked());

        mHandler.post(() -> ServiceUtils.checkGlyphService());
    }

    private void updateDependencies(boolean glyphEnabled, boolean musicEnabled) {
        boolean canEnableSubFeatures = glyphEnabled && !musicEnabled;

        mFlipPreference.setEnabled(canEnableSubFeatures);
        mBrightnessPreference.setEnabled(glyphEnabled);
        mNotifsPreference.setEnabled(canEnableSubFeatures);
        mNotifsPreference.setSwitchEnabled(canEnableSubFeatures);
        mCallPreference.setEnabled(canEnableSubFeatures);
        mCallPreference.setSwitchEnabled(canEnableSubFeatures);
        mChargingLevelPreference.setEnabled(canEnableSubFeatures);
        mChargingPowersharePreference.setEnabled(canEnableSubFeatures);
        mVolumeLevelPreference.setEnabled(canEnableSubFeatures);
        mMusicVisualizerPreference.setEnabled(glyphEnabled);
        mShakeTorchPreference.setEnabled(canEnableSubFeatures);
        mShakeSensitivityPreference.setEnabled(canEnableSubFeatures && mShakeTorchPreference.isChecked());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String preferenceKey = preference.getKey();
        boolean isGlyphEnabled = SettingsManager.isGlyphEnabled();
        boolean isMusicEnabled = mMusicVisualizerPreference.isChecked();

        if (preferenceKey.equals(Constants.GLYPH_ENABLE)) {
            boolean enabled = (Boolean) newValue;
            SettingsManager.enableGlyph(enabled);
            updateDependencies(enabled, isMusicEnabled);
            mHandler.post(() -> ServiceUtils.checkGlyphService());
            return true;
        }

        if (preferenceKey.equals(Constants.GLYPH_CALL_ENABLE)) {
            SettingsManager.setGlyphCallEnabled((Boolean) newValue);
            return true;
        }

        if (preferenceKey.equals(Constants.GLYPH_NOTIFS_ENABLE)) {
            SettingsManager.setGlyphNotifsEnabled((Boolean) newValue);
            return true;
        }

        if (preferenceKey.equals(Constants.GLYPH_SHAKE_TORCH_ENABLE)) {
            boolean enabled = (Boolean) newValue;
            if (enabled) {
                ShakeManager.startShakeService(getContext());
            } else {
                ShakeManager.stopShakeService(getContext());
            }
            mShakeSensitivityPreference.setEnabled(enabled);
            return true;
        }

        if (preferenceKey.equals(Constants.GLYPH_SHAKE_SENSITIVITY)) {
            if (mShakeTorchPreference.isChecked()) {
                ShakeManager.restartShakeService(getContext());
            }
            return true;
        }

        if (preferenceKey.equals(Constants.GLYPH_MUSIC_VISUALIZER_ENABLE)) {
            boolean musicValue = (Boolean) newValue;
            updateDependencies(isGlyphEnabled, musicValue);
            mHandler.post(() -> ServiceUtils.checkGlyphService());
            return true;
        }

        mHandler.post(() -> ServiceUtils.checkGlyphService());
        return true;
    }

    @Override
    public void onDestroy() {
        mSettingObserver.unregister(mContentResolver);
        super.onDestroy();
    }

    private class SettingObserver extends ContentObserver {
        public SettingObserver() {
            super(new Handler());
        }

        public void register(ContentResolver cr) {
            cr.registerContentObserver(Settings.Secure.getUriFor(
                Constants.GLYPH_CALL_ENABLE), false, this);
            cr.registerContentObserver(Settings.Secure.getUriFor(
                Constants.GLYPH_NOTIFS_ENABLE), false, this);
        }

        public void unregister(ContentResolver cr) {
            cr.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri.equals(Settings.Secure.getUriFor(Constants.GLYPH_CALL_ENABLE))) {
                mCallPreference.setChecked(SettingsManager.isGlyphCallEnabled());
            }
            if (uri.equals(Settings.Secure.getUriFor(Constants.GLYPH_NOTIFS_ENABLE))) {
                mNotifsPreference.setChecked(SettingsManager.isGlyphNotifsEnabled());
            }
        }
    }
}
