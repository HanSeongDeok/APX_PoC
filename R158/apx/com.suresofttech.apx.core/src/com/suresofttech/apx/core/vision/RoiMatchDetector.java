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
 * <p>판정 지표는 <b>NCC 하나</b>다({@code matchTemplate} / {@code TM_CCOEFF_NORMED}).
 * 예전에는 SSIM을 함께 계산했으나, 판정에 쓰이지도 표시되지도 않는 값이라 제거했다.
 * 법규 근거 자료로 쓰려면 판정 근거가 하나여야 하고 식으로 설명될 수 있어야 한다.
 *
 * <p><b>정렬(ORB) 경로는 사용하지 않는 것을 권장한다.</b> 기준 화면은 설정 탭에서
 * 라이브 캡처로 잡는 것으로 클라이언트와 협의되어, 정답과 촬영이 같은 카메라·같은 위치다.
 * 좌표를 맞출 이유가 없고, {@code aligning} 에 갇히는 실패 모드만 늘어난다.
 * 라이브 경로는 {@link #setAlignEnabled(boolean)} 에 {@code false} 를 주면 된다
 * (해상도만 맞춘 뒤 곧바로 NCC).
 */
public final class RoiMatchDetector implements VisionJudge {

    private static final int LOCK_INLIERS = 25;
    public static final double DEFAULT_SIM = 0.70;

    /** 판정기 이름 - 증거 / HUD 표기용. */
    public String name() {
        return "NCC";
    }

    private Mat refCanon;
    private int canonW;
    private int canonH;
    private OrbAligner aligner;
    private int[] roi;                 // {y1,y2,x1,x2} in ref pixel coords
    private Mat tmpl;                  // refCanon[roi]
    /** 직접 ROI 경로의 재사용 버퍼 - 프레임마다 대형 배열/Mat을 만들지 않는다. */
    private int[] roiArgb;
    private byte[] roiBgr;
    private Mat roiMat;
    /** matchTemplate 결과는 같은 크기(1x1)이므로 재사용한다. */
    private Mat nccResult;
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
    /** process()에서 만든 입력 Mat을 증거 링이 인수했는지. */
    private boolean inputTransferredToEvidence;
    private VisionChannel captureChannel = VisionChannel.CLUSTER;

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
        // 기준 영상 원본 해상도 유지 - 640² stretch 금지
        this.refCanon = ref;
        this.canonW = ref.cols();
        this.canonH = ref.rows();
        this.aligner = new OrbAligner(this.refCanon);
        this.simThr = simThr;
        setRoi(roi != null ? roi : defaultCenterRoi(canonW, canonH));
    }

    /** 간격은 이 채널 {@link CameraService} grab 주기를 우선한다. */
    public void setCaptureChannel(VisionChannel ch) {
        if (ch != null) {
            this.captureChannel = ch;
        }
    }

    /** @deprecated 정사각 가정이 깨짐 - {@link #canonWidth()}/{@link #canonHeight()} 사용. */
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

    /** 판정 ROI 지정(드래그) - 기준 화면 픽셀 좌표. */
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
        roiArgb = null;
        roiBgr = null;
        if (roiMat != null) {
            roiMat.release();
            roiMat = null;
        }
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
     * ORB 정렬 on/off. 설정 탭 라이브 기준은 false -
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

    /** 측정 중단 시 - post 미완이어도 pre/decide 확정. */
    public void flushEvidence() {
        ev.flush();
    }

    /** ui용 진입점 - BufferedImage(webcam)로 받아 내부에서 Mat 변환/해제. */
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
        double gapMs = resolveFrameGapMs(medianGap());
        if (!alignEnabled && bi.getWidth() == canonW && bi.getHeight() == canonH) {
            return processDirectRoi(bi, tArrive, gapMs);
        }
        Mat frame = Cv.toMat(bi);
        inputTransferredToEvidence = false;
        try {
            lastResult = processMat(frame, tArrive, gapMs);
            return lastResult;
        } finally {
            if (!inputTransferredToEvidence) {
                frame.release();
            }
        }
    }

    /**
     * 정렬·리사이즈가 필요 없는 일반 웹캠 경로. 전체 FHD Mat 대신 NCC ROI만 변환한다.
     * 전체 프레임은 BufferedImage 참조로 증거 링에 보관하고 실제 저장 시에만 Mat으로 만든다.
     */
    private RoiMatchResult processDirectRoi(BufferedImage bi, double tArrive, double gapMs) {
        if (lastReturned != null) {
            lastReturned.release();
            lastReturned = null;
        }
        ev.push(bi, tArrive);
        int rw = roi[3] - roi[2];
        int rh = roi[1] - roi[0];
        int pixels = rw * rh;
        if (roiArgb == null || roiArgb.length != pixels) {
            roiArgb = new int[pixels];
            roiBgr = new byte[pixels * 3];
            if (roiMat != null) {
                roiMat.release();
            }
            roiMat = new Mat(rh, rw, org.opencv.core.CvType.CV_8UC3);
        }
        bi.getRGB(roi[2], roi[0], rw, rh, roiArgb, 0, rw);
        for (int i = 0, j = 0; i < pixels; i++) {
            int p = roiArgb[i];
            roiBgr[j++] = (byte) (p & 0xFF);
            roiBgr[j++] = (byte) ((p >> 8) & 0xFF);
            roiBgr[j++] = (byte) ((p >> 16) & 0xFF);
        }
        roiMat.put(0, 0, roiBgr);
        double ncc = nccOfLive(roiMat);
        boolean hit = ncc >= simThr;
        boolean latchNow = hit && !latched;
        if (latchNow) {
            double analysisMs = (now() - tArrive) * 1000.0;
            pass = new double[] { gapMs + analysisMs, gapMs, analysisMs };
            latched = true;
            ev.trigger();
        } else if (ev.needsPostFrame()) {
            ev.stepAfter(bi, tArrive);
        }
        double procMs = (now() - tArrive) * 1000.0;

        RoiMatchResult r = new RoiMatchResult("ok");
        r.procMs = procMs;
        r.canonImage = bi;
        r.roi = roi;
        r.ncc = ncc;
        r.psc = ncc;
        r.simThr = simThr;
        r.hit = hit;
        r.frameGapMs = (pass != null) ? pass[1] : gapMs;
        r.analysisMs = (pass != null) ? pass[2] : null;
        r.passMs = (pass != null) ? pass[0] : null;
        r.lockInliers = lockInliers;
        r.lockAng = lockAng;
        lastResult = r;
        return r;
    }

    /**
     * 프레임 간격(ms) = 캡처 {@code read()} 주기 중앙값.
     * 판정기 쪽 실측은 같은 픽셀 서명을 건너뛰어 표본이 비는 경우가 있어
     * 그때 30fps(33.3ms)로 위장하던 폴백은 쓰지 않는다.
     */
    private double resolveFrameGapMs(double measuredMedianMs) {
        double cam = CameraService.of(captureChannel).grabGapMs();
        if (cam > 0.5) {
            return cam;
        }
        if (measuredMedianMs > 0.5) {
            return measuredMedianMs;
        }
        return 0.0;
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
            // 라이브: 해상도가 같으면 입력 Mat을 그대로 빌려 쓴다.
            // caller가 processMat 반환 뒤 frame을 release하므로 여기서 FHD 전체 clone은 불필요하다.
            if (frame.cols() == canonW && frame.rows() == canonH) {
                canon = frame;
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
        // resize/warp로 만든 Mat만 다음 호출에서 해제한다. frame은 process()가 소유한다.
        lastReturned = canon == frame ? null : canon;

        if (canon == frame) {
            // 이 Mat은 process()가 이번 프레임 전용으로 생성했다. 증거 링에 소유권을
            // 그대로 넘기면 매 프레임 FHD 전체 clone(약 6MB)을 없앨 수 있다.
            ev.push(canon, tArrive);
            inputTransferredToEvidence = true;
        } else {
            ev.push(canon.clone(), tArrive);
        }
        double ncc = nccOf(canon);
        double psc = ncc;
        boolean hit = psc >= simThr;
        boolean latchNow = hit && !latched;

        if (latchNow) {
            double analysisMs = (now() - tArrive) * 1000.0;
            pass = new double[] { gapMs + analysisMs, gapMs, analysisMs };
            latched = true;
            ev.trigger();
        } else if (ev.needsPostFrame()) {
            ev.stepAfter(canon.clone(), tArrive);
        }
        double procMs = (now() - tArrive) * 1000.0;

        RoiMatchResult r = new RoiMatchResult("ok");
        r.procMs = procMs;
        r.canonImage = Cv.toBufferedImage(canon);
        r.roi = roi;
        r.ncc = ncc;
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
        try {
            return nccOfLive(live);
        } finally {
            live.release();
        }
    }

    private double nccOfLive(Mat live) {
        Mat liveUse = live;
        if (live.rows() != tmpl.rows() || live.cols() != tmpl.cols()) {
            liveUse = new Mat();
            Imgproc.resize(live, liveUse, new Size(tmpl.cols(), tmpl.rows()));
        }
        if (nccResult == null) {
            nccResult = new Mat();
        }
        Imgproc.matchTemplate(liveUse, tmpl, nccResult, Imgproc.TM_CCOEFF_NORMED);
        double ncc = nccResult.get(0, 0)[0];
        if (liveUse != live) {
            liveUse.release();
        }
        return ncc;
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
