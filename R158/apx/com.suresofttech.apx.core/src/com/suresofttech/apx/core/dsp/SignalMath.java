package com.suresofttech.apx.core.dsp;

/**
 * 벡터 연산 / 윈도우 / 정규화 상호상관(NCC) 헬퍼.
 * 파이썬 NumPy/SciPy 대응을 무의존성으로 직접 구현.
 */
public final class SignalMath {
    private SignalMath() {}

    /** np.hanning(M) 과 동일: 0.5 - 0.5*cos(2*pi*n/(M-1)). */
    public static double[] hanning(int m) {
        double[] w = new double[m];
        if (m == 1) {
            w[0] = 1.0;
            return w;
        }
        for (int n = 0; n < m; n++) {
            w[n] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / (m - 1));
        }
        return w;
    }

    public static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }

    public static double norm(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v * v;
        }
        return Math.sqrt(s);
    }

    public static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** 원소별 곱 a*b. */
    public static double[] mul(double[] a, double[] b) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * b[i];
        }
        return r;
    }

    /** 평균 제거 후 단위벡터 정규화(정합필터 템플릿용). */
    public static double[] unitNormalize(double[] x) {
        double mu = mean(x);
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            r[i] = x[i] - mu;
        }
        double nrm = norm(r) + 1e-9;
        for (int i = 0; i < r.length; i++) {
            r[i] /= nrm;
        }
        return r;
    }

    /**
     * 정규화 상호상관(NCC) 최대값. SciPy scipy.signal.correlate(mode="valid") 대응.
     * seg 위로 단위벡터 tmpl 을 슬라이딩, 각 위치의 국소 에너지로 정규화 후 최대 |값| 반환.
     * 무음 창(최대 에너지의 10% 미만)은 제외. 결과 [0,1] 클립.
     *
     * <p>상관값(분자)은 FFT(합성곱 정리)로 O(S log S), 국소 에너지(분모)는 prefix-sum O(S).
     * 직접 O(S / L) 방식({@link #nccMaxDirect})과 수치 동일, 실시간 처리를 위해 이쪽 사용.
     */
    public static double nccMax(double[] seg, double[] tmpl) {
        int l = tmpl.length;
        int s = seg.length;
        if (s < l) {
            return 0.0;
        }
        int outN = s - l + 1;
        double[] num = crossCorrValid(seg, tmpl);      // FFT 상호상관 (valid)
        // 국소 에너지: seg^2 의 prefix-sum → 창별 합 O(1)
        double[] pre = new double[s + 1];
        for (int i = 0; i < s; i++) {
            pre[i + 1] = pre[i] + seg[i] * seg[i];
        }
        double[] eng = new double[outN];
        double maxEng = 0.0;
        for (int k = 0; k < outN; k++) {
            eng[k] = Math.sqrt(Math.max(pre[k + l] - pre[k], 0.0));
            if (eng[k] > maxEng) {
                maxEng = eng[k];
            }
        }
        if (maxEng <= 0.0) {
            return 0.0;
        }
        double thr = 0.1 * maxEng;
        double best = 0.0;
        for (int k = 0; k < outN; k++) {
            if (eng[k] >= thr) {
                double v = Math.abs(num[k] / (eng[k] + 1e-9));
                if (v > best) {
                    best = v;
                }
            }
        }
        return Math.min(Math.max(best, 0.0), 1.0);
    }

    /**
     * valid 모드 상호상관 num[k] = Σ_i seg[k+i] / tmpl[i], k=0..S-L. FFT(합성곱 정리) 사용.
     * corr = conv(seg, reverse(tmpl)); num[k] = conv[L-1+k].
     */
    static double[] crossCorrValid(double[] seg, double[] tmpl) {
        int s = seg.length;
        int l = tmpl.length;
        int n = Fft.nextPow2(s + l - 1);
        double[] ar = new double[n];
        double[] ai = new double[n];
        double[] br = new double[n];
        double[] bi = new double[n];
        System.arraycopy(seg, 0, ar, 0, s);
        for (int i = 0; i < l; i++) {
            br[i] = tmpl[l - 1 - i];                   // tmpl 뒤집기
        }
        Fft.transform(ar, ai);
        Fft.transform(br, bi);
        for (int i = 0; i < n; i++) {                  // 복소 곱 A / B
            double re = ar[i] * br[i] - ai[i] * bi[i];
            double im = ar[i] * bi[i] + ai[i] * br[i];
            ar[i] = re;
            ai[i] = im;
        }
        Fft.inverse(ar, ai);
        int outN = s - l + 1;
        double[] num = new double[outN];
        for (int k = 0; k < outN; k++) {
            num[k] = ar[l - 1 + k];                    // 전출력 중 full-overlap 구간
        }
        return num;
    }

    /**
     * seg 위에서 tmpl 이 가장 잘 겹치는 지연(lag) 반환 - 위상 정렬(오버레이 표시)용.
     * 부호 있는 정규화 상관을 최대화(peak↔peak, 극성 유지). 무음/불가 시 -1.
     * lag 위치의 seg[lag..lag+L] 이 tmpl 과 위상이 맞는 구간.
     */
    public static int bestNccLag(double[] seg, double[] tmpl) {
        int l = tmpl.length;
        int s = seg.length;
        if (s < l) {
            return -1;
        }
        int outN = s - l + 1;
        double[] num = crossCorrValid(seg, tmpl);
        double[] pre = new double[s + 1];
        for (int i = 0; i < s; i++) {
            pre[i + 1] = pre[i] + seg[i] * seg[i];
        }
        double maxEng = 0.0;
        double[] eng = new double[outN];
        for (int k = 0; k < outN; k++) {
            eng[k] = Math.sqrt(Math.max(pre[k + l] - pre[k], 0.0));
            if (eng[k] > maxEng) {
                maxEng = eng[k];
            }
        }
        if (maxEng <= 0.0) {
            return -1;
        }
        double thr = 0.1 * maxEng;
        int bestK = -1;
        double best = -Double.MAX_VALUE;
        for (int k = 0; k < outN; k++) {
            if (eng[k] >= thr) {
                double v = num[k] / (eng[k] + 1e-9);   // 부호 유지(같은 극성으로 겹침)
                if (v > best) {
                    best = v;
                    bestK = k;
                }
            }
        }
        return bestK;
    }

    /** 직접 O(S / L) NCC (참조 / 동등성 검증용). 실시간 경로는 {@link #nccMax} 사용. */
    public static double nccMaxDirect(double[] seg, double[] tmpl) {
        int l = tmpl.length;
        int s = seg.length;
        if (s < l) {
            return 0.0;
        }
        int outN = s - l + 1;
        double[] num = new double[outN];
        double[] eng = new double[outN];
        double maxEng = 0.0;
        for (int k = 0; k < outN; k++) {
            double d = 0.0, ss = 0.0;
            for (int i = 0; i < l; i++) {
                double sv = seg[k + i];
                d += sv * tmpl[i];
                ss += sv * sv;
            }
            num[k] = d;
            eng[k] = Math.sqrt(Math.max(ss, 0.0));
            if (eng[k] > maxEng) {
                maxEng = eng[k];
            }
        }
        if (maxEng <= 0.0) {
            return 0.0;
        }
        double thr = 0.1 * maxEng;
        double best = 0.0;
        for (int k = 0; k < outN; k++) {
            if (eng[k] >= thr) {
                double v = Math.abs(num[k] / (eng[k] + 1e-9));
                if (v > best) {
                    best = v;
                }
            }
        }
        return Math.min(Math.max(best, 0.0), 1.0);
    }
}
