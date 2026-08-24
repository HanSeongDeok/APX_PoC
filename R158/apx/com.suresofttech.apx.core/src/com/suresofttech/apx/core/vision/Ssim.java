package com.suresofttech.apx.core.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * 구조적 유사도(SSIM) - 파이썬 skimage structural_similarity 대체.
 * Wang et al. 표준 구현(11x11 gaussian, sigma 1.5)을 OpenCV 연산으로. 입력은 동일크기 그레이(8U).
 */
public final class Ssim {

    private Ssim() {
    }

    private static final double C1 = 6.5025;     // (0.01*255)^2
    private static final double C2 = 58.5225;    // (0.03*255)^2

    /** 두 그레이스케일 Mat의 평균 SSIM [−1,1] (일반적으로 [0,1]). */
    public static double mssim(Mat g1, Mat g2) {
        Mat i1 = new Mat();
        Mat i2 = new Mat();
        g1.convertTo(i1, CvType.CV_32F);
        g2.convertTo(i2, CvType.CV_32F);

        Size k = new Size(11, 11);
        double sig = 1.5;
        Mat i1_2 = mul(i1, i1);
        Mat i2_2 = mul(i2, i2);
        Mat i1_i2 = mul(i1, i2);

        Mat mu1 = blur(i1, k, sig);
        Mat mu2 = blur(i2, k, sig);
        Mat mu1_2 = mul(mu1, mu1);
        Mat mu2_2 = mul(mu2, mu2);
        Mat mu1_mu2 = mul(mu1, mu2);

        Mat sig1 = sub(blur(i1_2, k, sig), mu1_2);
        Mat sig2 = sub(blur(i2_2, k, sig), mu2_2);
        Mat sig12 = sub(blur(i1_i2, k, sig), mu1_mu2);

        // t1 = (2*mu1_mu2 + C1) * (2*sig12 + C2)
        Mat a1 = addS(mulS(mu1_mu2, 2), C1);
        Mat a2 = addS(mulS(sig12, 2), C2);
        Mat num = mul(a1, a2);
        // t2 = (mu1_2 + mu2_2 + C1) * (sig1 + sig2 + C2)
        Mat b1 = addS(add(mu1_2, mu2_2), C1);
        Mat b2 = addS(add(sig1, sig2), C2);
        Mat den = mul(b1, b2);

        Mat map = new Mat();
        Core.divide(num, den, map);
        double res = Core.mean(map).val[0];

        for (Mat m : new Mat[] { i1, i2, i1_2, i2_2, i1_i2, mu1, mu2, mu1_2, mu2_2, mu1_mu2,
                sig1, sig2, sig12, a1, a2, num, b1, b2, den, map }) {
            m.release();
        }
        return res;
    }

    private static Mat mul(Mat a, Mat b) {
        Mat o = new Mat();
        Core.multiply(a, b, o);
        return o;
    }

    private static Mat mulS(Mat a, double s) {
        Mat o = new Mat();
        Core.multiply(a, new Scalar(s), o);
        return o;
    }

    private static Mat add(Mat a, Mat b) {
        Mat o = new Mat();
        Core.add(a, b, o);
        return o;
    }

    private static Mat addS(Mat a, double s) {
        Mat o = new Mat();
        Core.add(a, new Scalar(s), o);
        return o;
    }

    private static Mat sub(Mat a, Mat b) {
        Mat o = new Mat();
        Core.subtract(a, b, o);
        a.release();     // a는 임시(blur 결과)라 즉시 해제
        return o;
    }

    private static Mat blur(Mat src, Size k, double sig) {
        Mat o = new Mat();
        Imgproc.GaussianBlur(src, o, k, sig);
        return o;
    }
}
