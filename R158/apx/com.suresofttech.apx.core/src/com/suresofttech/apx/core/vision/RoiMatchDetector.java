package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;

import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * 고정 ROI 이미지 유사도 검출기 (기어 R 체결 / 클러스터 팝업 공용).
 * 파이썬 cluster.py ClusterDetector(roi_match) 이식 + 기어에도 적용(채도앵커 대체).
 *
 * <p>좌표계는 <b>기준 영상의 실제 픽셀 크기</b>(canonW×canonH)다. 640²로 강제하지 않는다.
 * 웹캠/기준 해상도가 바뀌면 그 크기가 곧 작업 공간이다.
 *
 * <p>기본: ORB로 프레임을 기준 좌표계로 정렬한 뒤, 고정 ROI만 NCC/SSIM 비교.
 * {@link #setAlignEnabled(false)} — 설정 탭 라이브 기준: ORB 없이 동일 해상도(다르면 ref 크기로 resize) 후 NCC.
 */
public final class RoiMatchDetector {

    private static final int LOCK_INLIERS = 25;
    public static final double DEFAULT_SIM = 0.70;

    private Mat refCanon;
    private int canonW;
    private int canonH;
    private OrbAligner aligner;
    private int[] roi;                 // {y1,y2,x1,x2} in ref pixel coords
    private Mat tmpl;                  // refCanon[roi]
    private double simThr;

    private Mat lockedM;
    private Integer lockInliers;
    private double[] lockAng;
    /** true면 ORB 정렬. false면 ref 크기 맞춤 후 NCC(설정 라이브). */
    private boolean alignEnabled = true;
    private boolean latched;
    private double[] pass;             // {passMs, gapMs, analysisMs}
    private long prevSig;
    private boolean haveSig;
    private int[] sigRow;
    private double tPrevFrame;
    private final EvidenceCapture ev = new EvidenceCapture(1, 1);
    private Mat lastReturned;
    private RoiMatchResult lastResult;
    private double lastSsim;

    private static final int GAP_N = 15;
    private final double[] gapRing = new double[GAP_N];
    private int gapCount;
    private int gapHead;

    /** 파일 경로로 기준영상 등록 (한글경로 대응). */
    public RoiMatchDetector(String refPath, int[] roi, double simThr) {
        Cv.ensureLoaded();
        Mat ref = Cv.imreadKr(refPath);
        if (ref == null) {
            throw new IllegalArgumentException("기준영상 로드 실패: " + refPath);
        }
        initRef(ref, roi, simThr);
    }

    /** 웹캠 프레임을 기준(기대) 이미지로 직접 등록 (파일 없이 화면 캡처). */
    public RoiMatchDetector(BufferedImage refImage, int[] roi, double simThr) {
        Cv.ensureLoaded();
        initRef(Cv.toMat(refImage), roi, simThr);
    }

    private void initRef(Mat ref, int[] roi, double simThr) {
        // 기준 영상 원본 해상도 유지 — 640² stretch 금지
        this.refCanon = ref;
        this.canonW = ref.cols();
        this.canonH = ref.rows();
        this.aligner = new OrbAligner(this.refCanon);
        this.simThr = simThr;
        setRoi(roi != null ? roi : defaultCenterRoi(canonW, canonH));
    }

    /** @deprecated 정사각 가정이 깨짐 — {@link #canonWidth()}/{@link #canonHeight()} 사용. */
    public int canonSize() {
        return Math.max(canonW, canonH);
    }

    public int canonWidth() {
        return canonW;
    }

    public int canonHeight() {
        return canonH;
    }

    public Mat refCanon() {
        return refCanon;
    }

    public int[] getRoi() {
        return roi == null ? null : roi.clone();
    }

    public double getSimThr() {
        return simThr;
    }

    public void setSimThr(double v) {
        simThr = Math.max(0.0, Math.min(1.0, v));
    }

    /** 판정 ROI 지정(드래그) — 기준 화면 픽셀 좌표. */
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
        if (tmpl != null) {
            tmpl.release();
        }
        Mat sub = refCanon.submat(y1, y2, x1, x2);
        tmpl = sub.clone();
        sub.release();
    }

    public void resetAlignment() {
        if (lockedM != null) {
            lockedM.release();
            lockedM = null;
        }
        lockInliers = null;
        lockAng = null;
    }

    /**
     * ORB 정렬 on/off. 설정 탭 라이브 기준은 false —
     * 특징점 부족 장면에서도 NCC가 aligning에 갇히지 않는다.
     */
    public void setAlignEnabled(boolean enabled) {
        this.alignEnabled = enabled;
        if (!enabled) {
            resetAlignment();
        }
    }

    public boolean isAlignEnabled() {
        return alignEnabled;
    }

    public void resetJudgment() {
        latched = false;
        pass = null;
        ev.reset();
    }

    public EvidenceCapture.Evidence getEvidence() {
        return ev.getEvidence();
    }

    /** 측정 중단 시 — post 미완이어도 pre/decide 확정. */
    public void flushEvidence() {
        ev.flush();
    }

    /** ui용 진입점 — BufferedImage(webcam)로 받아 내부에서 Mat 변환/해제. */
    public RoiMatchResult process(BufferedImage bi) {
        double tArrive = now();
        long sig = frameSigBi(bi);
        if (haveSig && sig == prevSig && lastResult != null) {
            return lastResult;
        }
        double rawGap = (haveSig && tPrevFrame > 0) ? (tArrive - tPrevFrame) * 1000.0 : 0.0;
        tPrevFrame = tArrive;
        prevSig = sig;
        haveSig = true;
        if (rawGap > 0) {
            pushGap(rawGap);
        }
        // D_gap = 1/fps (프레임 양자화). 폴링이 카메라보다 잦으면 median이 작아지므로
        // 실측 FPS 주기와 중앙값 중 타당한 쪽을 쓴다(미지이면 30fps→33.3ms).
        double gapMs = resolveFrameGapMs(medianGap());
        Mat frame = Cv.toMat(bi);
        try {
            lastResult = processMat(frame, tArrive, gapMs);
            return lastResult;
        } finally {
            frame.release();
        }
    }

    /**
     * 프레임 간격(ms). CameraService 실측 FPS → 1000/fps, 없으면 30fps 가정.
     * 실측 median이 fps 주기의 0.5~2.5배면 median 채택(가변 fps 대응).
     */
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

    private RoiMatchResult processMat(Mat frame, double tArrive, double gapMs) {
        if (lastReturned != null) {
            lastReturned.release();
            lastReturned = null;
        }

        Size refSize = new Size(canonW, canonH);
        Mat canon;
        if (!alignEnabled) {
            // 라이브: 해상도만 맞추고(같으면 clone) NCC — 640 강제 없음
            if (frame.cols() == canonW && frame.rows() == canonH) {
                canon = frame.clone();
            } else {
                canon = new Mat();
                Imgproc.resize(frame, canon, refSize);
            }
        } else {
            if (lockedM == null) {
                OrbAligner.Result hr = aligner.homography(frame);
                if (hr.m != null && hr.inliers >= LOCK_INLIERS) {
                    lockedM = hr.m;
                    lockInliers = hr.inliers;
                    lockAng = OrbAligner.angles(hr.m);
                } else if (hr.m != null) {
                    hr.m.release();
                }
            }
            if (lockedM == null) {
                Mat c = new Mat();
                Imgproc.resize(frame, c, refSize);
                lastReturned = c;
                RoiMatchResult r = new RoiMatchResult("aligning");
                r.canonImage = Cv.toBufferedImage(c);
                return r;
            }
            canon = new Mat();
            Imgproc.warpPerspective(frame, canon, lockedM, refSize);
        }
        lastReturned = canon;

        ev.push(canon.clone(), tArrive);
        double ncc = nccOf(canon);
        double psc = ncc;
        boolean hit = psc >= simThr;
        boolean latchNow = hit && !latched;

        if (latchNow) {
            double analysisMs = (now() - tArrive) * 1000.0;
            pass = new double[] { gapMs + analysisMs, gapMs, analysisMs };
            latched = true;
            ev.trigger();
        } else {
            ev.stepAfter(canon.clone(), tArrive);
        }
        double procMs = (now() - tArrive) * 1000.0;

        double ssim;
        if (latchNow) {
            ssim = ssimOf(canon);
            lastSsim = ssim;
        } else {
            ssim = lastSsim;
        }

        RoiMatchResult r = new RoiMatchResult("ok");
        r.procMs = procMs;
        r.canonImage = Cv.toBufferedImage(canon);
        r.roi = roi;
        r.ncc = ncc;
        r.ssim = ssim;
        r.psc = psc;
        r.simThr = simThr;
        r.hit = hit;
        r.frameGapMs = (pass != null) ? pass[1] : gapMs;
        r.analysisMs = (pass != null) ? pass[2] : null;
        r.passMs = (pass != null) ? pass[0] : null;
        r.lockInliers = lockInliers;
        r.lockAng = lockAng;
        return r;
    }

    private double nccOf(Mat canon) {
        Mat live = canon.submat(roi[0], roi[1], roi[2], roi[3]);
        Mat liveUse = live;
        if (live.rows() != tmpl.rows() || live.cols() != tmpl.cols()) {
            liveUse = new Mat();
            Imgproc.resize(live, liveUse, new Size(tmpl.cols(), tmpl.rows()));
        }
        Mat res = new Mat();
        Imgproc.matchTemplate(liveUse, tmpl, res, Imgproc.TM_CCOEFF_NORMED);
        double ncc = res.get(0, 0)[0];
        res.release();
        if (liveUse != live) {
            liveUse.release();
        }
        live.release();
        return ncc;
    }

    private double ssimOf(Mat canon) {
        Mat live = canon.submat(roi[0], roi[1], roi[2], roi[3]);
        Mat liveUse = live;
        if (live.rows() != tmpl.rows() || live.cols() != tmpl.cols()) {
            liveUse = new Mat();
            Imgproc.resize(live, liveUse, new Size(tmpl.cols(), tmpl.rows()));
        }
        Mat g1 = new Mat();
        Mat g2 = new Mat();
        Imgproc.cvtColor(liveUse, g1, Imgproc.COLOR_BGR2GRAY);
        Imgproc.cvtColor(tmpl, g2, Imgproc.COLOR_BGR2GRAY);
        double ssim = Ssim.mssim(g1, g2);
        g1.release();
        g2.release();
        if (liveUse != live) {
            liveUse.release();
        }
        live.release();
        return ssim;
    }

    /** 프레임 중앙 ~18.75%(구 640 기준 120px) 박스. */
    public static int[] defaultCenterRoi(int w, int h) {
        int rw = Math.max(4, (int) Math.round(w * 0.1875));
        int rh = Math.max(4, (int) Math.round(h * 0.1875));
        int x1 = Math.max(0, (w - rw) / 2);
        int y1 = Math.max(0, (h - rh) / 2);
        return new int[] { y1, y1 + rh, x1, x1 + rw };
    }

    private static double now() {
        return System.nanoTime() * 1e-9;
    }

    private long frameSigBi(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        if (w <= 0 || h <= 0) {
            return 0;
        }
        if (sigRow == null || sigRow.length < w) {
            sigRow = new int[w];
        }
        int rows = Math.min(h, 24);
        long sum = 0;
        for (int r = 0; r < rows; r++) {
            int y = (rows == 1) ? 0 : (int) ((long) r * (h - 1) / (rows - 1));
            bi.getRGB(0, y, w, 1, sigRow, 0, w);
            for (int x = 0; x < w; x++) {
                sum += (sigRow[x] & 0xffffff) * (long) (x + 1);
            }
        }
        return sum;
    }
}
