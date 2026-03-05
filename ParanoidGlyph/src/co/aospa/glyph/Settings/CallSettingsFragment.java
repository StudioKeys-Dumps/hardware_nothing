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

package co.aospa.glyph.Settings;

import android.content.ContentUris;
import android.content.Intent;
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
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceScreen;

import com.android.internal.util.ArrayUtils;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import co.aospa.glyph.R;
import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Manager.AnimationManager;
import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Preference.GlyphAnimationPreference;
import co.aospa.glyph.Utils.ResourceUtils;
import co.aospa.glyph.Utils.ServiceUtils;

public class CallSettingsFragment extends SettingsBasePreferenceFragment
        implements OnPreferenceChangeListener {

    private static final String TAG = "GlyphCallSettingsFragment";

    private PreferenceScreen mScreen;
    private ListPreference mListPreference;
    private GlyphAnimationPreference mGlyphAnimationPreference;
    private Handler mHandler = new Handler();
    private MediaPlayer mMediaPlayer;

    private volatile boolean mPreviewActive = false;

    private final ActivityResultLauncher<Intent> mRingtonePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::onRingtonePickerResult);

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.glyph_call_settings);
        mScreen = this.getPreferenceScreen();
        getActivity().setTitle(R.string.glyph_settings_call_toggle_title);

        MainSwitchPreference switchBar = findPreference(Constants.GLYPH_CALL_SUB_ENABLE);
        switchBar.setOnPreferenceChangeListener(this);
        switchBar.setChecked(SettingsManager.isGlyphCallEnabled());

        mListPreference = (ListPreference) findPreference(Constants.GLYPH_CALL_SUB_ANIMATIONS);
        mListPreference.setOnPreferenceChangeListener(this);
        populateAnimationList();

        mGlyphAnimationPreference = (GlyphAnimationPreference) findPreference(Constants.GLYPH_CALL_SUB_PREVIEW);
    }

    private void populateAnimationList() {
        String[] callAnims = ResourceUtils.getCallAnimations();
        String customLabel = getString(R.string.glyph_settings_call_sub_custom_ringtone_title);

        CharSequence[] entries = new CharSequence[callAnims.length + 1];
        CharSequence[] entryValues = new CharSequence[callAnims.length + 1];
        for (int i = 0; i < callAnims.length; i++) {
            entries[i] = callAnims[i];
            entryValues[i] = callAnims[i];
        }
        entries[callAnims.length] = customLabel;
        entryValues[callAnims.length] = Constants.GLYPH_CALL_CUSTOM_VALUE;

        mListPreference.setEntries(entries);
        mListPreference.setEntryValues(entryValues);

        String saved = mListPreference.getValue();
        if (saved == null || (!ArrayUtils.contains(callAnims, saved)
                && !saved.equals(Constants.GLYPH_CALL_CUSTOM_VALUE))) {
            mListPreference.setValue(ResourceUtils.getString("glyph_settings_call_animations_default"));
        }

        if (Constants.GLYPH_CALL_CUSTOM_VALUE.equals(mListPreference.getValue())) {
            mListPreference.setSummary(getString(R.string.glyph_settings_call_sub_custom_ringtone_set));
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mHandler.postDelayed(() -> {
            String current = mListPreference.getValue();
            if (Constants.GLYPH_CALL_CUSTOM_VALUE.equals(current)) {
                String uriString = SettingsManager.getGlyphCallCustomRingtoneUri();
                if (uriString != null && !uriString.isEmpty()) {
                    playCustomAudioOnly(Uri.parse(uriString));
                }
            } else {
                playPreviewSynced(SettingsManager.getGlyphCallAnimation(), "ringtones");
            }
        }, 300);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        stopPreview();
    }

    private void openRingtonePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        mRingtonePickerLauncher.launch(intent);
    }

    private void onRingtonePickerResult(ActivityResult result) {
        if (result.getData() == null) {
            String prev = SettingsManager.getGlyphCallAnimation();
            mListPreference.setValue(prev);
            return;
        }

        Uri uri = result.getData().getData();
        if (uri == null) {
            String prev = SettingsManager.getGlyphCallAnimation();
            mListPreference.setValue(prev);
            return;
        }

        SettingsManager.setGlyphCallCustomRingtoneUri(uri.toString());
        RingtoneManager.setActualDefaultRingtoneUri(
                getContext(), RingtoneManager.TYPE_RINGTONE, uri);
        Log.d(TAG, "Custom ringtone set to: " + uri);

        mListPreference.setValue(Constants.GLYPH_CALL_CUSTOM_VALUE);
        mListPreference.setSummary(getString(R.string.glyph_settings_call_sub_custom_ringtone_set));

        playCustomAudioOnly(uri);
    }

    private String resolveCustomRingtoneName() {
        String uriString = SettingsManager.getGlyphCallCustomRingtoneUri();
        if (uriString == null || uriString.isEmpty()) {
            return getString(R.string.glyph_settings_call_sub_custom_ringtone_title);
        }
        try (Cursor cursor = getContext().getContentResolver().query(
                Uri.parse(uriString),
                new String[]{MediaStore.Audio.Media.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && name.contains(".")) {
                    name = name.substring(0, name.lastIndexOf('.'));
                }
                return name;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve ringtone name: " + e.getMessage());
        }
        return getString(R.string.glyph_settings_call_sub_custom_ringtone_title);
    }


    private void stopPreview() {
        mPreviewActive = false;
        mHandler.removeCallbacksAndMessages(null);

        if (mGlyphAnimationPreference != null) {
            mGlyphAnimationPreference.updateAnimation(false, "", 0, false);
        }

        AnimationManager.stopCall();

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

        mHandler.postDelayed(() -> {
            mPreviewActive = true;

            if (mGlyphAnimationPreference != null) {
                mGlyphAnimationPreference.updateAnimation(true, name, 0, true);
            }

            AnimationManager.playCall(name);

            new Thread(() -> {
                try {
                    startMediaPlayer("/product/media/audio/" + type + "/" + name + ".ogg", null);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to play ringtone preview: " + e.getMessage());
                    finishPreview(name);
                }
            }).start();
        }, 150);
    }

    private void playPreviewSynced(Uri uri) {
        stopPreview();

        String animName = SettingsManager.getGlyphCallAnimation();

        mHandler.postDelayed(() -> {
            mPreviewActive = true;

            if (mGlyphAnimationPreference != null) {
                mGlyphAnimationPreference.updateAnimation(true, animName, 0, true);
            }

            AnimationManager.playCall(animName);

            new Thread(() -> {
                try {
                    startMediaPlayer(null, uri);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to play custom ringtone preview: " + e.getMessage());
                    finishPreview(animName);
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
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
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
                    Log.e(TAG, "Failed to play custom audio: " + e.getMessage());
                    mPreviewActive = false;
                }
            }).start();
        }, 150);
    }

    private void startMediaPlayer(String path, Uri uri) throws Exception {
        String animName = SettingsManager.getGlyphCallAnimation();
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        if (uri != null) {
            mMediaPlayer.setDataSource(getContext(), uri);
        } else {
            mMediaPlayer.setDataSource(path);
        }
        mMediaPlayer.prepare();
        mMediaPlayer.start();

        mMediaPlayer.setOnCompletionListener(mp -> {
            finishPreview(animName);
            mp.release();
            mMediaPlayer = null;
        });
    }

    private void finishPreview(String name) {
        if (mGlyphAnimationPreference != null) {
            mGlyphAnimationPreference.updateAnimation(false, name);
        }
        AnimationManager.stopCall();
        mPreviewActive = false;
    }

    private Uri getRingtoneUri(String name) {
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
            Log.e(TAG, "Failed to get ringtone URI: " + e.getMessage());
        }
        return null;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String preferenceKey = preference.getKey();

        if (preferenceKey.equals(Constants.GLYPH_CALL_SUB_ENABLE)) {
            boolean isChecked = (Boolean) newValue;
            SettingsManager.setGlyphCallEnabled(isChecked);
            ServiceUtils.checkGlyphService();
            mGlyphAnimationPreference.updateAnimation(isChecked, SettingsManager.getGlyphCallAnimation());
        }

        if (preferenceKey.equals(Constants.GLYPH_CALL_SUB_ANIMATIONS)) {
            String value = newValue.toString();

            if (Constants.GLYPH_CALL_CUSTOM_VALUE.equals(value)) {
                stopPreview();
                openRingtonePicker();
                return false;
            }

            Uri soundUri = getRingtoneUri(value);
            if (soundUri != null) {
                RingtoneManager.setActualDefaultRingtoneUri(getContext(),
                        RingtoneManager.TYPE_RINGTONE, soundUri);
                Log.d(TAG, "Ringtone set to: " + soundUri);
            } else {
                Log.e(TAG, "Ringtone URI not found for: " + value);
            }
            playPreviewSynced(value, "ringtones");
        }

        return true;
    }
}
