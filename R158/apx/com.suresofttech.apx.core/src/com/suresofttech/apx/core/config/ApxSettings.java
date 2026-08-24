package com.suresofttech.apx.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.suresofttech.apx.core.vision.RoiMatchDetector;
import com.suresofttech.apx.core.vision.VisionChannel;
import com.suresofttech.apx.core.vision.VisionJudges;
import com.suresofttech.apx.core.vision.YoloVisionJudge;

/**
 * R158 공유 설정 - 설정 탭에서 측정 전 세팅하고, 비전/음향/후방 탭이 재사용한다.
 * (웹캠은 {@link com.suresofttech.apx.core.vision.CameraService} - 클러스터/기어봉 2채널)
 *
 * <p>ROI는 <b>정규화 좌표</b>(프레임 대비 비율)로 저장한다. 웹캠 해상도가 바뀌어도
 * {@link #getRoi(int, int)}로 해당 픽셀 ROI를 얻는다.
 */
public final class ApxSettings {

    public interface Listener {
        void onSettingsChanged(ApxSettings s);
    }

    /** 후방 격자 크기 지정 방식. */
    public static final String REAR_MODE_PRESET = "preset";
    public static final String REAR_MODE_CUSTOM = "custom";

    /** 비전 판정 방식 - 기준영상 대조(NCC) / 학습모델(YOLO). */
    public static final String JUDGE_NCC = "NCC";
    public static final String JUDGE_YOLO = "YOLO";

    private static final ApxSettings INSTANCE = new ApxSettings();

    public static ApxSettings get() {
        return INSTANCE;
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    /** 선택 마이크 표시명 (없으면 null). */
    private String micName;
    /** 기대 경고음 WAV 경로 (없으면 null). */
    private String expectedWavPath;
    /** 기준 이미지 사용 ON/OFF. 기본 OFF - 옵션 컴포넌트(ReferenceImageBar) 없이도 ROI 지정이 디폴트. */
    private boolean useReferenceImage = false;
    /** 비전 기준 이미지 경로. */
    private String visionRefPath;
    /**
     *  ROI 초기 값
     */
    private double[] roiNorm = { 0.40625, 0.59375, 0.40625, 0.59375 };
    /** 비전 NCC 유사도 임계. */
    private double simThr = RoiMatchDetector.DEFAULT_SIM;

    // ── 비전 판정 방식 ────────────────────────────────────────────────
    /** {@link #JUDGE_NCC} 또는 {@link #JUDGE_YOLO}. 기본은 NCC. */
    private String visionJudge = JUDGE_NCC;
    /** YOLO 분류 모델(.onnx) 경로. 비어 있으면 YOLO 를 켜도 판정이 비활성된다. */
    private String yoloModelPath;
    /** 학습 시 imgsz 와 반드시 같아야 한다. */
    private int yoloInputSize = 128;
    /** PASS 로 볼 클래스 인덱스. 모델의 names 순서를 확인해 넣는다. */
    private int yoloHitClassId = 0;

    /** 기어봉 채널 - 클러스터와 같은 기본값, 설정에서 따로 둔다. */
    private boolean gearUseReferenceImage = false;
    private String gearVisionRefPath;
    private double[] gearRoiNorm = { 0.40625, 0.59375, 0.40625, 0.59375 };
    private double gearSimThr = RoiMatchDetector.DEFAULT_SIM;
    private String gearVisionJudge = JUDGE_NCC;
    private String gearYoloModelPath;
    private int gearYoloInputSize = 128;
    private int gearYoloHitClassId = 0;
    /**
     * 비전 최초 PASS 때 기대음을 스피커로 1회 재생한다.
     * 시뮬레이터 데모: 화면을 R로 바꾸면(비전 PASS) 경고음이 나와 마이크 채널이 따라간다.
     * 끄면 설정 탭 {@code ExpectedTonePlayBar}로 수동 재생한다.
     */
    private boolean autoPlayExpectedOnVisionPass = true;

    /** 음향 주파수/파형 일치 임계. */
    private double audioFreqThr = 0.90;
    private double audioWaveThr = 0.90;

    /** 후방 격자 열 / 행 (기본 4×6 프리셋). */
    private int rearCols = 4;
    private int rearRows = 6;
    /** {@link #REAR_MODE_PRESET} 또는 {@link #REAR_MODE_CUSTOM}. */
    private String rearSizeMode = REAR_MODE_PRESET;
    /** 후방 격자 범례 표시. */
    private boolean rearShowLegend = true;
    /** 후방 Select 포인트 - 각 원소 {col, row}. */
    private List<int[]> rearSelectedPoints = new ArrayList<int[]>();

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
        return isUseReferenceImage(VisionChannel.CLUSTER);
    }

