package com.suresofttech.apx.core.audio;

/**
 * 주차센서식 근접 경고음 파형 생성 (파이썬 apx_app/engine/tone.py 이식).
 * 간격을 gapStart→gapEnd 로 선형 축소하며 짧은 비프 반복 후, 마지막에 연속음.
 * 재생(javax.sound.sampled)은 UI 번들에서 처리; 여기서는 파형 생성만.
 */
public final class Tone {
    private Tone() {}

    private static double[] beep(int sr, double freq, double ms, double fadeMs) {
        int n = Math.max(1, (int) (sr * ms / 1000.0));
        double[] wave = new double[n];
        for (int i = 0; i < n; i++) {
            wave[i] = Math.sin(2.0 * Math.PI * freq * i / sr);
        }
        int f = Math.max(1, (int) (sr * fadeMs / 1000.0));
        if (2 * f < n) {
            for (int i = 0; i < f; i++) {
                double g = (double) i / f;
                wave[i] *= g;
                wave[n - 1 - i] *= g;
            }
        }
        return wave;
    }

    /**
     * 근접 경고음 파형 생성. steps 회에 걸쳐 간격을 gapStartMs→gapEndMs 로 좁힌 뒤
     * solidMs 연속음으로 마무리. 진폭 [-amp, amp].
     */
    public static double[] proximityWarning(int sr, double freq, double beepMs,
                                            double gapStartMs, double gapEndMs,
                                            int steps, double solidMs, double amp) {
        double[] beep = beep(sr, freq, beepMs, 5.0);
        java.util.List<double[]> parts = new java.util.ArrayList<double[]>();
        int total = 0;
        for (int i = 0; i < steps; i++) {
            double frac = i / (double) Math.max(1, steps - 1);
            double gapMs = gapStartMs + (gapEndMs - gapStartMs) * frac;
            parts.add(beep);
            parts.add(new double[(int) (sr * gapMs / 1000.0)]);
        }
        parts.add(beep(sr, freq, solidMs, 5.0));
        for (double[] p : parts) {
            total += p.length;
        }
        double[] out = new double[total];
        int off = 0;
        for (double[] p : parts) {
            for (int i = 0; i < p.length; i++) {
                out[off + i] = p[i] * amp;
            }
            off += p.length;
        }
        return out;
    }

    /** 기본 파라미터 (freq=2000, 9단계, 600→60ms, 연속 1500ms). */
    public static double[] proximityWarning(int sr) {
        return proximityWarning(sr, 2000.0, 90, 600, 60, 9, 1500, 0.5);
    }
}
