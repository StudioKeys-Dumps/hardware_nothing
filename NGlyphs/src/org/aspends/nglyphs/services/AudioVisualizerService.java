package org.aspends.nglyphs.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.IBinder;
import android.os.PowerManager;
import org.aspends.nglyphs.R;
import org.aspends.nglyphs.core.AnimationManager;
import org.aspends.nglyphs.core.GlyphManagerV2;
import org.aspends.nglyphs.util.SleepGuard;

public class AudioVisualizerService extends Service {
    private static final String TAG = "GlyphMusicVisualizerService";

    public static final int MODE_BEAT = 0;
    public static final int MODE_5ZONE = 1;
    public static final int MODE_15ZONE = 2;

    private Visualizer mVisualizer;
    private volatile boolean isRunning = true;
    private AudioManager mAudioManager;
    private SharedPreferences mPrefs;
    private int visualizerMode;

    private float[] smoothed;
    private static final float ATTACK = 0.6f;
    private static final float DECAY = 0.82f;

    private double[] mRunningSoundAvg;
    private double[] mCurrentAvgEnergyOneSec;
    private int mNumberOfSamplesInOneSec;
    private long mSystemTimeStartSec;
    private float[] beatDecay;
    private static final float BEAT_DECAY_RATE = 0.85f;

    private static final int LOW_FREQUENCY = 200;
    private static final int MID_LOW_FREQUENCY = 500;
    private static final int MID_FREQUENCY = 1500;
    private static final int MID_HIGH_FREQUENCY = 5000;
    private static final int HIGH_FREQUENCY = 10000;

    private static final int[] FREQ_BOUNDARIES_15 = {
            60, 120, 200, 300, 450, 630, 900, 1300, 1800, 2500, 3500, 5000, 7000, 10000, 16000};

    private static final int[] FREQ_BOUNDARIES_5 = {150, 500, 2000, 6000, 16000};

