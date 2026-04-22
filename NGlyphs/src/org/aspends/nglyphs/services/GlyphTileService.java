package org.aspends.nglyphs.services;

import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.GlyphManagerV2;

public class GlyphTileService extends TileService {
    /**
     * Called when the Quick Settings tile becomes visible to the user.
     * Updates the tile's visual state based on whether it is allowed to operate.
     */
    @Override
    public void onStartListening() {
        super.onStartListening();
        org.aspends.nglyphs.core.AnimationManager.init(this);
        org.aspends.nglyphs.core.GlyphManagerV2.getInstance().init(this);
        syncTile();
    }

    /**
     * Called when the user clicks the Quick Settings tile.
     * Toggles the glyphs on or off if the master setting allows it.
     */
    @Override
    public void onClick() {
        super.onClick();
        org.aspends.nglyphs.core.AnimationManager.init(this);
        org.aspends.nglyphs.core.GlyphManagerV2.getInstance().init(this);
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        if (!prefs.getBoolean("master_allow", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(()
                                    -> android.widget.Toast
                                            .makeText(this, "Enable Glyphs first!",
                                                    android.widget.Toast.LENGTH_SHORT)
                                            .show());
            syncTile();
            return;
        }

        boolean isLightOn = prefs.getBoolean("is_light_on", false);
        boolean newState = !isLightOn;

        prefs.edit().putBoolean("is_light_on", newState).apply();
        org.aspends.nglyphs.core.AnimationManager.refreshBackgroundState();
        syncTile();
    }

    /**
     * Synchronizes the tile's visual state with the is_light_on preference.
     * The Torch tile now respects the master allowance.
     */
    private void syncTile() {
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        boolean masterAllow = prefs.getBoolean("master_allow", false);
        boolean isLightOn = prefs.getBoolean("is_light_on", false);
        int brightness = prefs.getInt("torch_brightness", 2048);
        Tile tile = getQsTile();

        if (tile != null) {
            tile.setLabel(getString(R.string.tile_light_label));
            if (!masterAllow) {
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setSubtitle(getString(R.string.master_off_subtitle));
            } else if (isLightOn) {
                tile.setState(Tile.STATE_ACTIVE);
                int percent = Math.round((brightness / 4095f) * 100f);
                if (percent < 0) percent = 0;
                if (percent > 100) percent = 100;
                tile.setSubtitle(percent + "%");
            } else {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(null);
            }
            tile.updateTile();
        }
    }

    /**
     * Directly updates the brightness of all glyph LEDs.
     *
     * @param val The brightness level to apply.
     */
    private void updateHardware(int val) {
        for (GlyphManagerV2.Glyph g : GlyphManagerV2.Glyph.getBasicGlyphs()) {
            GlyphManagerV2.getInstance().setBrightness(g, val);
        }
        if (val == 0) {
            GlyphManagerV2.getInstance().setBrightness(GlyphManagerV2.Glyph.SINGLE_LED, 0);
        }
    }
}