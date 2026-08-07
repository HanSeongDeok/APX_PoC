package com.suresofttech.apx.core.audio;

/**
 * BeepMatcher.feed() 한 블록 처리 결과 (파이썬 dict 대응 POJO).
 * onsetT 는 미검출 시 Double.NaN.
 * passMs(자체 판단) = blockGapMs + analysisMs — 전환 확정 블록에만 non-null.
 * blockGap = 실제 캡처 블록 길이/sr (고정 ms 아님).
 */
public final class MatchResult {
    public final double targetFreq;   // 목표 주파수(Hz)
    public final double energyRatio;  // 대역 에너지 / 배경
    public final boolean hasSound;    // 소리 있음(트리거)
    public final double freqSim;      // 주파수 일치도 [0,1]
    public final double waveSim;      // 파형 일치도 [0,1]
    public final double combined;     // 합산(참고용)
    public final double freqThr;      // 주파수 임계
    public final double waveThr;      // 파형 임계
    public final boolean isPass;      // (freq>=freqThr) AND (wave>=waveThr)
    public final boolean match;       // 최초 확정(latch) 발생 블록
    public final double onsetT;       // 확정 시각(초), 미확정 NaN

    /** 블록 양자화 간격(ms) = blockSamples/sr·1000. 매 블록. */
    public final double blockGapMs;
    /** 전환 순간 분석 시간(ms). 미확정이면 null. */
    public final Double analysisMs;
    /** 자체 판단(ms) = blockGap + analysis. 미확정이면 null. */
    public final Double passMs;

    public MatchResult(double targetFreq, double energyRatio, boolean hasSound,
                       double freqSim, double waveSim, double combined,
                       double freqThr, double waveThr, boolean isPass,
                       boolean match, double onsetT,
                       double blockGapMs, Double analysisMs, Double passMs) {
        this.targetFreq = targetFreq;
        this.energyRatio = energyRatio;
        this.hasSound = hasSound;
        this.freqSim = freqSim;
        this.waveSim = waveSim;
        this.combined = combined;
        this.freqThr = freqThr;
        this.waveThr = waveThr;
        this.isPass = isPass;
        this.match = match;
        this.onsetT = onsetT;
        this.blockGapMs = blockGapMs;
        this.analysisMs = analysisMs;
        this.passMs = passMs;
    }
}
