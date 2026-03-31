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

package co.aospa.glyph.Utils;
 
import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
 
import com.android.internal.util.ArrayUtils;
 
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
 
import co.aospa.glyph.Constants.Constants;
 
public final class ResourceUtils {
 
    private static final String TAG = "GlyphResourceUtils";
    
    private static AssetManager assetManager = null;
    private static String[] callAnimations = null;
    private static String[] notificationAnimations = null;
 
    private static Context getContext() {
        return Constants.CONTEXT;
    }
 
    private static AssetManager getAssetManager() {
        if (assetManager == null && getContext() != null) {
            assetManager = getContext().getAssets();
        }
        return assetManager;
    }
 
    public static int getIdentifier(String id, String type) {
        return getContext().getResources().getIdentifier(id, type, getContext().getPackageName());
    }

    public static Boolean getBoolean(String id) {
        return getContext().getResources().getBoolean(getIdentifier(id, "bool"));
    }
 
    public static String getString(String id) {
        try {
            return getContext().getResources().getString(getIdentifier(id, "string"));
        } catch (Exception e) {
            return "";
        }
    }
 
    public static int getInteger(String id) {
        return getContext().getResources().getInteger(getIdentifier(id, "integer"));
    }
 
    public static String[] getStringArray(String id) {
        return getContext().getResources().getStringArray(getIdentifier(id, "array"));
    }
 
    public static int[] getIntArray(String id) {
        return getContext().getResources().getIntArray(getIdentifier(id, "array"));
    }
 
    public static String[] getCallAnimations() {
        if (callAnimations == null) {
            callAnimations = listAssetsWithoutExtension("call");
        }
        return callAnimations;
    }
 
    public static String[] getNotificationAnimations() {
        if (notificationAnimations == null) {
            notificationAnimations = listAssetsWithoutExtension("notification");
        }
        return notificationAnimations;
    }

    private static String[] listAssetsWithoutExtension(String path) {
        try {
            String[] assets = getAssetManager().list(path);
            if (assets == null) return new String[0];
            
            List<String> cleaned = new ArrayList<>();
            for (String asset : assets) {
                if (asset.endsWith(".csv")) {
                    cleaned.add(asset.substring(0, asset.lastIndexOf('.')));
                }
            }
            return cleaned.toArray(new String[0]);
        } catch (IOException e) {
            Log.e(TAG, "Failed to list assets in " + path, e);
            return new String[0];
        }
    }
 
    public static InputStream getCallAnimation(String name) throws IOException {
        if (callAnimations == null) getCallAnimations();
        if (ArrayUtils.contains(callAnimations, name)) {
            return getAssetManager().open("call/" + name + ".csv");
        }
        String defaultAnim = getString("glyph_settings_call_animations_default");
        return getAssetManager().open("call/" + (defaultAnim.isEmpty() ? "default" : defaultAnim) + ".csv");
    }
 
    public static InputStream getNotificationAnimation(String name) throws IOException {
        if (notificationAnimations == null) getNotificationAnimations();
        if (ArrayUtils.contains(notificationAnimations, name)) {
            return getAssetManager().open("notification/" + name + ".csv");
        }
        String defaultAnim = getString("glyph_settings_notifs_animations_default");
        return getAssetManager().open("notification/" + (defaultAnim.isEmpty() ? "default" : defaultAnim) + ".csv");
    }
 
    public static InputStream getAnimation(String name) throws IOException {
        if (callAnimations == null) getCallAnimations();
        if (notificationAnimations == null) getNotificationAnimations();
        if (ArrayUtils.contains(callAnimations, name)) return getCallAnimation(name);
        if (ArrayUtils.contains(notificationAnimations, name)) return getNotificationAnimation(name);
        try {
            return getAssetManager().open(name + ".csv");
        } catch (IOException e) {
            return getCallAnimation(getString("glyph_settings_call_animations_default"));
        }
    }
}
