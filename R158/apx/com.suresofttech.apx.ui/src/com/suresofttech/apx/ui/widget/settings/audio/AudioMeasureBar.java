package com.suresofttech.apx.ui.widget.settings.audio;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

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
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.AudioRecorder;
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
    private static final int[] RECORD_SAMPLE_RATES = { 48000, 44100, 32000 };

    public static final class Cfg {
        public String measureText = "파형 측정";
        public String measuringText = "측정 중지";
        public String resetText = "초기화";
        public String recordText = "실차 기대음 녹음";
        public String recordingText = "녹음 중지/저장";
    }

    private final Display display;
    private final ApxSettings settings = ApxSettings.get();
    private final AudioCapture measureCapture = new AudioCapture();
    private final AudioRecorder expectedRecorder = new AudioRecorder();
    private final Cfg cfg;
    private final Button measureBtn;
    private final Button resetBtn;
    private final ApxSettings.Listener settingsListener;

    private Button recordBtn;
    private ExpectedTonePlayBar tonePlayBar;
    private Label verdictLabel;
    private AudioScope scope;
    private BeepMatcher matcher;
    private volatile MatchResult latestMatch;
    private volatile double latestElapsedSec;
    private volatile long capturedSamples;
    private String loadedPath;
    private boolean wavePolling;
    private volatile boolean recordingExpected;
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

        resetBtn = new Button(this, SWT.PUSH);
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
        measureCapture.setErrorListener(new AudioCapture.ErrorListener() {
            public void onCaptureError(final String reason) {
                display.asyncExec(new Runnable() {
                    public void run() {
                        if (!isDisposed()) {
                            recordingExpected = false;
                            measureBtn.setSelection(false);
                            measureBtn.setText(AudioMeasureBar.this.cfg.measureText);
                            if (recordBtn != null && !recordBtn.isDisposed()) {
                                recordBtn.setSelection(false);
                                recordBtn.setText(AudioMeasureBar.this.cfg.recordText);
                                recordBtn.setToolTipText(reason);
                            }
                            updateCaptureControls();
                            updateVerdict(null);
                        }
                    }
                });
            }
        });
        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                wavePolling = false;
                recordingExpected = false;
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

    /** 파형 아래에 현재 PASS/FAIL과 주파수·파형 일치율을 표시한다. */
    public void addVerdictLabel(Composite parent) {
        if (verdictLabel != null && !verdictLabel.isDisposed()) {
            return;
        }
        verdictLabel = new Label(parent, SWT.NONE);
        verdictLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        updateVerdict(latestMatch);
    }

    /**
     * 액션 행 마지막에 기대음 녹음 버튼을 추가한다. 마이크 입력 전체를 WAV로 저장하고
     * 저장 직후 {@link ApxSettings#setExpectedWavPath(String)}로 기대음에 적용한다.
     */
    public void enableExpectedRecording() {
        if (recordBtn != null && !recordBtn.isDisposed()) {
            return;
        }
        Object currentLayout = getLayout();
        if (currentLayout instanceof GridLayout) {
            ((GridLayout) currentLayout).numColumns = 4;
        }
        org.eclipse.swt.widgets.Control[] children = getChildren();
        for (int i = 0; i < children.length; i++) {
            if (children[i] instanceof ExpectedTonePlayBar) {
                tonePlayBar = (ExpectedTonePlayBar) children[i];
                break;
            }
        }
        recordBtn = new Button(this, SWT.TOGGLE);
        recordBtn.setText(cfg.recordText);
        recordBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        recordBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (recordBtn.getSelection()) {
                    if (!startExpectedRecording()) {
                        recordBtn.setSelection(false);
                    }
                } else {
                    finishExpectedRecording();
                }
            }
        });
        updateCaptureControls();
        layout(true, true);
    }

    private boolean loadExpectedWav(boolean announceError) {
        String p = settings.getExpectedWavPath();
        if (p == null || p.isEmpty() || !new File(p).isFile()) {
            matcher = null;
            loadedPath = null;
            if (scope != null && !scope.isDisposed()) {
                scope.clear();
            }
            updateVerdict(null);
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
            updateCaptureControls();
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
            updateVerdict(null);
            return true;
        } catch (Exception ex) {
            matcher = null;
            loadedPath = null;
            updateVerdict(null);
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
            if (recordingExpected) {
                measureBtn.setSelection(false);
                return;
            }
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
                updateCaptureControls();
                updateVerdict(latestMatch);
            } catch (Exception ex) {
                measureBtn.setSelection(false);
                measureBtn.setText(cfg.measureText);
                updateCaptureControls();
                updateVerdict(null);
            }
        } else {
            measureCapture.stop();
            measureBtn.setText(cfg.measureText);
            updateCaptureControls();
            updateVerdict(latestMatch);
        }
    }

    private boolean startExpectedRecording() {
        if (measureCapture.isRunning()) {
            measureCapture.stop();
            measureBtn.setSelection(false);
            measureBtn.setText(cfg.measureText);
        }
        if (tonePlayBar != null && !tonePlayBar.isDisposed()) {
            tonePlayBar.stopPlayback();
        }
        AudioCapture.Device dev = AudioCapture.findInputDevice(settings.getMicName());
        if (dev == null) {
            setRecordMessage("마이크 입력 장치를 선택하세요.");
            return false;
        }
        settings.setMicName(dev.name);
        Exception last = null;
        for (int i = 0; i < RECORD_SAMPLE_RATES.length; i++) {
            final int rate = RECORD_SAMPLE_RATES[i];
            for (int channels = 1; channels <= 2; channels++) {
                final int channelCount = channels;
                expectedRecorder.start(rate);
                try {
                    measureCapture.start(dev.info, rate, channelCount,
                            new AudioCapture.BlockListener() {
                        public void onBlock(double[] block, double now) {
                            expectedRecorder.feed(block);
                        }
                    });
                    recordingExpected = true;
                    recordBtn.setText(cfg.recordingText);
                    setRecordMessage("기대음 녹음 중: " + rate + " Hz / "
                            + channelCount + "ch→mono");
                    updateCaptureControls();
                    updateVerdict(null);
                    return true;
                } catch (Exception ex) {
                    last = ex;
                    measureCapture.stop();
                }
            }
        }
        String reason = last == null || last.getMessage() == null
                ? "지원 형식 없음" : last.getMessage();
        setRecordMessage("마이크 입력을 열 수 없습니다: " + reason);
        recordingExpected = false;
        updateCaptureControls();
        updateVerdict(null);
        return false;
    }

    private void finishExpectedRecording() {
        if (!recordingExpected) {
            updateCaptureControls();
            return;
        }
        recordingExpected = false;
        measureCapture.stop();
        expectedRecorder.stop();
        double[] samples = expectedRecorder.getSamples();
        int rate = expectedRecorder.getSampleRate();
        updateCaptureControls();
        updateVerdict(null);

        double durationMs = samples.length * 1000.0 / Math.max(1, rate);
        if (durationMs < 100.0 || maxAbs(samples) < 1e-6) {
            setRecordMessage("녹음이 너무 짧거나 마이크 신호가 없습니다.");
            return;
        }

        FileDialog dialog = new FileDialog(getShell(), SWT.SAVE);
        dialog.setText("실차 기대음 WAV 저장");
        dialog.setFilterExtensions(new String[] { "*.wav" });
        dialog.setFilterNames(new String[] { "WAV (*.wav)" });
        dialog.setFileName("audio-reference-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".wav");
        dialog.setOverwrite(true);
        String path = dialog.open();
        if (path == null || path.isEmpty()) {
            setRecordMessage("기대음 WAV 저장을 취소했습니다.");
            return;
        }
        if (!path.toLowerCase().endsWith(".wav")) {
            path += ".wav";
        }
        try {
            WavIo.save(path, samples, rate);
            settings.setExpectedWavPath(path);
            setRecordMessage("기대음 WAV 저장 및 적용 완료: " + new File(path).getName());
        } catch (Exception ex) {
            setRecordMessage("기대음 WAV 저장 실패: " + safeMessage(ex));
        }
    }

    private void updateCaptureControls() {
        boolean measuring = measureCapture.isRunning() && !recordingExpected;
        measureBtn.setEnabled(!recordingExpected);
        resetBtn.setEnabled(!recordingExpected);
        if (recordBtn != null && !recordBtn.isDisposed()) {
            recordBtn.setEnabled(!measuring || recordingExpected);
            recordBtn.setSelection(recordingExpected);
            recordBtn.setText(recordingExpected ? cfg.recordingText : cfg.recordText);
        }
        if (tonePlayBar != null && !tonePlayBar.isDisposed()) {
            tonePlayBar.setEnabled(!recordingExpected);
        }
    }

    private void setRecordMessage(String message) {
        if (recordBtn != null && !recordBtn.isDisposed()) {
            recordBtn.setToolTipText(message);
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
        updateVerdict(mr);
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
        if (recordingExpected) {
            return;
        }
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
        updateVerdict(null);
    }

    private void updateVerdict(MatchResult result) {
        if (verdictLabel == null || verdictLabel.isDisposed()) {
            return;
        }
        if (recordingExpected) {
            verdictLabel.setText("판정: 기대음 녹음 중");
        } else if (matcher == null) {
            verdictLabel.setText("판정: 기대 음향 없음");
        } else if (!measureCapture.isRunning()) {
            verdictLabel.setText("판정: 대기");
        } else if (result == null) {
            verdictLabel.setText("판정: 비교 데이터 대기");
        } else {
            verdictLabel.setText(String.format("판정: %s / 주파수 %.1f%% / 파형 %.1f%%",
                    result.isPass ? "PASS" : "FAIL",
                    result.freqSim * 100.0, result.waveSim * 100.0));
        }
    }

    private static double maxAbs(double[] samples) {
        double max = 0;
        for (int i = 0; i < samples.length; i++) {
            max = Math.max(max, Math.abs(samples[i]));
        }
        return max;
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
