/*
 * Copyright (C) 2023-2024 Paranoid Android
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
package co.aospa.glyph.Preference;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import co.aospa.glyph.R;
import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Utils.ResourceUtils;

public class GlyphAnimationPreference extends Preference {

    private final String TAG = "GlyphAnimationPreference";
    private final boolean DEBUG = true;

    private String animationType = "call";

    private Activity mActivity;

    private String animationName;
    private volatile boolean animationTerminated;
    private volatile boolean animationPaused = true;
    private volatile boolean animationOnce = false;
    private volatile boolean animationReset = false;
    private volatile int animationTimeBetween = 0;
    private String[] animationSlugs;
    private ImageView[] animationImgs;

    private Thread animationThread;

    private View mRootView;
    private final View.OnClickListener mClickListener = v -> performClick(v);

    public GlyphAnimationPreference(Context context) {
        super(context);
        setActivity(context);
        setLayout(R.layout.glyph_settings_preview);
    }
    public GlyphAnimationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setActivity(context);
        setLayout(R.layout.glyph_settings_preview);
    }
    public GlyphAnimationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setActivity(context);
        setLayout(R.layout.glyph_settings_preview);
    }
    public GlyphAnimationPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr);
        setActivity(context);
        setLayout(defStyleRes);
    }

    private void setLayout(int layoutResource) {
        setLayoutResource(R.layout.glyph_settings_preview_frame);
        mRootView = LayoutInflater.from(getContext())
                .inflate(layoutResource, null, false);
        setShouldDisableView(false);
    }

    private void setActivity(Context context) {
        if (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                mActivity = (Activity) context;
            } else {
                setActivity(((ContextWrapper) context).getBaseContext());
            }
        }
    }

    public void setAnimationType(String type) {
        animationType = type;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        holder.itemView.setOnClickListener(mClickListener);

        holder.itemView.setFocusable(isSelectable());
        holder.itemView.setClickable(isSelectable());

        FrameLayout layout = (FrameLayout) holder.itemView;
        layout.removeAllViews();

        if (mRootView.getParent() != null) {
            ((ViewGroup) mRootView.getParent()).removeView(mRootView);
        }
        layout.addView(mRootView);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        startAnimation();
    }

    @Override
    public void onDetached() {
        super.onDetached();
        stopAnimation();
    }

    private void startAnimation() {
        stopAnimation();

        animationSlugs = ResourceUtils.getStringArray("glyph_settings_animations_slugs");
        animationImgs = new ImageView[animationSlugs.length];
        for (int i = 0; i < animationSlugs.length; i++) {
            animationImgs[i] = (ImageView) mRootView.findViewById(
                ResourceUtils.getIdentifier("preview_device_" + animationSlugs[i], "id"));
        }

        animationTerminated = false;
        animationThread = createAnimationThread();
        animationThread.start();
    }

    private void stopAnimation() {
        animationTerminated = true;
        if (animationThread != null) {
            animationThread.interrupt();
        }
    }

    public void updateAnimation(boolean play, String name) {
        updateAnimation(play, name, 0, false);
    }

    public void updateAnimation(boolean play, String name, int time) {
        updateAnimation(play, name, time, false);
    }

    public void updateAnimation(boolean play, String name, int time, boolean once) {
        animationName = name;
        animationTimeBetween = time;
        animationPaused = !play;
        animationOnce = once;
        animationReset = true;

        if (animationThread != null) {
            animationThread.interrupt();
        }
    }

    private Thread createAnimationThread() {
        return new Thread() {
            @Override
            public void run() {
                while (!animationTerminated) {
                    while (animationPaused && !animationTerminated) {
                        try {
                            Thread.sleep(30);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }

                    if (animationTerminated) break;

                    animationReset = false;

                    if (DEBUG) Log.d(TAG, "Starting animation | name: " + animationName
                            + " | type: " + animationType);

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            resolveAnimation(animationName)))) {

                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (animationTerminated || animationPaused || animationReset) break;

                            long start = System.currentTimeMillis();
                            line = line.replace(" ", "");
                            line = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
                            String[] split = line.replace(" ", "").split(",");
                            if (Constants.getDevice().equals("phone1") && split.length == 5) {
                                if (mActivity != null) {
                                    mActivity.runOnUiThread(() -> {
                                        if (animationImgs == null) return;
                                        int minLen = Math.min(split.length, animationImgs.length);
                                        for (int i = 0; i < minLen; i++) {
                                            setGlyphsDrawable(animationImgs[i], Integer.parseInt(split[i]));
                                        }
                                    });
                                }
                            } else if (Constants.getDevice().equals("phone2") && split.length == 5) {
                                mActivity.runOnUiThread(() -> {
                                    setGlyphsDrawable(animationImgs[0], Integer.parseInt(split[0]));
                                    setGlyphsDrawable(animationImgs[1], Integer.parseInt(split[0]));
                                    setGlyphsDrawable(animationImgs[2], Integer.parseInt(split[1]));
                                    setGlyphsDrawable(animationImgs[3], Integer.parseInt(split[2]));
                                    setGlyphsDrawable(animationImgs[4], Integer.parseInt(split[2]));
                                    setGlyphsDrawable(animationImgs[5], Integer.parseInt(split[2]));
                                    setGlyphsDrawable(animationImgs[6], Integer.parseInt(split[2]));
                                    setGlyphsDrawable(animationImgs[7], Integer.parseInt(split[2]));
                                    setGlyphsDrawable(animationImgs[8], Integer.parseInt(split[2]));
                                    setGlyphsDrawable(animationImgs[9], Integer.parseInt(split[3]));
                                    setGlyphsDrawable(animationImgs[10], Integer.parseInt(split[4]));
                                });
                            } else if (Constants.getDevice().equals("phone2") && split.length == 33) {
                                mActivity.runOnUiThread(() -> {
                                    setGlyphsDrawable(animationImgs[0], Integer.parseInt(split[0]));
                                    setGlyphsDrawable(animationImgs[1], Integer.parseInt(split[1]));
                                    setGlyphsDrawable(animationImgs[2], Integer.parseInt(split[2]));
                                    setGlyphsDrawable(animationImgs[3], Integer.parseInt(split[3]));
                                    setGlyphsDrawable(animationImgs[4], Integer.parseInt(split[19]));
                                    setGlyphsDrawable(animationImgs[5], Integer.parseInt(split[20]));
                                    setGlyphsDrawable(animationImgs[6], Integer.parseInt(split[21]));
                                    setGlyphsDrawable(animationImgs[7], Integer.parseInt(split[22]));
                                    setGlyphsDrawable(animationImgs[8], Integer.parseInt(split[23]));
                                    setGlyphsDrawable(animationImgs[9], Integer.parseInt(split[25]));
                                    setGlyphsDrawable(animationImgs[10], Integer.parseInt(split[24]));
                                });
                            } else {
                                if (DEBUG) Log.d(TAG, "Line length mismatch: " + animationName);
                                updateAnimation(false, animationName);
                                break;
                            }

                            long elapsed = System.currentTimeMillis() - start;
                            long delay = 16L - elapsed;
                            if (delay > 0) {
                                try {
                                    Thread.sleep(delay);
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                        }

                        if (animationOnce) {
                            animationPaused = true;
                            animationOnce = false;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Animation error | name: " + animationName, e);
                    } finally {
                        if (mActivity != null && (animationPaused || animationReset)) {
                            mActivity.runOnUiThread(() -> {
                                if (animationImgs == null) return;
                                for (ImageView img : animationImgs) {
                                    setGlyphsDrawable(img, 0);
                                }
                            });
                        }
                    }
                }
            }

            private void setGlyphsDrawable(ImageView imageView, int brightness) {
                if (imageView == null) return;
                if (brightness <= 0) {
                    imageView.setAlpha(0.3f);
                } else {
                    float factor = (float) (0.4 + 0.6 * (brightness / (double) Constants.getMaxBrightness()));
                    imageView.setAlpha(factor);
                }
            }
        };
    }

    private java.io.InputStream resolveAnimation(String name) throws java.io.IOException {
        if ("notification".equals(animationType)) {
            return ResourceUtils.getNotificationAnimation(name);
        }
        return ResourceUtils.getCallAnimation(name);
    }
}