    public boolean isUseReferenceImage(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearUseReferenceImage : useReferenceImage;
    }

    public void setUseReferenceImage(boolean on) {
        setUseReferenceImage(VisionChannel.CLUSTER, on);
    }

    public void setUseReferenceImage(VisionChannel ch, boolean on) {
        if (ch == VisionChannel.GEAR) {
            if (this.gearUseReferenceImage == on) {
                return;
            }
            this.gearUseReferenceImage = on;
        } else {
        if (this.useReferenceImage == on) {
            return;
        }
        this.useReferenceImage = on;
        }
        fire();
    }

    public String getVisionRefPath() {
        return getVisionRefPath(VisionChannel.CLUSTER);
    }

    public String getVisionRefPath(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearVisionRefPath : visionRefPath;
    }

    public void setVisionRefPath(String path) {
        setVisionRefPath(VisionChannel.CLUSTER, path);
    }

    public void setVisionRefPath(VisionChannel ch, String path) {
        if (ch == VisionChannel.GEAR) {
            if (eq(this.gearVisionRefPath, path)) {
                return;
            }
            this.gearVisionRefPath = path;
        } else {
        if (eq(this.visionRefPath, path)) {
            return;
        }
        this.visionRefPath = path;
        }
        fire();
    }

    /**
     * 지정 프레임 크기(픽셀)에 맞는 ROI {y1,y2,x1,x2}.
     * 웹캠/기준 해상도가 바뀌면 호출 측이 현재 w×h를 넘긴다.
     */
    public int[] getRoi(int frameW, int frameH) {
        return getRoi(VisionChannel.CLUSTER, frameW, frameH);
    }

    public int[] getRoi(VisionChannel ch, int frameW, int frameH) {
        double[] n = roiNormOf(ch);
        if (n == null || frameW <= 0 || frameH <= 0) {
            return null;
        }
        return normToPixels(n, frameW, frameH);
    }

    /**
     * @deprecated 해상도 없는 호출은 부정확 - {@link #getRoi(int, int)} 사용.
     * 호환용으로 정규화→가상 640×640 픽셀을 반환한다.
     */
    public int[] getRoi() {
        return getRoi(640, 640);
    }

    /** 현재 프레임 픽셀 ROI를 정규화해 저장. */
    public void setRoi(int[] roi, int frameW, int frameH) {
        setRoi(VisionChannel.CLUSTER, roi, frameW, frameH);
    }

    public void setRoi(VisionChannel ch, int[] roi, int frameW, int frameH) {
        if (roi == null || frameW <= 0 || frameH <= 0) {
            return;
        }
        double[] next = pixelsToNorm(roi, frameW, frameH);
        if (ch == VisionChannel.GEAR) {
            if (normEq(this.gearRoiNorm, next)) {
                return;
            }
            this.gearRoiNorm = next;
        } else {
        if (normEq(this.roiNorm, next)) {
            return;
        }
        this.roiNorm = next;
        }
        fire();
    }

    /**
     * @deprecated {@link #setRoi(int[], int, int)} 사용. 가상 640×640으로 해석.
     */
    public void setRoi(int[] roi) {
        setRoi(roi, 640, 640);
    }

    /** 정규화 ROI 복사(디버그 / 핑거프린트). */
    public double[] getRoiNorm() {
        return getRoiNorm(VisionChannel.CLUSTER);
    }

    public double[] getRoiNorm(VisionChannel ch) {
        double[] n = roiNormOf(ch);
        return n == null ? null : n.clone();
    }

    public double getSimThr() {
        return getSimThr(VisionChannel.CLUSTER);
    }

    public double getSimThr(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearSimThr : simThr;
    }

    public void setSimThr(double simThr) {
        setSimThr(VisionChannel.CLUSTER, simThr);
    }

    public void setSimThr(VisionChannel ch, double simThr) {
        double v = clamp01(simThr);
        if (ch == VisionChannel.GEAR) {
            if (Math.abs(this.gearSimThr - v) < 1e-9) {
                return;
            }
            this.gearSimThr = v;
        } else {
        if (Math.abs(this.simThr - v) < 1e-9) {
            return;
        }
        this.simThr = v;
        }
        fire();
    }

    // ── 비전 판정 방식 ────────────────────────────────────────────────

    public String getVisionJudge() {
        return getVisionJudge(VisionChannel.CLUSTER);
    }

