package com.suresofttech.apx.core.vision;

import java.util.ArrayList;
import java.util.List;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.ORB;

/**
 * ORB 특징점 정렬 (frame → 기준영상 좌표계 homography). 기어 / 클러스터 공용.
 * 파이썬 gear.py Aligner / cluster.py OrbAligner 이식.
 *
 * @deprecated <b>사용하지 않는 것을 권장한다.</b> 이 정렬은 미리 캡처한 파일을 기준 화면으로
 *             쓸 때(= 정답과 촬영의 카메라 위치가 다를 때) 좌표를 맞추기 위한 것이다.
 *             기준 화면은 설정 탭 <b>라이브 캡처</b>로 잡는 것으로 클라이언트와 협의되어,
 *             정답과 촬영이 같은 카메라·같은 위치다. 맞출 좌표가 없다.
 *             <p>켜면 손해만 있다 - 특징점이 부족한 장면에서 {@code aligning} 상태에 갇혀
 *             판정을 못 하고, 프레임마다 변환이 미세하게 달라져 ROI 가 떠다닌다(드리프트).
 *             판정 시간도 늘어 30ms 동기 예산을 깎는다.
 *             <p>코드는 되돌릴 여지를 남겨 두기 위해 삭제하지 않았다.
 *             라이브 경로는 {@code RoiMatchDetector.setAlignEnabled(false)} 다.
 */
@Deprecated
public final class OrbAligner {

    public static final int MIN_MATCH = 12;

    /** 정렬 결과: M(frame→ref, 실패 시 null) + inliers. */
    public static final class Result {
        public final Mat m;
        public final int inliers;

        Result(Mat m, int inliers) {
            this.m = m;
            this.inliers = inliers;
        }
    }

    private final ORB orb = ORB.create(3000);
    private final MatOfKeyPoint kpRef = new MatOfKeyPoint();
    private final Mat desRef = new Mat();
    private final DescriptorMatcher bf =
            DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);

    public OrbAligner(Mat ref) {
        orb.detectAndCompute(ref, new Mat(), kpRef, desRef);
    }

    public Result homography(Mat frame) {
        MatOfKeyPoint kp = new MatOfKeyPoint();
        Mat des = new Mat();
        orb.detectAndCompute(frame, new Mat(), kp, des);
        KeyPoint[] kpArr = kp.toArray();
        if (des.empty() || kpArr.length < MIN_MATCH) {
            kp.release();
            des.release();
            return new Result(null, 0);
        }
        List<MatOfDMatch> knn = new ArrayList<MatOfDMatch>();
        bf.knnMatch(desRef, des, knn, 2);
        List<DMatch> good = new ArrayList<DMatch>();
        for (MatOfDMatch mm : knn) {
            DMatch[] pair = mm.toArray();
            if (pair.length >= 2 && pair[0].distance < 0.75f * pair[1].distance) {
                good.add(pair[0]);
            }
            mm.release();
        }
        if (good.size() < MIN_MATCH) {
            kp.release();
            des.release();
            return new Result(null, good.size());
        }
        KeyPoint[] kpRefArr = kpRef.toArray();
        Point[] srcP = new Point[good.size()];   // ref
        Point[] dstP = new Point[good.size()];   // frame
        for (int i = 0; i < good.size(); i++) {
            DMatch m = good.get(i);
            srcP[i] = kpRefArr[m.queryIdx].pt;
            dstP[i] = kpArr[m.trainIdx].pt;
        }
        MatOfPoint2f srcM = new MatOfPoint2f(srcP);
        MatOfPoint2f dstM = new MatOfPoint2f(dstP);
        Mat mask = new Mat();
        Mat h = Calib3d.findHomography(dstM, srcM, Calib3d.RANSAC, 3.0, mask);   // frame→ref
        int inl = (h == null || h.empty() || mask.empty()) ? 0 : Core.countNonZero(mask);
        kp.release();
        des.release();
        srcM.release();
        dstM.release();
        mask.release();
        if (h == null || h.empty()) {
            return new Result(null, good.size());
        }
        return new Result(h, inl);
    }

    /** 정렬 지표 {roll(도), scale, persp}. M=null 이면 null. */
    public static double[] angles(Mat m) {
        if (m == null || m.empty()) {
            return null;
        }
        double a = m.get(0, 0)[0];
        double b = m.get(0, 1)[0];
        double d = m.get(1, 0)[0];
        double e = m.get(1, 1)[0];
        double roll = Math.toDegrees(Math.atan2(d, a));
        double scale = (Math.hypot(a, d) + Math.hypot(b, e)) / 2.0;
        double persp = Math.hypot(m.get(2, 0)[0], m.get(2, 1)[0]) * 1000.0;
        return new double[] { roll, scale, persp };
    }
}
