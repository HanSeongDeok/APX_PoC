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
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;

/**
 * 음향 모니터 — {@link AudioScope}만 (설정과 동일: 파형만).
 * 초록 PASS 밴드는 블록 {@code match.isPass} 구간에만 그린다.
 * 결과/증거 스냅샷: PASS 시작 → PASS 아닌 시점(밴드 종료)에 캡처. 중단 시 열린 밴드 flush.
 */
public class AudioMonitorView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.audioMonitor";

    private static final int POLL_MS = 80;

    private AudioScope scope;
    private Display display;
    private boolean tickPolling;
    /** 직전 폴링의 블록 PASS — falling edge 스냅샷용. */
    private boolean prevBlockPass;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        scope = new AudioScope(parent, 5000.0);
        scope.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scope.setShowPitch(false);
        scope.setShowTrend(false);
    }

    /** 측정 시작 시 Kickoff가 호출 — 기대 템플릿·그래프 초기화 + 폴링 시작. */
    public void onMeasureStarted(MeasureSession session) {
        if (scope == null || scope.isDisposed() || session == null) {
            return;
        }
        prevBlockPass = false;
        scope.clear();
        double[] tmpl = session.getAudioTemplate();
        int sr = session.getAudioSampleRate();
        if (tmpl != null && sr > 0) {
            scope.setExpected(tmpl, sr);
        }
        startPolling();
    }

    public void onMeasureStopped() {
        tickPolling = false;
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
            pushScope(s, false); // 밴드 닫기 + 리빌드
            prevBlockPass = false;
        }
        // PASS 밴드가 한 번도 없으면 찍지 않음(빈 파형만 Result에 올리는 것 방지)
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

    private void startPolling() {
        tickPolling = true;
        display.timerExec(POLL_MS, new Runnable() {
            public void run() {
                if (!tickPolling || scope == null || scope.isDisposed()) {
                    return;
                }
                pollScope();
                if (tickPolling && !scope.isDisposed()) {
                    display.timerExec(POLL_MS, this);
                }
            }
        });
    }

    private void pollScope() {
        MeasureSession s = MeasureSession.get();
        if (!s.isRunning()) {
            return;
        }
        MatchResult match = s.getLatestMatch();
        // 초록 밴드 = 블록 isPass만 (세션 latch와 분리)
        boolean blockPass = match != null && match.isPass;
        pushScope(s, blockPass);

        // PASS 구간 종료 시점 → 결과/증거 스냅샷 (밴드가 다 그려진 상태)
        if (prevBlockPass && !blockPass) {
            capturePassSpanToEvidence();
        }
        prevBlockPass = blockPass;
    }

    private void pushScope(MeasureSession s, boolean passBand) {
        int sr = s.getAudioSampleRate();
        double[] waveBuf = s.getWaveBuffer();
        if (sr <= 0 || waveBuf == null) {
            return;
        }
        double elapsedSec = s.getElapsedSec();
        scope.updatePass(elapsedSec * 1000.0, passBand);
        scope.setData(waveBuf, sr, s.getTargetFreq(), elapsedSec);
    }

    private void capturePassSpanToEvidence() {
        MeasureEvidence ev = MeasureSession.get().getEvidence();
        if (ev == null || scope == null || scope.isDisposed()) {
            return;
        }
        byte[] png = scope.capturePng();
        if (png != null) {
            ev.putAudioPng(png); // 최초 PASS 구간만 보관(ResultView·wave_center)
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
        tickPolling = false;
        super.dispose();
    }
}
