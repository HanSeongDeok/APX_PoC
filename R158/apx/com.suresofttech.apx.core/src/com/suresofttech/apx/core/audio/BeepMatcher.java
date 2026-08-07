package com.suresofttech.apx.core.audio;

import java.util.Arrays;

import com.suresofttech.apx.core.dsp.Fft;
import com.suresofttech.apx.core.dsp.SignalMath;

/**
 * 기대 beep 음향 일치 검출 엔진 (순수·결정적). 파이썬 apx_app/engine/audio.py 이식.
 *
 * <p>주파수(FFT 코사인 유사도) + 파형(정규화 상호상관) 각각 일치도를 내고,
 * 둘 다 각자 임계 이상이면 PASS(AND).
 * 템플릿은 기대음에서 에너지 최대 구간(삐 한 번)만 잘라 정규화한 단일 펄스.
 */
public final class BeepMatcher {

    private final int sr;
    private final double band;          // 대역 에너지 폭(Hz)
    private final double energyFactor;  // 트리거 배수
    private double freqThr;             // 주파수 임계 [0~1]
    private double waveThr;             // 파형 임계 [0~1]

    private final double[] tmpl;        // 단위정규화 펄스(정합필터)
    private final int l;                // 펄스 길이
    private final int specN;            // 펄스 스펙트럼 FFT 크기(pow2)
    private final double[] tmplSpec;    // 펄스 크기 스펙트럼(단위벡터)
    private final double targetFreq;    // 목표 주파수

    private final int buflen;
    private final double[] buf;
    private Double bg = null;           // 배경 에너지 EMA
    private boolean armed = false;
    private double onsetT = Double.NaN;
    /** 전환 확정 시 {passMs, blockGapMs, analysisMs}. 미확정이면 null. */
    private double[] pass;

    public BeepMatcher(double[] template, int sr, double band, double energyFactor,
                       double freqThr, double waveThr, double pulseSec) {
        this.sr = sr;
        this.band = band;
        this.energyFactor = energyFactor;
        this.freqThr = freqThr;
        this.waveThr = waveThr;

        // 평균 제거
        double mu = SignalMath.mean(template);
        double[] t = new double[template.length];
        for (int i = 0; i < t.length; i++) {
            t[i] = template[i] - mu;
        }
        // 파형 템플릿 = 에너지 최대 pulseSec 구간(삐 한 번). 주기 변화에도 각 펄스는 동일.
        int plen = Math.min(t.length, Math.max((int) (0.01 * sr), (int) (pulseSec * sr)));   // 하한 10ms (pulseSec 15ms 반영)
        double[] pulse;
        if (t.length > plen) {
            double[] pre = new double[t.length + 1];
            for (int i = 0; i < t.length; i++) {
                pre[i + 1] = pre[i] + t[i] * t[i];
            }
            int best = 0;
            double bestE = -1;
            for (int st = 0; st + plen <= t.length; st++) {
                double e = pre[st + plen] - pre[st];
                if (e > bestE) {
                    bestE = e;
                    best = st;
                }
            }
            pulse = Arrays.copyOfRange(t, best, best + plen);
        } else {
            pulse = t.clone();
        }
        this.tmpl = SignalMath.unitNormalize(pulse);
        this.l = tmpl.length;

        // 펄스 스펙트럼(주파수 일치도용, 단위벡터)
        double[] win = SignalMath.mul(pulse, SignalMath.hanning(l));
        double[] spec = Fft.magnitude(win);
        double specNorm = SignalMath.norm(spec) + 1e-9;
        this.tmplSpec = new double[spec.length];
        for (int i = 0; i < spec.length; i++) {
            this.tmplSpec[i] = spec[i] / specNorm;
        }
        this.specN = Fft.nextPow2(l);
        int argmax = 0;
        for (int i = 1; i < spec.length; i++) {
            if (spec[i] > spec[argmax]) {
                argmax = i;
            }
        }
        this.targetFreq = (double) argmax * sr / specN;

        this.buflen = Math.max(l, (int) (0.5 * sr));
        this.buf = new double[buflen];
    }

    /** 기본값 편의 생성자 (band=150, energyFactor=4, thr=0.5/0.5, pulseSec=0.12). */
    public BeepMatcher(double[] template, int sr) {
        this(template, sr, 150.0, 4.0, 0.5, 0.5, 0.12);
    }

