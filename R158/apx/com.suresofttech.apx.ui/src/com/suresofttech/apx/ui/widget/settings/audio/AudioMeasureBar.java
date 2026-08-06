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
 * 파형 측정/초기화 버튼 행 — matcher·capture·틱 내장.
 * 스코프는 {@link #setScope(AudioScope)}로 주입. 기대음 재생은 {@link ExpectedTonePlayBar}.
 */
public class AudioMeasureBar extends Composite {

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
    private volatile long capturedSamples;
    private String loadedPath;
    private boolean tickPolling;

    public AudioMeasureBar(Composite parent) {
        this(parent, new Cfg());
    }

    public AudioMeasureBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        this.cfg = (cfg != null) ? cfg : new Cfg();
        display = getDisplay();
        // 3열 — 3번째에 ExpectedTonePlayBar를 붙일 수 있다.
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
                tickPolling = false;
                measureCapture.stop();
                settings.removeListener(settingsListener);
            }
        });

        loadExpectedWav(false);
        startMeasureTick();
    }

    /**
     * 파형 그래프 주입 — {@link AudioScope}를 넘겨야 그래프가 구동된다.
     * 주입하지 않으면 이 바는 <b>버튼만</b> 표시하고 그래프는 나오지 않는다.
     */
    public void setScope(AudioScope scope) {
        this.scope = scope;
        if (scope != null && !scope.isDisposed() && matcher != null) {
            scope.clear();
            scope.setExpected(matcher.getTemplate(), matcher.getSampleRate());
        }
    }

    /** 이 바 자신(3열) — ExpectedTonePlayBar를 세 번째 칸에 붙일 때 사용. */
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
            }
            try {
                measureCapture.start(dev.info, matcher.getSampleRate(), new AudioCapture.BlockListener() {
                    public void onBlock(double[] block, double now) {
                        capturedSamples += block.length;
                        double t = capturedSamples / (double) matcher.getSampleRate();
                        latestMatch = matcher.feed(block, t);
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
        if (!measureCapture.isRunning() && measureBtn != null && !measureBtn.isDisposed()) {
            measureBtn.setSelection(false);
            measureBtn.setText(cfg.measureText);
        }
    }

    private void startMeasureTick() {
        tickPolling = true;
        display.timerExec(60, new Runnable() {
            public void run() {
                if (!tickPolling || isDisposed()) {
                    return;
                }
                if (measureCapture.isRunning() && matcher != null
                        && scope != null && !scope.isDisposed()) {
                    int sr = matcher.getSampleRate();
                    double elapsedSec = capturedSamples / (double) sr;
                    double elapsedMs = elapsedSec * 1000.0;
                    scope.setData(matcher.getBuffer(), sr, matcher.getTargetFreq(), elapsedSec);
                    MatchResult mr = latestMatch;
                    if (mr != null) {
                        scope.updatePass(elapsedMs, mr.isPass);
                        scope.setMatchTrend(mr.freqSim, mr.waveSim, mr.freqThr, mr.waveThr, elapsedSec);
                    }
                }
                if (tickPolling && !isDisposed()) {
                    display.timerExec(60, this);
                }
            }
        });
    }
}
