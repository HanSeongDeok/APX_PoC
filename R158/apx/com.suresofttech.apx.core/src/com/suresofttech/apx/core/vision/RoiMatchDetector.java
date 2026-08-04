package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;

import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * 고정 ROI 이미지 유사도 검출기 (기어 R 체결 / 클러스터 팝업 공용).
 * 파이썬 cluster.py ClusterDetector(roi_match) 이식 + 기어에도 적용(채도앵커 대체).
 *
 * <p>ORB로 프레임을 기준영상 좌표계(canon 640)로 정렬한 뒤, 사용자가 드래그로 지정한
 * 고정 ROI만 기준 크롭과 NCC/SSIM 비교. max(ncc,ssim) >= 임계면 hit(R 체결/팝업 등장).
 * 검색·배율보정 없음(ORB로 같은 좌표계라 위치·크기 일치). 최초 hit 순간의 전환지연·증거 기록.
 */
public final class RoiMatchDetector {

    private static final int CANON = 640;
    private static final int LOCK_INLIERS = 25;
    public static final double DEFAULT_SIM = 0.70;

    private Mat refCanon;
    private OrbAligner aligner;
    private int[] roi;                 // {y1,y2,x1,x2}
    private Mat tmpl;                  // refCanon[roi]
    private double simThr;

    private Mat lockedM;
    private Integer lockInliers;
    private double[] lockAng;
    private boolean latched;
    private double[] pass;             // {passMs, gapMs, analysisMs}
    private long prevSig;              // 직전 프레임 내용 서명(새 프레임 판별용)
    private boolean haveSig;
    private int[] sigRow;              // frameSigBi 행 bulk 읽기 재사용 버퍼(할당·per-pixel getRGB 회피)
    private double tPrevFrame;         // 직전 "새 프레임" 도착 시각(초)
    private final EvidenceCapture ev = new EvidenceCapture(3, 3);
    private Mat lastReturned;
    private RoiMatchResult lastResult; // 같은 프레임 재폴링 시 반환할 캐시(중복 스킵)
    private double lastSsim;            // 마지막 SSIM(참고용) — PASS 확정 순간에만 갱신

    private static final int GAP_N = 15;              // 프레임주기 스무딩 창(중앙값) — USB 지터 완화
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
    public RoiMatchDetector(java.awt.image.BufferedImage refImage, int[] roi, double simThr) {
        Cv.ensureLoaded();
        initRef(Cv.toMat(refImage), roi, simThr);
    }

    private void initRef(Mat ref, int[] roi, double simThr) {
        this.refCanon = new Mat();
        Imgproc.resize(ref, this.refCanon, new Size(CANON, CANON));
        ref.release();
        this.aligner = new OrbAligner(this.refCanon);
        this.simThr = simThr;
        setRoi(roi != null ? roi
                : new int[] { CANON / 2 - 60, CANON / 2 + 60, CANON / 2 - 60, CANON / 2 + 60 });
    }

    public int canonSize() {
        return CANON;
    }

    public Mat refCanon() {
        return refCanon;
    }

    public int[] getRoi() {
        return roi;
    }

    public double getSimThr() {
        return simThr;
    }

    public void setSimThr(double v) {
        simThr = Math.max(0.0, Math.min(1.0, v));
    }