    /** 검출 무장 + 콜드스타트를 위해 버퍼·배경에너지 초기화(빈 상태에서 시작). */
    public void arm() {
        armed = true;
        onsetT = Double.NaN;
        pass = null;
        java.util.Arrays.fill(buf, 0.0);
        bg = null;
    }

    public void reset() {
        armed = false;
        onsetT = Double.NaN;
        pass = null;
    }

    public double getTargetFreq() {
        return targetFreq;
    }

    public int getSampleRate() {
        return sr;
    }

    public double getFreqThr() {
        return freqThr;
    }

    public double getWaveThr() {
        return waveThr;
    }

    public void setFreqThr(double v) {
        freqThr = v;
    }

    public void setWaveThr(double v) {
        waveThr = v;
    }

    public double[] getBuffer() {
        return buf;
    }

    /** 기대 파형(단위정규화 단일 펄스) — 스코프 '기대 그래프' 표시용. */
    public double[] getTemplate() {
        return tmpl.clone();
    }

    private double bandEnergy(double[] x) {
        double[] mag = Fft.magnitude(SignalMath.mul(x, SignalMath.hanning(x.length)));
        int n = Fft.nextPow2(x.length);
        double sum = 0;
        for (int k = 0; k < mag.length; k++) {
            double f = (double) k * sr / n;
            if (f >= targetFreq - band && f <= targetFreq + band) {
                sum += mag[k];
            }
        }
        return sum;
    }

    private double waveSim() {
        int segLen = Math.min(buflen, (int) (0.5 * sr));
        double[] seg = Arrays.copyOfRange(buf, buflen - segLen, buflen);
        return SignalMath.nccMax(seg, tmpl);
    }

    private double freqSim() {
        double[] seg = Arrays.copyOfRange(buf, buflen - l, buflen);
        double[] mag = Fft.magnitude(SignalMath.mul(seg, SignalMath.hanning(l)));
        double nrm = SignalMath.norm(mag) + 1e-9;
        double[] sn = new double[mag.length];
        for (int i = 0; i < mag.length; i++) {
            sn[i] = mag[i] / nrm;
        }
        double v = SignalMath.dot(sn, tmplSpec);
        return Math.min(Math.max(v, 0.0), 1.0);
    }

    /** 오디오 블록(mono) 처리. now=시각(초, 검출 타임스탬프용). */
    public MatchResult feed(double[] block, double now) {
        long tAna0 = System.nanoTime();
        int n = block.length;
        double blockGapMs = n * 1000.0 / sr;
        // 롤링 버퍼 갱신
        if (n >= buflen) {
            System.arraycopy(block, n - buflen, buf, 0, buflen);
        } else {
            System.arraycopy(buf, n, buf, 0, buflen - n);
            System.arraycopy(block, 0, buf, buflen - n, n);
        }

        double e = bandEnergy(block);
        bg = (bg == null) ? e : 0.98 * bg + 0.02 * e;
        double ratio = e / Math.max(bg, 1e-6);
        boolean hasSound = ratio >= energyFactor;

        double waveSim = waveSim();
        double freqSim = freqSim();
        double combined = freqSim + waveSim;
        boolean isPass = (freqSim >= freqThr) && (waveSim >= waveThr);   // AND 조건

        boolean match = false;
        // 확정 = 주파수 AND 파형 일치(isPass). 에너지 급증(hasSound)은 게이트에서 제외:
        //   콜드스타트(소리 먼저 재생 후 측정 시작)엔 배경 대비 급증이 없어 hasSound가
        //   안 뜬다. 에너지 온셋은 항상 있다고 가정하고, 강한 조건인 isPass로만 확정.
        if (armed && Double.isNaN(onsetT) && isPass) {
            onsetT = now;
            match = true;
            double analysisMs = (System.nanoTime() - tAna0) / 1e6;
            pass = new double[] { blockGapMs + analysisMs, blockGapMs, analysisMs };
        }
        return new MatchResult(targetFreq, ratio, hasSound, freqSim, waveSim,
                combined, freqThr, waveThr, isPass, match, onsetT,
                blockGapMs,
                pass != null ? Double.valueOf(pass[2]) : null,
                pass != null ? Double.valueOf(pass[0]) : null);
    }
}
