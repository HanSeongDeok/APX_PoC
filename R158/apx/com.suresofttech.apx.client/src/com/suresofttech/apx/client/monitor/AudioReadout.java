package com.suresofttech.apx.client.monitor;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.audio.MatchResult;

/**
 * 음향 모니터 판독값 — 파형 그래프가 못 보여주는 <b>판정 근거 숫자</b>.
 *
 * @deprecated 모니터 뷰에서 판독값 UI를 제공하지 않는다. {@link com.suresofttech.apx.client.view.AudioMonitorView} 참고.
 */
@Deprecated
public class AudioReadout extends ReadoutBar {

    private final Label freqLbl;
    private final Label waveLbl;
    private final Label targetLbl;
    private final Label energyLbl;
    private final Label judgeLbl;
    private final Label passAtLbl;
    private final Label spanLbl;
    private final Label srcLbl;

    private Long passAtMs;
    private boolean running;

    public AudioReadout(Composite parent) {
        super(parent, "음향: 대기");
        freqLbl = field("주파수 일치도");
        waveLbl = field("파형 일치도");
        targetLbl = field("목표 주파수");
        energyLbl = field("에너지비");
        judgeLbl = field("자체 판단");
        passAtLbl = field("PASS 시각");
        spanLbl = field("PASS 구간");
        srcLbl = field("입력");
    }

    /** 측정 시작 — 스냅샷 소스 표기 후 지표 초기화. */
    public void onStarted(String micName, int sampleRate, String expectedWavPath) {
        running = true;
        passAtMs = null;
        set(srcLbl, (micName == null || micName.isEmpty() ? "기본 마이크" : micName)
                + " · " + sampleRate + " Hz · " + baseName(expectedWavPath));
        clear(freqLbl);
        clear(waveLbl);
        clear(targetLbl);
        clear(energyLbl);
        clear(judgeLbl);
        clear(passAtLbl);
        set(spanLbl, "0개");
        head("음향: 측정 중", STATE_BUSY);
        commit();
    }

    /**
     * 매 폴링 틱 — 블록 판정 지표.
     * @param m 최신 블록 결과(없으면 지표 유지)
     * @param elapsedSec 측정 경과(초)
     * @param passSpanCount 스코프 초록 밴드 수
     */
    public void update(MatchResult m, double elapsedSec, int passSpanCount) {
        if (m != null) {
            set(freqLbl, vsThr(m.freqSim, m.freqThr), thrState(m.freqSim, m.freqThr));
            set(waveLbl, vsThr(m.waveSim, m.waveThr), thrState(m.waveSim, m.waveThr));
            set(targetLbl, String.format("%.0f Hz", Double.valueOf(m.targetFreq)));
            set(energyLbl, f1(m.energyRatio) + "배 · 소리 " + (m.hasSound ? "있음" : "없음"),
                    m.hasSound ? STATE_BUSY : STATE_IDLE);
            if (passAtMs == null) {
                set(judgeLbl, "블록 간격 " + f1(m.blockGapMs) + " ms (전환 대기)");
            }
        }
        set(spanLbl, passSpanCount + "개" + (passSpanCount > 0 ? " · clip.wav = PASS 밴드" : ""),
                passSpanCount > 0 ? STATE_PASS : STATE_IDLE);
        head(headText(elapsedSec), passAtMs != null ? STATE_PASS : STATE_BUSY);
        commit();
    }

    /**
     * PASS 확정 — 검출 시각과 자체 판단 분해.
     * 검출 시각은 L2 캘리브 보정 없이 물리지연(D_mic)을 포함한 값이다.
     */
    public void setPass(Long passAtMs, Double judgeMs, Double gapMs, Double analysisMs) {
        this.passAtMs = passAtMs;
        if (passAtMs == null) {
            clear(passAtLbl);
            commit();
            return;
        }
        set(passAtLbl, passAtMs + " ms (물리지연 D_mic 포함)", STATE_PASS);
        if (judgeMs != null && gapMs != null && analysisMs != null) {
            set(judgeLbl, f1(judgeMs.doubleValue()) + " ms = 간격 " + f1(gapMs.doubleValue())
                    + " + 분석 " + f1(analysisMs.doubleValue()), STATE_PASS);
        } else if (judgeMs != null) {
            set(judgeLbl, f1(judgeMs.doubleValue()) + " ms", STATE_PASS);
        }
        commit();
    }

    /** 측정 중단 — 헤더를 최종 표기로 고정. */
    public void onStopped(boolean pass) {
        running = false;
        if (pass && passAtMs != null) {
            head("음향: PASS @ " + passAtMs + " ms", STATE_PASS);
        } else if (pass) {
            head("음향: PASS", STATE_PASS);
        } else {
            head("음향: FAIL (기대음 미검출)", STATE_FAIL);
        }
        commit();
    }

    /**
     * 입력 끊김 표시 — 측정 중 마이크가 빠지거나 드라이버가 멈춘 경우.
     * 이 시점 이후 {@code full.wav}는 더 쌓이지 않으므로 증거 해석에 반드시 필요한 정보다.
     */
    public void setInputError(String reason) {
        set(srcLbl, reason == null ? "입력 끊김" : reason, STATE_FAIL);
        head("음향: 입력 끊김", STATE_FAIL);
        commit();
    }

    /** 대기 상태로 초기화. */
    public void reset() {
        running = false;
        passAtMs = null;
        head("음향: 대기", STATE_IDLE);
        clear(freqLbl);
        clear(waveLbl);
        clear(targetLbl);
        clear(energyLbl);
        clear(judgeLbl);
        clear(passAtLbl);
        clear(spanLbl);
        commit();
    }

    private String headText(double elapsedSec) {
        String t = String.format("%.1f s", Double.valueOf(elapsedSec));
        if (!running) {
            return passAtMs != null ? "음향: PASS @ " + passAtMs + " ms" : "음향: 대기";
        }
        return passAtMs != null
                ? "음향: PASS @ " + passAtMs + " ms · " + t
                : "음향: 측정 중 · " + t;
    }
}
