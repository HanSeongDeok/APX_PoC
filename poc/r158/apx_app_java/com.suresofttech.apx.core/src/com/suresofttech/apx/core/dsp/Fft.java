package com.suresofttech.apx.core.dsp;

/**
 * 의존성 없는 radix-2 FFT (Cooley-Tukey, in-place).
 *
 * <p>파이썬 NumPy {@code np.fft.rfft} 대체. 임의 길이 신호는 다음 2의 거듭제곱으로
 * zero-padding 후 변환한다. 주파수 일치도는 스펙트럼을 단위벡터로 정규화해 쓰므로
 * 패딩에 따른 절대 스케일 차이는 상쇄된다(라이브·템플릿이 동일 길이 → 동일 N).
 *
 * <p>생산 단계에서 NumPy와 완전 수치 일치가 필요하면 JTransforms(임의 길이 rfft)로
 * 교체할 것. PoC 성능·무의존성을 위해 radix-2 로 구현.
 */
public final class Fft {
    private Fft() {}

    /** n 이상인 최소 2의 거듭제곱. */
    public static int nextPow2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /** in-place 복소 FFT. re/im 길이는 2의 거듭제곱이어야 함. */
    public static void transform(double[] re, double[] im) {
        int n = re.length;
        // 비트 반전 순열
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1.0, ci = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr;        im[a] += xi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    /** in-place 역 FFT. re/im 길이는 2의 거듭제곱. IFFT(x)=conj(FFT(conj(x)))/N. */
    public static void inverse(double[] re, double[] im) {
        int n = re.length;
        for (int i = 0; i < n; i++) {
            im[i] = -im[i];
        }
        transform(re, im);
        double inv = 1.0 / n;
        for (int i = 0; i < n; i++) {
            re[i] = re[i] * inv;
            im[i] = -im[i] * inv;
        }
    }

    /**
     * 실수 신호의 크기 스펙트럼. 길이는 nextPow2(x.length)=N 으로 패딩,
     * 반환은 bins 0..N/2 (rfft 와 동일하게 N/2+1개).
     */
    public static double[] magnitude(double[] x) {
        int N = nextPow2(x.length);
        double[] re = new double[N];
        double[] im = new double[N];
        System.arraycopy(x, 0, re, 0, x.length);
        transform(re, im);
        int half = N / 2 + 1;
        double[] mag = new double[half];
        for (int i = 0; i < half; i++) {
            mag[i] = Math.hypot(re[i], im[i]);
        }
        return mag;
    }
}
