package com.suresofttech.apx.core.measure;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.AudioRecorder;
import com.suresofttech.apx.core.audio.BeepMatcher;
import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.audio.TonePlayer;
import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.sync.SyncBus;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.Cv;
import com.suresofttech.apx.core.vision.VisionChannel;
import com.suresofttech.apx.core.vision.EvidenceCapture;
import com.suresofttech.apx.core.vision.VisionJudge;
import com.suresofttech.apx.core.vision.VisionJudges;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.core.vision.VisionEvidenceStore;
import com.suresofttech.apx.core.vision.VisionMatchLog;
import com.suresofttech.apx.core.vision.VisionRecorder;
import com.suresofttech.apx.core.vision.VisionReference;

/**
 * 측정 세션 - 시작 시 설정 스냅샷 고정, 음향 / 비전 엔진 / 후방 판정 상태 / 증거 버퍼.
 *
 * <p>후방은 판정 <b>저장소</b>만 제공한다({@link #setRearVerdict}/{@link #getRearVerdict}).
 * "이번 측정 결과를 어느 격자 포인트에 넣을지"는 시험 계획(어느 포인트가 어느 TC인지)을
 * 아는 클라이언트가 정한다 - core는 그 매핑 규칙을 갖지 않는다.
 */
public final class MeasureSession {

    public interface Listener {
        void onAudioTick(MatchResult match, double[] waveBuf, double elapsedSec);

        void onVisionMatch(RoiMatchResult result);

        /** audioPass / visionPass / overall(음향∧비전). */
        void onState(boolean audioPass, boolean visionPass, boolean overallPass);
    }

    /** 최초 기어 R 판정 직후 실행할 시뮬레이터 출력 트리거. */
    public interface GearTriggerListener {
        void onGearTrigger();
    }

    private static final MeasureSession INSTANCE = new MeasureSession();

    public static MeasureSession get() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private volatile GearTriggerListener gearTriggerListener;

    private volatile boolean running;
    private volatile boolean preparing;
    private MeasureConfigSnapshot snapshot;
    private MeasureEvidence evidence;

    private BeepMatcher matcher;
    private AudioCapture capture;
    private AudioRecorder recorder;
    private volatile long capturedSamples;
    /** 마이크 샘플 0초가 측정 공통시계에서 시작한 위치(초). */
    private volatile double audioTimelineOffsetSec;
    private volatile boolean audioTimelineAligned;
    private volatile MatchResult latestMatch;
    /** 측정 중 마이크가 빠지거나 입력이 끊긴 사유. 정상이면 null. */
    private volatile String audioError;
    private volatile boolean audioPass;
    private volatile Long audioPassAtMs;
    /** 자체 판단(ms) = blockGap + analysis. 미확정 null. */
    private Double audioJudgeMs;
    private Double audioGapMs;
    private Double audioAnalysisMs;

    private VisionJudge visionDet;
    private volatile RoiMatchResult latestVision;
    /** 비전 PASS = 클러스터 ∧ 기어봉. */
    private volatile boolean visionPass;
    private volatile Long visionPassAtMs;
    /** 자체 판단(ms) = frameGap + analysis. 미확정 null. */
    private Double visionJudgeMs;
    private Double visionGapMs;
    private Double visionAnalysisMs;
    private volatile boolean clusterPass;
    private volatile boolean gearPass;
    private volatile Long clusterPassAtMs;
    private volatile Long gearPassAtMs;
    private Double clusterJudgeMs;
    private Double clusterGapMs;
    private Double clusterAnalysisMs;
    private Double gearJudgeMs;
    private Double gearGapMs;
    private Double gearAnalysisMs;
    /** stop 시 detector에서 보존 - 전/중/후 프레임 증거. */
    private EvidenceCapture.Evidence visionFrameEvidence;
    private EvidenceCapture.Evidence gearVisionFrameEvidence;
    /** 측정 전체 구간 비전 녹화(결과 탭 스크럽용, 클러스터). */
    private final VisionRecorder visionRecorder = new VisionRecorder();
    private VisionRecorder.Recording visionRecording;
    /** 녹화 임시 폴더 - 중단 후 증거 폴더로 옮긴다. */
    private File visionRecordDir;
    private final VisionRecorder gearRecorder = new VisionRecorder();
    private VisionRecorder.Recording gearRecording;
    private File gearRecordDir;
    /** 프레임별 ROI hit/ncc - 결과 스크럽 PASS/FAIL 색 복원. */
    private final VisionMatchLog visionMatchLog = new VisionMatchLog();

    /** 기대음 1회 재생(시뮬레이터 자극). start()에서 라인을 미리 열어 둔다. */
    private final TonePlayer expectedPlayer = new TonePlayer();
    private double[] expectedSamples;
    private int expectedSampleRate;
    private boolean expectedAutoPlayed;

    /**
     * 자극 발사 시각(기어봉 R 전환 순간). 측정 시작 기준 ms. 미발사면 null.
     * <p>이 시각이 동기 판정의 T0 가 된다 - {@link #markStimulus()} 참고.
     */
    private volatile Long stimulusAtMs;

    private final Object rearLock = new Object();
    private Verdict[][] rearVerdicts; // [col][row], null = NONE

    /** SyncBus 공통시계(초) - 측정 시작 시각. PASS ms = (stamp − startNanoSec)*1000. */
    private volatile double startNanoSec;

