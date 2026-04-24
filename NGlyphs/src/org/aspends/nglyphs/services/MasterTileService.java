package org.aspends.nglyphs.services;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphManagerV2;

public class MasterTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        syncTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        AnimationManager.init(this);
        GlyphManagerV2.getInstance().init(this);

        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);

        boolean currentState = prefs.getBoolean("master_allow", false);
        boolean newState = !currentState;

        prefs.edit().putBoolean("master_allow", newState).apply();

        if (newState) {
            startService(new Intent(this, FlipToGlyphService.class));
            if (prefs.getBoolean("battery_glyph_enabled", false))
                startService(new Intent(this, BatteryGlyphService.class));
            if (prefs.getBoolean("powershare_glyph_enabled", false))
                startService(new Intent(this, PowershareService.class));
            if (prefs.getBoolean("volume_bar_enabled", true))
                startService(new Intent(this, VolumeObserverService.class));
            if (prefs.getBoolean("auto_brightness_enabled", false))
                startService(new Intent(this, AutoBrightnessService.class));
            if (prefs.getBoolean("music_visualizer_enabled", false))
                startService(new Intent(this, AudioVisualizerService.class));
            if (prefs.getBoolean("assistant_animations_enabled", false))
                startService(new Intent(this, AssistantInteractionService.class));
        } else {
            stopService(new Intent(this, FlipToGlyphService.class));
            stopService(new Intent(this, BatteryGlyphService.class));
            stopService(new Intent(this, PowershareService.class));
            stopService(new Intent(this, VolumeObserverService.class));
            stopService(new Intent(this, AutoBrightnessService.class));
            stopService(new Intent(this, AudioVisualizerService.class));
            stopService(new Intent(this, AssistantInteractionService.class));

            AnimationManager.cancelAnimation();
            GlyphManagerV2.getInstance().toggleAll(false);
        }

        try {
            TileService.requestListeningState(
                    this, new ComponentName(this, GlyphTileService.class));
        } catch (NullPointerException | SecurityException ignored) {
        }
        syncTile();
    }

    private void syncTile() {
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        boolean isAllowed = prefs.getBoolean("master_allow", false);
        Tile tile = getQsTile();

        if (tile != null) {
            tile.setState(isAllowed ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.tile_master_label));
            tile.updateTile();
        }
    }
}
