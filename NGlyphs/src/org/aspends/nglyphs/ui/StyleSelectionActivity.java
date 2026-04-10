package org.aspends.nglyphs.ui;

import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.adapters.StyleAdapter;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphEffects;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.util.CustomRingtoneManager;
import org.aspends.nglyphs.services.RingtoneSyncObserver;
import org.aspends.nglyphs.util.RingtoneHelper;

public class StyleSelectionActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private StyleAdapter adapterBuiltIn, adapterImported;
    private int titleRes;
    private String idxKey, valKey, folderName;
    private String[] values;
    private int audioStreamType;
    private boolean isFlipMode;

    private ImageView layerCamera, layerDiagonal, layerMain, layerLine, layerDot;
    private AnimationManager.GlyphFrameListener frameListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_style_selection);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.nestedScroll), (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    int applyButtonPadding =
                            (int) (180 * getResources().getDisplayMetrics().density);
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                            applyButtonPadding + insets.bottom);
                    return windowInsets;
                });

        prefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        vibrator = getSystemService(Vibrator.class);

        titleRes = getIntent().getIntExtra("titleRes", 0);
        idxKey = getIntent().getStringExtra("idxKey");
        valKey = getIntent().getStringExtra("valKey");
        folderName = getIntent().getStringExtra("folderName");
        values = getIntent().getStringArrayExtra("values");
        isFlipMode = valKey != null && valKey.contains("flip");

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView tvCurrentRingtone = findViewById(R.id.tvCurrentRingtone);

        if (titleRes != 0) {
            tvTitle.setText(titleRes);
        }

        setupExpansionLogic();
        setupRecyclerViews();

        layerCamera = findViewById(R.id.layerCamera);
        layerDiagonal = findViewById(R.id.layerDiagonal);
        layerMain = findViewById(R.id.layerMain);
        layerLine = findViewById(R.id.layerLine);
        layerDot = findViewById(R.id.layerDot);

        frameListener = frame -> {
            if (layerCamera == null || frame == null || frame.length < 15)
                return;
            this.runOnUiThread(() -> {
                layerCamera.setAlpha(Math.min(1.0f, (frame[0]) / 4095.0f));
                layerDiagonal.setAlpha(Math.min(1.0f, (frame[1]) / 4095.0f));

                int maxMain = Math.max(frame[2], Math.max(frame[3], Math.max(frame[4], frame[5])));
                layerMain.setAlpha(Math.min(1.0f, maxMain / 4095.0f));

                layerDot.setAlpha(Math.min(1.0f, frame[6] / 4095.0f));

                int maxLine = 0;
                for (int i = 7; i <= 14; i++) maxLine = Math.max(maxLine, frame[i]);
                layerLine.setAlpha(Math.min(1.0f, maxLine / 4095.0f));
            });
        };
        AnimationManager.setFrameListener(frameListener);

        // Initialize current pattern label
        updateCurrentPatternLabel(tvCurrentRingtone);

        MaterialButton btnApply = findViewById(R.id.btnApply);
        btnApply.setOnClickListener(v -> {
            StyleAdapter activeAdapter =
                    (adapterBuiltIn != null && adapterBuiltIn.getSelectedPosition() != -1)
                    ? adapterBuiltIn
                    : adapterImported;

            if (activeAdapter != null && activeAdapter.getSelectedPosition() >= 0) {
                int finalIdx = activeAdapter.getSelectedPosition();
                String finalSelection = activeAdapter.getSelectedValue();

                // If it's built-in, idx is direct. If it's imported, idx needs offset
                if (activeAdapter == adapterImported && values != null) {
                    finalIdx += values.length;

                    // Auto-apply system tone for imported patterns (Skip if in Flip mode)
                    if (finalSelection != null && !finalSelection.endsWith(".csv") && !isFlipMode) {
                        try {
                            File file = CustomRingtoneManager.getCustomRingtoneFile(
                                    this, finalSelection);
                            if (file != null && file.exists()) {
                                try (InputStream is = new FileInputStream(file)) {
                                    int type = (audioStreamType
                                                       == android.media.AudioManager.STREAM_RING)
                                            ? RingtoneManager.TYPE_RINGTONE
                                            : RingtoneManager.TYPE_NOTIFICATION;
                                    RingtoneSyncObserver.setSelfUpdating(type, true);
                                    RingtoneHelper.setSystemTone(this, is,
                                            CustomRingtoneManager.cleanStyleName(finalSelection),
                                            type);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                // Auto-apply system tone for built-in entries
                if (activeAdapter == adapterBuiltIn && !isFlipMode) {
                    try {
                        String oggName = finalSelection + ".ogg";
                        InputStream is = getAssets().open(folderName + "/" + oggName);
                        int type = (audioStreamType == android.media.AudioManager.STREAM_RING)
                                ? RingtoneManager.TYPE_RINGTONE
                                : RingtoneManager.TYPE_NOTIFICATION;
                        RingtoneSyncObserver.setSelfUpdating(type, true);
                        RingtoneHelper.setSystemTone(this, is,
                                CustomRingtoneManager.cleanStyleName(finalSelection), type);
                        is.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                prefs.edit().putInt(idxKey, finalIdx).putString(valKey, finalSelection).apply();
            }
            GlyphEffects.stopCustomRingtone();
            finish();
        });

        ImageView btnImportedInfo = findViewById(R.id.btnImportedInfo);
        if (btnImportedInfo != null) {
            btnImportedInfo.setOnClickListener(v -> {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.imported_ringtones)
                        .setMessage("Custom ringtones must be previewed directly on the back of the "
                                    + "device. Live UI preview is only supported for built-in "
                                    + "ringtones.")
                        .setPositiveButton("Got it", null)
                        .show();
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (frameListener != null) {
            AnimationManager.setFrameListener(null);
        }
    }

    private void updateCurrentPatternLabel(TextView tv) {
        if (tv == null)
            return;
        if (adapterBuiltIn != null && adapterBuiltIn.getSelectedPosition() != -1) {
            tv.setText(adapterBuiltIn.getSelectedName());
        } else if (adapterImported != null && adapterImported.getSelectedPosition() != -1) {
            tv.setText(adapterImported.getSelectedName());
        }
    }

    private void setupExpansionLogic() {
        android.view.View headerBuiltIn = findViewById(R.id.headerBuiltIn);
        android.view.View headerImported = findViewById(R.id.headerImported);
        android.view.View rvBuiltIn = findViewById(R.id.rvBuiltIn);
        android.view.View rvImported = findViewById(R.id.rvImported);
        android.view.View arrowBuiltIn = findViewById(R.id.arrowBuiltIn);
        android.view.View arrowImported = findViewById(R.id.arrowImported);

        headerBuiltIn.setOnClickListener(v -> toggleCard(rvBuiltIn, arrowBuiltIn));
        headerImported.setOnClickListener(v -> toggleCard(rvImported, arrowImported));
    }

    private void toggleCard(android.view.View list, android.view.View arrow) {
        boolean isVisible = list.getVisibility() == android.view.View.VISIBLE;
        android.transition.TransitionManager.beginDelayedTransition(
                (android.view.ViewGroup) list.getParent());
        list.setVisibility(isVisible ? android.view.View.GONE : android.view.View.VISIBLE);
        arrow.animate().rotation(isVisible ? -90 : 0).setDuration(300).start();
    }

    private void setupRecyclerViews() {
        if (idxKey == null)
            idxKey = "glyph_blink_style_idx";
        int currentIdx = prefs.getInt(idxKey, 0);

        List<String> builtInNames = new ArrayList<>();
        List<String> builtInValues = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                builtInNames.add(CustomRingtoneManager.cleanStyleName(v));
                builtInValues.add(v);
            }
        }

        boolean isCall = valKey != null && valKey.contains("call");
        List<File> customs = isCall ? CustomRingtoneManager.getImportedRingtones(this)
                                    : CustomRingtoneManager.getImportedFiles(this);
        List<String> importedNames = new ArrayList<>();
        List<String> importedValues = new ArrayList<>();
        for (File f : customs) {
            String prefix = f.getName().endsWith(".csv") ? "🧩 " : "🎵 ";
            importedNames.add(prefix + CustomRingtoneManager.cleanStyleName(f.getName()));
            importedValues.add(f.getName());
        }

        int builtInSel = -1;
        int importedSel = -1;

        if (values != null && currentIdx < values.length) {
            builtInSel = currentIdx;
        } else if (values != null) {
            importedSel = currentIdx - values.length;
        } else {
            importedSel = currentIdx;
        }

        // Safety check
        if (importedSel >= importedNames.size())
            importedSel = -1;

        RecyclerView rvBuilt = findViewById(R.id.rvBuiltIn);
        RecyclerView rvImp = findViewById(R.id.rvImported);
        rvBuilt.setLayoutManager(new LinearLayoutManager(this));
        rvImp.setLayoutManager(new LinearLayoutManager(this));

        isCall = valKey != null && valKey.contains("call");
        audioStreamType = isCall ? android.media.AudioManager.STREAM_RING
                                 : android.media.AudioManager.STREAM_NOTIFICATION;

        // Update section headers based on call vs notification
        TextView tvHeaderBuiltIn = findViewById(R.id.tvHeaderBuiltIn);
        TextView tvHeaderImported = findViewById(R.id.tvHeaderImported);
        if (isCall) {
            tvHeaderBuiltIn.setText(R.string.builtin_ringtones);
            tvHeaderImported.setText(R.string.imported_ringtones);
        } else {
            tvHeaderBuiltIn.setText(R.string.builtin_sounds);
            tvHeaderImported.setText(R.string.imported_sounds);
        }

        StyleAdapter.SharedPreferencesProvider provider = () -> prefs.getInt("brightness", 2048);

        adapterBuiltIn = new StyleAdapter(this, builtInNames, builtInValues, builtInSel, vibrator,
                audioStreamType, provider, folderName, isFlipMode);
        adapterImported = new StyleAdapter(this, importedNames, importedValues, importedSel,
                vibrator, audioStreamType, provider, folderName, isFlipMode);

        rvBuilt.setAdapter(adapterBuiltIn);
        rvImp.setAdapter(adapterImported);

        StyleAdapter.OnSelectionChangedListener listener = (adapter, pos) -> {
            if (adapter == adapterBuiltIn) {
                adapterImported.clearSelection();
            } else {
                adapterBuiltIn.clearSelection();
            }
            updateCurrentPatternLabel(findViewById(R.id.tvCurrentRingtone));
        };

        adapterBuiltIn.setOnSelectionChangedListener(listener);
        adapterImported.setOnSelectionChangedListener(listener);

        // Initial expansion: expand the one with selection
        if (builtInSel == -1) {
            findViewById(R.id.rvBuiltIn).setVisibility(android.view.View.GONE);
            findViewById(R.id.arrowBuiltIn).setRotation(-90);
            findViewById(R.id.arrowImported).setRotation(0);
        } else {
            findViewById(R.id.rvImported).setVisibility(android.view.View.GONE);
            findViewById(R.id.arrowImported).setRotation(-90);
            findViewById(R.id.arrowBuiltIn).setRotation(0);
        }
    }

    @Override
    protected void onPause() {
        if (adapterBuiltIn != null) adapterBuiltIn.stopPreview();
        if (adapterImported != null) adapterImported.stopPreview();
        GlyphEffects.stopCustomRingtone();
        super.onPause();
    }
}
