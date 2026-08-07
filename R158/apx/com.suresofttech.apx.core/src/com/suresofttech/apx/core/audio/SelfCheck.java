package com.suresofttech.apx.core.audio;

/**
 * 엔진 자체검증 (파이썬에서 돌린 것과 동일 시나리오). RCP/번들 없이 순수 실행.
 *   javac -d bin src/.../*.java  후  java com.suresofttech.apx.core.audio.SelfCheck
 * 모든 기대가 맞으면 exit 0, 하나라도 틀리면 exit 1.
 */
public final class SelfCheck {

    private static int fails = 0;

    private static void check(String name, boolean cond) {
        System.out.println((cond ? "OK  " : "XX  ") + name);
        if (!cond) {
            fails++;
        }
    }

    private static double[] tone(int sr, double freq, double sec) {
        int n = (int) (sec * sr);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = Math.sin(2.0 * Math.PI * freq * i / sr);
        }
        return x;
    }

    public static void main(String[] args) {
        int sr = 16000;
        double[] tmpl = tone(sr, 2000.0, 0.15);
        BeepMatcher m = new BeepMatcher(tmpl, sr, 150.0, 4.0, 0.5, 0.5, 0.12);

        // 목표 주파수 ≈ 2000Hz 자동 추출
        check("targetFreq≈2000 (" + Math.round(m.getTargetFreq()) + ")",
                Math.abs(m.getTargetFreq() - 2000.0) < 60.0);

        // 일치 입력 → PASS + latch
        m.arm();
        for (int i = 0; i < 3; i++) {
            m.feed(new double[2048], 0.0);
        }
        MatchResult r = m.feed(tmpl, 1.0);
        check("matching: freqSim≥0.9 (" + fmt(r.freqSim) + ")", r.freqSim >= 0.9);
        check("matching: waveSim≥0.9 (" + fmt(r.waveSim) + ")", r.waveSim >= 0.9);
        check("matching: isPass=true", r.isPass);
        check("matching: match latched", r.match && !Double.isNaN(r.onsetT));
        check("matching: passMs=blockGap+analysis",
                r.passMs != null && r.analysisMs != null
                        && Math.abs(r.passMs.doubleValue() - (r.blockGapMs + r.analysisMs.doubleValue())) < 1e-6);

        // 불일치(900Hz) → FAIL
        BeepMatcher m2 = new BeepMatcher(tmpl, sr);
        m2.arm();
        MatchResult r2 = m2.feed(tone(sr, 900.0, 0.15), 1.0);
        check("mismatch 900Hz: isPass=false (freq=" + fmt(r2.freqSim) + ")", !r2.isPass);

        // AND 게이트: 임계를 sim 위로 올리면 FAIL (한쪽만 미달해도 탈락)
        BeepMatcher m3 = new BeepMatcher(tmpl, sr);
        m3.setFreqThr(1.01);  // freqSim(≈1.0) < 1.01 → 주파수 미달
        m3.arm();
        MatchResult r3 = m3.feed(tmpl, 1.0);
        check("AND gate: freqThr=1.01 → isPass=false", !r3.isPass);

        // FFT 상호상관 == 직접 상호상관 (동등성, 결정적 신호)
        double[] seg = new double[8000];
        for (int i = 0; i < seg.length; i++) {
            seg[i] = Math.sin(2 * Math.PI * 2000.0 * i / sr) * (i > 3000 && i < 6000 ? 1.0 : 0.05);
        }
        double[] pulse = com.suresofttech.apx.core.dsp.SignalMath.unitNormalize(tone(sr, 2000.0, 0.12));
        double fft = com.suresofttech.apx.core.dsp.SignalMath.nccMax(seg, pulse);
        double dir = com.suresofttech.apx.core.dsp.SignalMath.nccMaxDirect(seg, pulse);
        check("nccMax FFT==Direct (fft=" + fmt(fft) + " dir=" + fmt(dir) + ")", Math.abs(fft - dir) < 1e-6);

        // Tone 생성기: 근접 경고음 총 길이 ≈ 5.28s (44100Hz 기본값)
        double[] warn = Tone.proximityWarning(44100);
        double dur = warn.length / 44100.0;
        check("Tone dur≈5.28s (" + fmt(dur) + ")", Math.abs(dur - 5.28) < 0.2);

        System.out.println("---");
        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
