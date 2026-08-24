package com.suresofttech.apx.client.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.measure.MeasureEvidence;
import com.suresofttech.apx.core.measure.MeasureSession;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;

/**
 * 음향 모니터 - {@link AudioScope} 파형.
 * 초록 PASS 밴드는 블록 {@code match.isPass} 구간에 그린다.
 * <b>캡처 블록마다</b>{@link MeasureSession.Listener#onAudioTick}으로 갱신해
 * UI 폴링 간격보다 짧은 PASS도 밴드가 빠지지 않게 한다.
 *
 * <p>결과/증거 스냅샷: PASS 시작 → PASS 아닌 시점(밴드 종료)에 캡처. 중단 시 열린 밴드 flush.
 * <p>상단 라벨에 자체 판단 속도(ms)를 표시한다. 음정 추적 / 일치도 추이 UI는 제공하지 않는다.
 */
public class AudioMonitorView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.audioMonitor";

    private AudioScope scope;
    private Label statusLbl;
    private Display display;
    private MeasureSession.Listener tickListener;
    /** 직전 블록의 isPass - falling edge 스냅샷용. */
    private boolean prevBlockPass;

    /**
     * 파형 갱신 주기(ms) - 설정 화면({@code AudioMeasureBar.WAVE_POLL_MS})과 같은 방식.
     *
     * <p>예전에는 캡처 스레드의 블록 틱마다 {@code asyncExec} 로 파형을 밀어 넣었다.
     * UI 스레드가 바쁘면(비전 NCC / 캔버스 리페인트 등) 그 런너블이 큐에 쌓이고,
     * 나중에 실행될 때 <b>오래된 elapsedSec</b> 으로 <b>현재 버퍼</b>를 그리게 된다
     * - 타임스탬프와 데이터가 어긋나 파형이 끊기고 멈춘 것처럼 보였다.
     * 설정 화면이 멀쩡했던 이유가 여기에 있다(그쪽은 UI 스레드가 스스로 폴링한다).
     *
     * <p>이제 UI 스레드가 직접 주기적으로 버퍼와 경과시간을 <b>같은 시점에</b> 읽는다.
     * PASS 밴드는 정확도가 중요하므로 블록 틱을 그대로 쓴다(리빌드가 없어 가볍다).
     */
    private static final int WAVE_POLL_MS = 50;
    private boolean wavePolling;
    /** 리빌드 중첩 방지 - 설정 화면 {@code waveBusy} 와 동일. */
    private boolean waveBusy;

    /** 캡처 스레드가 남기는 최신 판정 - UI는 <b>낡은 값이 아니라 이것</b>을 읽는다. */
    private volatile MatchResult latestMatch;
    private volatile double latestElapsedSec;
    /** PASS 오버레이 UI 갱신 합침 - 블록마다 asyncExec 를 새로 만들지 않는다. */
    private volatile boolean passUiScheduled;
    /**
     * PASS 블록 구간 큐 - 합치기로 짧은 PASS가 사라지지 않게 한다.
     * (PASS 블록만 쌓이므로 큐가 커지지 않는다.)
     */
    private final java.util.List<double[]> pendingPassSpans =
            java.util.Collections.synchronizedList(new java.util.ArrayList<double[]>());

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        statusLbl = new Label(parent, SWT.WRAP);
        statusLbl.setText("음향: 대기");
        statusLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        scope = new AudioScope(parent, 5000.0);
        scope.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scope.setShowPitch(false);
        scope.setShowTrend(false);
        scope.setTickMs(AudioScope.DEFAULT_TICK_MS); // 설정 화면과 동일 1s 눈금
    }

    /** 측정 시작 시 Kickoff가 호출 - 기대 템플릿 / 그래프 초기화 + 블록 틱 구독. */
    public void onMeasureStarted(MeasureSession session) {
        if (scope == null || scope.isDisposed() || session == null) {
            return;
        }
        stopTickListener();
        prevBlockPass = false;
        latestMatch = null;
        latestElapsedSec = 0;
        pendingPassSpans.clear();
        scope.clear();
        double[] tmpl = session.getAudioTemplate();
        int sr = session.getAudioSampleRate();
        if (tmpl != null && sr > 0) {
            scope.setExpected(tmpl, sr);
        }
        startTickListener();
        startWavePoll();
        setStatusText("음향: 측정 중");
    }

    public void onMeasureStopped() {
        wavePolling = false;
        stopTickListener();
    }

    /**
     * 측정 중단 직전({@code session.stop} 전) - 아직 열린 PASS 밴드가 있으면 닫고 스냅샷.
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
                latestMatch = match;
                latestElapsedSec = elapsedSec;
                if (match != null && match.isPass) {
                    // PASS 블록은 구간을 큐에 남긴다 - UI 갱신을 합쳐도 누락되지 않는다
                    double nowMs = elapsedSec * 1000.0;
                    double gap = match.blockGapMs > 0 ? match.blockGapMs : 0;
                    pendingPassSpans.add(new double[] { Math.max(0, nowMs - gap), nowMs });
                }
                schedulePassUi();
            }

            public void onVisionMatch(RoiMatchResult result) {
            }

            public void onState(boolean audioPass, boolean visionPass, boolean overallPass) {
                if (display == null || display.isDisposed()) {
                    return;
                }
                display.asyncExec(new Runnable() {
                        public void run() {
                            refreshStatus();
                    }
                });
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

    private void refreshStatus() {
        if (statusLbl == null || statusLbl.isDisposed()) {
            return;
        }
        MeasureSession s = MeasureSession.get();
        if (s.isAudioPass()) {
            setStatusText(MeasureSession.formatPassLine("음향", s.getAudioPassMs(),
                s.getAudioJudgeMs(), s.getAudioGapMs(), s.getAudioAnalysisMs()));
        } else if (s.isRunning()) {
            setStatusText("음향: 측정 중");
        } else {
            setStatusText("음향: FAIL (기대음 미검출)");
        }
    }

    private void setStatusText(String text) {
        if (statusLbl != null && !statusLbl.isDisposed()) {
            statusLbl.setText(text);
        }
    }

    /** PASS 오버레이만 UI에 예약(합침). 파형 리빌드는 하지 않는다 - 설정 화면과 동일. */
    private void schedulePassUi() {
        if (passUiScheduled || display == null || display.isDisposed()) {
            return;
        }
        passUiScheduled = true;
        display.asyncExec(new Runnable() {
            public void run() {
                passUiScheduled = false;
                applyPassBand(latestMatch, latestElapsedSec);
            }
        });
    }

    /**
     * 초록 PASS 밴드 반영. 큐에 쌓인 PASS 블록을 먼저 적용해 짧은 PASS도 남긴다.
     * {@code updatePass} 는 리빌드를 하지 않으므로 {@code redraw()} 로 즉시 화면에 올린다
     * (밴드는 paintOverlays 가 캐시 이미지 위에 덧그린다).
     */
    private void applyPassBand(MatchResult mr, double elapsedSec) {
        if (scope == null || scope.isDisposed()) {
            return;
        }
        if (!MeasureSession.get().isRunning()) {
            return;
        }
        double[][] spans;
        synchronized (pendingPassSpans) {
            spans = pendingPassSpans.toArray(new double[pendingPassSpans.size()][]);
            pendingPassSpans.clear();
        }
        for (int i = 0; i < spans.length; i++) {
            scope.updatePass(spans[i][0], true);
            scope.updatePass(spans[i][1], true);
        }

        boolean pass = mr != null && mr.isPass;
        if (!pass) {
            scope.updatePass(elapsedSec * 1000.0, false);   // 밴드 닫기
        }
        if (prevBlockPass && !pass) {
            capturePassSpanToEvidence();
        }
        prevBlockPass = pass;
        scope.redraw();
    }

    /** UI 스레드 자체 폴링 - 설정 화면과 동일 방식. 재예약을 작업 앞에 둬 예외에도 루프가 죽지 않는다. */
    private void startWavePoll() {
        if (wavePolling) {
            return;
        }
        wavePolling = true;
        display.timerExec(WAVE_POLL_MS, new Runnable() {
            public void run() {
                if (!wavePolling || scope == null || scope.isDisposed()) {
                    wavePolling = false;
                    return;
                }
                display.timerExec(WAVE_POLL_MS, this);
                MeasureSession s = MeasureSession.get();
                if (!waveBusy && s.isRunning()) {
                    waveBusy = true;
                    try {
                        // 파형 프레임과 PASS를 같이 맞춘다(설정 화면과 동일)
                        applyPassBand(latestMatch, latestElapsedSec);
                        pushWave(s, s.getElapsedSec());
                    } finally {
                        waveBusy = false;
                    }
                }
            }
        });
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
