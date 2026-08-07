package com.suresofttech.apx.client.view;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.client.result.EvidenceScrubber;
import com.suresofttech.apx.core.measure.MeasureSyncResult;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;

/**
 * 최근 측정 결과 View — DB 없이 {@link LastMeasureResult} 1건만 표시.
 * 측정 시각(검출·자체판단·동기) + 모니터 스냅샷
 * (음향=PASS 밴드 종료 시점, 비전=최초 PASS, 후방=overallPass).
 */
public class ResultView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.result";

    private Display display;
    private ScrolledComposite scroll;
    private Composite body;
    private Label emptyLbl;
    private Label headerLbl;
    private Label audioTimeLbl;
    private Label visionTimeLbl;
    private Label syncLbl;
    private Label audioSnapLbl;
    private Label visionSnapLbl;
    private Label rearSnapLbl;
    private Label audioImgLbl;
    private Label visionImgLbl;
    private Label rearImgLbl;

    private Image audioImg;
    private Image visionImg;
    private Image rearImg;

    // 전 구간 다시 보기(스크럽)
    private Label scrubSectionLbl;
    private Label scrubPathLbl;
    private Button openDirBtn;
    private EvidenceScrubber scrubber;
    private File openedEvidenceDir;

    // 후방 스냅샷 조회 API 테스트
    private org.eclipse.swt.widgets.List tcIdList;
    private Text snapResultText;
    private final java.util.List<String> lastTcIds = new ArrayList<String>();
    /** 조회 결과로 마지막에 받은 파일 — "결과 열기" 버튼 대상. */
    private File lastQueriedFile;

    private LastMeasureResult.Listener resultListener;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);

        body = new Composite(scroll, SWT.NONE);
        body.setLayout(new GridLayout(1, false));
        scroll.setContent(body);

        emptyLbl = label(body, "아직 측정 결과가 없습니다. Kickoff에서 측정 시작 → 중단 하면 여기에 표시됩니다.");
        headerLbl = label(body, "");
        audioTimeLbl = label(body, "");
        visionTimeLbl = label(body, "");
        syncLbl = label(body, "");

        buildScrubSection(body);
        buildSnapshotQueryGroup(body);

        audioSnapLbl = section(body, "음향 모니터 — PASS 구간 종료 스냅샷");
        audioImgLbl = imageSlot(body);
        visionSnapLbl = section(body, "비전 모니터 — 최초 PASS 스냅샷");
        visionImgLbl = imageSlot(body);
        rearSnapLbl = section(body, "후방 모니터 — 최초 PASS 스냅샷");
        rearImgLbl = imageSlot(body);

        resultListener = new LastMeasureResult.Listener() {
            public void onResult(final LastMeasureResult result) {
                if (display.isDisposed()) {
                    return;
                }
                display.asyncExec(new Runnable() {
                    public void run() {
                        refresh(result);
                    }
                });
            }
        };
        LastMeasureResult.get().addListener(resultListener);
        refresh(LastMeasureResult.get());
    }

    /**
     * 전 구간 다시 보기 — 슬라이더 하나로 비전 녹화 프레임과 음향 파형·재생을 같이 끈다.
     * 직전 측정은 자동으로 물리고, 지난 TC는 "증거 폴더 열기…"로 다시 연다
     * (메모리가 아니라 폴더의 {@code meta.properties}·{@code full.avi}·{@code full.wav}에서 복원).
     */
    private void buildScrubSection(Composite parent) {
        scrubSectionLbl = section(parent, "전 구간 다시 보기 — 슬라이더를 옮기면 그 시점 화면·소리");

        Composite bar = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        bar.setLayout(gl);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        openDirBtn = new Button(bar, SWT.PUSH);
        openDirBtn.setText("증거 폴더 열기…");
        openDirBtn.setToolTipText("지난 TC의 증거 폴더를 열어 그때 측정을 되짚습니다");
        openDirBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        openDirBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                chooseEvidenceDir();
            }
        });

        scrubPathLbl = new Label(bar, SWT.NONE);
        scrubPathLbl.setText("(직전 측정 자동 연결)");
        scrubPathLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        scrubber = new EvidenceScrubber(parent);
        scrubber.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    /** 지난 증거 폴더 선택 → 스크럽 재연결. */
    private void chooseEvidenceDir() {
        DirectoryDialog dlg = new DirectoryDialog(getSite().getShell(), SWT.OPEN);
        dlg.setText("증거 폴더 열기");
        dlg.setMessage("audio/ · vision/ · rear/ 가 들어 있는 증거 루트를 선택하세요.");
        if (openedEvidenceDir != null) {
            dlg.setFilterPath(openedEvidenceDir.getAbsolutePath());
        } else {
            dlg.setFilterPath(System.getProperty("user.home"));
        }
        String picked = dlg.open();
        if (picked != null) {
            openEvidence(new File(picked));
        }
    }

    /** 증거 폴더를 스크럽에 물린다(같은 폴더면 다시 열지 않음). */
    private void openEvidence(File dir) {
        if (dir == null || scrubber == null || scrubber.isDisposed()) {
            return;
        }
        if (dir.equals(openedEvidenceDir)) {
            return;
        }
        boolean ok = scrubber.open(dir);
        openedEvidenceDir = ok ? dir : null;
        scrubPathLbl.setText(ok ? dir.getAbsolutePath()
                : "되짚을 자료가 없습니다: " + dir.getAbsolutePath());
        layoutScroll();
    }

    // ── 후방 스냅샷 조회 규약 API 테스트 (getSnapshot / getSnapshots / getCombinedSnapshot) ──
    // 측정이 아니라 "저장된 증거를 어떻게 꺼내 쓰는가"를 확인하는 자리라 결과 탭에 둔다.

    private void buildSnapshotQueryGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("후방 스냅샷 조회 API 테스트");
        g.setLayout(new GridLayout(2, false));
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.verticalIndent = 12;
        g.setLayoutData(gd);

        tcIdList = new org.eclipse.swt.widgets.List(g, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL);
        GridData ld = new GridData(SWT.FILL, SWT.FILL, false, true, 1, 4);
        ld.widthHint = 130;
        ld.heightHint = 96;
        tcIdList.setLayoutData(ld);

        addQueryButton(g, "단일 조회", "getSnapshot(tcId) — 선택한 첫 tcId", new Runnable() {
            public void run() {
                doGetSnapshot();
            }
        });
        addQueryButton(g, "배치 조회", "getSnapshots(tcIds) — 선택(미선택 시 전체)", new Runnable() {
            public void run() {
                doGetSnapshots();
            }
        });
        addQueryButton(g, "통합 조회", "getCombinedSnapshot(tcIds) — 한 판으로 합친 PNG", new Runnable() {
            public void run() {
                doGetCombinedSnapshot();
            }
        });
        addQueryButton(g, "결과 열기", "마지막 조회 결과 PNG를 기본 뷰어로 연다", new Runnable() {
            public void run() {
                openLastQueried();
            }
        });

        snapResultText = new Text(g, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.WRAP);
        GridData rd = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
        rd.heightHint = 70;
        snapResultText.setLayoutData(rd);
        snapResultText.setText("측정을 한 번 중단하면 저장된 tcId 목록이 채워집니다.");
    }

    private static void addQueryButton(Composite parent, String text, String tip,
            final Runnable action) {
        Button b = new Button(parent, SWT.PUSH);
        b.setText(text);
        b.setToolTipText(tip);
        b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        b.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                action.run();
            }
        });
    }

    /** 저장된 tcId 목록 갱신 — 조회 테스트 UI의 소스. */
    private void setTcIds(java.util.List<String> ids) {
        lastTcIds.clear();
        if (ids != null) {
            lastTcIds.addAll(ids);
        }
        if (tcIdList == null || tcIdList.isDisposed()) {
            return;
        }
        tcIdList.removeAll();
        for (int i = 0; i < lastTcIds.size(); i++) {
            tcIdList.add(lastTcIds.get(i));
        }
        if (tcIdList.getItemCount() > 0) {
            tcIdList.select(0);
            setSnapResult(lastTcIds.size() + "개 tcId 저장됨 — 조회 버튼으로 규약 API를 테스트하세요.");
        } else {
            setSnapResult("저장된 스냅샷이 없습니다 (후방 Select 포인트를 1개 이상 지정하세요).");
        }
    }

    /** 선택된 tcId — 아무것도 선택 안 했으면 전체. */
    private java.util.List<String> selectedTcIds() {
        if (tcIdList == null || tcIdList.isDisposed()) {
            return new ArrayList<String>();
        }
        String[] sel = tcIdList.getSelection();
        if (sel == null || sel.length == 0) {
            return new ArrayList<String>(lastTcIds);
        }
        return new ArrayList<String>(Arrays.asList(sel));
    }

    /** 조회 API는 후방 캔버스가 들고 있다 — 모니터 View의 캔버스를 빌려 쓴다. */
    private RearGridCanvas rearCanvas() {
        IWorkbenchPage page = getSite().getPage();
        if (page == null) {
            return null;
        }
        IViewPart v = page.findView(RearMonitorView.ID);
        if (!(v instanceof RearMonitorView)) {
            return null;
        }
        RearGridCanvas c = ((RearMonitorView) v).getCanvas();
        return (c == null || c.isDisposed()) ? null : c;
    }

    private void doGetSnapshot() {
        RearGridCanvas c = rearCanvas();
        if (c == null) {
            setSnapResult("후방 모니터 View가 열려 있지 않습니다.");
            return;
        }
        java.util.List<String> ids = selectedTcIds();
        if (ids.isEmpty()) {
            setSnapResult("조회할 tcId가 없습니다.");
            return;
        }
        String id = ids.get(0);
        File f = c.getSnapshot(id);
        lastQueriedFile = f;
        setSnapResult("getSnapshot(\"" + id + "\")\n  → " + describe(f));
    }

    private void doGetSnapshots() {
        RearGridCanvas c = rearCanvas();
        if (c == null) {
            setSnapResult("후방 모니터 View가 열려 있지 않습니다.");
            return;
        }
        java.util.List<String> ids = selectedTcIds();
        if (ids.isEmpty()) {
            setSnapResult("조회할 tcId가 없습니다.");
            return;
        }
        java.util.List<File> files = c.getSnapshots(ids);
        StringBuilder sb = new StringBuilder();
        sb.append("getSnapshots(").append(ids).append(")  → ").append(files.size()).append("개\n");
        lastQueriedFile = null;
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (f != null && lastQueriedFile == null) {
                lastQueriedFile = f;
            }
            sb.append("  ").append(ids.get(i)).append(" → ").append(describe(f)).append('\n');
        }
        setSnapResult(sb.toString());
    }

    private void doGetCombinedSnapshot() {
        RearGridCanvas c = rearCanvas();
        if (c == null) {
            setSnapResult("후방 모니터 View가 열려 있지 않습니다.");
            return;
        }
        java.util.List<String> ids = selectedTcIds();
        if (ids.size() < 2) {
            setSnapResult("통합 조회는 tcId 2개 이상 필요합니다 (현재 " + ids.size() + "개).");
            return;
        }
        try {
            File f = c.getCombinedSnapshot(ids);
            lastQueriedFile = f;
            setSnapResult("getCombinedSnapshot(" + ids + ")\n  → " + describe(f));
        } catch (IllegalArgumentException ex) {
            // 동일 격자 규격만 통합 가능 — 부분 통합본 대신 에러를 그대로 보여준다
            lastQueriedFile = null;
            setSnapResult("getCombinedSnapshot(" + ids + ")\n  → 통합 불가: " + ex.getMessage());
        }
    }

    private void openLastQueried() {
        if (lastQueriedFile == null || !lastQueriedFile.isFile()) {
            setSnapResult("열 파일이 없습니다. 먼저 조회하세요.");
            return;
        }
        Program.launch(lastQueriedFile.getAbsolutePath());
    }

    private static String describe(File f) {
        if (f == null) {
            return "null (해당 tcId 스냅샷 없음)";
        }
        if (!f.isFile()) {
            return f.getAbsolutePath() + "  (파일 없음)";
        }
        return f.getAbsolutePath() + "  (" + f.length() + " bytes)";
    }

    private void setSnapResult(String text) {
        if (snapResultText != null && !snapResultText.isDisposed()) {
            snapResultText.setText(text == null ? "" : text);
        }
    }

    private static Label label(Composite parent, String text) {
        Label l = new Label(parent, SWT.WRAP);
        l.setText(text);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 400;
        l.setLayoutData(gd);
        return l;
    }

    private static Label section(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.verticalIndent = 12;
        l.setLayoutData(gd);
        return l;
    }

    private static Label imageSlot(Composite parent) {
        Label l = new Label(parent, SWT.BORDER);
        l.setText("(없음)");
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 480;
        gd.heightHint = 180;
        l.setLayoutData(gd);
        return l;
    }

    private void refresh(LastMeasureResult r) {
        if (body == null || body.isDisposed()) {
            return;
        }
        // 스크럽·조회 섹션은 결과 유무와 무관하게 늘 열려 있다 — 지난 TC 폴더를 직접 열 수 있어야 하므로
        if (r != null && r.getEvidenceDir() != null) {
            openEvidence(r.getEvidenceDir());
            setTcIds(r.getRearTcIds());
        }
        if (r == null || !r.hasResult()) {
            emptyLbl.setText("아직 측정 결과가 없습니다. Kickoff에서 측정 시작 → 중단 하면 여기에 표시됩니다."
                    + "\n지난 TC는 아래 \"증거 폴더 열기…\"로 되짚을 수 있습니다.");
            setVisible(true, emptyLbl);
            setVisible(false, headerLbl, audioTimeLbl, visionTimeLbl, syncLbl,
                    audioSnapLbl, visionSnapLbl, rearSnapLbl,
                    audioImgLbl, visionImgLbl, rearImgLbl);
            layoutScroll();
            return;
        }
        setVisible(false, emptyLbl);
        setVisible(true, headerLbl, audioTimeLbl, visionTimeLbl, syncLbl,
                audioSnapLbl, visionSnapLbl, rearSnapLbl,
                audioImgLbl, visionImgLbl, rearImgLbl);

        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(r.getStoppedAtEpochMs()));
        headerLbl.setText(String.format("최종: %s — %s  (%s)",
                r.isOverallPass() ? "PASS" : "FAIL", r.getSummary(), when));
        audioTimeLbl.setText(formatTimeLine("음향", r.getAudioPassMs(), r.getAudioJudgeMs(),
                r.getAudioGapMs(), r.getAudioAnalysisMs()));
        visionTimeLbl.setText(formatTimeLine("비전", r.getVisionPassMs(), r.getVisionJudgeMs(),
                r.getVisionGapMs(), r.getVisionAnalysisMs()));
        if (r.getSyncSpreadMs() != null) {
            syncLbl.setText(String.format("동기: %.0f ms %s (≤%.0fms)",
                    r.getSyncSpreadMs().doubleValue(),
                    r.isSyncOk() ? "OK" : "FAIL",
                    MeasureSyncResult.SYNC_TOL_MS));
        } else {
            syncLbl.setText("동기: — (PASS 시각 2개 미만)");
        }

        audioImg = setPng(audioImgLbl, audioImg, r.getAudioPassPng());
        visionImg = setPng(visionImgLbl, visionImg, r.getVisionPassPng());
        rearImg = setPng(rearImgLbl, rearImg, r.getRearPassPng());
        layoutScroll();
    }

    private void layoutScroll() {
        body.layout(true, true);
        Point size = body.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        scroll.setMinSize(size);
    }

    private static void setVisible(boolean vis, Label... labels) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != null && !labels[i].isDisposed()) {
                labels[i].setVisible(vis);
                Object ld = labels[i].getLayoutData();
                if (ld instanceof GridData) {
                    ((GridData) ld).exclude = !vis;
                }
            }
        }
    }

    private static String formatTimeLine(String channel, Long passMs, Double judgeMs,
            Double gapMs, Double analysisMs) {
        if (passMs == null) {
            return channel + ": FAIL (미검출)";
        }
        if (judgeMs != null && gapMs != null && analysisMs != null) {
            return String.format("%s: PASS @ %d ms (자체판단 %.1f = 간격 %.1f + 분석 %.1f)",
                    channel, passMs.longValue(),
                    judgeMs.doubleValue(), gapMs.doubleValue(), analysisMs.doubleValue());
        }
        if (judgeMs != null) {
            return String.format("%s: PASS @ %d ms (자체판단 %.1f ms)",
                    channel, passMs.longValue(), judgeMs.doubleValue());
        }
        return String.format("%s: PASS @ %d ms", channel, passMs.longValue());
    }

    private Image setPng(Label slot, Image old, byte[] png) {
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
        slot.setImage(null);
        if (png == null || png.length == 0) {
            slot.setText("(스냅샷 없음)");
            return null;
        }
        try {
            ImageData data = new ImageData(new ByteArrayInputStream(png));
            // 너무 크면 폭 제한
            int maxW = 560;
            if (data.width > maxW) {
                int h = Math.max(1, (int) (data.height * (maxW / (double) data.width)));
                data = data.scaledTo(maxW, h);
            }
            Image img = new Image(slot.getDisplay(), data);
            slot.setText("");
            slot.setImage(img);
            GridData gd = (GridData) slot.getLayoutData();
            if (gd != null) {
                gd.widthHint = img.getBounds().width;
                gd.heightHint = img.getBounds().height;
            }
            return img;
        } catch (Exception ex) {
            slot.setText("(이미지 로드 실패)");
            return null;
        }
    }

    @Override
    public void setFocus() {
        if (scroll != null && !scroll.isDisposed()) {
            scroll.setFocus();
        }
    }

    @Override
    public void dispose() {
        LastMeasureResult.get().removeListener(resultListener);
        if (audioImg != null && !audioImg.isDisposed()) {
            audioImg.dispose();
        }
        if (visionImg != null && !visionImg.isDisposed()) {
            visionImg.dispose();
        }
        if (rearImg != null && !rearImg.isDisposed()) {
            rearImg.dispose();
        }
        super.dispose();
    }
}
