package org.aspends.nglyphs.util;

import android.content.Context;
import android.content.res.AssetManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lists built-in call/notification styles from paired asset folders. */
public final class AssetStyleUtils {
    private AssetStyleUtils() {}

    /**
     * Returns sorted unique base names from both {@code .ogg} and {@code .csv}
     * files under {@code assets/<folder>/}. Ogg-only community tones appear even
     * when no glyph pattern CSV is bundled alongside them.
     */
    public static String[] listStyleNames(Context context, String folder) {
        return listStyleNames(context.getAssets(), folder);
    }

    public static String[] listStyleNames(AssetManager assets, String folder) {
        try {
            String[] files = assets.list(folder);
            if (files == null || files.length == 0) {
                return new String[0];
            }

            Set<String> names = new HashSet<>();
            for (String file : files) {
                String lower = file.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".csv") || lower.endsWith(".ogg")) {
                    names.add(file.substring(0, file.length() - 4));
                }
            }

            List<String> sorted = new ArrayList<>(names);
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            return sorted.toArray(new String[0]);
        } catch (Exception ignored) {
            return new String[0];
        }
    }

    /** Zero-based index of {@code styleName} in {@link #listStyleNames(Context, String)}. */
    public static int findStyleIndex(Context context, String folder, String styleName) {
        if (styleName == null || styleName.isEmpty()) {
            return -1;
        }

        String[] names = listStyleNames(context, folder);
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(styleName)) {
                return i;
            }
        }
        return -1;
    }
}
