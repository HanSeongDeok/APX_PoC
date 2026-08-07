package com.suresofttech.apx.core.measure;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.AudioRecorder;
import com.suresofttech.apx.core.audio.BeepMatcher;
import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.sync.SyncBus;
import com.suresofttech.apx.core.vision.Cv;
import com.suresofttech.apx.core.vision.EvidenceCapture;
import com.suresofttech.apx.core.vision.RoiMatchDetector;
import com.suresofttech.apx.core.vision.RoiMatchResult;

/**
 * 측정 세션 — 시작 시 설정 스냅샷 고정, 음향·비전 엔진·후방 판정 상태·증거 버퍼.
 * 후방: 측정 중은 {@link #markRearMeasuring()}. PASS/FAIL은 중단 시 Kickoff가
 * {@link #setRearVerdict} / markRearPass 등으로 반영한다.
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
    private volatile boolean audioPass;
    private Long audioPassAtMs;
    /** 자체 판단(ms) = blockGap + analysis. 미확정 null. */
    private Double audioJudgeMs;
    private Double audioGapMs;
    private Double audioAnalysisMs;

    private RoiMatchDetector visionDet;
    private volatile RoiMatchResult latestVision;
    private volatile boolean visionPass;
    private Long visionPassAtMs;
    /** 자체 판단(ms) = frameGap + analysis. 미확정 null. */
    private Double visionJudgeMs;
    private Double visionGapMs;
    private Double visionAnalysisMs;
    /** stop 시 detector에서 보존 — 전/중/후 프레임 증거. */
    private EvidenceCapture.Evidence visionFrameEvidence;

    private final Object rearLock = new Object();
    private Verdict[][] rearVerdicts; // [col][row], null = NONE

    private long startEpochMs;
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
     * 비전 판정 전·중·후 프레임 증거 ({@code evidence_pre_-3f} 등).
     * {@link #stop()} 이후에도 유지.
     */
    public synchronized EvidenceCapture.Evidence getVisionFrameEvidence() {
        return visionFrameEvidence;
    }

    /**
     * 클라 {@code RoiNcc} detector에서 수확한 ±3프레임 증거 수용.
     * (모니터는 Session.visionDet가 아닌 RoiNcc det로 process 하므로 stop 전에 넘긴다)
     */
    public synchronized void acceptVisionFrameEvidence(EvidenceCapture.Evidence e) {
        if (e != null) {
            this.visionFrameEvidence = e;
        }
    }

    /**
     * 클라 폴더에 비전 증거 PNG 저장 (OpenCV Mat은 core에서만 다룸).
     * {@code evidence_pre_-3f.png} / {@code evidence_decide.png} / {@code evidence_post_+3f.png}
     */
    public synchronized void saveVisionFrameEvidenceTo(File dir) {
        if (dir == null || visionFrameEvidence == null) {
            return;
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        writeVisionSnap(dir, "evidence_pre_-3f.png", visionFrameEvidence.pre);
        writeVisionSnap(dir, "evidence_decide.png", visionFrameEvidence.decide);
        writeVisionSnap(dir, "evidence_post_+3f.png", visionFrameEvidence.post);
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
        this.startEpochMs = System.currentTimeMillis();
        SyncBus.get().reset();
        this.startNanoSec = SyncBus.now();
        initRearVerdicts(snap);

        final BeepMatcher feedMatcher = bm;
        final AudioRecorder feedRec = rec;
        final double t0 = this.startNanoSec;
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
        fireState();
    }

    /**
     * 비전 프레임 주입. UI가 카메라 폴링 후 호출.
     * 스냅샷 기준 검출기를 lazy 생성.
     */
    public RoiMatchResult processVisionFrame(BufferedImage bi) {
        if (!running || bi == null || snapshot == null) {
            return null;
        }
        RoiMatchDetector det;
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
        latchVisionPass(r);
        fireVision(r);
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
        fireState();
    }

    /** Select 포인트 전부 MEASURING. */
    public void markRearMeasuring() {
        setAllSelectedVerdict(Verdict.MEASURING);
    }

    /** Select 포인트 전부 PASS (Kickoff 제품 규칙용). */
    public void markRearPass() {
        setAllSelectedVerdict(Verdict.PASS);
        if (evidence != null && audioPass && visionPass) {
            evidence.setOverallPassMs(System.currentTimeMillis() - startEpochMs);
        }
    }

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

    private void setAllSelectedVerdict(Verdict v) {
        if (snapshot == null) {
            return;
        }
        List<int[]> pts = snapshot.rearSelectedPoints;
        if (pts == null) {
            return;
        }
        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i);
            if (p != null && p.length >= 2) {
                setRearVerdict(p[0], p[1], v);
            }
        }
    }

    private RoiMatchDetector ensureVisionDetector(BufferedImage bi) throws Exception {
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
            visionDet = new RoiMatchDetector(path, null, snapshot.simThr);
            int[] roi = snapshot.toRoiPixels(visionDet.canonWidth(), visionDet.canonHeight());
            if (roi != null) {
                visionDet.setRoi(roi);
            }
            visionDet.setSimThr(snapshot.simThr);
        } else {
            int[] roi = snapshot.toRoiPixels(bi.getWidth(), bi.getHeight());
            visionDet = new RoiMatchDetector(bi, roi, snapshot.simThr);
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
