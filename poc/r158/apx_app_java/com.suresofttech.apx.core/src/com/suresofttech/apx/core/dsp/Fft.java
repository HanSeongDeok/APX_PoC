package com.suresofttech.apx.core.dsp;

import org.jtransforms.fft.DoubleFFT_1D;

/**
 * FFT — JTransforms(org.jtransforms) 백엔드. 파이썬 NumPy rfft/ifft 대체.
 *
 * <p>공개 API(nextPow2/transform/inverse/magnitude)는 기존 자작 radix-2와 동일하게 유지하여
 * 호출부(BeepMatcher·SignalMath·XChartScope 등) 변경 없음. 내부만 검증된 라이브러리로 교체.
 * transform=복소 forward, inverse=복소 역(1/n 스케일), magnitude=실수 rfft 크기(0..N/2).
 */
public final class Fft {
    private Fft() {}

    /** n 이상인 최소 2의 거듭제곱. */
    public static int nextPow2(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }

    /** in-place 복소 FFT(forward). re/im 동일 길이. (JTransforms complexForward, e^-i 규약) */
    public static void transform(double[] re, double[] im) {
        int n = re.length;
        if (n < 2) {
            return;
        }
        double[] a = new double[2 * n];
        for (int i = 0; i < n; i++) {
            a[2 * i] = re[i];
            a[2 * i + 1] = im[i];
        }
        new DoubleFFT_1D(n).complexForward(a);
        for (int i = 0; i < n; i++) {
            re[i] = a[2 * i];
            im[i] = a[2 * i + 1];
        }
    }

    /** in-place 역 FFT (1/n 스케일). */
    public static void inverse(double[] re, double[] im) {
        int n = re.length;
        if (n < 2) {
            return;
        }
        double[] a = new double[2 * n];
        for (int i = 0; i < n; i++) {
            a[2 * i] = re[i];
            a[2 * i + 1] = im[i];
        }
        new DoubleFFT_1D(n).complexInverse(a, true);   // true = 1/n 스케일
        for (int i = 0; i < n; i++) {
            re[i] = a[2 * i];
            im[i] = a[2 * i + 1];
        }
    }

    /**
     * 실수 신호의 크기 스펙트럼. 길이는 nextPow2(x.length)=N 으로 패딩,
     * 반환은 bins 0..N/2 (rfft 와 동일하게 N/2+1개). (JTransforms realForward 패킹)
     */
    public static double[] magnitude(double[] x) {
        int N = nextPow2(x.length);
        if (N < 2) {
            return new double[] { x.length > 0 ? Math.abs(x[0]) : 0.0 };
        }
        double[] a = new double[N];
        System.arraycopy(x, 0, a, 0, x.length);
        new DoubleFFT_1D(N).realForward(a);
        // realForward 패킹(N 짝수): a[0]=Re[0], a[1]=Re[N/2], a[2k]=Re[k], a[2k+1]=Im[k]
        int half = N / 2 + 1;
        double[] mag = new double[half];
        mag[0] = Math.abs(a[0]);
        for (int k = 1; k < N / 2; k++) {
            mag[k] = Math.hypot(a[2 * k], a[2 * k + 1]);
        }
        mag[N / 2] = Math.abs(a[1]);
        return mag;
    }
}