    @Override
    public void onCreate() {
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        mPrefs = getSharedPreferences(getString(R.string.pref_file), MODE_PRIVATE);
        visualizerMode = mPrefs.getInt("visualizer_mode", MODE_BEAT);

        if (visualizerMode == MODE_BEAT) {
            mRunningSoundAvg = new double[5];
            mCurrentAvgEnergyOneSec = new double[5];
            beatDecay = new float[5];
            for (int i = 0; i < 5; i++) mCurrentAvgEnergyOneSec[i] = -1;
            mSystemTimeStartSec = System.currentTimeMillis();
            smoothed = new float[5];
        } else {
            int zones = visualizerMode == MODE_15ZONE ? 15 : 5;
            smoothed = new float[zones];
        }

        try {
            mVisualizer = new Visualizer(0);
        } catch (Exception e) {
            stopSelf();
            return;
        }
        int captureSize = Visualizer.getCaptureSizeRange()[1];
        mVisualizer.setCaptureSize(captureSize);

        mVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
            @Override
            public void onWaveFormDataCapture(
                    Visualizer visualizer, byte[] waveform, int samplingRate) {}

            @Override
            public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                if (isRunning && isAudioActive() && !SleepGuard.isBlocked(mPrefs)
                        && !isScreenOffBlocked()) {
                    if (visualizerMode == MODE_BEAT) {
                        processBeatFFT(fft);
                    } else {
                        processFFT(fft, samplingRate);
                    }
                }
            }
        }, Visualizer.getMaxCaptureRate() / 2, false, true);

        mVisualizer.setEnabled(true);
    }

    private static volatile boolean sRingtonePreviewActive = false;

    /**
     * Signal that a ringtone preview is active so the visualizer responds
     * to non-music audio (STREAM_RING / STREAM_NOTIFICATION).
     */
    public static void setRingtonePreviewActive(boolean active) {
        sRingtonePreviewActive = active;
    }

    private boolean isAudioActive() {
        return mAudioManager.isMusicActive() || sRingtonePreviewActive;
    }

    private boolean isScreenOffBlocked() {
        if (!mPrefs.getBoolean("screen_off_only", false))
            return false;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private void processFFT(byte[] fft, int samplingRate) {
        int captureSize = mVisualizer.getCaptureSize();
        int halfSize = captureSize / 2;
        double hzPerBin = (samplingRate / 2000.0) / halfSize;

        double[] magnitudes = new double[halfSize];
        magnitudes[0] = Math.abs(fft[0]);
        for (int i = 1; i < halfSize; i++) {
            double re = fft[2 * i];
            double im = fft[2 * i + 1];
            magnitudes[i] = Math.sqrt(re * re + im * im);
        }

        int currentVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float volScale = maxVol > 0 ? (float) currentVol / maxVol : 0;

        int[] freqBoundaries =
                visualizerMode == MODE_15ZONE ? FREQ_BOUNDARIES_15 : FREQ_BOUNDARIES_5;
        int zones = freqBoundaries.length;
        int[] zoneIntensities = new int[zones];
        int prevBin = 0;

        for (int z = 0; z < zones; z++) {
            int endBin = Math.min((int) (freqBoundaries[z] / hzPerBin), halfSize);
            if (endBin <= prevBin)
                endBin = prevBin + 1;
            if (endBin > halfSize)
                endBin = halfSize;

            double sum = 0;
            int count = 0;
            for (int i = prevBin; i < endBin; i++) {
                sum += magnitudes[i];
                count++;
            }
            float avg = count > 0 ? (float) (sum / count) : 0;
            float normalized = Math.min(1.0f, avg / 40.0f);

            if (normalized > smoothed[z]) {
                smoothed[z] = smoothed[z] + (normalized - smoothed[z]) * ATTACK;
            } else {
                smoothed[z] *= DECAY;
            }

            zoneIntensities[z] = (int) (smoothed[z] * volScale * GlyphManagerV2.MAX_BRIGHTNESS);
            prevBin = endBin;
        }

        AnimationManager.showVisualizer(zoneIntensities, this);
    }

    private void processBeatFFT(byte[] fft) {
        double captureSize = mVisualizer.getCaptureSize() / 2.0;
        int sampleRate = mVisualizer.getSamplingRate() / 2000;

        int currentVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float volScale = maxVol > 0 ? (float) currentVol / maxVol : 0;

        int[] bandLimits = {LOW_FREQUENCY, MID_LOW_FREQUENCY, MID_FREQUENCY, MID_HIGH_FREQUENCY,
                HIGH_FREQUENCY};
        boolean[] beatDetected = new boolean[5];

        int k = 2;
        int energySum = Math.abs(fft[0]);
        double nextFrequency = (k / 2.0 * sampleRate) / captureSize;

        for (int band = 0; band < 5; band++) {
            if (band == 4) {
                energySum = Math.abs(fft[1]);
            } else if (band > 0) {
                energySum = 0;
            }

            while (nextFrequency < bandLimits[band] && k + 1 < fft.length) {
                energySum += Math.sqrt(fft[k] * fft[k] + fft[k + 1] * fft[k + 1]);
                k += 2;
                nextFrequency = (k / 2.0 * sampleRate) / captureSize;
            }

            double sampleAvg = energySum / Math.max(1, k / 2.0);
            mRunningSoundAvg[band] += sampleAvg;

            if (sampleAvg > mCurrentAvgEnergyOneSec[band] && mCurrentAvgEnergyOneSec[band] > 0) {
                beatDetected[band] = true;
            }
        }

        int[] zoneIntensities = new int[5];
        for (int i = 0; i < 5; i++) {
            if (beatDetected[i]) {
                beatDecay[i] = 1.0f;
            } else {
                beatDecay[i] *= BEAT_DECAY_RATE;
            }
            zoneIntensities[i] = (int) (beatDecay[i] * volScale * GlyphManagerV2.MAX_BRIGHTNESS);
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - mSystemTimeStartSec >= 1000) {
            int samples = Math.max(1, mNumberOfSamplesInOneSec);
            for (int i = 0; i < 5; i++) {
                mCurrentAvgEnergyOneSec[i] = mRunningSoundAvg[i] / samples;
                mRunningSoundAvg[i] = 0;
            }
            mNumberOfSamplesInOneSec = 0;
            mSystemTimeStartSec = currentTime;
        }
        mNumberOfSamplesInOneSec++;

        AnimationManager.showVisualizer(zoneIntensities, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (mVisualizer != null) {
            mVisualizer.setEnabled(false);
            mVisualizer.release();
        }
        AnimationManager.cancelAnimation();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
