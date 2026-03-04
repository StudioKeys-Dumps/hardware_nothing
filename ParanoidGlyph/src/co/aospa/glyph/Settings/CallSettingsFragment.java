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
import co.aospa.glyph.Manager.StatusManager;
import co.aospa.glyph.Preference.GlyphAnimationPreference;
import co.aospa.glyph.Utils.ResourceUtils;
import co.aospa.glyph.Utils.ServiceUtils;

public class CallSettingsFragment extends SettingsBasePreferenceFragment implements OnPreferenceChangeListener {

    private static final String TAG = "GlyphCallSettingsFragment";
    private PreferenceScreen mScreen;
    private ListPreference mListPreference;
    private GlyphAnimationPreference mGlyphAnimationPreference;
    private Handler mHandler = new Handler();
    private MediaPlayer mMediaPlayer;

    private volatile boolean mPreviewActive = false;

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
        mListPreference.setEntries(ResourceUtils.getCallAnimations());
        mListPreference.setEntryValues(ResourceUtils.getCallAnimations());
        if (!ArrayUtils.contains(ResourceUtils.getCallAnimations(), mListPreference.getValue())) {
            mListPreference.setValue(ResourceUtils.getString("glyph_settings_call_animations_default"));
        }

        mGlyphAnimationPreference = (GlyphAnimationPreference) findPreference(Constants.GLYPH_CALL_SUB_PREVIEW);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mHandler.postDelayed(() -> {
            playPreviewSynced(SettingsManager.getGlyphCallAnimation(), "ringtones");
        }, 300);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        stopPreview();
    }

    private void stopPreview() {
        mPreviewActive = false;
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
                    String path = "/product/media/audio/" + type + "/" + name + ".ogg";
                    mMediaPlayer = new MediaPlayer();
                    mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                    mMediaPlayer.setDataSource(path);
                    mMediaPlayer.prepare();
                    mMediaPlayer.start();

                    mMediaPlayer.setOnCompletionListener(mp -> {
                        if (mGlyphAnimationPreference != null) {
                            mGlyphAnimationPreference.updateAnimation(false, name);
                        }
                        AnimationManager.stopCall();
                        mPreviewActive = false;
                        mp.release();
                        mMediaPlayer = null;
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to play ringtone preview: " + e.getMessage());
                    if (mGlyphAnimationPreference != null) {
                        mGlyphAnimationPreference.updateAnimation(false, name);
                    }
                    AnimationManager.stopCall();
                    mPreviewActive = false;
                }
            }).start();
        }, 150);
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
            Uri soundUri = getRingtoneUri(newValue.toString());
            if (soundUri != null) {
                RingtoneManager.setActualDefaultRingtoneUri(getContext(),
                        RingtoneManager.TYPE_RINGTONE, soundUri);
                Log.d(TAG, "Ringtone set to: " + soundUri);
            } else {
                Log.e(TAG, "Ringtone URI not found for: " + newValue.toString());
            }
            playPreviewSynced(newValue.toString(), "ringtones");
        }
        return true;
    }
}
