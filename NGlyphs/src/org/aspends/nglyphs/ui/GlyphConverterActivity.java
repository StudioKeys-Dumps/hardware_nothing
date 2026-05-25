package org.aspends.nglyphs.ui;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.util.OggGlyphEncoder;

public class GlyphConverterActivity extends AppCompatActivity {
    private MaterialButton btnSelectFile, btnConvert;
    private TextView textSelectedFile, textStatus;
    private RadioGroup rgZoneMapping;
    private ProgressBar progressLoading;
    private Uri selectedFileUri;
    private volatile boolean isConverting;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedFileUri = result.getData().getData();
                    if (selectedFileUri != null) {
                        String name = selectedFileUri.getLastPathSegment();
                        String mime = getContentResolver().getType(selectedFileUri);
                        boolean isOgg = (mime != null
                                                && (mime.contains("ogg")
                                                        || mime.contains("application/ogg")))
                                || (name != null && name.toLowerCase().endsWith(".ogg"));
                        if (isOgg) {
                            Toast.makeText(this,
                                         "This is already an .ogg pattern — use Import Pattern on "
                                         + "the homepage instead.",
                                         Toast.LENGTH_LONG)
                                    .show();
                            selectedFileUri = null;
                            return;
                        }
                        textSelectedFile.setVisibility(View.VISIBLE);
                        textSelectedFile.setText(name);
                        btnConvert.setEnabled(true);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_glyph_converter);

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.nestedScroll), (v, windowInsets) -> {
                    androidx.core.graphics.Insets insets = windowInsets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.systemBars());
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                            insets.bottom);
                    return windowInsets;
                });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnConvert = findViewById(R.id.btnConvert);
        textSelectedFile = findViewById(R.id.textSelectedFile);
        textStatus = findViewById(R.id.textStatus);
        rgZoneMapping = findViewById(R.id.rgZoneMapping);
        progressLoading = findViewById(R.id.progressLoading);

        btnSelectFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            filePickerLauncher.launch(intent);
        });

        btnConvert.setOnClickListener(v -> startConversion());
    }

    private void startConversion() {
        if (selectedFileUri == null || isConverting)
            return;
        isConverting = true;

        boolean isExtended = rgZoneMapping.getCheckedRadioButtonId() == R.id.rbZones15;
        btnConvert.setEnabled(false);
        progressLoading.setVisibility(View.VISIBLE);
        textStatus.setText("Converting...");

        new Thread(() -> {
            try {
                // Copy to temp file for processing
                File tempIn = new File(getCacheDir(), "input_convert" + System.currentTimeMillis());
                try (InputStream is = getContentResolver().openInputStream(selectedFileUri);
                        FileOutputStream os = new FileOutputStream(tempIn)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                }

                File outputDir = new File(getFilesDir(), "custom_ringtones");
                if (!outputDir.exists())
                    outputDir.mkdirs();

                String baseName = deriveBaseName(selectedFileUri);
                String outName = uniqueOggName(outputDir, baseName);
                File tempOut = new File(outputDir, outName);

                org.aspends.nglyphs.util.OggGlyphEncoder encoder =
                        new org.aspends.nglyphs.util.OggGlyphEncoder(this);
                encoder.convert(tempIn, tempOut, isExtended,
                        new org.aspends.nglyphs.util.OggGlyphEncoder.ProgressListener() {
                            @Override
                            public void onProgress(String status) {
                                runOnUiThread(() -> textStatus.setText(status));
                            }

                            @Override
                            public void onFinished(File result) {
                                boolean shared = exposeToMediaStore(result);
                                isConverting = false;
                                runOnUiThread(() -> {
                                    progressLoading.setVisibility(View.GONE);
                                    String status = shared
                                            ? "Saved. Pick from Settings → Sound, or from "
                                                    + "Glyph styles."
                                            : "Saved to Glyph styles only (MediaStore unavailable)";
                                    textStatus.setText(status);
                                    Toast.makeText(GlyphConverterActivity.this, status,
                                                 Toast.LENGTH_LONG)
                                            .show();
                                    btnConvert.setEnabled(true);
                                });
                                tempIn.delete();
                            }

                            @Override
                            public void onError(String error) {
                                isConverting = false;
                                runOnUiThread(() -> {
                                    progressLoading.setVisibility(View.GONE);
                                    textStatus.setText("Error: " + error);
                                    btnConvert.setEnabled(true);
                                });
                                tempIn.delete();
                            }
                        });

            } catch (Exception e) {
                isConverting = false;
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    textStatus.setText("System Error: " + e.getMessage());
                    btnConvert.setEnabled(true);
                });
            }
        }).start();
    }

    /**
     * Derives a sanitized base filename for the converted OGG from the source
     * URI. Tries MediaStore DISPLAY_NAME first (works for files picked from
     * the Files/Music app), then OpenableColumns.DISPLAY_NAME, then the URI's
     * last path segment. Falls back to a timestamp when nothing usable is
     * available. Output is restricted to {@code [a-zA-Z0-9_-]} to keep
     * MediaStore and the file system happy.
     */
    private String deriveBaseName(Uri uri) {
        String name = queryDisplayName(uri);
        if (name == null || name.isEmpty()) {
            String seg = uri.getLastPathSegment();
            if (seg != null && !seg.isEmpty()) {
                int slash = seg.lastIndexOf('/');
                name = (slash >= 0) ? seg.substring(slash + 1) : seg;
            }
        }
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        if (name == null || name.isEmpty()) {
            return "glyph_" + System.currentTimeMillis();
        }
        String sanitized = name.replaceAll("[^a-zA-Z0-9_-]", "_").replaceAll("_+", "_");
        if (sanitized.isEmpty() || sanitized.equals("_")) {
            return "glyph_" + System.currentTimeMillis();
        }
        return sanitized;
    }

    private String queryDisplayName(Uri uri) {
        String[] columns = {OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME};
        try (Cursor cursor = getContentResolver().query(uri, columns, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                for (String col : columns) {
                    int idx = cursor.getColumnIndex(col);
                    if (idx >= 0) {
                        String v = cursor.getString(idx);
                        if (v != null && !v.isEmpty()) return v;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String uniqueOggName(File dir, String baseName) {
        File candidate = new File(dir, baseName + ".ogg");
        if (!candidate.exists()) return baseName + ".ogg";
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, baseName + "_" + i + ".ogg");
            if (!candidate.exists()) return baseName + "_" + i + ".ogg";
        }
        return baseName + "_" + System.currentTimeMillis() + ".ogg";
    }

    private boolean exposeToMediaStore(File ogg) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, ogg.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_RINGTONES + "/Glyphs");
            values.put(MediaStore.Audio.Media.IS_RINGTONE, 1);
            values.put(MediaStore.Audio.Media.IS_NOTIFICATION, 1);
            values.put(MediaStore.Audio.Media.IS_ALARM, 0);
            values.put(MediaStore.Audio.Media.IS_MUSIC, 0);

            Uri uri = getContentResolver().insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;

            try (InputStream in = new FileInputStream(ogg);
                    OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) return false;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            Log.w("GlyphConverter", "exposeToMediaStore failed: " + e.getMessage(), e);
            return false;
        }
    }
}