    private MeasureSession() {
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /**
     * <b>자극 발사</b> - 기어봉을 R로 바꾸는 순간 호출한다. 이 시각이 동기 판정의 T0.
     *
     * <p>여기서 <b>기대음도 같이 발사</b>한다. 화면 교체는 호출자(테스트 화면)가 바로 이어서 한다.
     * 세 자극이 같은 순간에 나가고, 그 뒤 각 채널을 언제 다시 읽어 들이는지가 곧
     * 그 채널의 전체 지연이다.
     * <pre>
     * 기어봉 지연  = 기어봉 검출  − T0     ← 기준점이 도구 자신이라 기어봉도 측정된다
     * 클러스터 지연 = 클러스터 검출 − T0
     * 음향 지연    = 음향 검출    − T0
     * Sync = MAX(셋)
     * </pre>
     *
     * <p>측정당 1회만 기록한다. 측정 중이 아니면 무시.
     *
     * @return 기록된 T0(측정 시작 기준 ms). 기록하지 않았으면 null
     */
    public synchronized Long markStimulus() {
        if (!running || stimulusAtMs != null) {
            return null;
        }
        stimulusAtMs = Long.valueOf(Math.round((SyncBus.now() - startNanoSec) * 1000.0));
        playExpectedNow();
        return stimulusAtMs;
    }

    /**
     * 테스트 기어 화면이 R로 바뀐 순간. 측정 중이면 T0+기대음, 아니면 설정 기대음만 재생.
     */
    public void onTestGearToR() {
        synchronized (this) {
            if (preparing) {
                return;
            }
            if (running) {
                if (stimulusAtMs == null) {
                    markStimulus();
                } else {
                    playExpectedAgain();
                }
                return;
            }
        }
        playExpectedFromSettings();
    }

    private void playExpectedFromSettings() {
        try {
            String path = ApxSettings.get().getExpectedWavPath();
            if (path == null || path.isEmpty() || !new File(path).isFile()) {
                return;
            }
            WavIo.Wav wav = WavIo.load(path);
            expectedPlayer.play(wav.samples, wav.sampleRate);
        } catch (Exception ignored) {
            // 테스트 화면 전환을 막지 않는다
        }
    }

    /** 자극 발사 시각(측정 시작 기준 ms). 미발사면 null. */
    public Long getStimulusAtMs() {
        return stimulusAtMs;
    }

    public void setGearTriggerListener(GearTriggerListener listener) {
        gearTriggerListener = listener;
    }

    public void clearGearTriggerListener(GearTriggerListener listener) {
        if (gearTriggerListener == listener) {
            gearTriggerListener = null;
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public boolean isPreparing() {
        return preparing;
    }

    public synchronized MeasureConfigSnapshot getSnapshot() {
        return snapshot;
    }

    public synchronized MeasureEvidence getEvidence() {
        return evidence;
    }

    /**
     * 비전 판정 전 / 중 / 후 프레임 증거 ({@code evidence_pre_-1f} 등).
     * {@link #stop()} 이후에도 유지.
     */
    public synchronized EvidenceCapture.Evidence getVisionFrameEvidence() {
        return visionFrameEvidence;
    }

    /**
     * 클라 {@code RoiNcc} detector에서 수확한 ±1프레임 증거 수용.
     * (모니터는 Session.visionDet가 아닌 RoiNcc det로 process 하므로 stop 전에 넘긴다)
     */
    public synchronized void acceptVisionFrameEvidence(EvidenceCapture.Evidence e) {
        if (e != null) {
            this.visionFrameEvidence = e;
        }
    }

    /** 기어봉 채널 ±1프레임 증거. */
    public synchronized void acceptGearVisionFrameEvidence(EvidenceCapture.Evidence e) {
        if (e != null) {
            this.gearVisionFrameEvidence = e;
        }
    }

    /**
     * 클라 폴더에 비전 증거 PNG 저장 (OpenCV Mat은 core에서만 다룸).
     * {@code evidence_pre_-1f.png} / {@code evidence_decide.png} / {@code evidence_post_+1f.png}
     */
    public synchronized void saveVisionFrameEvidenceTo(File dir) {
        if (dir == null) {
            return;
        }
        if (visionFrameEvidence == null && gearVisionFrameEvidence == null) {
            return;
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (visionFrameEvidence != null) {
        writeVisionSnap(dir, VisionEvidenceStore.Frame.PRE.fileName(), visionFrameEvidence.pre);
            writeVisionSnap(dir, VisionEvidenceStore.Frame.DECIDE.fileName(),
                visionFrameEvidence.decide);
        writeVisionSnap(dir, VisionEvidenceStore.Frame.POST.fileName(), visionFrameEvidence.post);
        }
        if (gearVisionFrameEvidence != null) {
            File gearDir = new File(dir, "gear");
            if (!gearDir.exists()) {
                gearDir.mkdirs();
            }
            writeVisionSnap(gearDir, VisionEvidenceStore.Frame.PRE.fileName(),
                gearVisionFrameEvidence.pre);
            writeVisionSnap(gearDir, VisionEvidenceStore.Frame.DECIDE.fileName(),
                gearVisionFrameEvidence.decide);
            writeVisionSnap(gearDir, VisionEvidenceStore.Frame.POST.fileName(),
                gearVisionFrameEvidence.post);
        }
    }

    private static void writeVisionSnap(File dir, String name, EvidenceCapture.Snap snap) {
        if (snap == null || snap.img == null || snap.img.empty()) {
            return;
        }
        Cv.imwriteKr(new File(dir, name).getAbsolutePath(), snap.img);
    }

    public boolean isAudioPass() {
        return audioPass;
    }

    public boolean isVisionPass() {
        return clusterPass && gearPass;
    }

    public boolean isClusterPass() {
        return clusterPass;
    }

    public boolean isGearPass() {
        return gearPass;
    }

    public boolean isOverallPass() {
        return audioPass && visionPass;
    }

    /** 측정 시작 기준 공통시계 PASS 시각(ms). 물리지연(D_mic/D_cap) 포함 / 미보정. 미검출 null. */
    public Long getAudioPassMs() {
        return audioPassAtMs;
    }

    public Long getVisionPassMs() {
        return visionPassAtMs;
    }

    public Long getClusterPassMs() {
        return clusterPassAtMs;
    }

    public Long getGearPassMs() {
        return gearPassAtMs;
    }

    /** 음향 자체 판단(ms) = blockGap + analysis. 미확정 null. */
    public Double getAudioJudgeMs() {
        return audioJudgeMs;
    }

    public Double getAudioGapMs() {
        return audioGapMs;
    }

    public Double getAudioAnalysisMs() {
        return audioAnalysisMs;
    }

    /** 비전 자체 판단(ms) = frameGap + analysis. 미확정 null. */
    public Double getVisionJudgeMs() {
        return visionJudgeMs;
    }

    public Double getVisionGapMs() {
        return visionGapMs;
    }

    public Double getVisionAnalysisMs() {
        return visionAnalysisMs;
    }

    public Double getClusterJudgeMs() {
        return clusterJudgeMs;
    }

    public Double getClusterGapMs() {
        return clusterGapMs;
    }

    public Double getClusterAnalysisMs() {
        return clusterAnalysisMs;
    }

    public Double getGearJudgeMs() {
        return gearJudgeMs;
    }

    public Double getGearGapMs() {
        return gearGapMs;
    }

    public Double getGearAnalysisMs() {
        return gearAnalysisMs;
    }

    /**
     * PASS 한 줄: 물리지연 포함 검출시각 + 자체 판단(간격+분석).
     * 예) {@code 음향: PASS @ 1030 ms (자체판단 20.1 = 간격 14.0 + 분석 6.1)}
     */
    public static String formatPassLine(String channel, Long passAtMs,
        Double judgeMs, Double gapMs, Double analysisMs) {
        if (passAtMs == null) {
            return channel + ": FAIL (미검출)";
        }
        if (judgeMs != null && gapMs != null && analysisMs != null) {
            return String.format("%s: PASS @ %d ms (자체판단 %.1f = 간격 %.1f + 분석 %.1f)",
                channel, passAtMs.longValue(),
                judgeMs.doubleValue(), gapMs.doubleValue(), analysisMs.doubleValue());
        }
        if (judgeMs != null) {
            return String.format("%s: PASS @ %d ms (자체판단 %.1f ms)",
                channel, passAtMs.longValue(), judgeMs.doubleValue());
        }
        return String.format("%s: PASS @ %d ms", channel, passAtMs.longValue());
    }

    /**
     * 최종 한 줄 - Kickoff / 결과 탭이 같은 문구를 쓴다.
     * 예) {@code 최종: PASS - PASS (동기 최대 12ms ≤ 30ms, 기어봉 R 검출 기준)  (2026-08-31 19:06:58)}
     */
    public static String formatOverallLine(boolean overallPass, String summary, long epochMs) {
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(epochMs));
        return String.format("최종: %s - %s  (%s)",
                overallPass ? "PASS" : "FAIL", summary == null ? "" : summary, when);
    }

    /**
     * 중단 시 최종 판정. CAN은 아직 미연동({@code requireCan=false}).
     *
     * <p>시뮬레이터의 기준점 T0는 기어봉 R 검출 시각이며, 측정 시작 때 고정한
     * 동기 임계값으로 클러스터·음향의 상대 지연을 판정한다.
     */
    public MeasureSyncResult evaluateFinal() {
        ApxSettings s = ApxSettings.get();
        double tolerance = snapshot == null
                ? MeasureSyncResult.DEFAULT_SYNC_TOL_MS : snapshot.syncToleranceMs;
        return MeasureSyncResult.evaluate(
                audioPass, clusterPass, gearPass,
                audioPassAtMs, clusterPassAtMs, gearPassAtMs,
                null, stimulusAtMs, false,
                s.getCalibMs(VisionChannel.GEAR),
                s.getCalibMs(VisionChannel.CLUSTER),
                s.getCalibAudioMs(), tolerance);
    }

    public MatchResult getLatestMatch() {
        return latestMatch;
    }

    /**
     * 측정 중 음향 입력이 끊긴 사유(마이크 분리 등). 정상이면 null.
     * null이 아니면 그 시점 이후 {@code full.wav}는 더 이상 쌓이지 않는다.
     */
    public String getAudioError() {
        return audioError;
    }

    /**
     * 비전 녹화 중 해상도가 달라 레터박스로 맞춘 프레임 수.
     * 0보다 크면 측정 도중 카메라 / 해상도가 바뀌었다는 뜻.
     */
    public int getVisionResizedFrames() {
        return visionRecorder.getResizedFrames();
    }

    /** 녹화 컨테이너의 프레임 크기 - 아직 안 열렸으면 0. */
    public int getVisionRecordWidth() {
        return visionRecorder.getWidth();
    }

    public int getVisionRecordHeight() {
        return visionRecorder.getHeight();
    }

    public RoiMatchResult getLatestVision() {
        return latestVision;
    }

    /**
     * 측정 시작. 현재 {@link ApxSettings}를 스냅샷으로 고정.
     * @throws IllegalStateException 이미 실행 중, 또는 기대 WAV/마이크 불가
     */
    public void start() throws Exception {
        synchronized (this) {
            if (running || preparing) {
                throw new IllegalStateException("이미 측정 또는 준비 중입니다");
            }
            preparing = true;
        }
        try {
            startInternal();
        } catch (Exception ex) {
            cleanupFailedStart();
            throw ex;
        }
    }

    private void startInternal() throws Exception {
        expectedPlayer.stop();
        MeasureConfigSnapshot snap = MeasureConfigSnapshot.from(ApxSettings.get());
        if (snap.expectedWavPath == null || snap.expectedWavPath.isEmpty()
                || !new File(snap.expectedWavPath).isFile()) {
            throw new IllegalStateException("기대음 WAV가 없습니다: " + snap.expectedWavPath);
        }
        AudioCapture.Device dev = AudioCapture.findInputDevice(snap.micName);
        if (dev == null) {
            throw new IllegalStateException("마이크 장치를 찾을 수 없습니다");
        }

        WavIo.Wav wav = WavIo.load(snap.expectedWavPath);
        this.expectedSamples = wav.samples;
        this.expectedSampleRate = wav.sampleRate;
        this.expectedAutoPlayed = false;
        this.stimulusAtMs = null;
        BeepMatcher bm = new BeepMatcher(wav.samples, wav.sampleRate, 150.0, 4.0,
                snap.audioFreqThr, snap.audioWaveThr, 0.015);
        bm.arm();

        AudioRecorder rec = new AudioRecorder();
        rec.start(bm.getSampleRate());

        AudioCapture cap = new AudioCapture();
        this.snapshot = snap;
        this.evidence = new MeasureEvidence();
        this.matcher = bm;
        this.recorder = rec;
        this.capture = cap;
        this.capturedSamples = 0;
        this.audioTimelineOffsetSec = 0;
        this.audioTimelineAligned = false;
        this.latestMatch = null;
        this.audioPass = false;
        this.audioPassAtMs = null;
        this.audioJudgeMs = null;
        this.audioGapMs = null;
        this.audioAnalysisMs = null;
        this.visionDet = null;
        this.latestVision = null;
        this.visionPass = false;
        this.visionPassAtMs = null;
        this.visionJudgeMs = null;
        this.visionGapMs = null;
        this.visionAnalysisMs = null;
        this.clusterPass = false;
        this.gearPass = false;
        this.clusterPassAtMs = null;
        this.gearPassAtMs = null;
        this.clusterJudgeMs = null;
        this.clusterGapMs = null;
        this.clusterAnalysisMs = null;
        this.gearJudgeMs = null;
        this.gearGapMs = null;
        this.gearAnalysisMs = null;
        this.visionFrameEvidence = null;
        this.gearVisionFrameEvidence = null;
        this.visionRecording = null;
        this.gearRecording = null;
        this.visionMatchLog.clear();
        SyncBus.get().reset();
        initRearVerdicts(snap);

        final BeepMatcher feedMatcher = bm;
        final AudioRecorder feedRec = rec;
        final CountDownLatch audioReady = new CountDownLatch(1);
        final CountDownLatch armedReady = new CountDownLatch(1);
        final AtomicBoolean armRequested = new AtomicBoolean(false);
        final AtomicBoolean armed = new AtomicBoolean(false);
        this.audioError = null;
        cap.setErrorListener(new AudioCapture.ErrorListener() {
            public void onCaptureError(String reason) {
                audioError = reason;
                audioReady.countDown();
                armedReady.countDown();
                fireState();
            }
        });
        try {
            startVisionRecording();
            if (!expectedPlayer.prepare(wav.samples, wav.sampleRate)) {
                throw new IllegalStateException("기대음 출력 장치를 준비할 수 없습니다");
            }
            cap.start(dev.info, bm.getSampleRate(), new AudioCapture.BlockListener() {
            public void onBlock(double[] block, double now) {
                if (!armRequested.get()) {
                    audioReady.countDown();
                    return;                         // 준비 중 샘플은 측정에 포함하지 않는다
                }
                if (armed.compareAndSet(false, true)) {
                    // 이 블록까지 버리고 경계를 T=0으로 잡는다. 다음 read 블록부터 측정 데이터다.
                    startNanoSec = now;
                    capturedSamples = 0;
                    audioTimelineOffsetSec = 0;
                    audioTimelineAligned = true;
                    visionRecorder.feed(CameraService.of(VisionChannel.CLUSTER).latest(), 0);
                    gearRecorder.feed(CameraService.of(VisionChannel.GEAR).latest(), 0);
                    running = true;
                    armedReady.countDown();
                    return;
                }
                if (!running) {
                    return;
                }
                feedRec.feed(block);
                long n = capturedSamples + block.length;
                capturedSamples = n;
                double tAudio = n / (double) feedMatcher.getSampleRate();
                MatchResult mr = feedMatcher.feed(block, tAudio);
                latestMatch = mr;
                if (mr != null && mr.isPass && !audioPass) {
                    audioPass = true;
                    // 공통시계(캡처 콜백 now = nanoTime 초) - 샘플경과와 별개로 동기 비교용
                    // L2 캘리브 없이 검출 시각 그대로(물리지연 D_mic 포함)
                    double tSec = now;
                    double judge = mr.passMs != null ? mr.passMs.doubleValue() : Double.NaN;
                    SyncBus.get().mark(SyncBus.Event.BEEP, tSec, judge);
                    long ms = Math.round((tSec - startNanoSec) * 1000.0);
                    audioPassAtMs = Long.valueOf(ms);
                    evidence.setAudioPassMs(ms);
                    if (mr.passMs != null) {
                        audioJudgeMs = mr.passMs;
                        audioGapMs = Double.valueOf(mr.blockGapMs);
                        audioAnalysisMs = mr.analysisMs;
                        evidence.setAudioJudgeMs(mr.passMs.doubleValue());
                    }
                    recordOverallPassIfComplete();
                    fireState();
                }
                fireAudio(mr, feedMatcher.getBuffer(), tAudio);
            }
            });
            if (!audioReady.await(5, TimeUnit.SECONDS) || audioError != null) {
                throw new IllegalStateException(audioError == null
                        ? "마이크 첫 입력을 5초 안에 받지 못했습니다" : audioError);
            }
            armRequested.set(true);
            if (!armedReady.await(5, TimeUnit.SECONDS) || audioError != null || !running) {
                throw new IllegalStateException(audioError == null
                        ? "마이크 측정 시작 경계를 만들지 못했습니다" : audioError);
            }
            preparing = false;
        } catch (Exception ex) {
            throw ex;
        }
        fireState();
    }

    private void cleanupFailedStart() {
        running = false;
        preparing = false;
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        if (recorder != null) {
            recorder.stop();
        }
        visionRecorder.stop();
        gearRecorder.stop();
        expectedPlayer.stop();
        matcher = null;
    }

    /** 측정 중단 - 캡처 중지, 증거 finalize. */
    public synchronized void stop() {
        if (!running && !preparing) {
            return;
        }
        if (preparing && !running) {
            cleanupFailedStart();
            fireState();
            return;
        }
        running = false;
        preparing = false;
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        if (recorder != null) {
            recorder.stop();
            evidence.setAudio(recorder.getSamples(), recorder.getSampleRate());
        }
        matcher = null;
        if (visionDet != null) {
            visionDet.flushEvidence();
            visionFrameEvidence = visionDet.getEvidence();
        }
        visionDet = null;
        visionRecording = visionRecorder.stop();
        gearRecording = gearRecorder.stop();
        expectedPlayer.stop();
        expectedSamples = null;
        fireState();
    }

    // ── 비전 FULL 녹화 ──────────────────────────────────────────

    /**
     * 측정 시작~중단 전체 녹화를 임시 폴더에 쌓는다. 증거 루트는 중단 시점에
     * 클라가 정하므로(저장 경로 버튼), 여기서는 temp에 쓰고
     * {@link #moveVisionRecordingTo}가 최종 폴더로 옮긴다.
     */
    private void startVisionRecording() throws Exception {
        visionRecordDir = startRecorder(visionRecorder, CameraService.of(VisionChannel.CLUSTER));
        gearRecordDir = startRecorder(gearRecorder, CameraService.of(VisionChannel.GEAR));
        if (!visionRecorder.awaitReady(5_000)) {
            throw new IllegalStateException("클러스터 녹화 장치를 준비하지 못했습니다");
        }
        if (!gearRecorder.awaitReady(5_000)) {
            throw new IllegalStateException("기어봉 녹화 장치를 준비하지 못했습니다");
        }
    }

    private static File startRecorder(VisionRecorder rec, CameraService cam) throws Exception {
        try {
            File tmp = File.createTempFile("apx-rec-", "");
            if (!tmp.delete() || !tmp.mkdirs()) {
                return null;
            }
            tmp.deleteOnExit();
            if (cam == null || !cam.isOpen()) {
                throw new IllegalStateException("카메라가 열려 있지 않습니다");
            }
            BufferedImage probe = cam == null ? null : cam.latest();
            if (probe == null) {
                throw new IllegalStateException("카메라의 첫 프레임을 받지 못했습니다");
            }
            rec.start(tmp, probe.getWidth(), probe.getHeight());
            return tmp;
        } catch (Exception ex) {
            throw ex;
        }
    }

    /**
     * 비전 프레임 투입 - UI가 새 카메라 프레임을 받을 때마다 호출.
     * 시각은 PASS 시각과 같은 공통시계 기준(측정 시작=0)이라 결과 스크럽이 정렬된다.
     */
    public void recordVisionFrame(BufferedImage bi) {
        recordVisionFrame(VisionChannel.CLUSTER, bi);
    }

    public void recordVisionFrame(VisionChannel ch, BufferedImage bi) {
        if (!running || bi == null) {
            return;
        }
        VisionRecorder rec = ch == VisionChannel.GEAR ? gearRecorder : visionRecorder;
        if (!rec.isRunning()) {
            return;
        }
        double tMs = (SyncBus.now() - startNanoSec) * 1000.0;
        rec.feed(bi, tMs);
    }

    /** 중단 후 남은 녹화 산출물({@code full.avi} + {@code frames.csv}). 없으면 null. */
    public synchronized VisionRecorder.Recording getVisionRecording() {
        return visionRecording;
    }

    /**
     * 녹화본을 증거 폴더로 옮긴다 - {@code <visionDir>/full.avi}, {@code frames.csv}.
     * @return 옮겨진 영상 파일(없으면 null)
     */
    public synchronized File moveVisionRecordingTo(File visionDir) {
        if (visionDir == null) {
            return null;
        }
        if (!visionDir.exists() && !visionDir.mkdirs()) {
            return null;
        }
        // 녹화본이 없어도 ROI 시계열은 남긴다(스크럽 색 복원)
        try {
            visionMatchLog.save(visionDir);
        } catch (Exception ignored) {
            // 매칭 로그 실패해도 아래 녹화 이동은 계속
        }
        moveGearRecordingTo(new File(visionDir, "gear"));
        VisionRecorder.Recording rec = visionRecording;
        if (rec == null) {
            return null;
        }
        File video = moveInto(rec.video, new File(visionDir, VisionRecorder.VIDEO_NAME));
        moveInto(rec.index, new File(visionDir, VisionRecorder.INDEX_NAME));
        if (visionRecordDir != null) {
            visionRecordDir.delete();   // 비었으면 정리(남아 있으면 deleteOnExit이 처리)
        }
        if (video != null) {
            visionRecording = new VisionRecorder.Recording(
                    video, new File(visionDir, VisionRecorder.INDEX_NAME),
                    rec.frameCount, rec.lastMs);
        }
        return video;
    }

    private void moveGearRecordingTo(File gearDir) {
        VisionRecorder.Recording rec = gearRecording;
        if (rec == null || gearDir == null) {
            return;
        }
        if (!gearDir.exists() && !gearDir.mkdirs()) {
            return;
        }
        File video = moveInto(rec.video, new File(gearDir, VisionRecorder.VIDEO_NAME));
        moveInto(rec.index, new File(gearDir, VisionRecorder.INDEX_NAME));
        if (gearRecordDir != null) {
            gearRecordDir.delete();
        }
        if (video != null) {
            gearRecording = new VisionRecorder.Recording(
                video, new File(gearDir, VisionRecorder.INDEX_NAME),
                rec.frameCount, rec.lastMs);
        }
    }

    /** rename 실패(다른 볼륨 등) 시 복사로 폴백. */
    private static File moveInto(File src, File dst) {
        if (src == null || !src.isFile()) {
            return null;
        }
        if (dst.exists() && !dst.delete()) {
            return null;
        }
        if (src.renameTo(dst)) {
            return dst;
        }
        try {
            java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            src.delete();
            return dst;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 비전 프레임 주입. UI가 카메라 폴링 후 호출.
     * 스냅샷 기준 검출기를 lazy 생성.
     */
    public RoiMatchResult processVisionFrame(BufferedImage bi) {
        if (!running || bi == null || snapshot == null) {
            return null;
        }
        VisionJudge det;
        synchronized (this) {
            if (!running) {
                return null;
            }
            try {
                det = ensureVisionDetector(bi);
            } catch (Exception ex) {
                return null;
            }
        }
        if (det == null) {
            return null;
        }
        RoiMatchResult r = det.process(bi);
        latestVision = r;
        latchVisionPass(VisionChannel.CLUSTER, r);
        fireVision(r);
        return r;
    }

    /** UI(RoiNcc)가 이미 낸 결과를 세션에 보고. 무인자는 클러스터. */
    public void reportVisionMatch(RoiMatchResult r) {
        reportVisionMatch(VisionChannel.CLUSTER, r);
    }

    public void reportVisionMatch(VisionChannel ch, RoiMatchResult r) {
        if (!running) {
            return;
        }
        latestVision = r;
        if (ch != VisionChannel.GEAR && r != null) {
            double tMs = (SyncBus.now() - startNanoSec) * 1000.0;
            boolean hit = r.hit || ("ok".equals(r.state) && r.ncc >= r.simThr);
            visionMatchLog.add(tMs, hit, r.ncc);
        }
        latchVisionPass(ch, r);
        fireVision(r);
    }

    /** 측정 중 쌓인 ROI 매칭 로그(결과 탭 복원용). */
    public VisionMatchLog getVisionMatchLog() {
        return visionMatchLog;
    }

    /**
     * 채널별 최초 hit → PASS. 비전 전체 PASS는 클러스터∧기어봉.
     * 기대음 자동재생은 둘 중 먼저 PASS일 때 1회.
     */
    private synchronized void latchVisionPass(VisionChannel ch, RoiMatchResult r) {
        if (r == null || !r.hit) {
            return;
        }
        boolean gear = ch == VisionChannel.GEAR;
        if (gear ? gearPass : clusterPass) {
            return;
        }
        double tSec = SyncBus.now();
        double judge = r.passMs != null ? r.passMs.doubleValue() : Double.NaN;
        SyncBus.get().mark(gear ? SyncBus.Event.GEAR_R : SyncBus.Event.CLUSTER_POPUP, tSec, judge);
        long ms = Math.round((tSec - startNanoSec) * 1000.0);
        if (gear) {
            gearPass = true;
            gearPassAtMs = Long.valueOf(ms);
            if (r.passMs != null) {
                gearJudgeMs = r.passMs;
                gearGapMs = Double.valueOf(r.frameGapMs);
                gearAnalysisMs = r.analysisMs;
            }
            // 자극을 이미 냈으면(markStimulus) 여기서 또 낼 필요가 없다.
            // 자극 없이 도는 구성(테스트 화면을 안 쓰는 경우)에서만 검출로 클러스터를 띄운다.
            if (stimulusAtMs == null) {
                GearTriggerListener trigger = gearTriggerListener;
                if (trigger != null) {
                    try {
                        trigger.onGearTrigger();
                    } catch (Exception ignored) {
                    }
                }
            }
        } else {
            clusterPass = true;
            clusterPassAtMs = Long.valueOf(ms);
            if (r.passMs != null) {
                clusterJudgeMs = r.passMs;
                clusterGapMs = Double.valueOf(r.frameGapMs);
                clusterAnalysisMs = r.analysisMs;
            }
        }
        if (gear) {
            maybeAutoPlayExpected();
        }
        if (clusterPass && gearPass && !visionPass) {
            visionPass = true;
            long both = Math.max(clusterPassAtMs.longValue(), gearPassAtMs.longValue());
            visionPassAtMs = Long.valueOf(both);
            visionJudgeMs = gearPassAtMs.longValue() >= clusterPassAtMs.longValue()
                    ? gearJudgeMs : clusterJudgeMs;
            visionGapMs = gearPassAtMs.longValue() >= clusterPassAtMs.longValue()
                    ? gearGapMs : clusterGapMs;
            visionAnalysisMs = gearPassAtMs.longValue() >= clusterPassAtMs.longValue()
                    ? gearAnalysisMs : clusterAnalysisMs;
            if (evidence != null) {
                evidence.setVisionPassMs(both);
                if (visionJudgeMs != null) {
                    evidence.setVisionJudgeMs(visionJudgeMs.doubleValue());
                }
            }
            recordOverallPassIfComplete();
        }
        fireState();
    }

    /**
     * 슬레이트 자동 트리거 - <b>기어봉 비전 PASS(T0) 시점</b>에 기대음을 한 번 발사한다.
     *
     * <p>영화 슬레이트와 같은 구조다. 기어봉 ROI 가 FAIL→PASS 로 바뀌는 순간이 "탁" 이고,
     * 그 순간을 0 으로 잡아 클러스터 화면 교체(={@link GearTriggerListener})와 이 기대음을
     * 함께 내보낸다. 그 뒤 두 채널을 각각 언제 다시 읽어 들이는지가 곧 도구의 동기 오차다.
     * <pre>
     * Sync = MAX(클러스터 검출 − T0, 음향 검출 − T0)
     * </pre>
     *
     * <p>발사 지연을 줄이려고 {@code start()} 에서 미리 라인을 열어 둔다({@code prepare}).
     * 준비가 안 된 상태로 play 하면 라인 오픈(수십 ms)이 그대로 음향 지연에 얹힌다.
     *
     * <p>수동 모드({@link MeasureConfigSnapshot#autoPlayExpectedOnVisionPass}=false)면 건너뛴다.
     */
    private void maybeAutoPlayExpected() {
        if (snapshot == null || !snapshot.autoPlayExpectedOnVisionPass) {
            return;
        }
        // 측정 시작 당시 화면이 이미 R이면 첫 판정만으로 기대음이 나가면 안 된다.
        // 테스트 화면에서 실제 P/N/D -> R 전환이 발생해 T0가 잡힌 경우에만 허용한다.
        if (stimulusAtMs == null) {
            return;
        }
        playExpectedNow();
    }

    /** 기대음 1회 발사. 라인은 {@code start()} 에서 미리 열어 두었다. */
    private synchronized void playExpectedNow() {
        if (expectedAutoPlayed || expectedSamples == null || expectedSampleRate <= 0) {
            return;
        }
        if (expectedPlayer.play(expectedSamples, expectedSampleRate)) {
            expectedAutoPlayed = true;
        }
    }

    /** 같은 측정에서 테스트 기어가 다시 R로 전환됐을 때 기대음을 다시 발사한다. */
    private void playExpectedAgain() {
        if (expectedSamples == null || expectedSampleRate <= 0) {
            return;
        }
        expectedPlayer.play(expectedSamples, expectedSampleRate);
    }

    /**
     * 후방 판정 <b>저장</b>만 담당한다. 어느 포인트에 무엇을 넣을지(=측정 결과를 격자에
     * 매핑하는 규칙)는 시험 계획을 아는 클라이언트 몫이라 core에 두지 않는다.
     */
    public void setRearVerdict(int col, int row, Verdict v) {
        synchronized (rearLock) {
            if (rearVerdicts == null || snapshot == null) {
                return;
            }
            if (col < 0 || row < 0 || col >= snapshot.rearCols || row >= snapshot.rearRows) {
                return;
            }
            rearVerdicts[col][row] = v == null ? Verdict.NONE : v;
        }
    }

    public void clearRearVerdicts() {
        synchronized (rearLock) {
            if (rearVerdicts == null) {
                return;
            }
            for (int c = 0; c < rearVerdicts.length; c++) {
                for (int r = 0; r < rearVerdicts[c].length; r++) {
                    rearVerdicts[c][r] = Verdict.NONE;
                }
            }
        }
    }

    public Verdict getRearVerdict(int col, int row) {
        synchronized (rearLock) {
            if (rearVerdicts == null || col < 0 || row < 0
                    || col >= rearVerdicts.length || row >= rearVerdicts[col].length) {
                return Verdict.NONE;
            }
            Verdict v = rearVerdicts[col][row];
            return v == null ? Verdict.NONE : v;
        }
    }

    /** 템플릿 파형 (스코프 setExpected 용). */
    public synchronized double[] getAudioTemplate() {
        return matcher == null ? null : matcher.getTemplate();
    }

    public synchronized int getAudioSampleRate() {
        return matcher == null ? 0 : matcher.getSampleRate();
    }

    public synchronized double getTargetFreq() {
        return matcher == null ? 0 : matcher.getTargetFreq();
    }

    /** 라이브 파형 버퍼 복사본(캡처 스레드와 경합 방지). UI 폴링용. */
    public synchronized double[] getWaveBuffer() {
        return matcher == null ? null : matcher.getBuffer().clone();
    }

    /** 마이크 샘플 끝을 측정 공통시계로 변환한 경과(초). UI 표시용. */
    public double getElapsedSec() {
        int sr = getAudioSampleRate();
        if (sr <= 0) {
            return 0;
        }
        double sampleSec = capturedSamples / (double) sr;
        return sampleSec + (audioTimelineAligned ? audioTimelineOffsetSec : 0.0);
    }

    /** WAV 샘플 0초가 측정 공통시계에서 시작한 위치(ms). */
    public double getAudioTimelineOffsetMs() {
        return (audioTimelineAligned ? audioTimelineOffsetSec : 0.0) * 1000.0;
    }

    private void initRearVerdicts(MeasureConfigSnapshot snap) {
        synchronized (rearLock) {
            rearVerdicts = new Verdict[snap.rearCols][snap.rearRows];
            for (int c = 0; c < snap.rearCols; c++) {
                for (int r = 0; r < snap.rearRows; r++) {
                    rearVerdicts[c][r] = Verdict.NONE;
                }
            }
        }
    }

    /**
     * 두 채널이 모두 PASS가 된 시각(늦은 쪽)을 증거에 기록 - 한쪽이 latch될 때마다 호출.
     *
     * <p>예전에는 클라가 중단을 누른 시각을 넣었는데, 그건 "조건이 충족된 시각"이 아니라
     * "운영자가 버튼을 누른 시각"이라 증거로 의미가 없었다. 두 시각 모두 측정 시작 기준
     * 공통시계라 max가 곧 전체 충족 시점이다. 최초 1회만 기록된다.
     */
    private void recordOverallPassIfComplete() {
        Long a = audioPassAtMs;
        Long v = visionPassAtMs;
        if (evidence == null || a == null || v == null) {
            return;
        }
        evidence.setOverallPassMs(Math.max(a.longValue(), v.longValue()));
    }

    private VisionJudge ensureVisionDetector(BufferedImage bi) throws Exception {
        if (visionDet != null) {
            if (!snapshot.useReferenceImage
                    && (bi.getWidth() != visionDet.canonWidth()
                            || bi.getHeight() != visionDet.canonHeight())) {
                visionDet = null;
            } else {
                return visionDet;
            }
        }
        if (snapshot.useReferenceImage) {
            String path = snapshot.visionRefPath;
            if (path == null || !new File(path).isFile()) {
                return null;
            }
            visionDet = VisionJudges.create(null, path, null, snapshot.simThr);
            int[] roi = snapshot.toRoiPixels(visionDet.canonWidth(), visionDet.canonHeight());
            if (roi != null) {
                visionDet.setRoi(roi);
            }
            visionDet.setSimThr(snapshot.simThr);
        } else {
            // 기준은 설정에서 잡아 둔 프레임 우선 — 없을 때만 지금 프레임.
            // 시작 버튼을 누른 순간의 화면이 기준으로 바뀌어 버리는 것을 막는다.
            BufferedImage ref = VisionReference.get(VisionChannel.CLUSTER);
            if (ref != null && (ref.getWidth() != bi.getWidth()
                    || ref.getHeight() != bi.getHeight())) {
                ref = null;     // 해상도가 다르면 못 쓴다(카메라가 바뀐 경우)
            }
            BufferedImage base = (ref != null) ? ref : bi;
            int[] roi = snapshot.toRoiPixels(base.getWidth(), base.getHeight());
            visionDet = VisionJudges.create(base, null, roi, snapshot.simThr);
            visionDet.setAlignEnabled(false);
            visionDet.setSimThr(snapshot.simThr);
        }
        return visionDet;
    }

    private void fireAudio(MatchResult mr, double[] buf, double elapsedSec) {
        for (Listener l : listeners) {
            try {
                l.onAudioTick(mr, buf, elapsedSec);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireVision(RoiMatchResult r) {
        for (Listener l : listeners) {
            try {
                l.onVisionMatch(r);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireState() {
        boolean a = audioPass;
        boolean v = clusterPass && gearPass;
        boolean o = a && v;
        for (Listener l : listeners) {
            try {
                l.onState(a, v, o);
            } catch (Exception ignored) {
            }
        }
    }
}
