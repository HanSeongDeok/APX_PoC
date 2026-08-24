package com.suresofttech.apx.core.measure;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

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
import com.suresofttech.apx.core.vision.RoiMatchDetector;
import com.suresofttech.apx.core.vision.VisionJudge;
import com.suresofttech.apx.core.vision.VisionJudges;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.core.vision.VisionEvidenceStore;
import com.suresofttech.apx.core.vision.VisionMatchLog;
import com.suresofttech.apx.core.vision.VisionRecorder;

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

    private static final MeasureSession INSTANCE = new MeasureSession();

    public static MeasureSession get() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private volatile boolean running;
    private MeasureConfigSnapshot snapshot;
    private MeasureEvidence evidence;

    private BeepMatcher matcher;
    private AudioCapture capture;
    private AudioRecorder recorder;
    private volatile long capturedSamples;
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

    /** 비전 PASS 시 기대음 1회 재생(시뮬레이터 자동 트리거). */
    private final TonePlayer expectedPlayer = new TonePlayer();
    private double[] expectedSamples;
    private int expectedSampleRate;
    private boolean expectedAutoPlayed;

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

    public synchronized boolean isRunning() {
        return running;
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
     * 중단 시 최종 판정. CAN은 아직 미연동({@code requireCan=false}).
     * 규칙: 음향∧비전 PASS 이고 (max−min) ≤ {@link MeasureSyncResult#SYNC_TOL_MS}.
     */
    public MeasureSyncResult evaluateFinal() {
        return MeasureSyncResult.evaluate(
                audioPass, visionPass,
                audioPassAtMs, visionPassAtMs,
                null, false);
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
    public synchronized void start() throws Exception {
        if (running) {
            throw new IllegalStateException("이미 측정 중입니다");
        }
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
        this.startNanoSec = SyncBus.now();
        initRearVerdicts(snap);
        startVisionRecording();

        final BeepMatcher feedMatcher = bm;
        final AudioRecorder feedRec = rec;
        final double t0 = this.startNanoSec;
        this.audioError = null;
        // 측정 도중 마이크가 빠지면 무음이 조용히 녹음된다 - 사유를 세션 상태로 올린다.
        cap.setErrorListener(new AudioCapture.ErrorListener() {
            public void onCaptureError(String reason) {
                audioError = reason;
                fireState();
            }
        });
        cap.start(dev.info, bm.getSampleRate(), new AudioCapture.BlockListener() {
            public void onBlock(double[] block, double now) {
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
                    long ms = Math.round((tSec - t0) * 1000.0);
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

        running = true;
        fireState();
    }

    /** 측정 중단 - 캡처 중지, 증거 finalize. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
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
    private void startVisionRecording() {
        visionRecordDir = startRecorder(visionRecorder, CameraService.of(VisionChannel.CLUSTER));
        gearRecordDir = startRecorder(gearRecorder, CameraService.of(VisionChannel.GEAR));
    }

    private static File startRecorder(VisionRecorder rec, CameraService cam) {
        try {
            File tmp = File.createTempFile("apx-rec-", "");
            if (!tmp.delete() || !tmp.mkdirs()) {
                return null;
            }
            tmp.deleteOnExit();
            BufferedImage probe = cam == null ? null : cam.latest();
            if (probe != null) {
                rec.start(tmp, probe.getWidth(), probe.getHeight());
            } else {
                rec.start(tmp);
            }
            return tmp;
        } catch (Exception ex) {
            return null;
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
    private void latchVisionPass(VisionChannel ch, RoiMatchResult r) {
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
        } else {
            clusterPass = true;
            clusterPassAtMs = Long.valueOf(ms);
            if (r.passMs != null) {
                clusterJudgeMs = r.passMs;
                clusterGapMs = Double.valueOf(r.frameGapMs);
                clusterAnalysisMs = r.analysisMs;
            }
        }
        maybeAutoPlayExpected();
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
     * 시뮬레이터 자동 트리거 - 비전(R단) 최초 PASS 때 기대음을 한 번 재생한다.
     * 수동 모드({@link MeasureConfigSnapshot#autoPlayExpectedOnVisionPass}=false)면 건너뛴다.
     */
    private void maybeAutoPlayExpected() {
        if (snapshot == null || !snapshot.autoPlayExpectedOnVisionPass) {
            return;
        }
        if (expectedAutoPlayed || expectedSamples == null || expectedSampleRate <= 0) {
            return;
        }
        expectedAutoPlayed = true;
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

    /** 측정 경과(초). UI 폴링용. */
    public double getElapsedSec() {
        int sr = getAudioSampleRate();
        if (sr <= 0) {
            return 0;
        }
        return capturedSamples / (double) sr;
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
            int[] roi = snapshot.toRoiPixels(bi.getWidth(), bi.getHeight());
            visionDet = VisionJudges.create(bi, null, roi, snapshot.simThr);
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
