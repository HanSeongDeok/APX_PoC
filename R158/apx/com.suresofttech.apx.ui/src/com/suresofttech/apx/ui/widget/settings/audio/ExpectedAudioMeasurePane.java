package com.suresofttech.apx.ui.widget.settings.audio;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.BeepMatcher;
import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.ui.widget.AudioScope;

/**
 * AudioScope + 파형 측정/초기화 — matcher·capture·틱 내장.
 * 기대음 재생은 {@link ExpectedTonePlayBar}로 분리.
 */
public class ExpectedAudioMeasurePane extends Composite {

    private final Display display;
    private final ApxSettings settings = ApxSettings.get();
    private final AudioCapture measureCapture = new AudioCapture();
    private final Composite actionRow;
    private final Button measureBtn;
    private final AudioScope scope;
    private final ApxSettings.Listener settingsListener;

    private BeepMatcher matcher;
    private volatile MatchResult latestMatch;
    private volatile long capturedSamples;
    private String loadedPath;
    private Runnable beforeMeasureStart;
    private MicDeviceProvider micProvider;
    private boolean tickPolling;

    public ExpectedAudioMeasurePane(Composite parent) {
        super(parent, SWT.NONE);
        display = getDisplay();
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // 3열 — 이솝/통짜가 3번째에 ExpectedTonePlayBar를 붙일 수 있다.
        actionRow = new Composite(this, SWT.NONE);
        actionRow.setLayout(new GridLayout(3, true));
        actionRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        measureBtn = new Button(actionRow, SWT.TOGGLE);
        measureBtn.setText("파형 측정");
        measureBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        measureBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                toggleMeasure(measureBtn.getSelection());
            }
        });

        Button resetBtn = new Button(actionRow, SWT.PUSH);
        resetBtn.setText("초기화");
        resetBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        resetBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                resetMeasure();
            }
        });

        scope = new AudioScope(this, 5000.0);
        scope.setShowPitch(false);
        scope.setShowTrend(false);
        GridData scopeGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        scopeGd.minimumHeight = 180;
        scope.setLayoutData(scopeGd);

        settingsListener = new ApxSettings.Listener() {
            public void onSettingsChanged(ApxSettings s) {
                if (isDisposed()) {
                    return;
                }
                display.asyncExec(new Runnable() {
                    public void run() {
                        if (isDisposed()) {
                            return;
                        }
                        String p = settings.getExpectedWavPath();
                        if (p == null || !p.equals(loadedPath)) {
                            loadExpectedWav(false);
                        } else {
                            applyMatcherThresholds();
                        }
                    }
                });
            }
        };
        settings.addListener(settingsListener);
        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                tickPolling = false;
                measureCapture.stop();
                settings.removeListener(settingsListener);
            }
        });

        loadExpectedWav(false);
        startMeasureTick();
    }


    public void setBeforeMeasureStart(Runnable r) {
        this.beforeMeasureStart = r;
    }

    public void setMicDeviceProvider(MicDeviceProvider provider) {
        this.micProvider = provider;
    }

    /** 측정/초기화 옆(3열)에 재생 바 등을 붙일 때 사용. */
    public Composite getActionRow() {
        return actionRow;
    }

    /** 마이크 테스트가 장치를 쓰기 전 — 측정 일시정지(재개 가능). */
    public void pauseForExclusive() {
        if (measureCapture.isRunning()) {
            measureCapture.stop();
            if (measureBtn != null && !measureBtn.isDisposed()) {
                measureBtn.setSelection(false);
                measureBtn.setText("파형 측정");
            }
            msg("측정 일시정지 — 마이크 테스트와 장치 충돌 방지");
        }
    }

    private boolean loadExpectedWav(boolean announceError) {
        String p = settings.getExpectedWavPath();
        if (p == null || p.isEmpty() || !new File(p).isFile()) {
            matcher = null;
            loadedPath = null;
            if (scope != null && !scope.isDisposed()) {
                scope.clear();
            }
            return false;
        }
        if (p.equals(loadedPath) && matcher != null) {
            applyMatcherThresholds();
            return true;
        }
        boolean wasMeasuring = measureCapture.isRunning();
        if (wasMeasuring) {
            measureCapture.stop();
            if (measureBtn != null && !measureBtn.isDisposed()) {
                measureBtn.setSelection(false);
                measureBtn.setText("파형 측정");
            }
        }
        try {
            WavIo.Wav wav = WavIo.load(p);
            loadedPath = p;
            matcher = new BeepMatcher(wav.samples, wav.sampleRate, 150.0, 4.0,
                    settings.getAudioFreqThr(), settings.getAudioWaveThr(), 0.015);
            capturedSamples = 0;
            latestMatch = null;
            if (scope != null && !scope.isDisposed()) {
                scope.clear();
                scope.setExpected(matcher.getTemplate(), wav.sampleRate);
            }
            return true;
        } catch (Exception ex) {
            matcher = null;
            loadedPath = null;
            if (announceError) {
                msg("기대음 로드 실패: " + ex.getMessage());
            }
            return false;
        }
    }

    private void applyMatcherThresholds() {
        if (matcher != null) {
            matcher.setFreqThr(settings.getAudioFreqThr());
            matcher.setWaveThr(settings.getAudioWaveThr());
        }
    }

    private void toggleMeasure(boolean on) {
        if (on) {
            if (!loadExpectedWav(true)) {
                measureBtn.setSelection(false);
                msg("기대 경고음 .wav를 먼저 등록하세요.");
                return;
            }
            if (beforeMeasureStart != null) {
                beforeMeasureStart.run();
            }
            AudioCapture.Device dev = micProvider != null ? micProvider.selectedDevice() : null;
            if (dev == null) {
                measureBtn.setSelection(false);
                msg("마이크가 없습니다");
                return;
            }
            boolean fresh = (capturedSamples == 0);
            matcher.arm();
            applyMatcherThresholds();
            if (fresh) {
                latestMatch = null;
            }
            try {
                measureCapture.start(dev.info, matcher.getSampleRate(), new AudioCapture.BlockListener() {
                    public void onBlock(double[] block, double now) {
                        capturedSamples += block.length;
                        double t = capturedSamples / (double) matcher.getSampleRate();
                        latestMatch = matcher.feed(block, t);
                    }
                });
                measureBtn.setText("측정 중지");
                msg(fresh ? "파형 측정 중…" : "파형 측정 재개…");
            } catch (Exception ex) {
                measureBtn.setSelection(false);
                measureBtn.setText("파형 측정");
                msg("마이크 열기 실패: " + ex.getMessage());
            }
        } else {
            measureCapture.stop();
            measureBtn.setText("파형 측정");
            msg("측정 일시정지 — 다시 누르면 이어서 측정");
        }
    }

    private void resetMeasure() {
        if (matcher != null) {
            matcher.arm();
        }
        latestMatch = null;
        capturedSamples = 0;
        if (scope != null && !scope.isDisposed()) {
            scope.clear();
            if (matcher != null) {
                scope.setExpected(matcher.getTemplate(), matcher.getSampleRate());
            }
        }
        if (measureCapture.isRunning()) {
            msg("파형 측정 초기화 — 계속 측정 중");
        } else {
            if (measureBtn != null && !measureBtn.isDisposed()) {
                measureBtn.setSelection(false);
                measureBtn.setText("파형 측정");
            }
            msg("파형 측정 초기화");
        }
    }

    private void startMeasureTick() {
        tickPolling = true;
        display.timerExec(60, new Runnable() {
            public void run() {
                if (!tickPolling || isDisposed() || scope == null || scope.isDisposed()) {
                    return;
                }
                if (measureCapture.isRunning() && matcher != null) {
                    int sr = matcher.getSampleRate();
                    double elapsedSec = capturedSamples / (double) sr;
                    double elapsedMs = elapsedSec * 1000.0;
                    scope.setData(matcher.getBuffer(), sr, matcher.getTargetFreq(), elapsedSec);
                    MatchResult mr = latestMatch;
                    if (mr != null) {
                        scope.updatePass(elapsedMs, mr.isPass);
                        scope.setMatchTrend(mr.freqSim, mr.waveSim, mr.freqThr, mr.waveThr, elapsedSec);
                        if (mr.isPass) {
                            msg(String.format("일치 → PASS  ·  주파수 %.0f%% / 파형 %.0f%%",
                                    mr.freqSim * 100, mr.waveSim * 100));
                        } else if (mr.hasSound) {
                            msg(String.format("불일치 → FAIL  ·  주파수 %.0f%% / 파형 %.0f%%",
                                    mr.freqSim * 100, mr.waveSim * 100));
                        } else {
                            msg("파형 측정 중… (소리 대기)");
                        }
                    }
                }
                if (tickPolling && !isDisposed()) {
                    display.timerExec(60, this);
                }
            }
        });
    }

    private void msg(String m) {
        // 상태 표시 제거(미니멀)
    }
}
