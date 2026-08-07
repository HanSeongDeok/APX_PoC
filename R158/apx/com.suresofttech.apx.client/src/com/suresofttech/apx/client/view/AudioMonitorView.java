package com.suresofttech.apx.client.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.measure.MeasureEvidence;
import com.suresofttech.apx.core.measure.MeasureSession;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;

/**
 * 음향 모니터 — {@link AudioScope} 파형.
 * 초록 PASS 밴드는 블록 {@code match.isPass} 구간에 그린다.
 * <b>캡처 블록마다</b>{@link MeasureSession.Listener#onAudioTick}으로 갱신해
 * UI 폴링 간격보다 짧은 PASS도 밴드가 빠지지 않게 한다.
 *
 * <p>결과/증거 스냅샷: PASS 시작 → PASS 아닌 시점(밴드 종료)에 캡처. 중단 시 열린 밴드 flush.
 * <p>음정 추적·일치도 추이·판독값 UI는 제공하지 않는다(파형만).
 */
public class AudioMonitorView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.audioMonitor";

    private AudioScope scope;
    private Display display;
    private MeasureSession.Listener tickListener;
    /** 직전 블록의 isPass — falling edge 스냅샷용. */
    private boolean prevBlockPass;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        scope = new AudioScope(parent, 5000.0);
        scope.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scope.setShowPitch(false);
        scope.setShowTrend(false);
        scope.setTickMs(AudioScope.DEFAULT_TICK_MS); // 설정 화면과 동일 1s 눈금
    }

    /** 측정 시작 시 Kickoff가 호출 — 기대 템플릿·그래프 초기화 + 블록 틱 구독. */
    public void onMeasureStarted(MeasureSession session) {
        if (scope == null || scope.isDisposed() || session == null) {
            return;
        }
        stopTickListener();
        prevBlockPass = false;
        scope.clear();
        double[] tmpl = session.getAudioTemplate();
        int sr = session.getAudioSampleRate();
        if (tmpl != null && sr > 0) {
            scope.setExpected(tmpl, sr);
        }
        startTickListener();
    }

    public void onMeasureStopped() {
        stopTickListener();
    }

    /**
     * 측정 중단 직전({@code session.stop} 전) — 아직 열린 PASS 밴드가 있으면 닫고 스냅샷.
     * falling edge에서 이미 찍었으면 {@link MeasureEvidence#putAudioPng}가 최초만 보관.
     */
    public void flushPassSpanSnapshotIfNeeded() {
        if (scope == null || scope.isDisposed()) {
            return;
        }
        MeasureSession s = MeasureSession.get();
        boolean hadOpen = prevBlockPass;
        if (prevBlockPass) {
            double nowMs = s.getElapsedSec() * 1000.0;
            scope.updatePass(nowMs, false);
            prevBlockPass = false;
            // 파형 한 번 더 커밋
            pushWave(s, s.getElapsedSec());
        }
        boolean hadSpan = !scope.getPassSpans().isEmpty() || hadOpen || s.isAudioPass();
        if (hadSpan) {
            capturePassSpanToEvidence();
        }
    }

    public AudioScope getScope() {
        return scope;
    }

    public byte[] capturePng() {
        return scope == null || scope.isDisposed() ? null : scope.capturePng();
    }

    private void startTickListener() {
        tickListener = new MeasureSession.Listener() {
            public void onAudioTick(final MatchResult match, final double[] waveBuf,
                    final double elapsedSec) {
                if (display == null || display.isDisposed()) {
                    return;
                }
                display.asyncExec(new Runnable() {
                    public void run() {
                        applyAudioTick(match, waveBuf, elapsedSec);
                    }
                });
            }

            public void onVisionMatch(RoiMatchResult result) {
            }

            public void onState(boolean audioPass, boolean visionPass, boolean overallPass) {
            }
        };
        MeasureSession.get().addListener(tickListener);
    }

    private void stopTickListener() {
        if (tickListener != null) {
            MeasureSession.get().removeListener(tickListener);
            tickListener = null;
        }
    }

    /**
     * 캡처 스레드가 넘긴 블록 결과 — isPass면 그 블록 구간 전체를 초록 밴드로 덮는다.
     * (폴링보다 촘촘해서 짧은 PASS 누락 없음. 세션 latch로 밴드를 붙들어 두지 않음.)
     */
    private void applyAudioTick(MatchResult match, double[] waveBuf, double elapsedSec) {
        if (scope == null || scope.isDisposed()) {
            return;
        }
        MeasureSession s = MeasureSession.get();
        if (!s.isRunning()) {
            return;
        }
        boolean passBand = match != null && match.isPass;
        double nowMs = elapsedSec * 1000.0;
        if (passBand) {
            // 블록 시작~끝까지 밴드 — 한 블록짜리 PASS도 폭이 있게 남는다
            double gap = match.blockGapMs > 0 ? match.blockGapMs : 0;
            if (gap > 0) {
                scope.updatePass(Math.max(0, nowMs - gap), true);
            }
            scope.updatePass(nowMs, true);
        } else {
            scope.updatePass(nowMs, false);
        }

        int sr = s.getAudioSampleRate();
        if (sr > 0) {
            // 캡처 스레드 버퍼와 경합 방지 — 폴링 때와 같이 복사본 사용
            double[] wave = s.getWaveBuffer();
            if (wave != null) {
                scope.setData(wave, sr, s.getTargetFreq(), elapsedSec);
            }
        }

        if (prevBlockPass && !passBand) {
            capturePassSpanToEvidence();
        }
        prevBlockPass = passBand;
    }

    private void pushWave(MeasureSession s, double elapsedSec) {
        int sr = s.getAudioSampleRate();
        double[] waveBuf = s.getWaveBuffer();
        if (sr <= 0 || waveBuf == null) {
            return;
        }
        scope.setData(waveBuf, sr, s.getTargetFreq(), elapsedSec);
    }

    private void capturePassSpanToEvidence() {
        MeasureEvidence ev = MeasureSession.get().getEvidence();
        if (ev == null || scope == null || scope.isDisposed()) {
            return;
        }
        byte[] png = scope.capturePng();
        if (png != null) {
            ev.putAudioPng(png);
        }
    }

    @Override
    public void setFocus() {
        if (scope != null && !scope.isDisposed()) {
            scope.setFocus();
        }
    }

    @Override
    public void dispose() {
        stopTickListener();
        super.dispose();
    }
}
