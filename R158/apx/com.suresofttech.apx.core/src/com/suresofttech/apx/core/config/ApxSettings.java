package com.suresofttech.apx.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.suresofttech.apx.core.vision.RoiMatchDetector;

/**
 * R158 공유 설정 — 설정 탭에서 측정 전 세팅하고, 비전/음향 탭이 재사용한다.
 * (웹캠은 {@link com.suresofttech.apx.core.vision.CameraService} 싱글턴으로 이미 공유)
 *
 * <p>Notion 설정 시나리오: 장치·기대음·기준이미지/ROI·비전·음향 임계.
 */
public final class ApxSettings {

    public interface Listener {
        void onSettingsChanged(ApxSettings s);
    }

    private static final ApxSettings INSTANCE = new ApxSettings();

    public static ApxSettings get() {
        return INSTANCE;
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    /** 선택 마이크 표시명 (없으면 null). */
    private String micName;
    /** 기대 경고음 WAV 경로 (없으면 null). */
    private String expectedWavPath;
    /** 기준 이미지 사용 ON/OFF. */
    private boolean useReferenceImage = true;
    /** 비전 기준 이미지 경로. */
    private String visionRefPath;
    /** 고정 ROI {y1,y2,x1,x2} canon 좌표. */
    private int[] roi;
    /** 비전 NCC 유사도 임계. */
    private double simThr = RoiMatchDetector.DEFAULT_SIM;
    /** 음향 주파수/파형 일치 임계. */
    private double audioFreqThr = 0.90;
    private double audioWaveThr = 0.90;

    private ApxSettings() {
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        for (Listener l : listeners) {
            l.onSettingsChanged(this);
        }
    }

    public String getMicName() {
        return micName;
    }

    public void setMicName(String micName) {
        if (eq(this.micName, micName)) {
            return;
        }
        this.micName = micName;
        fire();
    }

    public String getExpectedWavPath() {
        return expectedWavPath;
    }

    public void setExpectedWavPath(String path) {
        if (eq(this.expectedWavPath, path)) {
            return;
        }
        this.expectedWavPath = path;
        fire();
    }

    public boolean isUseReferenceImage() {
        return useReferenceImage;
    }

    public void setUseReferenceImage(boolean on) {
        if (this.useReferenceImage == on) {
            return;
        }
        this.useReferenceImage = on;
        fire();
    }

    public String getVisionRefPath() {
        return visionRefPath;
    }

    public void setVisionRefPath(String path) {
        if (eq(this.visionRefPath, path)) {
            return;
        }
        this.visionRefPath = path;
        fire();
    }

    /** ROI 복사본 반환 (없으면 null). */
    public int[] getRoi() {
        return roi == null ? null : roi.clone();
    }

    public void setRoi(int[] roi) {
        int[] next = (roi == null) ? null : roi.clone();
        if (roiEq(this.roi, next)) {
            return;
        }
        this.roi = next;
        fire();
    }

    public double getSimThr() {
        return simThr;
    }

    public void setSimThr(double simThr) {
        double v = clamp01(simThr);
        if (Math.abs(this.simThr - v) < 1e-9) {
            return;
        }
        this.simThr = v;
        fire();
    }

    public double getAudioFreqThr() {
        return audioFreqThr;
    }

    public double getAudioWaveThr() {
        return audioWaveThr;
    }

    public void setAudioThresholds(double freq, double wave) {
        double f = clamp01(freq);
        double w = clamp01(wave);
        if (Math.abs(this.audioFreqThr - f) < 1e-9 && Math.abs(this.audioWaveThr - w) < 1e-9) {
            return;
        }
        this.audioFreqThr = f;
        this.audioWaveThr = w;
        fire();
    }

    /** 알림 없이 일괄 반영 (UI 초기 로드용). */
    public void replaceQuiet(String micName, String expectedWav, boolean useRef, String refPath,
            int[] roi, double simThr, double freqThr, double waveThr) {
        this.micName = micName;
        this.expectedWavPath = expectedWav;
        this.useReferenceImage = useRef;
        this.visionRefPath = refPath;
        this.roi = (roi == null) ? null : roi.clone();
        this.simThr = clamp01(simThr);
        this.audioFreqThr = clamp01(freqThr);
        this.audioWaveThr = clamp01(waveThr);
    }

    public List<String> snapshotSummary() {
        List<String> lines = new ArrayList<String>();
        lines.add("mic=" + micName);
        lines.add("wav=" + expectedWavPath);
        lines.add("useRef=" + useReferenceImage);
        lines.add("ref=" + visionRefPath);
        lines.add("simThr=" + String.format("%.2f", simThr));
        lines.add("audioThr=" + String.format("%.2f/%.2f", audioFreqThr, audioWaveThr));
        return lines;
    }

    private static double clamp01(double v) {
        if (v < 0.05) {
            return 0.05;
        }
        if (v > 0.99) {
            return 0.99;
        }
        return v;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean roiEq(int[] a, int[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
