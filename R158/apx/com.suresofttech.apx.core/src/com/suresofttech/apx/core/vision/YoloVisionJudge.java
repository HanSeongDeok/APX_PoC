package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;
import java.io.File;

import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;

/**
 * YOLO(분류) 기반 비전 판정기 - ONNX 모델을 <b>OpenCV DNN</b>으로 돌린다(Python 불필요).
 *
 * <p><b>NCC와의 차이</b>: NCC는 기준 영상과 픽셀을 비교해 "닮은 정도"를 계산하지만,
 * 이 판정기는 기준 영상 없이 학습된 모델이 "그 클래스일 <b>확률</b>"을 낸다.
 * 값의 의미는 다르지만 <b>0~1 점수 ≥ 임계 → PASS</b> 구조가 같아
 * {@link RoiMatchResult} 를 그대로 채운다({@code ncc}/{@code psc} = 확률).
 * 덕분에 기존 오버레이 / HUD / 임계 바를 고치지 않고 바꿔 낄 수 있다.
 *
 * <p>ORB 정렬은 쓰지 않는다 - 학습 모델은 위치가 조금 틀어져도 인식하므로
 * {@link #setAlignEnabled}는 무시된다(정렬 드리프트 문제 없음).
 *
 * <p>모델이 없거나 로드에 실패하면 판정하지 않고 {@code state="no-model"} 을 돌려준다.
 * (UI는 "ok"가 아니면 점수를 표시하지 않으므로 안전하게 비활성 상태가 된다.)
 */
public final class YoloVisionJudge implements VisionJudge {

    /** 학습 / 내보내기 설정과 맞춰야 하는 값들. */
    public static final class Cfg {
        /** ONNX 모델 경로(필수). */
        public String modelPath;
        /** 모델 입력 한 변(px) - 학습 시 imgsz 와 동일해야 한다. */
        public int inputSize = 224;
        /** PASS 로 볼 클래스 인덱스(예: 0=없음, 1=팝업있음 이면 1). */
        public int hitClassId = 1;
        /** PASS 확률 임계. */
        public double thr = 0.70;
    }

    private final Cfg cfg;
    private Net net;
    private String loadError;

    private int[] roi;
    private int canonW = 640;
    private int canonH = 480;
    private double thr;

    private boolean latched;
    private double[] pass;             // {passMs, gapMs, analysisMs}
    private long prevSig;
    private boolean haveSig;
    private double tPrevFrame;
    private RoiMatchResult lastResult;
    private final EvidenceCapture ev = new EvidenceCapture(1, 1);

    private static final int GAP_N = 15;
    private final double[] gapRing = new double[GAP_N];
    private int gapCount;
    private int gapHead;

    public YoloVisionJudge(Cfg cfg) {
        this.cfg = (cfg != null) ? cfg : new Cfg();
        this.thr = this.cfg.thr;
        Cv.ensureLoaded();
        loadModel();
    }

    private void loadModel() {
        String p = cfg.modelPath;
        if (p == null || p.isEmpty() || !new File(p).isFile()) {
            loadError = "모델 파일 없음: " + p;
            return;
        }
        try {
            net = Dnn.readNetFromONNX(p);
            if (net == null || net.empty()) {
                net = null;
                loadError = "ONNX 로드 실패(빈 네트워크): " + p;
            }
        } catch (Throwable t) {
            net = null;
            // OpenCV DNN이 지원하지 않는 opset/연산이면 여기로 온다. export 시 opset=12 권장.
            loadError = "ONNX 로드 실패: " + t.getMessage();
        }
    }

    /** 모델이 준비됐는지. false면 판정하지 않는다. */
    public boolean isAvailable() {
        return net != null;
    }

    /** 로드 실패 사유(정상이면 null). */
    public String getLoadError() {
        return loadError;
    }

    public String name() {
        return "YOLO";
    }

    public int canonWidth() {
        return canonW;
    }

    public int canonHeight() {
        return canonH;
    }

