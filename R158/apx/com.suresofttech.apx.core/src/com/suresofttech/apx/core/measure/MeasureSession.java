package com.suresofttech.apx.core.measure;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.AudioRecorder;
import com.suresofttech.apx.core.audio.BeepMatcher;
import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.sync.SyncBus;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.Cv;
import com.suresofttech.apx.core.vision.EvidenceCapture;
import com.suresofttech.apx.core.vision.RoiMatchDetector;
import com.suresofttech.apx.core.vision.VisionJudge;
import com.suresofttech.apx.core.vision.VisionJudges;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.core.vision.VisionEvidenceStore;
import com.suresofttech.apx.core.vision.VisionMatchLog;
import com.suresofttech.apx.core.vision.VisionRecorder;

/**
 * 측정 세션 — 시작 시 설정 스냅샷 고정, 음향·비전 엔진·후방 판정 상태·증거 버퍼.
 *
 * <p>후방은 판정 <b>저장소</b>만 제공한다({@link #setRearVerdict}/{@link #getRearVerdict}).
 * "이번 측정 결과를 어느 격자 포인트에 넣을지"는 시험 계획(어느 포인트가 어느 TC인지)을
 * 아는 클라이언트가 정한다 — core는 그 매핑 규칙을 갖지 않는다.
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
    private volatile boolean visionPass;
    private volatile Long visionPassAtMs;
    /** 자체 판단(ms) = frameGap + analysis. 미확정 null. */
    private Double visionJudgeMs;
    private Double visionGapMs;
    private Double visionAnalysisMs;
    /** stop 시 detector에서 보존 — 전/중/후 프레임 증거. */
    private EvidenceCapture.Evidence visionFrameEvidence;
    /** 측정 전체 구간 비전 녹화(결과 탭 스크럽용). */
    private final VisionRecorder visionRecorder = new VisionRecorder();
    private VisionRecorder.Recording visionRecording;
    /** 녹화 임시 폴더 — 중단 후 증거 폴더로 옮긴다. */
    private File visionRecordDir;
    /** 프레임별 ROI hit/ncc — 결과 스크럽 PASS/FAIL 색 복원. */
    private final VisionMatchLog visionMatchLog = new VisionMatchLog();

    private final Object rearLock = new Object();
    private Verdict[][] rearVerdicts; // [col][row], null = NONE

    /** SyncBus 공통시계(초) — 측정 시작 시각. PASS ms = (stamp − startNanoSec)*1000. */
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
     * 비전 판정 전·중·후 프레임 증거 ({@code evidence_pre_-1f} 등).
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

    /**
     * 클라 폴더에 비전 증거 PNG 저장 (OpenCV Mat은 core에서만 다룸).
     * {@code evidence_pre_-1f.png} / {@code evidence_decide.png} / {@code evidence_post_+1f.png}
     */
    public synchronized void saveVisionFrameEvidenceTo(File dir) {
        if (dir == null || visionFrameEvidence == null) {
            return;
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 파일명은 VisionEvidenceStore가 단일 출처 — 조회 API와 어긋나지 않게.
        writeVisionSnap(dir, VisionEvidenceStore.Frame.PRE.fileName(), visionFrameEvidence.pre);
        writeVisionSnap(dir, VisionEvidenceStore.Frame.DECIDE.fileName(), visionFrameEvidence.decide);
        writeVisionSnap(dir, VisionEvidenceStore.Frame.POST.fileName(), visionFrameEvidence.post);
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
        return visionPass;
    }

    public boolean isOverallPass() {
        return audioPass && visionPass;
    }

    /** 측정 시작 기준 공통시계 PASS 시각(ms). 물리지연(D_mic/D_cap) 포함·미보정. 미검출 null. */
    public Long getAudioPassMs() {
        return audioPassAtMs;
    }

    public Long getVisionPassMs() {
        return visionPassAtMs;
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
     * 0보다 크면 측정 도중 카메라·해상도가 바뀌었다는 뜻.
     */
    public int getVisionResizedFrames() {
        return visionRecorder.getResizedFrames();
    }

    /** 녹화 컨테이너의 프레임 크기 — 아직 안 열렸으면 0. */
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
        this.visionFrameEvidence = null;
        this.visionRecording = null;
        this.visionMatchLog.clear();
        SyncBus.get().reset();
        this.startNanoSec = SyncBus.now();
        initRearVerdicts(snap);
        startVisionRecording();

        final BeepMatcher feedMatcher = bm;
        final AudioRecorder feedRec = rec;
        final double t0 = this.startNanoSec;
        this.audioError = null;
        // 측정 도중 마이크가 빠지면 무음이 조용히 녹음된다 — 사유를 세션 상태로 올린다.
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
                    // 공통시계(캡처 콜백 now = nanoTime 초) — 샘플경과와 별개로 동기 비교용
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

    /** 측정 중단 — 캡처 중지, 증거 finalize. */
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
        fireState();
    }

    // ── 비전 FULL 녹화 ──────────────────────────────────────────

    /**
     * 측정 시작~중단 전체 녹화를 임시 폴더에 쌓는다. 증거 루트는 중단 시점에
     * 클라가 정하므로(저장 경로 버튼), 여기서는 temp에 쓰고
     * {@link #moveVisionRecordingTo}가 최종 폴더로 옮긴다.
     */
    private void startVisionRecording() {
        try {
            File tmp = File.createTempFile("apx-rec-", "");
            if (!tmp.delete() || !tmp.mkdirs()) {
                return;
            }
            tmp.deleteOnExit();
            visionRecordDir = tmp;
            // 열려 있는 카메라 해상도를 미리 넘겨 writer를 사전 오픈 —
            // 첫 프레임에 열면 오픈에 걸리는 수백 ms 동안 측정 앞부분이 유실된다.
            BufferedImage probe = CameraService.get().latest();
            if (probe != null) {
                visionRecorder.start(tmp, probe.getWidth(), probe.getHeight());
            } else {
                visionRecorder.start(tmp);
            }
        } catch (Exception ex) {
            visionRecordDir = null;
        }
    }

    /**
     * 비전 프레임 투입 — UI가 새 카메라 프레임을 받을 때마다 호출.
     * 시각은 PASS 시각과 같은 공통시계 기준(측정 시작=0)이라 결과 스크럽이 정렬된다.
     */
    public void recordVisionFrame(BufferedImage bi) {
        if (!running || bi == null || !visionRecorder.isRunning()) {
            return;
        }
        double tMs = (SyncBus.now() - startNanoSec) * 1000.0;
        visionRecorder.feed(bi, tMs);
    }

    /** 중단 후 남은 녹화 산출물({@code full.avi} + {@code frames.csv}). 없으면 null. */
    public synchronized VisionRecorder.Recording getVisionRecording() {
        return visionRecording;
    }

    /**
     * 녹화본을 증거 폴더로 옮긴다 — {@code <visionDir>/full.avi}, {@code frames.csv}.
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
        latchVisionPass(r);
        fireVision(r);
        return r;
    }

    /** UI(RoiNcc)가 이미 낸 결과를 세션에 보고. */
    public void reportVisionMatch(RoiMatchResult r) {
        if (!running) {
            return;
        }
        latestVision = r;
        // 스크럽용 시계열 — PASS latch와 무관하게 매 프레임 hit/ncc 기록
        if (r != null) {
            double tMs = (SyncBus.now() - startNanoSec) * 1000.0;
            boolean hit = r.hit || ("ok".equals(r.state) && r.ncc >= r.simThr);
            visionMatchLog.add(tMs, hit, r.ncc);
        }
        latchVisionPass(r);
        fireVision(r);
    }

    /** 측정 중 쌓인 ROI 매칭 로그(결과 탭 복원용). */
    public VisionMatchLog getVisionMatchLog() {
        return visionMatchLog;
    }

    /**
     * 비전 최초 hit → PASS. 검출 시각은 L2 보정 없이 공통시계 그대로(물리지연 D_cap 포함).
     * 자체 판단 = RoiMatchResult.passMs (frameGap + analysis).
     */
    private void latchVisionPass(RoiMatchResult r) {
        if (r == null || !r.hit || visionPass) {
            return;
        }
        visionPass = true;
        double tSec = SyncBus.now();
        double judge = r.passMs != null ? r.passMs.doubleValue() : Double.NaN;
        SyncBus.get().mark(SyncBus.Event.CLUSTER_POPUP, tSec, judge);
        long ms = Math.round((tSec - startNanoSec) * 1000.0);
        visionPassAtMs = Long.valueOf(ms);
        if (evidence != null) {
            evidence.setVisionPassMs(ms);
            if (r.passMs != null) {
                visionJudgeMs = r.passMs;
                visionGapMs = Double.valueOf(r.frameGapMs);
                visionAnalysisMs = r.analysisMs;
                evidence.setVisionJudgeMs(r.passMs.doubleValue());
            }
        } else if (r.passMs != null) {
            visionJudgeMs = r.passMs;
            visionGapMs = Double.valueOf(r.frameGapMs);
            visionAnalysisMs = r.analysisMs;
        }
        recordOverallPassIfComplete();
        fireState();
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
     * 두 채널이 모두 PASS가 된 시각(늦은 쪽)을 증거에 기록 — 한쪽이 latch될 때마다 호출.
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
        boolean v = visionPass;
        boolean o = a && v;
        for (Listener l : listeners) {
            try {
                l.onState(a, v, o);
            } catch (Exception ignored) {
            }
        }
    }
}
