package org.aspends.nglyphs.util;

import android.util.Log;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serialized direct-write driver for the AW210XX LED sysfs nodes. NGlyphs is
 * platform-signed and runs in the system_app domain; sepolicy in the spacewar
 * device tree must permit write on sysfs_leds. There is no shell fallback —
 * if a write fails, sepolicy/DAC is the only honest fix.
 */
public class ShellUtils {
    private static final String TAG = "ShellUtils";

    private static final ExecutorService shellExecutor = Executors.newSingleThreadExecutor();

    public static void fastWrite(String path, String value) {
        shellExecutor.execute(() -> directWrite(path, value));
    }

    public static void fastWriteBatch(Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        shellExecutor.execute(() -> {
            for (Map.Entry<String, String> entry : updates.entrySet()) {
                directWrite(entry.getKey(), entry.getValue());
            }
        });
    }

    private static void directWrite(String path, String value) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(value.getBytes());
            fos.flush();
        } catch (Exception e) {
            Log.e(TAG, "sysfs write failed: " + path + " <- \"" + value + "\": " + e.getMessage());
        }
    }
}