    public double getSimThr() {
        return thr;
    }

    public void setSimThr(double v) {
        thr = Math.max(0.0, Math.min(1.0, v));
    }

    public int[] getRoi() {
        return roi == null ? null : roi.clone();
    }

    public void setRoi(int[] r) {
        if (r == null || r.length < 4) {
            return;
        }
        int y1 = Math.max(0, Math.min(canonH - 1, r[0]));
        int y2 = Math.max(y1 + 1, Math.min(canonH, r[1]));
        int x1 = Math.max(0, Math.min(canonW - 1, r[2]));
        int x2 = Math.max(x1 + 1, Math.min(canonW, r[3]));
        if (y2 - y1 < 4 || x2 - x1 < 4) {
            return;
        }
        roi = new int[] { y1, y2, x1, x2 };
    }

    /** YOLO는 정렬이 필요 없다 - 계약 유지를 위한 no-op. */
    public void setAlignEnabled(boolean enabled) {
        // no-op
    }

    public boolean isAlignEnabled() {
        return false;
    }

    public void resetJudgment() {
        latched = false;
        pass = null;
        ev.reset();
    }

    public void resetAlignment() {
        // no-op
    }

    public EvidenceCapture.Evidence getEvidence() {
        return ev.getEvidence();
    }

    public void flushEvidence() {
        ev.flush();
    }

    public RoiMatchResult process(BufferedImage bi) {
        if (bi == null) {
            return new RoiMatchResult("no-frame");
        }
        if (net == null) {
            return new RoiMatchResult("no-model");
        }
        double tArrive = now();
        long sig = frameSig(bi);
        if (haveSig && sig == prevSig && lastResult != null) {
            return lastResult;   // 같은 프레임 - 재계산 안 함
        }
        double rawGap = (haveSig && tPrevFrame > 0) ? (tArrive - tPrevFrame) * 1000.0 : 0.0;
        tPrevFrame = tArrive;
        prevSig = sig;
        haveSig = true;
        if (rawGap > 0) {
            pushGap(rawGap);
        }
        double gapMs = resolveFrameGapMs(medianGap());

        Mat frame = Cv.toMat(bi);
        try {
            canonW = frame.cols();
            canonH = frame.rows();
            lastResult = judge(frame, tArrive, gapMs);
            return lastResult;
        } finally {
            frame.release();
        }
    }

    private RoiMatchResult judge(Mat frame, double tArrive, double gapMs) {
        Mat crop = null;
        Mat blob = null;
        Mat out = null;
        try {
            crop = cropRoi(frame);
            ev.push(crop.clone(), tArrive);

            blob = Dnn.blobFromImage(crop, 1.0 / 255.0,
                    new Size(cfg.inputSize, cfg.inputSize), new Scalar(0, 0, 0), true, false);
            net.setInput(blob);
            out = net.forward();

            double score = scoreOf(out, cfg.hitClassId);
            boolean hit = score >= thr;
            boolean latchNow = hit && !latched;
            if (latchNow) {
                double analysisMs = (now() - tArrive) * 1000.0;
                pass = new double[] { gapMs + analysisMs, gapMs, analysisMs };
                latched = true;
                ev.trigger();
            } else {
                ev.stepAfter(crop.clone(), tArrive);
            }

            RoiMatchResult r = new RoiMatchResult("ok");
            r.procMs = (now() - tArrive) * 1000.0;
            r.frameGapMs = gapMs;
            r.canonImage = Cv.toBufferedImage(frame);
            r.roi = roi;
            r.ncc = score;    // NCC 자리에 YOLO 확률 - 기존 UI/증거가 그대로 동작
            r.ssim = 0;
            r.psc = score;
            r.simThr = thr;
            r.hit = hit;
            if (pass != null) {
                r.passMs = Double.valueOf(pass[0]);
                r.analysisMs = Double.valueOf(pass[2]);
            }
            return r;
        } catch (Throwable t) {
            RoiMatchResult r = new RoiMatchResult("infer-failed");
            r.canonImage = Cv.toBufferedImage(frame);
            return r;
        } finally {
            if (crop != null && crop != frame) {
                crop.release();
            }
            if (blob != null) {
                blob.release();
            }
            if (out != null) {
                out.release();
            }
        }
    }

