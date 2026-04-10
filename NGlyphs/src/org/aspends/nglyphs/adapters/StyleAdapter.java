package org.aspends.nglyphs.adapters;

import android.content.Context;
import android.media.RingtoneManager;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.GlyphEffects;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.util.CustomRingtoneManager;
import org.aspends.nglyphs.util.RingtoneHelper;

public class StyleAdapter extends RecyclerView.Adapter<StyleAdapter.ViewHolder> {
    private final Context context;
    private final List<String> names;
    private final List<String> values;
    private final Vibrator vibrator;
    private int selectedPosition;
    private final int audioStreamType;
    private final SharedPreferencesProvider prefsProvider;
    private final String folderName;
    private final boolean isFlipMode;

    public interface SharedPreferencesProvider {
        int getBrightness();
    }

    public interface OnItemClickListener {
        void onItemClick(String styleName);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(StyleAdapter adapter, int position);
    }

    private OnItemClickListener clickListener;
    private OnSelectionChangedListener selectionChangedListener;
    private android.media.MediaPlayer currentPreviewPlayer;

    public StyleAdapter(Context context, List<String> names, List<String> values,
            int selectedPosition, Vibrator vibrator, int audioStreamType,
            SharedPreferencesProvider prefsProvider, String folderName, boolean isFlipMode) {
        this.context = context;
        this.names = names;
        this.values = values;
        this.selectedPosition = selectedPosition;
        this.vibrator = vibrator;
        this.audioStreamType = audioStreamType;
        this.prefsProvider = prefsProvider;
        this.folderName = folderName;
        this.isFlipMode = isFlipMode;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_style_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String name = names.get(position);
        String value = values.get(position);
        holder.textName.setText(name);
        holder.radioButton.setChecked(position == selectedPosition);

        // Show delete button only for custom ringtones (imported ones have 🎵 emoji in
        // name)
        boolean isImportedAudio = name.startsWith("🎵 ");
        boolean isImportedPattern = name.startsWith("🧩 ");
        boolean isCustom = isImportedAudio || isImportedPattern;

        holder.btnDelete.setVisibility(isCustom ? View.VISIBLE : View.GONE);

        boolean isSelected = position == selectedPosition;
        holder.card.setStrokeWidth(isSelected ? 3 : 1);
        int primaryColor = 0xFF000000;
        int outlineColor = 0x1F000000;
        int surfaceVariant = 0xFFEEEEEE;

        try {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(
                        androidx.appcompat.R.attr.colorPrimary, typedValue, true)) {
                primaryColor = typedValue.data;
            }
            if (context.getTheme().resolveAttribute(
                        com.google.android.material.R.attr.colorOutline, typedValue, true)) {
                outlineColor = typedValue.data;
            }
            if (context.getTheme().resolveAttribute(
                        com.google.android.material.R.attr.colorSurfaceVariant, typedValue, true)) {
                surfaceVariant = typedValue.data;
            }
        } catch (Exception e) {
        }

        holder.card.setStrokeColor(isSelected ? primaryColor : outlineColor);
        holder.card.setCardBackgroundColor(
                isSelected ? (primaryColor & 0x22FFFFFF) : surfaceVariant);
        holder.radioButton.setChecked(isSelected);

        holder.itemView.setOnClickListener(v -> {
            int oldPos = selectedPosition;
            selectedPosition = holder.getBindingAdapterPosition();
            notifyItemChanged(oldPos);
            notifyItemChanged(selectedPosition);

            // Haptic feedback
            if (vibrator != null) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(15, 80));
            }

            // Stop any existing preview
            if (currentPreviewPlayer != null) {
                try {
                    currentPreviewPlayer.stop();
                    currentPreviewPlayer.release();
                } catch (Exception ignored) {}
                currentPreviewPlayer = null;
            }