    /** 판정 ROI 지정(드래그) — 기준 화면에서 그 영역을 잘라 템플릿으로. */
    public void setRoi(int[] r) {
        int y1 = Math.max(0, r[0]);
        int y2 = Math.min(CANON, r[1]);
        int x1 = Math.max(0, r[2]);
        int x2 = Math.min(CANON, r[3]);
        if (y2 - y1 < 4 || x2 - x1 < 4) {
            return;                    // 너무 작은 영역 무시
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

    public void resetJudgment() {
        latched = false;
        pass = null;
        ev.reset();
    }

    public EvidenceCapture.Evidence getEvidence() {
        return ev.getEvidence();
    }

    /** ui용 진입점 — BufferedImage(webcam)로 받아 내부에서 Mat 변환/해제. ui는 OpenCV 무의존. */
    public RoiMatchResult process(BufferedImage bi) {
        double tArrive = now();
        long sig = frameSigBi(bi);
        // 같은 프레임을 다시 폴링한 경우 → 무거운 처리(warp/NCC) 스킵하고 캐시 반환(CPU 절약).
        if (haveSig && sig == prevSig && lastResult != null) {
            return lastResult;
        }
        // 새 프레임 → 프레임 주기(gapMs) 갱신. 폴링 주기가 아니라 실제 프레임 도착 간격을 잼.
        double rawGap = (haveSig && tPrevFrame > 0) ? (tArrive - tPrevFrame) * 1000.0 : 0.0;
        tPrevFrame = tArrive;
        prevSig = sig;
        haveSig = true;
        if (rawGap > 0) {
            pushGap(rawGap);          // 프레임 주기 링에 누적
        }
        double gapMs = medianGap();   // 중앙값 스무딩 — USB 지터로 개별 간격이 튀어도(65ms) 안정적(~34ms)
        Mat frame = Cv.toMat(bi);
        try {
            lastResult = processMat(frame, tArrive, gapMs);
            return lastResult;
        } finally {
            frame.release();
        }
    }

    private void pushGap(double g) {
        gapRing[(gapHead + gapCount) % GAP_N] = g;
        if (gapCount < GAP_N) {
            gapCount++;
        } else {
            gapHead = (gapHead + 1) % GAP_N;
        }
    }

    /** 최근 프레임 간격의 중앙값(스파이크에 강함). 비었으면 0. */
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
            Imgproc.resize(frame, c, new Size(CANON, CANON));
            lastReturned = c;
            RoiMatchResult r = new RoiMatchResult("aligning");
            r.canonImage = Cv.toBufferedImage(c);
            return r;
        }

        Mat canon = new Mat();
        Imgproc.warpPerspective(frame, canon, lockedM, new Size(CANON, CANON));
        lastReturned = canon;

        ev.push(canon.clone(), tArrive);
        // 게이트는 NCC(zero-mean 정규화 상관)만. SSIM은 참고용인데 ROI가 크면 비싸(Mat 20개+블러 6회)
        // 매 프레임 계산 시 판단속도가 튐 → 라이브는 NCC만, SSIM은 PASS 확정 순간에만 1회 계산.
        double ncc = nccOf(canon);
        double psc = ncc;
        boolean hit = psc >= simThr;
        boolean latchNow = hit && !latched;

        if (latchNow) {                            // 최초 hit = 전환 순간
            double analysisMs = (now() - tArrive) * 1000.0;
            pass = new double[] { gapMs + analysisMs, gapMs, analysisMs };
            latched = true;
            ev.trigger();
        } else {
            ev.stepAfter(canon.clone(), tArrive);
        }
        double procMs = (now() - tArrive) * 1000.0;   // 판단(처리) 속도 — NCC 기준(SSIM 제외)

        // SSIM(참고용)은 지연·procMs 측정 뒤, 확정 순간에만 계산 → 라이브 procMs는 NCC만으로 낮게 유지.
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

    /** canon[roi] vs 기준 크롭(tmpl) NCC (게이트, 매 프레임 호출). 동일크기 → matchTemplate 1×1. */
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

    /** canon[roi] vs 기준 크롭 SSIM (참고용, 확정 순간에만 호출). ROI 크면 비싸므로 매 프레임 금지. */
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

    private static double now() {
        return System.nanoTime() * 1e-9;
    }

    /**
     * BufferedImage 내용 서명(격자 샘플 합). 카메라 노이즈로 새 프레임은 합이 달라지고,
     * 같은 프레임 재반환은 합이 동일 → 새 프레임 판별(중복 스킵)에 사용. Mat 변환 전이라 중복 시 변환도 스킵.
     */
    private long frameSigBi(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        if (w <= 0 || h <= 0) {
            return 0;
        }
        if (sigRow == null || sigRow.length < w) {
            sigRow = new int[w];
        }
        int rows = Math.min(h, 24);          // 균등 24행
        long sum = 0;
        for (int r = 0; r < rows; r++) {
            int y = (rows == 1) ? 0 : (int) ((long) r * (h - 1) / (rows - 1));
            bi.getRGB(0, y, w, 1, sigRow, 0, w);   // 한 행 bulk 읽기(native, per-pixel getRGB보다 훨씬 빠름)
            for (int x = 0; x < w; x++) {          // 그 행 전체 픽셀 합 — 변별력↑(충돌↓), bulk라 빠름
                sum += (sigRow[x] & 0xffffff) * (long) (x + 1);   // 위치 가중 → 다른 프레임 충돌 방지
            }
        }
        return sum;
    }
}
