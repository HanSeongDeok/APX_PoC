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
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.measure.EvidenceBundle;
import com.suresofttech.apx.core.measure.EvidenceStore;
import com.suresofttech.apx.core.measure.MeasureConfigSnapshot;
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
 * 증거: {@link #setEvidenceDir} = 증거 <b>루트</b>, {@link #setTcId} = 측정 TC.
 * 실제 저장은 {@code <루트>/<tcId>/audio|vision|rear/} ({@link EvidenceStore}).
 * <ul>
 *   <li>{@code audio/} — {@code full.wav}, {@code clip.wav}(PASS 시작~해제),
 *       {@code wave_pass.png}, {@code wave_full.png}</li>
 *   <li>{@code vision/} — {@code evidence_pre_-1f.png}, {@code evidence_decide.png},
 *       {@code evidence_post_+1f.png}</li>
 *   <li>{@code rear/} — PASS/FAIL만 {@code <셀tcId>_c_r_VERDICT_WxH.png}, {@code combined_….png}</li>
 * </ul>
 */
public class KickoffView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.kickoff";

    private Display display;
    private Button startBtn;
    private Button stopBtn;
    private Button settingsBtn;
    private Button evidenceDirBtn;
    private Label audioLbl;
    private Label visionLbl;
    private Label syncLbl;
    private Label overallLbl;
    private Label evidenceLbl;

    /** 이번 측정에서 저장된 후방 셀 스냅샷 id — 결과 탭 조회 테스트로 넘긴다. */
    private final List<String> lastRearTcIds = new ArrayList<String>();

    private MeasureSession.Listener sessionListener;
    private boolean visionSnapTaken;
    private boolean rearSnapTaken;
    /** 클라가 넣는 증거 루트. null이면 stop 시 {@code ~/apx-evidence}. */
    private File evidenceDir;
    /** Aesop/클라 측정 TC id. null이면 저장 시 시각 스탬프로 자동 부여. */
    private String measureTcId;
    /** 직전 저장에 쓰인 측정 TC id(sanitize 후). */
    private String lastMeasureTcId;
    /** 직전 측정 TC 폴더 — 결과 탭 스크럽이 바로 물 수 있게. */
    private File lastEvidenceDir;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(4, false));

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

        evidenceDirBtn = new Button(parent, SWT.PUSH);
        evidenceDirBtn.setText("저장 경로…");
        evidenceDirBtn.setToolTipText("증거 루트 폴더(아래에 TC별 하위 폴더가 생깁니다)");
        evidenceDirBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        evidenceDirBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                chooseEvidenceDir();
            }
        });

        // 한 줄씩: 음향 → 비전 → 동기 → 최종 → 증거 (4열 그리드에서 가로로 붙지 않게 span=4)
        audioLbl = statusLabel(parent, "음향: —");
        visionLbl = statusLabel(parent, "비전: —");
        syncLbl = statusLabel(parent, "동기: — (≤30ms, CAN 추후)");
        overallLbl = statusLabel(parent, "최종: — (중단 시 판정)");

        evidenceLbl = new Label(parent, SWT.WRAP);
        evidenceLbl.setText("증거: (중단 시 저장) · 루트: ~/apx-evidence/<tcId>/");
        GridData eg = new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1);
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
        Label l = new Label(parent, SWT.WRAP);
        l.setText(text);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1);
        gd.widthHint = 280;
        l.setLayoutData(gd);
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
            // 지정 포인트를 측정중으로 — 어느 포인트가 이번 측정 대상인지는 클라 규칙이다
            applyRearVerdicts(session, buildRearVerdicts(session, Verdict.MEASURING));

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
        if (rear != null) {
            rear.setVerdicts(rearResults);   // 내부에서 session에도 반영
            rear.setFinalVerdict(sync.overallPass, bothPassedAtMs(sync), sync.summary);
            rear.refreshNow();   // PASS/FAIL 색을 즉시 그린 뒤 캡처(빈 화면 스냅샷 방지)
        } else {
            applyRearVerdicts(session, rearResults);
        }

        // 음향: PASS 밴드 종료 스냅샷. 비전 ±3프레임: RoiNcc→Session (det 파괴 전).
        // 후방 Result: overallPass일 때만(오프스크린 capturePng).
        if (audio != null) {
            audio.flushPassSpanSnapshotIfNeeded();
            // 전체 파형 스냅샷(측정 종료 시점) — wave_full.png
            MeasureEvidence aev = session.getEvidence();
            byte[] fullPng = audio.capturePng();
            if (aev != null && fullPng != null) {
                aev.putAudioFullPng(fullPng);
            }
        }
        if (vision != null) {
            vision.harvestEvidenceToSession(session);
        }
        // 후방은 중단 시 항상 캡처(PASS/FAIL 무관) — 결과 View·증거에 후방 스냅샷이 비지 않도록
        captureFirstPassSnapshots(session, false, sync.visionPass, true);
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

        String path = saveEvidence(session.getEvidence(), rear, sync);
        if (path != null) {
            String tc = lastMeasureTcId == null ? "" : lastMeasureTcId;
            evidenceLbl.setText("증거 저장 [" + tc + "]: " + path);
            // 결과 탭이 이 TC 폴더로 전 구간 스크럽을 물린다
            LastMeasureResult.get().publishEvidence(
                    new File(path), lastMeasureTcId, lastRearTcIds);
        } else {
            evidenceLbl.setText("증거: 저장 실패 또는 없음");
        }
        relayoutStatus();
        refreshButtons();
    }

    /** WRAP 라벨 텍스트 변경 후 줄바꿈·세로 배치가 다시 잡히도록. */
    private void relayoutStatus() {
        Composite p = audioLbl == null || audioLbl.isDisposed() ? null : audioLbl.getParent();
        if (p != null && !p.isDisposed()) {
            p.layout(true, true);
        }
    }

    /**
     * 증거 <b>루트</b>. 실제 1회 측정은 {@code <루트>/<tcId>/} 아래 ({@link EvidenceStore}).
     * null이면 다음 stop 시 {@code ~/apx-evidence/<tcId>/} 사용.
     */
    public void setEvidenceDir(File dir) {
        this.evidenceDir = dir;
    }

    public File getEvidenceDir() {
        return evidenceDir;
    }

    /**
     * 측정 TC id(Aesop). stop 시 {@code <루트>/<tcId>/}에 저장한다.
     * null/빈 문자열이면 시각 스탬프({@code yyyyMMdd_HHmmss})를 쓴다.
     * 후방 셀 스냅샷 파일명의 {@code TC-001}과는 별개다.
     */
    public void setTcId(String tcId) {
        this.measureTcId = tcId;
    }

    public String getTcId() {
        return measureTcId;
    }

    /** 직전 저장에 사용된 측정 TC id. 저장 전이면 null. */
    public String getLastMeasureTcId() {
        return lastMeasureTcId;
    }

    /** "저장 경로…" — 증거 루트를 사용자가 직접 지정. 취소하면 기존 값 유지. */
    private void chooseEvidenceDir() {
        DirectoryDialog dlg = new DirectoryDialog(getSite().getShell(), SWT.OPEN);
        dlg.setText("증거 루트 폴더 선택");
        dlg.setMessage("TC별 하위 폴더가 생길 루트를 선택하세요.");
        if (evidenceDir != null) {
            dlg.setFilterPath(evidenceDir.getAbsolutePath());
        } else {
            dlg.setFilterPath(System.getProperty("user.home"));
        }
        String picked = dlg.open();
        if (picked == null) {
            return;
        }
        evidenceDir = new File(picked);
        if (evidenceLbl != null && !evidenceLbl.isDisposed()) {
            evidenceLbl.setText("증거 루트: " + evidenceDir.getAbsolutePath()
                    + " (저장 시 <tcId>/ 하위)");
        }
        // 실제 rear/ 는 stop 시 TC 폴더 아래로 지정된다(resolveEvidenceDir)
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
        // stop()의 fireState가 async로 오면 중단 직후 쓴 최종 판정을 덮어쓴다 → 측정 중만 갱신
        if (!s.isRunning()) {
            return;
        }
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
        } else {
            syncLbl.setText("동기: — (중단 시 판정, ≤30ms)");
            overallLbl.setText("최종: — (중단 시 판정)");
        }
        relayoutStatus();
    }

    /** 만든 판정 목록을 세션 저장소에 반영 — 후방 View가 없을 때/시작 시. */
    private static void applyRearVerdicts(MeasureSession session, List<VerdictResult> results) {
        for (int i = 0; i < results.size(); i++) {
            VerdictResult r = results.get(i);
            session.setRearVerdict(r.getPoint().x, r.getPoint().y, r.getVerdict());
        }
    }

    /**
     * <b>측정 결과 → 후방 격자 매핑 규칙(클라 영역).</b>
     * 이 데모는 "지정 포인트 전부에 같은 판정"이라는 단순 규칙을 쓴다.
     * 포인트마다 TC가 다른 실제 운용에서는 이 메서드를 시험 계획에 맞게 바꾼다 —
     * core는 어느 포인트가 어느 TC인지 모르므로 이 규칙을 갖지 않는다.
     */
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

    /**
     * 두 채널이 <b>모두</b> PASS가 된 시각(늦은 쪽) — 후방 판독값의 "최종 @ ms".
     * 한쪽이라도 미검출이면 null. {@code MeasureEvidence.overallPassMs}는 중단을 누른 시각이라
     * 표출용으로는 쓰지 않는다.
     */
    private static Long bothPassedAtMs(MeasureSyncResult sync) {
        if (sync == null || sync.audioPassMs == null || sync.visionPassMs == null) {
            return null;
        }
        return Long.valueOf(Math.max(sync.audioPassMs.longValue(), sync.visionPassMs.longValue()));
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
     * @param rearPass true면 후방 캡처 — 중단 시 setVerdicts(PASS/FAIL) 반영 후 항상 true로 호출
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
     * 클라 {@code setEvidenceDir} 폴더 아래 채널별 하위 폴더로 증거 저장.
     * <ul>
     *   <li>{@code audio/}: {@code full.wav}, {@code clip.wav}(PASS 시작~해제),
     *       {@code wave_pass.png}(PASS 시점), {@code wave_full.png}(전체)</li>
     *   <li>{@code vision/}: {@code evidence_pre_-1f.png}, {@code evidence_decide.png},
     *       {@code evidence_post_+1f.png}</li>
     *   <li>{@code rear/}: PASS/FAIL만 {@code <tcId>_c_r_VERDICT_WxH.png}, {@code combined_…png}</li>
     * </ul>
     */
    private String saveEvidence(MeasureEvidence ev, RearMonitorView rear, MeasureSyncResult sync) {
        if (ev == null) {
            return null;
        }
        try {
            File dir = resolveEvidenceDir();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                return null;
            }
            applyAudioPassSpan(ev);
            // 채널별 하위 폴더 — <루트>/<tcId>/audio|vision|rear
            ev.saveTo(new File(dir, EvidenceBundle.AUDIO_DIR));
            saveVisionEvidence(new File(dir, EvidenceBundle.VISION_DIR));
            saveRearSnapshots(new File(dir, EvidenceBundle.REAR_DIR), rear);
            writeEvidenceMeta(dir, ev, sync);
            lastEvidenceDir = dir;
            return dir.getAbsolutePath();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 결과 재오픈용 메타 — 앱을 껐다 켜도 결과 탭이 PASS 시각·판정·타임라인 길이를
     * 복원할 수 있어야 한다(메모리 {@link LastMeasureResult}는 세션과 함께 사라진다).
     */
    private void writeEvidenceMeta(File dir, MeasureEvidence ev, MeasureSyncResult sync)
            throws Exception {
        double durationMs = 0;
        double[] samples = ev.getAudioSamples();
        int sr = ev.getAudioSampleRate();
        if (samples != null && sr > 0) {
            durationMs = samples.length * 1000.0 / sr;
        }
        List<double[]> audioSpans = null;
        AudioMonitorView audio = findAudio();
        if (audio != null && audio.getScope() != null && !audio.getScope().isDisposed()) {
            audioSpans = audio.getScope().getPassSpans();
        }
        MeasureConfigSnapshot snap = MeasureSession.get().getSnapshot();
        double[] roiNorm = snap == null ? null : snap.roiNorm;
        double simThr = snap == null ? 0 : snap.simThr;
        EvidenceBundle.writeMeta(dir,
                sync != null && sync.overallPass,
                sync == null ? "" : sync.summary,
                ev.getAudioPassMs(), ev.getVisionPassMs(),
                sync == null ? null : sync.syncSpreadMs,
                sync != null && sync.syncOk,
                durationMs,
                ev.getAudioPassStartMs(), ev.getAudioPassEndMs(),
                audioSpans, roiNorm, simThr);
    }

    /** 마지막으로 증거를 저장한 폴더 — 결과 탭이 바로 열 수 있게. */
    public File getLastEvidenceDir() {
        return lastEvidenceDir;
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
        // FULL 녹화본(full.avi + frames.csv) — 측정 중엔 temp에 쌓고 여기서 증거 폴더로 이동
        MeasureSession.get().moveVisionRecordingTo(dir);
    }

    /**
     * {@code <루트>/<tcId>/} 준비. tcId 미설정이면 시각 스탬프.
     * {@link #lastMeasureTcId}·{@link #lastEvidenceDir}를 갱신한다.
     */
    private File resolveEvidenceDir() {
        File root = evidenceDir;
        if (root == null) {
            root = new File(System.getProperty("user.home"), "apx-evidence");
        }
        String id = measureTcId;
        if (id == null || id.trim().isEmpty()) {
            id = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        }
        File dir = EvidenceStore.at(root).prepare(id);
        if (dir == null) {
            lastMeasureTcId = null;
            return null;
        }
        lastMeasureTcId = dir.getName();
        lastEvidenceDir = dir;
        return dir;
    }

    /**
     * 후방 스냅샷 — PASS/FAIL만 ({@code MEASURING}·{@code NONE} 제외).
     * 데모 tcId = {@code TC-001, TC-002 …} (이솝은 실제 TC ID를 넘기면 됨).
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
        int seq = 0;
        for (int i = 0; i < verdicts.size(); i++) {
            VerdictResult r = verdicts.get(i);
            Verdict v = r.getVerdict();
            if (v != Verdict.PASS && v != Verdict.FAIL) {
                continue; // MEASURING 스냅샷 안 찍음
            }
            // 좌표는 파일명 뒤쪽(_c_r_)에 이미 들어가므로 tcId엔 넣지 않는다(중복 방지).
            String tcId = String.format("TC-%03d", Integer.valueOf(++seq));
            File f = canvas.saveVerdictSnapshot(r, tcId);
            if (f != null) {
                tcIds.add(tcId);
            }
        }
        if (tcIds.size() >= 2) {
            canvas.getCombinedSnapshot(tcIds);
        }
        rear.setEvidenceNote(evidenceNote(tcIds));
        lastRearTcIds.clear();
        lastRearTcIds.addAll(tcIds);
    }

    /** 후방 판독값에 표기할 저장 결과 요약 — 파일명 규약을 그대로 보여준다. */
    private static String evidenceNote(List<String> tcIds) {
        if (tcIds == null || tcIds.isEmpty()) {
            return "저장된 스냅샷 없음";
        }
        return tcIds.size() + "건 (" + tcIds.get(0) + "…) · <tcId>_c_r_VERDICT_WxH.png"
                + (tcIds.size() >= 2 ? " + combined" : "");
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