            // Preview must live under external cache (media_rw_data_file) so that
            // mediaserver can read it — internal cache carries per-app MLS
            // categories that block mediaserver access.
            if (!isCustom && folderName != null) {
                try {
                    String oggName = value + ".ogg";
                    String assetPath = folderName + "/" + oggName;
                    java.io.File cacheDir = context.getExternalCacheDir();
                    if (cacheDir == null) cacheDir = context.getCacheDir();
                    java.io.File cacheFile = new java.io.File(cacheDir, "preview_" + oggName);
                    if (!cacheFile.exists()) {
                        try (java.io.InputStream is = context.getAssets().open(assetPath);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile)) {
                            byte[] buf = new byte[4096];
                            int len;
                            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                        }
                    }
                    android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    mp.setDataSource(cacheFile.getAbsolutePath());
                    int usage = (audioStreamType == android.media.AudioManager.STREAM_RING)
                            ? android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                            : android.media.AudioAttributes.USAGE_NOTIFICATION;
                    mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    mp.setOnPreparedListener(preparedMp -> {
                        if (currentPreviewPlayer == preparedMp) {
                            try {
                                preparedMp.start();
                            } catch (Exception ignored) {}
                        } else {
                            try {
                                preparedMp.release();
                            } catch (Exception ignored) {}
                        }
                    });
                    mp.setOnCompletionListener(completedMp -> {
                        try {
                            completedMp.release();
                        } catch (Exception ignored) {}
                        if (currentPreviewPlayer == completedMp) {
                            currentPreviewPlayer = null;
                        }
                    });
                    mp.setOnErrorListener((errMp, what, extra) -> {
                        try {
                            errMp.release();
                        } catch (Exception ignored) {}
                        if (currentPreviewPlayer == errMp) {
                            currentPreviewPlayer = null;
                        }
                        return true;
                    });
                    currentPreviewPlayer = mp;
                    mp.prepareAsync();
                } catch (Exception e) {
                    // No paired OGG or playback error — silent preview
                }
            }

            if (clickListener != null) {
                clickListener.onItemClick(name);
            }

            if (selectionChangedListener != null) {
                selectionChangedListener.onSelectionChanged(this, selectedPosition);
            }

            // Preview the effect
            boolean isCustomStyle = value.endsWith(".ogg") || name.startsWith("🎵 ");
            if (isCustomStyle) {
                GlyphEffects.run(value, prefsProvider.getBrightness(), vibrator, context,
                        audioStreamType, true);
            } else if ("native_flip".equals(value)) {
                GlyphEffects.run("stock", prefsProvider.getBrightness(), vibrator, context,
                        audioStreamType, true);
            } else if (folderName != null) {
                GlyphEffects.play(
                        context, folderName, value, vibrator, prefsProvider.getBrightness());
            } else {
                GlyphEffects.run(value, prefsProvider.getBrightness(), vibrator, context,
                        audioStreamType, true);
            }
        });

        holder.radioButton.setOnClickListener(v -> holder.itemView.performClick());

        if (isCustom) {
            holder.btnDelete.setOnClickListener(v -> showDeleteConfirmation(position, value));
        }
    }

    private void showDeleteConfirmation(int position, String value) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.delete,
                        (dialog, which) -> {
                            if (CustomRingtoneManager.deleteRingtone(context, value)) {
                                names.remove(position);
                                values.remove(position);
                                if (selectedPosition == position) {
                                    selectedPosition = -1;
                                } else if (selectedPosition > position) {
                                    selectedPosition--;
                                }
                                notifyDataSetChanged();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public int getItemCount() {
        return names.size();
    }

    public void stopPreview() {
        if (currentPreviewPlayer != null) {
            try {
                currentPreviewPlayer.stop();
                currentPreviewPlayer.release();
            } catch (Exception ignored) {}
            currentPreviewPlayer = null;
        }
    }

    public int getSelectedPosition() { return selectedPosition; }

    public String getSelectedValue() {
        if (selectedPosition < 0 || selectedPosition >= values.size())
            return null;
        return values.get(selectedPosition);
    }

    public String getSelectedName() {
        if (selectedPosition < 0 || selectedPosition >= names.size())
            return "None";
        return names.get(selectedPosition);
    }

    public void clearSelection() {
        int oldPos = selectedPosition;
        selectedPosition = -1;
        if (oldPos != -1) {
            notifyItemChanged(oldPos);
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        RadioButton radioButton;
        TextView textName;
        ImageButton btnDelete;
        MaterialCardView card;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.cardStyleItem);
            radioButton = itemView.findViewById(R.id.radioStyle);
            textName = itemView.findViewById(R.id.textStyleName);
            btnDelete = itemView.findViewById(R.id.btnDeleteStyle);
        }
    }
}
