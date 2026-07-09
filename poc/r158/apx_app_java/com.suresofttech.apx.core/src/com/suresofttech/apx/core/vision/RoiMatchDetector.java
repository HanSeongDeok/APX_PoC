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

    private final Mat refCanon;
    private final OrbAligner aligner;
    private int[] roi;                 // {y1,y2,x1,x2}
    private Mat tmpl;                  // refCanon[roi]
    private double simThr;

    private Mat lockedM;
    private Integer lockInliers;
    private double[] lockAng;
    private boolean latched;
    private double[] pass;             // {passMs, gapMs, analysisMs}
    private Double tPrev;
    private final EvidenceCapture ev = new EvidenceCapture(3, 3);
    private Mat lastReturned;

    public RoiMatchDetector(String refPath, int[] roi, double simThr) {
        Cv.ensureLoaded();
        Mat ref = Cv.imreadKr(refPath);
        if (ref == null) {
            throw new IllegalArgumentException("기준영상 로드 실패: " + refPath);
        }
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
        Mat frame = Cv.toMat(bi);
        try {
            return processMat(frame);
        } finally {
            frame.release();
        }
    }

    private RoiMatchResult processMat(Mat frame) {
        if (lastReturned != null) {
            lastReturned.release();
            lastReturned = null;
        }
        double tArrive = now();
        double gapMs = (tPrev != null) ? (tArrive - tPrev) * 1000.0 : 0.0;
        tPrev = tArrive;

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
        double[] ns = roiMatch(canon);
        double ncc = ns[0];
        double ssim = ns[1];
        // 게이트는 NCC(zero-mean 정규화 상관). SSIM은 공통 배경 탓에 비-대상도 높게 나와
        // 분리력이 약함(실측: 기어 P가 ssim 0.72로 오검출) → 참고용으로만 표시.
        double psc = ncc;
        boolean hit = psc >= simThr;

        if (hit && !latched) {                     // 최초 hit = 전환 순간
            double analysisMs = (now() - tArrive) * 1000.0;
            pass = new double[] { gapMs + analysisMs, gapMs, analysisMs };
            latched = true;
            ev.trigger();
        } else {
            ev.stepAfter(canon.clone(), tArrive);
        }

        RoiMatchResult r = new RoiMatchResult("ok");
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

    /** canon[roi] vs 기준 크롭(tmpl) → {ncc, ssim}. 동일크기 → matchTemplate 1×1 + SSIM. */
    private double[] roiMatch(Mat canon) {
        Mat live = canon.submat(roi[0], roi[1], roi[2], roi[3]);
        Mat liveUse = live;
        if (live.rows() != tmpl.rows() || live.cols() != tmpl.cols()) {
            liveUse = new Mat();
            Imgproc.resize(live, liveUse, new Size(tmpl.cols(), tmpl.rows()));
        }
        Mat res = new Mat();
        Imgproc.matchTemplate(liveUse, tmpl, res, Imgproc.TM_CCOEFF_NORMED);
        double ncc = res.get(0, 0)[0];

        Mat g1 = new Mat();
        Mat g2 = new Mat();
        Imgproc.cvtColor(liveUse, g1, Imgproc.COLOR_BGR2GRAY);
        Imgproc.cvtColor(tmpl, g2, Imgproc.COLOR_BGR2GRAY);
        double ssim = Ssim.mssim(g1, g2);

        res.release();
        g1.release();
        g2.release();
        if (liveUse != live) {
            liveUse.release();
        }
        live.release();
        return new double[] { ncc, ssim };
    }

    private static double now() {
        return System.nanoTime() * 1e-9;
    }
}