    /** ROI가 있으면 그 영역만, 없으면 프레임 전체. */
    private Mat cropRoi(Mat frame) {
        if (roi == null) {
            return frame;
        }
        int y1 = Math.max(0, Math.min(frame.rows() - 1, roi[0]));
        int y2 = Math.max(y1 + 1, Math.min(frame.rows(), roi[1]));
        int x1 = Math.max(0, Math.min(frame.cols() - 1, roi[2]));
        int x2 = Math.max(x1 + 1, Math.min(frame.cols(), roi[3]));
        Mat sub = frame.submat(y1, y2, x1, x2);
        Mat c = sub.clone();
        sub.release();
        return c;
    }

    /**
     * 출력 텐서 → 해당 클래스 확률.
     * Ultralytics 분류 모델은 보통 softmax까지 포함하지만, 아니면 여기서 적용한다.
     */
    private static double scoreOf(Mat out, int classId) {
        Mat flat = out.reshape(1, 1);
        int n = (int) flat.total();
        if (n <= 0) {
            return 0;
        }
        float[] v = new float[n];
        flat.get(0, 0, v);
        int idx = (classId >= 0 && classId < n) ? classId : (n - 1);

        double sum = 0;
        boolean negative = false;
        for (int i = 0; i < n; i++) {
            if (v[i] < 0) {
                negative = true;
            }
            sum += v[i];
        }
        if (!negative && sum > 0.99 && sum < 1.01) {
            return v[idx];   // 이미 확률(softmax 포함 모델)
        }
        double max = v[0];
        for (int i = 1; i < n; i++) {
            if (v[i] > max) {
                max = v[i];
            }
        }
        double denom = 0;
        for (int i = 0; i < n; i++) {
            denom += Math.exp(v[i] - max);
        }
        return denom > 0 ? Math.exp(v[idx] - max) / denom : 0;
    }

    // ── 프레임 간격 / 중복 프레임 판별 (NCC 판정기와 동일 규칙) ──────────────

    private static double resolveFrameGapMs(double measuredMedianMs) {
        double fps = 0;
        try {
            fps = CameraService.get().fps();
        } catch (Throwable ignored) {
            fps = 0;
        }
        double nominal = (fps > 1.0) ? (1000.0 / fps) : (1000.0 / 30.0);
        if (measuredMedianMs >= nominal * 0.5 && measuredMedianMs <= nominal * 2.5) {
            return measuredMedianMs;
        }
        return nominal;
    }

    private void pushGap(double g) {
        gapRing[(gapHead + gapCount) % GAP_N] = g;
        if (gapCount < GAP_N) {
            gapCount++;
        } else {
            gapHead = (gapHead + 1) % GAP_N;
        }
    }

    private double medianGap() {
        if (gapCount == 0) {
            return 0.0;
        }
        double[] tmp = new double[gapCount];
        for (int i = 0; i < gapCount; i++) {
            tmp[i] = gapRing[(gapHead + i) % GAP_N];
        }
        java.util.Arrays.sort(tmp);
        return tmp[gapCount / 2];
    }

    /** 가운데 가로줄 샘플링 해시 - 같은 프레임이 다시 들어왔는지 판별. */
    private static long frameSig(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        if (w <= 0 || h <= 0) {
            return 0;
        }
        int n = Math.min(32, w);
        int y = h / 2;
        long s = 1469598103934665603L;
        for (int i = 0; i < n; i++) {
            int x = (int) ((long) i * (w - 1) / Math.max(1, n - 1));
            s ^= bi.getRGB(x, y);
            s *= 1099511628211L;
        }
        return s;
    }

    private static double now() {
        return System.nanoTime() / 1e9;
    }
}
