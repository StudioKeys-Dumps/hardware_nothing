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

package co.aospa.glyph.Settings;

import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.util.ArrayUtils;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import co.aospa.glyph.R;
import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Manager.AnimationManager;
import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Manager.StatusManager;
import co.aospa.glyph.Preference.GlyphAnimationPreference;
import co.aospa.glyph.Utils.ResourceUtils;

public class NotifsSettingsFragment extends SettingsBasePreferenceFragment
        implements OnPreferenceChangeListener {

    private static final String TAG = "GlyphNotifsSettingsFragment";

    private PreferenceScreen mScreen;
    private PreferenceCategory mCategory;
    private List<String> mEssentialApps = new ArrayList<>();
    private List<String> mEssentialAppsNames = new ArrayList<>();
    private PackageManager mPackageManager;
    private ListPreference mListPreference;
    private MultiSelectListPreference mMultiSelectListPreference;
    private GlyphAnimationPreference mGlyphAnimationPreference;
    private Handler mHandler = new Handler();
    private MediaPlayer mMediaPlayer;

    private volatile boolean mPreviewActive = false;

    private final ActivityResultLauncher<Intent> mSoundPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::onSoundPickerResult);

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.glyph_notifs_settings);

        mScreen = this.getPreferenceScreen();
        getActivity().setTitle(R.string.glyph_settings_notifs_toggle_title);

        mCategory = (PreferenceCategory) findPreference(Constants.GLYPH_NOTIFS_SUB_CATEGORY);

        mListPreference = (ListPreference) findPreference(Constants.GLYPH_NOTIFS_SUB_ANIMATIONS);
        mListPreference.setOnPreferenceChangeListener(this);
        populateAnimationList();

        mGlyphAnimationPreference = (GlyphAnimationPreference) findPreference(Constants.GLYPH_NOTIFS_SUB_PREVIEW);
        if (mGlyphAnimationPreference != null) {
            mGlyphAnimationPreference.setAnimationType("notification");
        }

        mPackageManager = getActivity().getPackageManager();
        List<ApplicationInfo> mApps = mPackageManager.getInstalledApplications(PackageManager.GET_GIDS);
        Collections.sort(mApps, new ApplicationInfo.DisplayNameComparator(mPackageManager));

        for (ApplicationInfo app : mApps) {
            if (mPackageManager.getLaunchIntentForPackage(app.packageName) != null
                    && !ArrayUtils.contains(Constants.APPS_TO_IGNORE, app.packageName)) {
                SwitchPreferenceCompat mSwitchPreference = new SwitchPreferenceCompat(mScreen.getContext());
                mSwitchPreference.setKey(app.packageName);
                mSwitchPreference.setTitle(" " + app.loadLabel(mPackageManager).toString());
                mSwitchPreference.setIcon(app.loadIcon(mPackageManager));
                mSwitchPreference.setDefaultValue(true);
                mSwitchPreference.setWidgetLayoutResource(R.layout.preference_widget_switch_compat);
                mSwitchPreference.setOnPreferenceChangeListener(this);
                mCategory.addPreference(mSwitchPreference);

                mEssentialApps.add(app.packageName);
                mEssentialAppsNames.add(app.loadLabel(mPackageManager).toString());
            }
        }

        mMultiSelectListPreference = (MultiSelectListPreference) findPreference(Constants.GLYPH_NOTIFS_SUB_ESSENTIAL);
        mMultiSelectListPreference.setOnPreferenceChangeListener(this);
        mMultiSelectListPreference.setEntries(mEssentialAppsNames.toArray(new CharSequence[0]));
        mMultiSelectListPreference.setEntryValues(mEssentialApps.toArray(new CharSequence[0]));
    }

    private void populateAnimationList() {
        String[] notifAnims = ResourceUtils.getNotificationAnimations();
        String customLabel = getString(R.string.glyph_settings_notifs_sub_custom_sound_title);

        CharSequence[] entries = new CharSequence[notifAnims.length + 1];
        CharSequence[] entryValues = new CharSequence[notifAnims.length + 1];
        for (int i = 0; i < notifAnims.length; i++) {
            entries[i] = notifAnims[i];
            entryValues[i] = notifAnims[i];
        }
        entries[notifAnims.length] = customLabel;
        entryValues[notifAnims.length] = Constants.GLYPH_NOTIFS_CUSTOM_VALUE;

        mListPreference.setEntries(entries);
        mListPreference.setEntryValues(entryValues);

        String saved = mListPreference.getValue();
        if (saved == null || (!ArrayUtils.contains(notifAnims, saved)
                && !saved.equals(Constants.GLYPH_NOTIFS_CUSTOM_VALUE))) {
            mListPreference.setValue(ResourceUtils.getString("glyph_settings_notifs_animations_default"));
        }

        if (Constants.GLYPH_NOTIFS_CUSTOM_VALUE.equals(mListPreference.getValue())) {
            mListPreference.setSummary(getString(R.string.glyph_settings_notifs_sub_custom_sound_set));
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mHandler.postDelayed(() -> {
            String current = mListPreference.getValue();
            if (Constants.GLYPH_NOTIFS_CUSTOM_VALUE.equals(current)) {
                String uriString = SettingsManager.getGlyphNotifsCustomSoundUri();
                if (uriString != null && !uriString.isEmpty()) {
                    playCustomAudioOnly(Uri.parse(uriString));
                }
            } else {
                playPreviewSynced(current, "notifications");
            }
        }, 300);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        stopPreview();
    }

    private void openSoundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        mSoundPickerLauncher.launch(intent);
    }

    private void onSoundPickerResult(ActivityResult result) {
        if (result.getData() == null) {
            String prev = SettingsManager.getGlyphNotifsAnimation();
            mListPreference.setValue(prev);
            return;
        }

        Uri uri = result.getData().getData();
        if (uri == null) {
            String prev = SettingsManager.getGlyphNotifsAnimation();
            mListPreference.setValue(prev);
            return;
        }

        SettingsManager.setGlyphNotifsCustomSoundUri(uri.toString());
        RingtoneManager.setActualDefaultRingtoneUri(
                getContext(), RingtoneManager.TYPE_NOTIFICATION, uri);
        Log.d(TAG, "Custom notification sound set to: " + uri);

        mListPreference.setValue(Constants.GLYPH_NOTIFS_CUSTOM_VALUE);
        mListPreference.setSummary(getString(R.string.glyph_settings_notifs_sub_custom_sound_set));

        playCustomAudioOnly(uri);
    }

    private void stopPreview() {
        mPreviewActive = false;
        mHandler.removeCallbacksAndMessages(null);

        if (mGlyphAnimationPreference != null) {
            mGlyphAnimationPreference.updateAnimation(false, "", 0, false);
        }

        if (StatusManager.isAnimationActive()) {
            StatusManager.setAnimationActive(false);
        }

        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping player: " + e.getMessage());
            } finally {
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
        }
    }

    private void playPreviewSynced(String name, String type) {
        stopPreview();

        if (!SettingsManager.isGlyphNotifsEnabled()) {
            Uri soundUri = getNotificationUri(name);
            if (soundUri != null) {
                playCustomAudioOnly(soundUri);
            }
            return;
        }

        mHandler.postDelayed(() -> {
            mPreviewActive = true;

            final boolean wasEssentialActive = StatusManager.isEssentialLedActive();
            if (wasEssentialActive) {
                StatusManager.setEssentialLedActive(false);
            }

            if (mGlyphAnimationPreference != null) {
                mGlyphAnimationPreference.updateAnimation(true, name, 0, true);
            }

            AnimationManager.playNotification(name, true);

            new Thread(() -> {
                try {
                    String path = "/product/media/audio/" + type + "/" + name + ".ogg";
                    mMediaPlayer = new MediaPlayer();
                    mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                    mMediaPlayer.setDataSource(path);
                    mMediaPlayer.prepare();
                    mMediaPlayer.start();

                    mMediaPlayer.setOnCompletionListener(mp -> {
                        if (wasEssentialActive) {
                            StatusManager.setEssentialLedActive(true);
                            AnimationManager.playEssential();
                        }
                        if (mGlyphAnimationPreference != null) {
                            mGlyphAnimationPreference.updateAnimation(false, name);
                        }
                        if (StatusManager.isAnimationActive()) {
                            StatusManager.setAnimationActive(false);
                        }
                        mPreviewActive = false;
                        mp.release();
                        mMediaPlayer = null;
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to play notification preview: " + e.getMessage());
                    if (wasEssentialActive) {
                        StatusManager.setEssentialLedActive(true);
                        AnimationManager.playEssential();
                    }
                    if (mGlyphAnimationPreference != null) {
                        mGlyphAnimationPreference.updateAnimation(false, name);
                    }
                    if (StatusManager.isAnimationActive()) {
                        StatusManager.setAnimationActive(false);
                    }
                    mPreviewActive = false;
                }
            }).start();
        }, 150);
    }

    private void playCustomAudioOnly(Uri uri) {
        stopPreview();

        mHandler.postDelayed(() -> {
            mPreviewActive = true;
            new Thread(() -> {
                try {
                    mMediaPlayer = new MediaPlayer();
                    mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    mMediaPlayer.setDataSource(getContext(), uri);
                    mMediaPlayer.prepare();
                    mMediaPlayer.start();

                    mMediaPlayer.setOnCompletionListener(mp -> {
                        mPreviewActive = false;
                        mp.release();
                        mMediaPlayer = null;
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to play custom notification sound: " + e.getMessage());
                    mPreviewActive = false;
                }
            }).start();
        }, 150);
    }

    private Uri getNotificationUri(String name) {
        Uri baseUri = MediaStore.Audio.Media.INTERNAL_CONTENT_URI;
        String selection = MediaStore.Audio.Media.DISPLAY_NAME + "=?";
        String[] selectionArgs = {name + ".ogg"};
        try (Cursor cursor = getContext().getContentResolver().query(
                baseUri,
                new String[]{MediaStore.Audio.Media._ID},
                selection,
                selectionArgs,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ContentUris.withAppendedId(baseUri, id);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get notification URI: " + e.getMessage());
        }
        return null;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String preferenceKey = preference.getKey();

        if (preferenceKey.equals(Constants.GLYPH_NOTIFS_SUB_ANIMATIONS)) {
            String value = newValue.toString();

            if (Constants.GLYPH_NOTIFS_CUSTOM_VALUE.equals(value)) {
                stopPreview();
                openSoundPicker();
                return false;
            }

            Uri soundUri = getNotificationUri(value);
            if (soundUri != null) {
                RingtoneManager.setActualDefaultRingtoneUri(getContext(),
                        RingtoneManager.TYPE_NOTIFICATION, soundUri);
                Log.d(TAG, "Notification sound set to: " + soundUri);
            } else {
                Log.e(TAG, "Notification URI not found for: " + value);
            }
            playPreviewSynced(value, "notifications");
        }

        return true;
    }
}
