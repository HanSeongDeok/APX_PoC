package com.suresofttech.apx.client.view;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.measure.MeasureEvidence;
import com.suresofttech.apx.core.measure.MeasureSession;
import com.suresofttech.apx.core.measure.MeasureSyncResult;
import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.rear.VerdictResult;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;

/**
 * 측정 Kickoff — Start/Stop/설정.
 * 측정 중: 음향·비전 PASS 시각(물리지연 포함) + 자체 판단(gap+분석) 표시.
 * 중단 시: (max−min)≤30ms 동기 포함 최종 PASS/FAIL. L2 캘리브 보정 없음.
 * 증거: {@link #setEvidenceDir}로 폴더를 넣으면 음향({@code full.wav}/{@code clip.wav}=초록 PASS 구간),
 * 비전({@code evidence_*}.png), 후방 PASS/FAIL만({@code <tcId>_c_r_VERDICT_WxH.png}) 저장.
 */
public class KickoffView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.kickoff";

    private Display display;
    private Button startBtn;
    private Button stopBtn;
    private Button settingsBtn;
    private Label audioLbl;
    private Label visionLbl;
    private Label syncLbl;
    private Label overallLbl;
    private Label evidenceLbl;

    private MeasureSession.Listener sessionListener;
    private boolean visionSnapTaken;
    private boolean rearSnapTaken;
    /** 클라가 넣는 증거 루트. null이면 stop 시 {@code ~/apx-evidence/<ts>/}. */
    private File evidenceDir;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(3, false));

        startBtn = new Button(parent, SWT.PUSH);
        startBtn.setText("측정 시작");
        startBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        startBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                doStart();
            }
        });

        stopBtn = new Button(parent, SWT.PUSH);
        stopBtn.setText("측정 중단");
        stopBtn.setEnabled(false);
        stopBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        stopBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                doStop();
            }
        });

        settingsBtn = new Button(parent, SWT.PUSH);
        settingsBtn.setText("설정");
        settingsBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        settingsBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                openSettings();
            }
        });

        audioLbl = statusLabel(parent, "음향: —");
        visionLbl = statusLabel(parent, "비전: —");
        syncLbl = statusLabel(parent, "동기: — (≤30ms, CAN 추후)");
        GridData sg = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
        syncLbl.setLayoutData(sg);

        overallLbl = statusLabel(parent, "최종: — (중단 시 판정)");
        GridData og = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
        overallLbl.setLayoutData(og);

        evidenceLbl = new Label(parent, SWT.WRAP);
        evidenceLbl.setText("증거: (중단 시 저장)");
        GridData eg = new GridData(SWT.FILL, SWT.TOP, true, true, 3, 1);
        eg.widthHint = 280;
        evidenceLbl.setLayoutData(eg);

        sessionListener = new MeasureSession.Listener() {
            public void onAudioTick(MatchResult match, double[] waveBuf, double elapsedSec) {
            }

            public void onVisionMatch(RoiMatchResult result) {
            }

            public void onState(final boolean audioPass, final boolean visionPass,
                    final boolean overallPass) {
                if (display.isDisposed()) {
                    return;
                }
                display.asyncExec(new Runnable() {
                    public void run() {
                        applyLiveState(audioPass, visionPass);
                    }
                });
            }
        };
        MeasureSession.get().addListener(sessionListener);
        refreshButtons();
    }

    private static Label statusLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    private void openSettings() {
        if (MeasureSession.get().isRunning()) {
            MessageDialog.openInformation(getSite().getShell(), "설정",
                    "측정 중에는 설정을 바꿀 수 없습니다. 중단 후 다시 시도하세요.");
            return;
        }
        SettingsDialog dlg = new SettingsDialog(getSite().getShell());
        if (dlg.open() == Window.OK) {
            applySettingsToMonitors();
        }
    }

    /** 설정 확인 후 모니터 View에 현재 ApxSettings 반영. */
    private void applySettingsToMonitors() {
        VisionMonitorView vision = ensureVision();
        RearMonitorView rear = ensureRear();
        ensureAudio();
        if (vision != null) {
            vision.applyFromSettings();
        }
        if (rear != null) {
            rear.applyFromSettings();
        }
        evidenceLbl.setText("설정 적용됨 (다음 측정 시작 시 스냅샷)");
    }

    private void doStart() {
        visionSnapTaken = false;
        rearSnapTaken = false;
        evidenceLbl.setText("증거: (측정 중)");
        audioLbl.setText("음향: 측정 중");
        visionLbl.setText("비전: 측정 중");
        syncLbl.setText("동기: — (중단 시 판정, ≤30ms)");
        overallLbl.setText("최종: — (중단 시 판정)");
        try {
            AudioMonitorView audio = ensureAudio();
            VisionMonitorView vision = ensureVision();
            RearMonitorView rear = ensureRear();

            MeasureSession session = MeasureSession.get();
            session.start();
            session.markRearMeasuring();

            if (audio != null) {
                audio.onMeasureStarted(session);
            }
            if (vision != null) {
                vision.onMeasureStarted(session);
            }
            if (rear != null) {
                rear.onMeasureStarted(session);
            }
            refreshButtons();
        } catch (Exception ex) {
            evidenceLbl.setText("시작 실패: " + ex.getMessage());
            refreshButtons();
        }
    }

    private void doStop() {
        MeasureSession session = MeasureSession.get();
        MeasureSyncResult sync = session.evaluateFinal();
        AudioMonitorView audio = findAudio();
        VisionMonitorView vision = findVision();
        RearMonitorView rear = findRear();

        // 중단 시에만 후방 PASS/FAIL — 측정 중은 MEASURING 유지
        Verdict rearVerdict = sync.overallPass ? Verdict.PASS : Verdict.FAIL;
        List<VerdictResult> rearResults = buildRearVerdicts(session, rearVerdict);
        if (sync.overallPass) {
            session.markRearPass(); // overallPassMs 기록
        }
        if (rear != null) {
            rear.setVerdicts(rearResults);
        } else {
            for (int i = 0; i < rearResults.size(); i++) {
                VerdictResult r = rearResults.get(i);
                session.setRearVerdict(r.getPoint().x, r.getPoint().y, r.getVerdict());
            }
        }

        // 음향: PASS 밴드 종료 스냅샷. 비전 ±3프레임: RoiNcc→Session (det 파괴 전).
        // 후방 Result: overallPass일 때만(오프스크린 capturePng).
        if (audio != null) {
            audio.flushPassSpanSnapshotIfNeeded();
        }
        if (vision != null) {
            vision.harvestEvidenceToSession(session);
        }
        captureFirstPassSnapshots(session, false, sync.visionPass, sync.overallPass);
        session.stop();

        if (audio != null) {
            audio.onMeasureStopped();
        }
        if (vision != null) {
            vision.onMeasureStopped();
        }
        if (rear != null) {
            rear.onMeasureStopped();
        }

        refreshPassLabels(sync.audioPass, sync.visionPass, sync.audioPassMs, sync.visionPassMs);
        if (sync.syncSpreadMs != null) {
            syncLbl.setText(String.format("동기: %.0f ms %s (≤%.0fms, CAN 추후)",
                    sync.syncSpreadMs.doubleValue(),
                    sync.syncOk ? "OK" : "FAIL",
                    MeasureSyncResult.SYNC_TOL_MS));
        } else {
            syncLbl.setText("동기: — (PASS 시각 2개 미만)");
        }
        overallLbl.setText("최종: " + (sync.overallPass ? "PASS" : "FAIL") + " — " + sync.summary);

        publishLastResult(sync, session.getEvidence());

        String path = saveEvidence(session.getEvidence(), rear);
        if (path != null) {
            evidenceLbl.setText("증거 저장: " + path);
        } else {
            evidenceLbl.setText("증거: 저장 실패 또는 없음");
        }
        refreshButtons();
    }

    /**
     * 증거 저장 폴더. 이솝/클라가 경로를 넣으면 그 아래에 규약 파일명이 생성된다.
     * null이면 다음 stop 시 {@code ~/apx-evidence/<yyyyMMdd_HHmmss>/} 사용.
     */
    public void setEvidenceDir(File dir) {
        this.evidenceDir = dir;
    }

    public File getEvidenceDir() {
        return evidenceDir;
    }

    /**
     * 측정 중 표시만 — 후방은 MEASURING 유지.
     * 최종 PASS/FAIL·후방 setVerdicts는 중단 시.
     */
    private void applyLiveState(boolean audioPass, boolean visionPass) {
        if (audioLbl.isDisposed()) {
            return;
        }
        MeasureSession s = MeasureSession.get();
        refreshPassLabels(audioPass, visionPass, s.getAudioPassMs(), s.getVisionPassMs());
        // 음향 스냅샷은 AudioMonitorView가 PASS→비PASS 엣지에서. 후방은 중단+overallPass만.
        captureFirstPassSnapshots(s, false, visionPass, false);

        if (audioPass && visionPass) {
            Long a = s.getAudioPassMs();
            Long v = s.getVisionPassMs();
            if (a != null && v != null) {
                long spread = Math.abs(a.longValue() - v.longValue());
                syncLbl.setText(String.format("동기(미리보기): %d ms (≤%.0fms, 최종은 중단 시)",
                        spread, MeasureSyncResult.SYNC_TOL_MS));
            }
            overallLbl.setText("최종: — (중단 시 판정)");
        } else if (s.isRunning()) {
            syncLbl.setText("동기: — (중단 시 판정, ≤30ms)");
            overallLbl.setText("최종: — (중단 시 판정)");
        }
    }

    /** Select 포인트에 동일 최종 판정 목록 생성. */
    private static List<VerdictResult> buildRearVerdicts(MeasureSession session, Verdict verdict) {
        List<VerdictResult> out = new ArrayList<VerdictResult>();
        if (session == null || session.getSnapshot() == null || verdict == null) {
            return out;
        }
        List<int[]> pts = session.getSnapshot().rearSelectedPoints;
        if (pts == null) {
            return out;
        }
        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i);
            if (p != null && p.length >= 2) {
                out.add(new VerdictResult(p[0], p[1], verdict));
            }
        }
        return out;
    }

    private void refreshPassLabels(boolean audioPass, boolean visionPass, Long audioMs, Long visionMs) {
        MeasureSession s = MeasureSession.get();
        if (audioPass && audioMs != null) {
            audioLbl.setText(formatPassLine("음향", audioMs, s.getAudioJudgeMs(),
                    s.getAudioGapMs(), s.getAudioAnalysisMs()));
        } else if (s.isRunning()) {
            audioLbl.setText("음향: 측정 중");
        } else {
            audioLbl.setText(audioPass ? "음향: PASS" : "음향: FAIL");
        }
        if (visionPass && visionMs != null) {
            visionLbl.setText(formatPassLine("비전", visionMs, s.getVisionJudgeMs(),
                    s.getVisionGapMs(), s.getVisionAnalysisMs()));
        } else if (s.isRunning()) {
            visionLbl.setText("비전: 측정 중");
        } else {
            visionLbl.setText(visionPass ? "비전: PASS" : "비전: FAIL");
        }
    }

    /**
     * PASS 한 줄: 물리지연 포함 검출시각 + 자체 판단(간격+분석).
     * 예) {@code 음향: PASS @ 1030 ms (자체판단 20.1 = 간격 14.0 + 분석 6.1)}
     */
    private static String formatPassLine(String channel, Long passAtMs,
            Double judgeMs, Double gapMs, Double analysisMs) {
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
     * 비전/후방 모니터 스냅샷 → MeasureEvidence.
     * 음향은 {@link AudioMonitorView}가 PASS 밴드 종료 시점에 넣는다.
     * @param rearPass true면 후방 — overallPass(음향∧비전∧동기≤30ms) + setVerdicts(PASS) 후
     */
    private void captureFirstPassSnapshots(MeasureSession session,
            boolean audioPass, boolean visionPass, boolean rearPass) {
        MeasureEvidence ev = session == null ? null : session.getEvidence();
        if (ev == null) {
            return;
        }
        // audioPass 인자는 호환용 — 음향 캡처는 AudioMonitorView.flush/falling-edge
        if (visionPass && !visionSnapTaken) {
            VisionMonitorView vision = findVision();
            if (vision != null) {
                byte[] png = vision.capturePng();
                if (png != null) {
                    ev.putVisionPng(png);
                    visionSnapTaken = true;
                }
            }
        }
        if (rearPass && !rearSnapTaken) {
            RearMonitorView rear = findRear();
            if (rear != null) {
                byte[] png = rear.capturePng();
                if (png != null) {
                    ev.putRearPng(png);
                    rearSnapTaken = true;
                }
            }
        }
    }

    private void publishLastResult(MeasureSyncResult sync, MeasureEvidence ev) {
        MeasureSession s = MeasureSession.get();
        byte[] audioPng = ev == null ? null : ev.getAudioScopeSnapshot();
        byte[] visionPng = ev == null ? null : ev.getVisionCanvasSnapshot();
        byte[] rearPng = ev == null ? null : ev.getRearCanvasSnapshot();
        LastMeasureResult.get().publish(
                sync.overallPass, sync.summary,
                sync.audioPassMs, sync.visionPassMs,
                s.getAudioJudgeMs(), s.getVisionJudgeMs(),
                s.getAudioGapMs(), s.getVisionGapMs(),
                s.getAudioAnalysisMs(), s.getVisionAnalysisMs(),
                sync.syncSpreadMs, sync.syncOk,
                audioPng, visionPng, rearPng);
        // 결과 View가 닫혀 있으면 열어 최신 결과 보이게
        ensure(ResultView.ID, ResultView.class);
    }

    /**
     * 클라 {@code setEvidenceDir} 폴더에 노션 규약 증거 저장.
     * <ul>
     *   <li>음향: {@code full.wav}, {@code wave_center.png},
     *       {@code clip.wav}(PASS 초록 시작~끝), {@code pass_times.txt}</li>
     *   <li>비전: {@code evidence_pre_-3f.png}, {@code evidence_decide.png}, {@code evidence_post_+3f.png}</li>
     *   <li>후방: PASS/FAIL만 {@code <tcId>_c_r_VERDICT_WxH.png}, {@code combined_…png}</li>
     * </ul>
     */
    private String saveEvidence(MeasureEvidence ev, RearMonitorView rear) {
        if (ev == null) {
            return null;
        }
        try {
            File dir = resolveEvidenceDir();
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            applyAudioPassSpan(ev);
            ev.saveTo(dir);
            saveVisionEvidence(dir);
            saveRearSnapshots(dir, rear);
            return dir.getAbsolutePath();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * AudioScope 초록 PASS 밴드 → clip.wav 구간.
     * 최초 PASS 시각을 포함하는 밴드를 쓰고, 없으면 첫 밴드.
     */
    private void applyAudioPassSpan(MeasureEvidence ev) {
        AudioMonitorView audio = findAudio();
        if (audio == null) {
            return;
        }
        AudioScope scope = audio.getScope();
        if (scope == null || scope.isDisposed()) {
            return;
        }
        List<double[]> spans = scope.getPassSpans();
        if (spans == null || spans.isEmpty()) {
            return;
        }
        double[] chosen = spans.get(0);
        Long passMs = ev.getAudioPassMs();
        if (passMs != null) {
            double t = passMs.longValue();
            for (int i = 0; i < spans.size(); i++) {
                double[] sp = spans.get(i);
                if (t >= sp[0] && t <= sp[1]) {
                    chosen = sp;
                    break;
                }
            }
        }
        ev.setAudioPassSpan(chosen[0], chosen[1]);
    }

    /** 비전 증거 PNG — OpenCV는 {@link MeasureSession#saveVisionFrameEvidenceTo} (core). */
    private void saveVisionEvidence(File dir) {
        MeasureSession.get().saveVisionFrameEvidenceTo(dir);
    }

    private File resolveEvidenceDir() {
        if (evidenceDir != null) {
            return evidenceDir;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return new File(System.getProperty("user.home"), "apx-evidence" + File.separator + ts);
    }

    /**
     * 후방 스냅샷 — PASS/FAIL만 ({@code MEASURING}·{@code NONE} 제외).
     * 데모 tcId = {@code P_c{col}_r{row}} (이솝은 실제 TC ID를 넘기면 됨).
     */
    private void saveRearSnapshots(File dir, RearMonitorView rear) {
        if (rear == null) {
            return;
        }
        RearGridCanvas canvas = rear.getCanvas();
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        canvas.setSnapshotDir(dir);
        List<VerdictResult> verdicts = canvas.getVerdicts();
        if (verdicts == null || verdicts.isEmpty()) {
            // 판정색이 아직 없으면 Select 포인트 + 세션 최종 판정으로 구성
            MeasureSession session = MeasureSession.get();
            List<int[]> pts = session.getSnapshot() == null
                    ? null : session.getSnapshot().rearSelectedPoints;
            if (pts == null || pts.isEmpty()) {
                return;
            }
            verdicts = new ArrayList<VerdictResult>();
            for (int i = 0; i < pts.size(); i++) {
                int[] p = pts.get(i);
                Verdict v = session.getRearVerdict(p[0], p[1]);
                if (v != Verdict.PASS && v != Verdict.FAIL) {
                    v = Verdict.FAIL; // 미확정·MEASURING → 중단 시 FAIL로 스냅샷
                }
                VerdictResult vr = new VerdictResult(p[0], p[1], v);
                canvas.setVerdict(vr);
                verdicts.add(vr);
            }
        }
        List<String> tcIds = new ArrayList<String>();
        for (int i = 0; i < verdicts.size(); i++) {
            VerdictResult r = verdicts.get(i);
            Verdict v = r.getVerdict();
            if (v != Verdict.PASS && v != Verdict.FAIL) {
                continue; // MEASURING 스냅샷 안 찍음
            }
            String tcId = "P_c" + r.getPoint().x + "_r" + r.getPoint().y;
            File f = canvas.saveVerdictSnapshot(r, tcId);
            if (f != null) {
                tcIds.add(tcId);
            }
        }
        if (tcIds.size() >= 2) {
            canvas.getCombinedSnapshot(tcIds);
        }
    }

    private void showMonitorViews() throws PartInitException {
        if (ensureAudio() == null || ensureVision() == null || ensureRear() == null) {
            throw new PartInitException("모니터 View를 열 수 없습니다");
        }
    }

    private AudioMonitorView ensureAudio() {
        return ensure(AudioMonitorView.ID, AudioMonitorView.class);
    }

    private VisionMonitorView ensureVision() {
        return ensure(VisionMonitorView.ID, VisionMonitorView.class);
    }

    private RearMonitorView ensureRear() {
        return ensure(RearMonitorView.ID, RearMonitorView.class);
    }

    private <T> T ensure(String id, Class<T> type) {
        try {
            IWorkbenchPage page = getSite().getPage();
            if (page == null) {
                page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            }
            if (page == null) {
                return null;
            }
            IViewPart v = page.showView(id);
            if (type.isInstance(v)) {
                return type.cast(v);
            }
        } catch (PartInitException ex) {
            // fall through to find
        }
        return find(id, type);
    }

    private void refreshButtons() {
        boolean running = MeasureSession.get().isRunning();
        if (startBtn != null && !startBtn.isDisposed()) {
            startBtn.setEnabled(!running);
        }
        if (stopBtn != null && !stopBtn.isDisposed()) {
            stopBtn.setEnabled(running);
        }
        if (settingsBtn != null && !settingsBtn.isDisposed()) {
            settingsBtn.setEnabled(!running);
        }
    }

    private AudioMonitorView findAudio() {
        return find(AudioMonitorView.ID, AudioMonitorView.class);
    }

    private VisionMonitorView findVision() {
        return find(VisionMonitorView.ID, VisionMonitorView.class);
    }

    private RearMonitorView findRear() {
        return find(RearMonitorView.ID, RearMonitorView.class);
    }

    private <T> T find(String id, Class<T> type) {
        IWorkbenchPage page = getSite().getPage();
        if (page == null) {
            return null;
        }
        IViewPart v = page.findView(id);
        if (type.isInstance(v)) {
            return type.cast(v);
        }
        return null;
    }

    @Override
    public void setFocus() {
        if (startBtn != null && !startBtn.isDisposed()) {
            startBtn.setFocus();
        }
    }

    @Override
    public void dispose() {
        MeasureSession.get().removeListener(sessionListener);
        super.dispose();
    }
}
