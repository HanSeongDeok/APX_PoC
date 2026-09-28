package com.suresofttech.apx.ui.widget.settings.vibration;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Text;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.AudioRecorder;
import com.suresofttech.apx.core.audio.BeepMatcher;
import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.audio.TonePlayer;
import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;

/**
 * 음향 설정과 같은 구성의 진동 입력/기대값 설정 패널.
 *
 * <p>피에조 픽업과 USB 오디오 인터페이스의 신호를 mono 파형으로 받아 15ms 단위로
 * FFT 코사인 유사도와 PCM NCC를 비교한다. 진동 패널에서만 실차 정상 진동을 직접
 * 녹화해 WAV로 저장하고, 저장 직후 기대값으로 적용할 수 있다.
 */
public final class VibrationProbe extends Composite {

    private enum CaptureMode { IDLE, TEST, MEASURE, RECORD }

    private static final int POLL_MS = 50;
    private static final int RING_SECONDS = 2;
    private static final double MATCH_WINDOW_SEC = 0.015;
    private static final double THRESHOLD_STEP = 0.05;
    private static final int[] SAMPLE_RATES = { 48000, 44100, 32000 };

    private final Display display;
    private final AudioCapture capture = new AudioCapture();
    private final AudioRecorder referenceRecorder = new AudioRecorder();
    private final TonePlayer previewPlayer = new TonePlayer();
    private final Object waveLock = new Object();

    private final Combo deviceCombo;
    private final Button refreshButton;
    private final ProgressBar levelBar;
    private final Button testButton;
    private final Text expectedPathText;
    private final Button loadReferenceButton;
    private final Button measureButton;
    private final Button resetButton;
    private final Button playButton;
    private final Button recordButton;
    private final AudioScope scope;
    private final Label verdictLabel;
    private final Label thresholdLabel;

    private List<AudioCapture.Device> devices;
    private volatile CaptureMode mode = CaptureMode.IDLE;
    private boolean polling;
    private int sampleRate;
    private double[] ring = new double[0];
    private int ringHead;
    private long capturedSamples;
    private long renderedSamples;
    private volatile double rms;
    private volatile double peak;
    private volatile BeepMatcher matcher;
    private volatile MatchResult latestMatch;
    private volatile boolean passLatched;
    private double threshold = 0.90;
    private double[] expectedSamples;
    private int expectedSampleRate;
    private String expectedPath;