    public String getVisionJudge(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearVisionJudge : visionJudge;
    }

    public boolean isYoloJudge() {
        return isYoloJudge(VisionChannel.CLUSTER);
    }

    public boolean isYoloJudge(VisionChannel ch) {
        return JUDGE_YOLO.equals(getVisionJudge(ch));
    }

    public String getYoloModelPath() {
        return getYoloModelPath(VisionChannel.CLUSTER);
    }

    public String getYoloModelPath(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearYoloModelPath : yoloModelPath;
    }

    public int getYoloInputSize() {
        return getYoloInputSize(VisionChannel.CLUSTER);
    }

    public int getYoloInputSize(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearYoloInputSize : yoloInputSize;
    }

    public int getYoloHitClassId() {
        return getYoloHitClassId(VisionChannel.CLUSTER);
    }

    public int getYoloHitClassId(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearYoloHitClassId : yoloHitClassId;
    }

    public void setVisionJudge(String judge, String modelPath, int inputSize, int hitClassId) {
        setVisionJudge(VisionChannel.CLUSTER, judge, modelPath, inputSize, hitClassId);
    }

    public void setVisionJudge(VisionChannel ch, String judge, String modelPath, int inputSize, int hitClassId) {
        String j = JUDGE_YOLO.equals(judge) ? JUDGE_YOLO : JUDGE_NCC;
        int size = (inputSize > 0) ? inputSize : getYoloInputSize(ch);
        int cls = (hitClassId >= 0) ? hitClassId : 0;
        if (ch == VisionChannel.GEAR) {
            if (j.equals(this.gearVisionJudge) && eq(this.gearYoloModelPath, modelPath)
                && this.gearYoloInputSize == size && this.gearYoloHitClassId == cls) {
                return;
            }
            this.gearVisionJudge = j;
            this.gearYoloModelPath = modelPath;
            this.gearYoloInputSize = size;
            this.gearYoloHitClassId = cls;
        } else {
        if (j.equals(this.visionJudge) && eq(this.yoloModelPath, modelPath)
                && this.yoloInputSize == size && this.yoloHitClassId == cls) {
            return;
        }
        this.visionJudge = j;
        this.yoloModelPath = modelPath;
        this.yoloInputSize = size;
        this.yoloHitClassId = cls;
        applyVisionJudge();
        }
        fire();
    }

    /** 현재 설정을 {@link VisionJudges} 에 반영. 앱 시작 시 한 번 불러 두면 좋다. */
    public void applyVisionJudge() {
        if (isYoloJudge()) {
            YoloVisionJudge.Cfg cfg = new YoloVisionJudge.Cfg();
            cfg.modelPath = yoloModelPath;
            cfg.inputSize = yoloInputSize;
            cfg.hitClassId = yoloHitClassId;
            cfg.thr = simThr;          // 임계는 설정 UI 의 임계 바 값을 따른다
            VisionJudges.useYolo(cfg);
        } else {
            VisionJudges.useNcc();
        }
    }

    public boolean isAutoPlayExpectedOnVisionPass() {
        return autoPlayExpectedOnVisionPass;
    }

