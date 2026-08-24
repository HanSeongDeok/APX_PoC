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

/**
 * 파형 측정/초기화 버튼 행 - matcher / capture 내장.
 * 스코프는 {@link #setScope(AudioScope)}로 주입. 기대음 재생은 {@link ExpectedTonePlayBar}.
 *
 * <p>설정 다이얼로그는 모니터와 <b>구동만</b> 다르다(위젯 {@link AudioScope}는 동일):
 * <ul>
 *   <li>PASS 밴드 - 캡처 블록마다 오버레이만(가벼움 → 실시간)</li>
 *   <li>파형 - {@link #WAVE_POLL_MS} 폴링으로 ChartDirector 리빌드
 *       (블록마다 setData 하면 설정 UI가 멈춰 파형이 안 움직임)</li>
 * </ul>
 * 모니터({@code AudioMonitorView})는 뷰가 단순해 블록마다 setData 해도 버틴다.
 */
public class AudioMeasureBar extends Composite {

    /** 파형 ChartDirector 리빌드 주기. 블록(~46ms)마다 돌리면 설정 창이 죽는다. */
    private static final int WAVE_POLL_MS = 50;

    public static final class Cfg {
        public String measureText = "파형 측정";
        public String measuringText = "측정 중지";
        public String resetText = "초기화";
    }

    private final Display display;
    private final ApxSettings settings = ApxSettings.get();
    private final AudioCapture measureCapture = new AudioCapture();
    private final Cfg cfg;
    private final Button measureBtn;
    private final ApxSettings.Listener settingsListener;

    private AudioScope scope;
    private BeepMatcher matcher;
    private volatile MatchResult latestMatch;
    private volatile double latestElapsedSec;
    private volatile long capturedSamples;
    private String loadedPath;
    private boolean wavePolling;
    /** PASS UI 갱신 합치기 - 블록마다 asyncExec 폭주 방지. */
    private volatile boolean passUiScheduled;
    /** 파형 리빌드 중이면 다음 폴링 스킵(큐 적체 방지). */
    private boolean waveBusy;

    public AudioMeasureBar(Composite parent) {
        this(parent, new Cfg());
    }

    public AudioMeasureBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        this.cfg = (cfg != null) ? cfg : new Cfg();
        display = getDisplay();
        setLayout(new GridLayout(3, true));
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        measureBtn = new Button(this, SWT.TOGGLE);
        measureBtn.setText(this.cfg.measureText);
        measureBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        measureBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                toggleMeasure(measureBtn.getSelection());
            }
        });

        Button resetBtn = new Button(this, SWT.PUSH);
        resetBtn.setText(this.cfg.resetText);
        resetBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        resetBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                resetMeasure();
            }
        });

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
                wavePolling = false;
                measureCapture.stop();
                settings.removeListener(settingsListener);
            }
        });

        loadExpectedWav(false);
        startWavePoll();
    }

    public void setScope(AudioScope scope) {
        this.scope = scope;
        if (scope != null && !scope.isDisposed() && matcher != null) {
            scope.clear();
            scope.setExpected(matcher.getTemplate(), matcher.getSampleRate());
        }
    }

    public Composite getActionRow() {
        return this;
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
                measureBtn.setText(cfg.measureText);
            }
        }
        try {
            WavIo.Wav wav = WavIo.load(p);
            loadedPath = p;
            matcher = new BeepMatcher(wav.samples, wav.sampleRate, 150.0, 4.0,
                    settings.getAudioFreqThr(), settings.getAudioWaveThr(), 0.015);
            capturedSamples = 0;
            latestMatch = null;
            latestElapsedSec = 0;
            if (scope != null && !scope.isDisposed()) {
                scope.clear();
                scope.setExpected(matcher.getTemplate(), wav.sampleRate);
            }
            return true;
        } catch (Exception ex) {
            matcher = null;
            loadedPath = null;
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
                return;
            }
            AudioCapture.Device dev = AudioCapture.findInputDevice(settings.getMicName());
            if (dev == null) {
                measureBtn.setSelection(false);
                return;
            }
            settings.setMicName(dev.name);
            boolean fresh = (capturedSamples == 0);
            matcher.arm();
            applyMatcherThresholds();
            if (fresh) {
                latestMatch = null;
                latestElapsedSec = 0;
            }
            try {
                measureCapture.start(dev.info, matcher.getSampleRate(), new AudioCapture.BlockListener() {
                    public void onBlock(double[] block, double now) {
                        capturedSamples += block.length;
                        final double t = capturedSamples / (double) matcher.getSampleRate();
                        latestMatch = matcher.feed(block, t);
                        latestElapsedSec = t;
                        schedulePassUi();
                    }
                });
                measureBtn.setText(cfg.measuringText);
            } catch (Exception ex) {
                measureBtn.setSelection(false);
                measureBtn.setText(cfg.measureText);
            }
        } else {
            measureCapture.stop();
            measureBtn.setText(cfg.measureText);
        }
    }

    /** PASS 오버레이만 UI에 예약(합침). ChartDirector setData는 하지 않음. */
    private void schedulePassUi() {
        if (passUiScheduled) {
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

    private void applyPassBand(MatchResult mr, double elapsedSec) {
        if (isDisposed() || scope == null || scope.isDisposed()) {
            return;
        }
        if (!measureCapture.isRunning()) {
            return;
        }
        double nowMs = elapsedSec * 1000.0;
        boolean pass = mr != null && mr.isPass;
        if (pass) {
            double gap = mr.blockGapMs > 0 ? mr.blockGapMs : 0;
            if (gap > 0) {
                scope.updatePass(Math.max(0, nowMs - gap), true);
            }
            scope.updatePass(nowMs, true);
        } else {
            scope.updatePass(nowMs, false);
        }
    }

    /** 파형만 주기적 리빌드 - 한 번에 하나만, 끝나면 다음 예약. */
    private void startWavePoll() {
        wavePolling = true;
        display.timerExec(WAVE_POLL_MS, new Runnable() {
            public void run() {
                if (!wavePolling || isDisposed()) {
                    return;
                }
                if (!waveBusy && measureCapture.isRunning() && matcher != null
                        && scope != null && !scope.isDisposed()) {
                    waveBusy = true;
                    try {
                        int sr = matcher.getSampleRate();
                        if (sr > 0) {
                            double elapsedSec = capturedSamples / (double) sr;
                            // 파형 프레임과 PASS를 같이 맞춤(폴링 직전 최신 판정)
                            applyPassBand(latestMatch, latestElapsedSec > 0
                                    ? latestElapsedSec : elapsedSec);
                            double[] wave = matcher.getBuffer().clone();
                            scope.setData(wave, sr, matcher.getTargetFreq(), elapsedSec);
                        }
                    } finally {
                        waveBusy = false;
                    }
                }
                if (wavePolling && !isDisposed()) {
                    display.timerExec(WAVE_POLL_MS, this);
                }
            }
        });
    }

    private void resetMeasure() {
        if (matcher != null) {
            matcher.arm();
        }
        latestMatch = null;
        latestElapsedSec = 0;
        capturedSamples = 0;
        if (scope != null && !scope.isDisposed()) {
            scope.clear();
            if (matcher != null) {
                scope.setExpected(matcher.getTemplate(), matcher.getSampleRate());
            }
        }
        if (!measureCapture.isRunning() && measureBtn != null && !measureBtn.isDisposed()) {
            measureBtn.setSelection(false);
            measureBtn.setText(cfg.measureText);
        }
    }
}