    public VibrationProbe(Composite parent) {
        super(parent, SWT.NONE);
        display = getDisplay();
        GridLayout root = new GridLayout(1, false);
        root.marginWidth = 0;
        root.marginHeight = 0;
        setLayout(root);
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group sensorGroup = new Group(this, SWT.NONE);
        sensorGroup.setText("진동 센서");
        sensorGroup.setLayout(new GridLayout(1, false));
        sensorGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite deviceRow = row(sensorGroup, 2, false);
        deviceCombo = new Combo(deviceRow, SWT.READ_ONLY | SWT.DROP_DOWN);
        deviceCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        deviceCombo.setToolTipText("피에조 픽업이 연결된 USB 오디오 입력을 선택하세요.");
        refreshButton = new Button(deviceRow, SWT.PUSH);
        refreshButton.setText("새로고침");
        refreshButton.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                refreshDevices();
            }
        });

        Composite levelRow = row(sensorGroup, 2, false);
        Label levelTitle = new Label(levelRow, SWT.NONE);
        levelTitle.setText("입력 레벨");
        levelBar = new ProgressBar(levelRow, SWT.HORIZONTAL | SWT.SMOOTH);
        levelBar.setMinimum(0);
        levelBar.setMaximum(100);
        levelBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        testButton = new Button(sensorGroup, SWT.TOGGLE);
        testButton.setText("진동 테스트 시작");
        testButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        testButton.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (testButton.getSelection()) {
                    if (!startCapture(CaptureMode.TEST)) {
                        testButton.setSelection(false);
                    }
                } else {
                    stopCapture("진동 입력 테스트를 중지했습니다.");
                }
            }
        });

        Group expectedGroup = new Group(this, SWT.NONE);
        expectedGroup.setText("기대 진동");
        expectedGroup.setLayout(new GridLayout(1, false));
        expectedGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite expectedFile = new Composite(expectedGroup, SWT.NONE);
        GridLayout expectedFileLayout = new GridLayout(2, false);
        expectedFileLayout.marginWidth = 0;
        expectedFileLayout.marginHeight = 0;
        expectedFile.setLayout(expectedFileLayout);
        expectedFile.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label expectedTitle = new Label(expectedFile, SWT.NONE);
        expectedTitle.setText("기대 진동 파일 (.wav)");
        expectedTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        expectedPathText = new Text(expectedFile, SWT.BORDER | SWT.READ_ONLY | SWT.SINGLE);
        expectedPathText.setText("진동 .wav를 선택하거나 실차에서 녹화하세요");
        expectedPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        loadReferenceButton = new Button(expectedFile, SWT.PUSH);
        loadReferenceButton.setText("파일...");
        loadReferenceButton.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                loadReference();
            }
        });

        Composite actionRow = row(expectedGroup, 4, true);
        measureButton = actionButton(actionRow, "측정 시작", true);
        resetButton = actionButton(actionRow, "리셋", false);
        playButton = actionButton(actionRow, "기대값 듣기", true);
        recordButton = actionButton(actionRow, "실차 진동 녹화", true);

        measureButton.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (measureButton.getSelection()) {
                    if (!startCapture(CaptureMode.MEASURE)) {
                        measureButton.setSelection(false);
                    }
                } else {
                    stopCapture("진동 측정을 중지했습니다.");
                }
            }
        });
        resetButton.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                resetMeasure();
            }
        });
        playButton.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                togglePreview(playButton.getSelection());
            }
        });
        recordButton.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (recordButton.getSelection()) {
                    if (!startCapture(CaptureMode.RECORD)) {
                        recordButton.setSelection(false);
                    }
                } else {
                    finishReferenceRecording();
                }
            }
        });

        scope = new AudioScope(expectedGroup, 10000.0);
        scope.setShowPitch(false);
        scope.setShowTrend(false);
        scope.setTickMs(1000);
        scope.setPassColor(0x2ecb5a);
        scope.setPassAlpha(90);
        scope.setWaveTitle("측정 파형 (진동)");
        GridData scopeData = new GridData(SWT.FILL, SWT.FILL, true, true);
        scopeData.minimumHeight = 160;
        scope.setLayoutData(scopeData);

        verdictLabel = new Label(expectedGroup, SWT.NONE);
        verdictLabel.setText("판정: 기대 진동 없음");
        verdictLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label thresholdDescription = new Label(expectedGroup, SWT.WRAP);
        thresholdDescription.setText("PASS 기준 임계");
        thresholdDescription.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        thresholdDescription.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        thresholdLabel = new Label(expectedGroup, SWT.NONE);
        thresholdLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite thresholdRow = row(expectedGroup, 2, true);
        Button minus = actionButton(thresholdRow, "− 완화", false);
        Button plus = actionButton(thresholdRow, "+ 엄격", false);
        minus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                changeThreshold(-THRESHOLD_STEP);
            }
        });
        plus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                changeThreshold(THRESHOLD_STEP);
            }
        });

        capture.setErrorListener(new AudioCapture.ErrorListener() {
            public void onCaptureError(final String reason) {
                display.asyncExec(new Runnable() {
                    public void run() {
                        if (!isDisposed()) {
                            cancelCaptureAfterError("입력 오류: " + reason);
                        }
                    }
                });
            }
        });
        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                polling = false;
                capture.stop();
                previewPlayer.stop();
            }
        });

        updateThresholdLabel();
        refreshDevices();
        startPoll();
    }

    private static Composite row(Composite parent, int columns, boolean equal) {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(columns, equal);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        row.setLayout(layout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return row;
    }

    private static Button actionButton(Composite parent, String text, boolean toggle) {
        Button button = new Button(parent, toggle ? SWT.TOGGLE : SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return button;
    }

    public void refreshDevices() {
        if (mode != CaptureMode.IDLE) {
            return;
        }
        String previous = deviceCombo.getText();
        devices = AudioCapture.listInputDevices();
        deviceCombo.removeAll();
        int selected = 0;
        for (int i = 0; i < devices.size(); i++) {
            String name = devices.get(i).name;
            deviceCombo.add(name);
            if (name.equals(previous)) {
                selected = i;
            }
        }
        if (!devices.isEmpty()) {
            deviceCombo.select(selected);
            setStatus("피에조 픽업 입력을 테스트하거나 기대 진동을 녹화하세요.");
        } else {
            setStatus("사용 가능한 USB 오디오 입력 장치가 없습니다.");
        }
        updateControlState();
        layout(true, true);
    }

    private AudioCapture.Device selectedDevice() {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        int index = deviceCombo.getSelectionIndex();
        return index < 0 || index >= devices.size() ? null : devices.get(index);
    }

    private boolean startCapture(CaptureMode requestedMode) {
        AudioCapture.Device device = selectedDevice();
        if (device == null) {
            setStatus("진동 입력 장치를 선택하세요.");
            return false;
        }
        if (requestedMode == CaptureMode.MEASURE && matcher == null) {
            setStatus("기대 진동 WAV를 먼저 선택하거나 녹화하세요.");
            return false;
        }
        if (mode != CaptureMode.IDLE) {
            stopCapture("이전 동작을 중지했습니다.");
        }
        previewPlayer.stop();
        playButton.setSelection(false);
        playButton.setText("기대값 듣기");
        clearLiveWave();

        int[] rates = requestedMode == CaptureMode.MEASURE && expectedSampleRate > 0
                ? new int[] { expectedSampleRate } : SAMPLE_RATES;
        Exception last = null;
        for (int i = 0; i < rates.length; i++) {
            final int rate = rates[i];
            for (int channels = 1; channels <= 2; channels++) {
                final int channelCount = channels;
                prepareRing(rate);
                if (requestedMode == CaptureMode.RECORD) {
                    referenceRecorder.start(rate);
                }
                try {
                    capture.start(device.info, rate, channelCount, new AudioCapture.BlockListener() {
                        public void onBlock(double[] block, double now) {
                            acceptBlock(block);
                        }
                    });
                    sampleRate = rate;
                    mode = requestedMode;
                    if (requestedMode == CaptureMode.MEASURE) {
                        armMatcher();
                    }
                    updateControlState();
                    setStatus(modeStatus(requestedMode, device.name, rate, channelCount));
                    return true;
                } catch (Exception ex) {
                    last = ex;
                    capture.stop();
                    mode = CaptureMode.IDLE;
                }
            }
        }
        String detail = last == null || last.getMessage() == null
                ? "지원 형식 없음" : last.getMessage();
        setStatus("입력을 열 수 없습니다: " + detail);
        updateControlState();
        return false;
    }

    private static String modeStatus(CaptureMode requestedMode, String name, int rate, int channels) {
        String action = requestedMode == CaptureMode.TEST ? "입력 테스트 중"
                : requestedMode == CaptureMode.MEASURE ? "진동 측정 중"
                : "실차 기대 진동 녹화 중";
        return action + ": " + name + " / " + rate + " Hz / " + channels + "ch→mono";
    }

    private void prepareRing(int rate) {
        synchronized (waveLock) {
            sampleRate = rate;
            ring = new double[Math.max(1, rate * RING_SECONDS)];
            ringHead = 0;
            capturedSamples = 0;
            renderedSamples = 0;
        }
        rms = 0;
        peak = 0;
    }

    private void acceptBlock(double[] block) {
        double sum = 0;
        double max = 0;
        long total;
        synchronized (waveLock) {
            for (int i = 0; i < block.length; i++) {
                double value = block[i];
                ring[ringHead] = value;
                ringHead = (ringHead + 1) % ring.length;
                sum += value * value;
                max = Math.max(max, Math.abs(value));
            }
            capturedSamples += block.length;
            total = capturedSamples;
        }
        rms = Math.sqrt(sum / Math.max(1, block.length));
        peak = max;

        CaptureMode activeMode = mode;
        if (activeMode == CaptureMode.RECORD) {
            referenceRecorder.feed(block);
        } else if (activeMode == CaptureMode.MEASURE) {
            BeepMatcher activeMatcher = matcher;
            if (activeMatcher != null && activeMatcher.getSampleRate() == sampleRate) {
                MatchResult result = activeMatcher.feed(block, total / (double) sampleRate);
                latestMatch = result;
                if (result.isPass) {
                    passLatched = true;
                }
            }
        }
    }

    private void stopCapture(String message) {
        CaptureMode stoppedMode = mode;
        mode = CaptureMode.IDLE;
        capture.stop();
        if (stoppedMode == CaptureMode.RECORD) {
            referenceRecorder.stop();
        }
        setStatus(message);
        updateControlState();
        updateVerdict();
    }

    private void finishReferenceRecording() {
        if (mode != CaptureMode.RECORD) {
            recordButton.setSelection(false);
            updateControlState();
            return;
        }
        mode = CaptureMode.IDLE;
        capture.stop();
        referenceRecorder.stop();
        double[] samples = referenceRecorder.getSamples();
        int rate = referenceRecorder.getSampleRate();
        updateControlState();

        double durationMs = samples.length * 1000.0 / Math.max(1, rate);
        if (durationMs < 100.0 || maxAbs(samples) < 1e-6) {
            setStatus("녹화가 너무 짧거나 진동 신호가 없습니다. 다시 녹화하세요.");
            return;
        }

        FileDialog dialog = new FileDialog(getShell(), SWT.SAVE);
        dialog.setText("실차 진동 기대값 WAV 저장");
        dialog.setFilterExtensions(new String[] { "*.wav" });
        dialog.setFilterNames(new String[] { "WAV (*.wav)" });
        dialog.setFileName("vibration-reference-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".wav");
        dialog.setOverwrite(true);
        String path = dialog.open();
        if (path == null || path.isEmpty()) {
            setStatus("실차 진동 WAV 저장을 취소했습니다.");
            return;
        }
        if (!path.toLowerCase().endsWith(".wav")) {
            path += ".wav";
        }
        try {
            WavIo.save(path, samples, rate);
            applyReference(samples, rate, path);
            setStatus("실차 진동 WAV 저장 및 기대값 적용 완료: "
                    + new File(path).getName());
        } catch (Exception ex) {
            setStatus("실차 진동 WAV 저장 실패: " + safeMessage(ex));
        }
    }

    private void cancelCaptureAfterError(String message) {
        CaptureMode failedMode = mode;
        mode = CaptureMode.IDLE;
        if (failedMode == CaptureMode.RECORD) {
            referenceRecorder.stop();
        }
        setStatus(message);
        updateControlState();
        updateVerdict();
    }

    private void loadReference() {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("진동 기대값 WAV 선택");
        dialog.setFilterExtensions(new String[] { "*.wav" });
        dialog.setFilterNames(new String[] { "WAV (*.wav)" });
        String path = dialog.open();
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            WavIo.Wav wav = WavIo.load(path);
            if (wav.samples.length < Math.max(2, wav.sampleRate / 100)) {
                throw new IllegalArgumentException("기대 WAV는 최소 10ms 이상이어야 합니다.");
            }
            applyReference(wav.samples, wav.sampleRate, path);
            setStatus("기대 진동 불러오기 완료: " + new File(path).getName());
        } catch (Exception ex) {
            setStatus("기대 진동 불러오기 실패: " + safeMessage(ex));
        }
    }

    private void applyReference(double[] samples, int rate, String path) {
        expectedSamples = samples.clone();
        expectedSampleRate = rate;
        expectedPath = path;
        expectedPathText.setText(new File(path).getName());
        expectedPathText.setToolTipText(path);
        rebuildMatcher();
        resetMeasure();
        updateControlState();
        layout(true, true);
    }

    private void rebuildMatcher() {
        if (expectedSamples == null || expectedSamples.length < 2 || expectedSampleRate <= 0) {
            matcher = null;
            return;
        }
        BeepMatcher replacement = new BeepMatcher(expectedSamples, expectedSampleRate,
                150.0, 4.0, threshold, threshold, MATCH_WINDOW_SEC);
        replacement.arm();
        matcher = replacement;
        if (scope != null && !scope.isDisposed()) {
            scope.clear();
            scope.setExpected(replacement.getTemplate(), expectedSampleRate);
        }
    }

    private void armMatcher() {
        BeepMatcher active = matcher;
        if (active != null) {
            active.setFreqThr(threshold);
            active.setWaveThr(threshold);
            active.arm();
        }
        latestMatch = null;
        passLatched = false;
        if (scope != null && !scope.isDisposed()) {
            scope.clearPass();
        }
    }

    private void togglePreview(boolean on) {
        if (on) {
            if (expectedSamples == null || expectedSampleRate <= 0) {
                playButton.setSelection(false);
                setStatus("기대 진동 WAV를 먼저 선택하거나 녹화하세요.");
                return;
            }
            if (previewPlayer.play(expectedSamples, expectedSampleRate)) {
                playButton.setText("재생 정지");
                setStatus("기대 진동 WAV를 재생 중입니다: "
                        + new File(expectedPath).getName());
            } else {
                playButton.setSelection(false);
                setStatus("기대 진동 WAV를 재생할 수 없습니다.");
            }
        } else {
            previewPlayer.stop();
            playButton.setText("기대값 듣기");
        }
    }

    private void changeThreshold(double delta) {
        threshold = Math.max(0.0, Math.min(1.0, threshold + delta));
        BeepMatcher active = matcher;
        if (active != null) {
            active.setFreqThr(threshold);
            active.setWaveThr(threshold);
            active.arm();
        }
        latestMatch = null;
        passLatched = false;
        scope.clearPass();
        updateThresholdLabel();
        updateVerdict();
    }

    private void updateThresholdLabel() {
        thresholdLabel.setText(String.format("주파수 및 파형 임계치 %.0f%%", threshold * 100.0));
    }

    private void resetMeasure() {
        clearLiveWave();
        armMatcher();
        if (matcher != null) {
            scope.setExpected(matcher.getTemplate(), matcher.getSampleRate());
        }
        updateVerdict();
    }

    private void clearLiveWave() {
        synchronized (waveLock) {
            if (ring.length > 0) {
                Arrays.fill(ring, 0);
            }
            ringHead = 0;
            capturedSamples = 0;
            renderedSamples = 0;
        }
        rms = 0;
        peak = 0;
        latestMatch = null;
        passLatched = false;
        scope.clear();
        BeepMatcher active = matcher;
        if (active != null) {
            scope.setExpected(active.getTemplate(), active.getSampleRate());
        }
    }

    private void updateControlState() {
        boolean hasDevice = devices != null && !devices.isEmpty();
        boolean idle = mode == CaptureMode.IDLE;
        boolean testing = mode == CaptureMode.TEST;
        boolean measuring = mode == CaptureMode.MEASURE;
        boolean recording = mode == CaptureMode.RECORD;

        deviceCombo.setEnabled(idle && hasDevice);
        refreshButton.setEnabled(idle);
        loadReferenceButton.setEnabled(idle);

        testButton.setEnabled(hasDevice && (idle || testing));
        testButton.setSelection(testing);
        testButton.setText(testing ? "진동 테스트 정지" : "진동 테스트 시작");

        measureButton.setEnabled(hasDevice && matcher != null && (idle || measuring));
        measureButton.setSelection(measuring);
        measureButton.setText(measuring ? "측정 중지" : "측정 시작");

        recordButton.setEnabled(hasDevice && (idle || recording));
        recordButton.setSelection(recording);
        recordButton.setText(recording ? "녹화 중지/저장" : "실차 진동 녹화");

        playButton.setEnabled(idle && expectedSamples != null);
        resetButton.setEnabled(!recording);
    }

    private void startPoll() {
        polling = true;
        display.timerExec(POLL_MS, new Runnable() {
            public void run() {
                if (!polling || isDisposed()) {
                    return;
                }
                updateUi();
                if (polling && !isDisposed()) {
                    display.timerExec(POLL_MS, this);
                }
            }
        });
    }

    private void updateUi() {
        boolean running = capture.isRunning();
        int level = running ? (int) Math.min(100, Math.round(rms * 800.0)) : 0;
        levelBar.setSelection(level);
        levelBar.setToolTipText(String.format("RMS %.1f%% / Peak %.1f%%%s",
                running ? rms * 100.0 : 0.0,
                running ? peak * 100.0 : 0.0,
                running && peak >= 0.98 ? " / CLIP" : ""));

        if (playButton.getSelection() && !previewPlayer.isPlaying()) {
            playButton.setSelection(false);
            playButton.setText("기대값 듣기");
        }

        CaptureMode activeMode = mode;
        if (activeMode == CaptureMode.MEASURE || activeMode == CaptureMode.RECORD) {
            renderWave(activeMode);
        }
        updateVerdict();
    }

    private void renderWave(CaptureMode activeMode) {
        long total;
        int rate;
        double[] wave;
        synchronized (waveLock) {
            total = capturedSamples;
            rate = sampleRate;
            if (total == renderedSamples || rate <= 0) {
                return;
            }
            int count = (int) Math.min(total, ring.length);
            wave = new double[count];
            int start = (ringHead - count + ring.length) % ring.length;
            for (int i = 0; i < count; i++) {
                wave[i] = ring[(start + i) % ring.length];
            }
            renderedSamples = total;
        }
        double elapsed = total / (double) rate;
        BeepMatcher activeMatcher = matcher;
        scope.setData(wave, rate,
                activeMatcher == null ? 0 : activeMatcher.getTargetFreq(), elapsed);
        MatchResult result = latestMatch;
        if (activeMode == CaptureMode.MEASURE && result != null) {
            scope.updatePass(elapsed * 1000.0, result.isPass);
        }
    }

    private void updateVerdict() {
        if (mode == CaptureMode.RECORD) {
            verdictLabel.setText(String.format("판정: 기대 진동 녹화 중 (%.1f초)",
                    referenceRecorder.getDurationMs() / 1000.0));
            return;
        }
        if (matcher == null) {
            verdictLabel.setText("판정: 기대 진동 없음");
            return;
        }
        if (mode != CaptureMode.MEASURE) {
            verdictLabel.setText("판정: 대기");
            return;
        }
        MatchResult result = latestMatch;
        if (result == null) {
            verdictLabel.setText("판정: 비교 데이터 대기");
        } else if (passLatched) {
            verdictLabel.setText(String.format("판정: PASS / 주파수 %.1f%% / 파형 %.1f%%",
                    result.freqSim * 100.0, result.waveSim * 100.0));
        } else {
            verdictLabel.setText(String.format("판정: FAIL / 주파수 %.1f%% / 파형 %.1f%%",
                    result.freqSim * 100.0, result.waveSim * 100.0));
        }
    }

    /** 하단 설명 행 대신 관련 컨트롤의 툴팁으로만 상태를 보존한다. */
    private void setStatus(String message) {
        setToolTipText(message);
        testButton.setToolTipText(message);
        measureButton.setToolTipText(message);
        recordButton.setToolTipText(message);
        verdictLabel.setToolTipText(message);
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