    public void setAutoPlayExpectedOnVisionPass(boolean on) {
        if (this.autoPlayExpectedOnVisionPass == on) {
            return;
        }
        this.autoPlayExpectedOnVisionPass = on;
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

    public int getRearCols() {
        return rearCols;
    }

    public int getRearRows() {
        return rearRows;
    }

    public String getRearSizeMode() {
        return rearSizeMode;
    }

    /**
     * 후방 격자 크기 / 모드. 크기가 바뀌면 Select 포인트는 비운다.
     * @param mode {@link #REAR_MODE_PRESET} 또는 {@link #REAR_MODE_CUSTOM}
     */
    public void setRearGridSize(int cols, int rows, String mode) {
        int c = Math.max(1, Math.min(60, cols));
        int r = Math.max(1, Math.min(60, rows));
        String m = REAR_MODE_CUSTOM.equals(mode) ? REAR_MODE_CUSTOM : REAR_MODE_PRESET;
        boolean sizeChanged = (this.rearCols != c || this.rearRows != r);
        boolean modeChanged = !m.equals(this.rearSizeMode);
        if (!sizeChanged && !modeChanged) {
            return;
        }
        this.rearCols = c;
        this.rearRows = r;
        this.rearSizeMode = m;
        if (sizeChanged) {
            this.rearSelectedPoints = new ArrayList<int[]>();
        }
        fire();
    }

    public boolean isRearShowLegend() {
        return rearShowLegend;
    }

    public void setRearShowLegend(boolean on) {
        if (this.rearShowLegend == on) {
            return;
        }
        this.rearShowLegend = on;
        fire();
    }

    /** Select 포인트 복사본. 각 원소 {col, row}. */
    public List<int[]> getRearSelectedPoints() {
        List<int[]> out = new ArrayList<int[]>(rearSelectedPoints.size());
        for (int i = 0; i < rearSelectedPoints.size(); i++) {
            int[] p = rearSelectedPoints.get(i);
            if (p != null && p.length >= 2) {
                out.add(new int[] { p[0], p[1] });
            }
        }
        return out;
    }

    /** Select 포인트 교체 (범위는 호출 측 / 격자 모델이 보장). */
    public void setRearSelectedPoints(List<int[]> points) {
        List<int[]> next = new ArrayList<int[]>();
        if (points != null) {
            for (int i = 0; i < points.size(); i++) {
                int[] p = points.get(i);
                if (p != null && p.length >= 2) {
                    next.add(new int[] { p[0], p[1] });
                }
            }
        }
        if (pointsEq(this.rearSelectedPoints, next)) {
            return;
        }
        this.rearSelectedPoints = next;
        fire();
    }

    /** 알림 없이 일괄 반영 (UI 초기 로드용). roi는 픽셀, frameW/H와 함께. */
    public void replaceQuiet(String micName, String expectedWav, boolean useRef, String refPath,
            int[] roi, int frameW, int frameH, double simThr, double freqThr, double waveThr) {
        this.micName = micName;
        this.expectedWavPath = expectedWav;
        this.useReferenceImage = useRef;
        this.visionRefPath = refPath;
        if (roi != null && frameW > 0 && frameH > 0) {
            this.roiNorm = pixelsToNorm(roi, frameW, frameH);
        }
        this.simThr = clamp01(simThr);
        this.audioFreqThr = clamp01(freqThr);
        this.audioWaveThr = clamp01(waveThr);
    }

    /** @deprecated frame 크기 없이 ROI 넣으면 640×640으로 해석. */
    public void replaceQuiet(String micName, String expectedWav, boolean useRef, String refPath,
            int[] roi, double simThr, double freqThr, double waveThr) {
        replaceQuiet(micName, expectedWav, useRef, refPath, roi, 640, 640, simThr, freqThr, waveThr);
    }

    public List<String> snapshotSummary() {
        List<String> lines = new ArrayList<String>();
        lines.add("mic=" + micName);
        lines.add("wav=" + expectedWavPath);
        lines.add("useRef=" + useReferenceImage);
        lines.add("ref=" + visionRefPath);
        lines.add("simThr=" + String.format("%.2f", simThr));
        lines.add("judge=" + visionJudge
                + (isYoloJudge() ? " model=" + yoloModelPath
                        + " imgsz=" + yoloInputSize + " hitClassId=" + yoloHitClassId : ""));
        lines.add("audioThr=" + String.format("%.2f/%.2f", audioFreqThr, audioWaveThr));
        lines.add("rear=" + rearCols + "x" + rearRows + "/" + rearSizeMode
                + " legend=" + rearShowLegend + " pts=" + rearSelectedPoints.size());
        return lines;
    }

    private static int[] normToPixels(double[] n, int w, int h) {
        int y1 = clamp((int) Math.round(n[0] * h), 0, h - 1);
        int y2 = clamp((int) Math.round(n[1] * h), y1 + 1, h);
        int x1 = clamp((int) Math.round(n[2] * w), 0, w - 1);
        int x2 = clamp((int) Math.round(n[3] * w), x1 + 1, w);
        return new int[] { y1, y2, x1, x2 };
    }

    private static double[] pixelsToNorm(int[] r, int w, int h) {
        int y1 = clamp(r[0], 0, h - 1);
        int y2 = clamp(r[1], y1 + 1, h);
        int x1 = clamp(r[2], 0, w - 1);
        int x2 = clamp(r[3], x1 + 1, w);
        return new double[] {
                y1 / (double) h,
                y2 / (double) h,
                x1 / (double) w,
                x2 / (double) w
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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

    private double[] roiNormOf(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearRoiNorm : roiNorm;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean normEq(double[] a, double[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointsEq(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            int[] pa = a.get(i);
            int[] pb = b.get(i);
            if (pa[0] != pb[0] || pa[1] != pb[1]) {
                return false;
            }
        }
        return true;
    }
}
