package com.suresofttech.apx.ui.widget;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;

import com.suresofttech.apx.core.rear.RearGrid;
import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.rear.VerdictResult;

/**
 * 후방 검증 포인트 격자 시각화 위젯 — 차량(후방 그림) 왼쪽, 검증 포인트 판(격자) 오른쪽.
 * SWT 무의존 모델 {@link RearGrid} 만 읽어 그리므로 다른 RCP 제품에서도 재사용 가능.
 *
 * <p>포인트 판은 네모(판) 위에 격자점을 얹고, 사용자가 셀을 클릭하면 <b>한 번에 하나</b>만
 * 빨간 포인트로 지정된다({@link RearGrid#selectSingle}). 차량 그림은 {@link #setCarImage}로
 * 임의의 후방 이미지를 넣을 수 있고, 없으면 간단한 차량 도형으로 대체한다.
 */
public class RearGridCanvas extends Canvas {

    private RearGrid grid;
    private Runnable onChange;
    private Image carImg;   // 선택적 차량 후방 그림(없으면 도형) — 로드시 배경 흰색화·반전 처리됨
    private VerdictResult[] verdicts;   // 셀별 판정 결과(지정과 별개 층). idx = r*cols + c. null=NONE
    private boolean showLegend = true;   // 상태 범례 표시(옵션)
    private File snapshotDir;             // 판정 스냅샷 저장 폴더(없으면 임시폴더)

    // 이미지 세로에서 트렁크(리어 폭)가 차지하는 구간(비율). 이 구간을 포인트 Y축(격자 높이)에 맞춘다.
    private static final double CAR_TRUNK_TOP = 0.21;
    private static final double CAR_TRUNK_BOT = 0.79;
    // 트렁크(차량) 고정 기하 — 그리드와 무관하게 캔버스 기준 고정. 판(그리드)이 여기에 맞춰 파생.
    private static final double TRUNK_H_FRAC = 0.6;        // 트렁크 세로 폭 = 캔버스 높이의 80%(여백 위·아래 각 10%)
    private static final double TRUNK_REAR_X_FRAC = 0.36;  // 트렁크 후면 x = 캔버스 폭의 36%
    private static final int GAP = 4;                      // 트렁크 ↔ 판 간격(좁게)
    private static final int PAD = 8;                      // 판 안쪽 여백(점 바깥)
    private static final int LEGEND_RESERVE = 150;         // 범례 표시 시 판 오른쪽에 확보할 폭(px)

    // 마지막 레이아웃(클릭 히트테스트 재사용)
    private int gx0, gy0, cell;

    private final Color cBg;
    private final Color cBoard;      // 포인트 판(네모) 채움
    private final Color cBoardEdge;
    private final Color cDot;        // 미지정 격자점
    private final Color cDotEdge;
    private final Color cSel;        // 지정 포인트(빨강)
    private final Color cSelEdge;    // 지정 포인트 테두리(검정)
    private final Color cCar;
    private final Color cCarEdge;
    private final Color cText;
    private final Color cPass;        // 판정 PASS(초록)
    private final Color cFail;        // 판정 FAIL(진빨강 — 지정 빨강과 구분)
    private final Color cMeas;        // 판정 MEASURING(노랑)

    public RearGridCanvas(Composite parent, RearGrid grid) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        this.grid = grid;
        Display d = getDisplay();
        cBg = new Color(d, 255, 255, 255);
        cBoard = new Color(d, 244, 244, 246);
        cBoardEdge = new Color(d, 120, 120, 130);
        cDot = new Color(d, 220, 220, 224);
        cDotEdge = new Color(d, 170, 170, 178);
        cSel = new Color(d, 210, 55, 55);
        cSelEdge = new Color(d, 20, 20, 20);
        cCar = new Color(d, 238, 238, 240);
        cCarEdge = new Color(d, 120, 120, 128);
        cText = new Color(d, 90, 90, 90);
        cPass = new Color(d, 40, 170, 70);
        cFail = new Color(d, 150, 20, 20);
        cMeas = new Color(d, 230, 200, 40);
        allocVerdicts();

        addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                paintScene(e.gc);
            }
        });
        addMouseListener(new MouseAdapter() {
            public void mouseDown(MouseEvent e) {
                onClick(e.x, e.y);
            }
        });
        addMouseMoveListener(new org.eclipse.swt.events.MouseMoveListener() {
            public void mouseMove(MouseEvent e) {
                updateCursor(e.x, e.y);
            }
        });
        addDisposeListener(e -> {
            cBg.dispose();
            cBoard.dispose();
            cBoardEdge.dispose();
            cDot.dispose();
            cDotEdge.dispose();
            cSel.dispose();
            cSelEdge.dispose();
            cCar.dispose();
            cCarEdge.dispose();
            cText.dispose();
            cPass.dispose();
            cFail.dispose();
            cMeas.dispose();
            if (carImg != null && !carImg.isDisposed()) {
                carImg.dispose();
            }
        });
    }

    /** 변경(클릭) 콜백 — 지정 개수 라벨 갱신 등. */
    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public RearGrid getGrid() {
        return grid;
    }

    /** 새 격자 모델 주입(크기 변경/TC 복원 등) 후 다시 그림. 판정 결과는 초기화. */
    public void setGrid(RearGrid g) {
        if (g != null) {
            this.grid = g;
            allocVerdicts();               // 크기 바뀌면 판정 배열도 재할당(초기화)
            if (!isDisposed()) {
                redraw();
            }
        }
    }

    // ── 판정 결과(Verdict) ────────────────────────────────────────────────────

    /** 격자 크기에 맞춰 판정 배열 (재)할당 — 전부 NONE(null). */
    private void allocVerdicts() {
        int n = (grid != null) ? grid.getCols() * grid.getRows() : 0;
        verdicts = new VerdictResult[Math.max(1, n)];
    }

    /**
     * 포인트 판정 결과 반영 — 그 포인트를 판정색으로 그림.
     * NONE=지정색 / MEASURING=노랑 / PASS=초록 / FAIL=진빨강.
     * @param r 위치(col,row)와 판정을 담은 결과 객체. 범위 밖이면 무시.
     */
    public void setVerdict(VerdictResult r) {
        if (r == null || grid == null) {
            return;
        }
        Point p = r.getPoint();
        int c = p.x;
        int rr = p.y;
        if (c < 0 || c >= grid.getCols() || rr < 0 || rr >= grid.getRows()) {
            return;
        }
        verdicts[rr * grid.getCols() + c] = r;
        if (!isDisposed()) {
            redraw();
        }
    }

    /** 모든 판정색 초기화(전부 NONE). 지정 상태는 유지. */
    public void clearVerdicts() {
        allocVerdicts();
        if (!isDisposed()) {
            redraw();
        }
    }

    /** 해당 포인트 판정 결과 조회(내부 좌표). 없으면 null. */
    public VerdictResult getVerdict(int c, int r) {
        if (grid == null || verdicts == null
                || c < 0 || c >= grid.getCols() || r < 0 || r >= grid.getRows()) {
            return null;
        }
        return verdicts[r * grid.getCols() + c];
    }

    /** 해당 포인트 판정 결과 조회 (java.awt.Point: x=col, y=row). 없으면 null. */
    public VerdictResult getVerdict(Point p) {
        return (p == null) ? null : getVerdict(p.x, p.y);
    }

    /** TC 복원 원샷 — 크기+지정+판정색 재현 후 갱신. point/verdict null 가능. */
    public void restore(int cols, int rows, Point point, VerdictResult verdict) {
        RearGrid g = new RearGrid(cols, rows);
        g.selectSingle(point);
        this.grid = g;
        allocVerdicts();
        if (verdict != null) {
            Point p = verdict.getPoint();
            int c = p.x;
            int rr = p.y;
            if (c >= 0 && c < cols && rr >= 0 && rr < rows) {
                this.verdicts[rr * cols + c] = verdict;
            }
        }
        if (!isDisposed()) {
            redraw();
        }
    }

    // ── 판정 스냅샷 = 파일 저장 + TC 이름으로 조회 ──────────────────────────────
    // 파일명 규약: <tcId>_c<col>_r<row>_<VERDICT>_<cols>x<rows>.png  (요소 사이 '_' 구분)
    // 예: TC-01_c3_r2_PASS_9x7.png  → DB 없이 통합 재렌더 가능

    private static final Pattern SNAP_NAME = Pattern.compile(
            "^(.+)_c(\\d+)_r(\\d+)_(NONE|MEASURING|PASS|FAIL)_(\\d+)x(\\d+)\\.png$");

    /** 스냅샷 저장 폴더 지정(없으면 시스템 임시폴더/rear_snapshots). */
    public void setSnapshotDir(File dir) {
        this.snapshotDir = dir;
    }

    private File dir() {
        File d = (snapshotDir != null) ? snapshotDir
                : new File(System.getProperty("java.io.tmpdir"), "rear_snapshots");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private static String safe(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 파일명: {@code <tcId>_c<col>_r<row>_<VERDICT>_<cols>x<rows>.png} — 요소 사이 '_' 구분. */
    private static String snapshotName(String tcId, VerdictResult r, int cols, int rows) {
        Point p = r.getPoint();
        return safe(tcId) + "_c" + p.x + "_r" + p.y + "_" + r.getVerdict().name()
                + "_" + cols + "x" + rows + ".png";
    }

    /** 동일 tcId의 기존 스냅샷 파일 삭제(재저장 시 덮어쓰기). 파일명 파싱해 tcId 정확 비교. */
    private void deleteSnapshotsFor(String tcId) {
        final String key = safe(tcId);
        File[] all = dir().listFiles();
        if (all == null) {
            return;
        }
        for (File f : all) {
            SnapMeta m = parseSnapshotName(f.getName());
            if (m != null && key.equals(m.tcId)) {
                f.delete();
            }
        }
    }

    /**
     * 이솝 측정 중지→save 시 판정 스냅샷 파일 저장.
     * 파일명에 Point·Verdict·격자크기를 넣어 DB 없이도 통합 재렌더 가능.
     */
    public File saveVerdictSnapshot(VerdictResult r, String tcId) {
        if (r == null || tcId == null || grid == null) {
            return null;
        }
        int cols = grid.getCols();
        int rows = grid.getRows();
        deleteSnapshotsFor(tcId);
        List<VerdictResult> one = new ArrayList<VerdictResult>();
        one.add(r);
        File f = new File(dir(), snapshotName(tcId, r, cols, rows));
        writePng(renderImage(cols, rows, one), f);
        return f;
    }

    /** TC 1개 → 저장된 스냅샷 파일({@code <tcId>_...png}). 없으면 null. 파일명 파싱해 tcId 정확 비교. */
    public File getSnapshot(String tcId) {
        if (tcId == null) {
            return null;
        }
        final String key = safe(tcId);
        File[] all = dir().listFiles();
        if (all == null) {
            return null;
        }
        for (File f : all) {
            SnapMeta m = parseSnapshotName(f.getName());
            if (m != null && key.equals(m.tcId)) {
                return f;
            }
        }
        return null;
    }

    /** TC 여러 개 → 개별 스냅샷 파일 목록. 없는 항목은 null. */
    public List<File> getSnapshots(List<String> tcIds) {
        List<File> out = new ArrayList<File>();
        if (tcIds == null) {
            return out;
        }
        for (String id : tcIds) {
            out.add(getSnapshot(id));
        }
        return out;
    }

    /**
     * 여러 TC 스냅샷 파일명에서 Point·Verdict·격자크기를 읽어 한 판에 합친 통합 이미지.
     * DB/결과 맵 불필요 — 개별 파일명만으로 복원.
     *
     * @return {@code combined_&lt;tcId&gt;_....png} (해당 파일 없으면 null)
     */
    public File getCombinedSnapshot(List<String> tcIds) {
        if (tcIds == null || tcIds.isEmpty()) {
            return null;
        }
        List<VerdictResult> merged = new ArrayList<VerdictResult>();
        StringBuilder key = new StringBuilder("combined");
        int cols = -1;
        int rows = -1;
        for (String id : tcIds) {
            File snap = getSnapshot(id);
            if (snap == null) {
                continue;
            }
            SnapMeta meta = parseSnapshotName(snap.getName());
            if (meta == null) {
                continue;
            }
            if (cols < 0) {
                cols = meta.cols;
                rows = meta.rows;
            } else if (cols != meta.cols || rows != meta.rows) {
                // 미결: 타일 통합 vs 동일 규격만 에러. PoC는 동일 규격만(불일치 제외)
                continue;
            }
            merged.add(new VerdictResult(meta.col, meta.row, meta.verdict));
            key.append('_').append(safe(id));
        }
        if (merged.isEmpty() || cols < 1 || rows < 1) {
            return null;
        }
        File f = new File(dir(), key.toString() + ".png");
        writePng(renderImage(cols, rows, merged), f);
        return f;
    }

    /** 파일명에서 Point·Verdict·cols×rows 파싱. 규약 불일치 시 null. */
    private static SnapMeta parseSnapshotName(String name) {
        if (name == null) {
            return null;
        }
        Matcher m = SNAP_NAME.matcher(name);
        if (!m.matches()) {
            return null;
        }
        try {
            SnapMeta meta = new SnapMeta();
            meta.tcId = m.group(1);
            meta.col = Integer.parseInt(m.group(2));
            meta.row = Integer.parseInt(m.group(3));
            meta.verdict = Verdict.valueOf(m.group(4));
            meta.cols = Integer.parseInt(m.group(5));
            meta.rows = Integer.parseInt(m.group(6));
            return meta;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final class SnapMeta {
        String tcId;
        int col;
        int row;
        Verdict verdict;
        int cols;
        int rows;
    }

    /** (크기, 결과들)을 라이브 판과 무관하게 오프스크린 이미지로 렌더. */
    private Image renderImage(int cols, int rows, List<VerdictResult> results) {
        RearGrid g = new RearGrid(cols, rows);
        VerdictResult[] vs = new VerdictResult[Math.max(1, cols * rows)];
        if (results != null) {
            for (VerdictResult r : results) {
                if (r == null) {
                    continue;
                }
                Point p = r.getPoint();
                int c = p.x;
                int rr = p.y;
                if (c >= 0 && c < cols && rr >= 0 && rr < rows) {
                    vs[rr * cols + c] = r;
                    g.setSelected(c, rr, true);
                }
            }
        }
        int w = Math.max(1, getSize().x > 0 ? getSize().x : 480);
        int h = Math.max(1, getSize().y > 0 ? getSize().y : 320);
        Image img = new Image(getDisplay(), w, h);
        GC gc = new GC(img);
        paintBoard(gc, new Rectangle(0, 0, w, h), g, vs, false);   // liveLayout=false
        gc.dispose();
        return img;
    }

    /** 이미지를 PNG 파일로 저장 후 이미지 dispose. */
    private void writePng(Image img, File f) {
        try {
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { img.getImageData() };
            loader.save(f.getAbsolutePath(), SWT.IMAGE_PNG);
        } finally {
            img.dispose();
        }
    }

    /** 판정된(NONE 아닌) 포인트 결과 목록 — 클라가 분류·저장·리포트에 사용. */
    public List<VerdictResult> getVerdicts() {
        List<VerdictResult> out = new ArrayList<VerdictResult>();
        if (verdicts != null) {
            for (VerdictResult v : verdicts) {
                if (v != null && v.getVerdict() != Verdict.NONE) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** 판정 결과를 상태별로 분류한 묶음 — {PASS:[...], FAIL:[...], MEASURING:[...], NONE:[]}. */
    public Map<Verdict, List<VerdictResult>> groupByVerdict() {
        Map<Verdict, List<VerdictResult>> m = new EnumMap<Verdict, List<VerdictResult>>(Verdict.class);
        for (Verdict v : Verdict.values()) {
            m.put(v, new ArrayList<VerdictResult>());
        }
        if (verdicts != null) {
            for (VerdictResult vr : verdicts) {
                if (vr != null) {
                    m.get(vr.getVerdict()).add(vr);
                }
            }
        }
        return m;
    }

    /** 상태 범례 표시 옵션. */
    public void setShowLegend(boolean b) {
        showLegend = b;
        if (!isDisposed()) {
            redraw();
        }
    }

    /** 셀 판정색 — 주어진 판정 배열 기준. PASS/FAIL/MEASURING이면 해당 색, 그 외 null. */
    private Color verdictColorFrom(VerdictResult[] vs, RearGrid g, int c, int r) {
        if (vs == null || g == null
                || c < 0 || c >= g.getCols() || r < 0 || r >= g.getRows()) {
            return null;
        }
        VerdictResult vr = vs[r * g.getCols() + c];
        if (vr == null) {
            return null;
        }
        switch (vr.getVerdict()) {
            case PASS:
                return cPass;
            case FAIL:
                return cFail;
            case MEASURING:
                return cMeas;
            default:
                return null;   // NONE
        }
    }

    /** 차량 그림 지정 — 원본 그대로 사용(별도 처리 없음). 기존 것은 dispose. null 이면 도형으로. */
    public void setCarImage(Image img) {
        if (carImg != null && !carImg.isDisposed()) {
            carImg.dispose();
        }
        carImg = img;
        if (!isDisposed()) {
            redraw();
        }
    }

    // ── 렌더 ─────────────────────────────────────────────────────────────────

    private void paintScene(GC gc) {
        paintBoard(gc, getClientArea(), grid, verdicts, true);
    }

    /**
     * 판 렌더 — 임의의 격자·판정 배열로 그림(라이브 화면 + 오프스크린 스냅샷 공용).
     * @param liveLayout true면 클릭 히트테스트용 레이아웃(gx0/gy0/cell)을 인스턴스에 저장.
     */
    private void paintBoard(GC gc, Rectangle ca, RearGrid g, VerdictResult[] vs, boolean liveLayout) {
        gc.setBackground(cBg);
        gc.fillRectangle(ca);
        if (g == null || ca.width < 40 || ca.height < 40) {
            return;
        }
        gc.setAntialias(SWT.ON);

        int cols = g.getCols();
        int rows = g.getRows();

        // ── 판(그리드)은 세로(트렁크폭) + 가로(범례 확보 후 남는 폭) 둘 다에 맞춰 파생 ──
        int ccy = ca.height / 2;
        int rearX = Math.max(60, (int) (ca.width * TRUNK_REAR_X_FRAC));
        int trunkH0 = Math.max(40, (int) (ca.height * TRUNK_H_FRAC));   // 세로 기준 트렁크폭
        int legendW = showLegend ? LEGEND_RESERVE : 0;                  // 범례 자리 미리 확보
        int availW = Math.max(cols * 5, ca.width - rearX - GAP - 2 * PAD - legendW);
        int lcell = Math.max(5, Math.min(trunkH0 / rows, availW / cols)); // 세로·가로 중 작은 셀
        int trunkH = lcell * rows;                                      // 차량도 격자에 맞춰 축소(정렬 유지)
        int usedW = lcell * cols;
        int usedH = lcell * rows;
        int lgx0 = rearX + GAP + PAD;
        int lgy0 = ccy - usedH / 2;
        if (liveLayout) {
            cell = lcell;
            gx0 = lgx0;
            gy0 = lgy0;
        }

        // 포인트 판(네모)
        int bx = lgx0 - PAD;
        int by = lgy0 - PAD;
        int bw = usedW + 2 * PAD;
        int bh = usedH + 2 * PAD;
        gc.setBackground(cBoard);
        gc.setForeground(cBoardEdge);
        gc.setLineWidth(2);
        gc.fillRoundRectangle(bx, by, bw, bh, 12, 12);
        gc.drawRoundRectangle(bx, by, bw, bh, 12, 12);

        drawCar(gc, rearX, ccy, trunkH);

        int selRad = Math.max(3, (int) (lcell * 0.36));
        int dotRad = Math.max(2, (int) (lcell * 0.16));
        gc.setLineWidth(1);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int cx = lgx0 + c * lcell + lcell / 2;
                int cy = lgy0 + r * lcell + lcell / 2;
                Color vColor = verdictColorFrom(vs, g, c, r);
                if (vColor != null) {
                    gc.setBackground(vColor);
                    gc.fillOval(cx - selRad, cy - selRad, selRad * 2, selRad * 2);
                    gc.setForeground(cSelEdge);
                    gc.drawOval(cx - selRad, cy - selRad, selRad * 2, selRad * 2);
                } else if (g.isSelected(c, r)) {
                    gc.setBackground(cSel);
                    gc.fillOval(cx - selRad, cy - selRad, selRad * 2, selRad * 2);
                    gc.setForeground(cSelEdge);
                    gc.drawOval(cx - selRad, cy - selRad, selRad * 2, selRad * 2);
                } else {
                    gc.setBackground(cDot);
                    gc.setForeground(cDotEdge);
                    gc.fillOval(cx - dotRad, cy - dotRad, dotRad * 2, dotRad * 2);
                    gc.drawOval(cx - dotRad, cy - dotRad, dotRad * 2, dotRad * 2);
                }
            }
        }

        if (showLegend) {
            drawLegend(gc, ca, bx + bw);
        }
    }

    /** 상태 범례 — 색↔의미 표. 판 오른쪽 확보 영역 최상단(확대). 판과 겹치지 않음. */
    private void drawLegend(GC gc, Rectangle ca, int boardRight) {
        String[] labels = { "DEFAULT", "MEASURING", "PASS", "FAIL" };
        Color[] cols = { cDot, cMeas, cPass, cFail };   // labels와 1:1 (지정 빨강 행 제외)
        int pad = 12;
        int rowH = 24;
        int dot = 14;
        int boxW = 130;
        int boxH = pad * 2 + rowH * labels.length;
        int bx = ca.x + ca.width - boxW - 8;             // 우측 최상단(확보 영역 안이라 판과 안 겹침)
        if (bx < boardRight + 8) {
            bx = boardRight + 8;                         // 극단적 좁은 창 보호
        }
        int by = ca.y + 10;                              // 최상단
        gc.setBackground(cBg);
        gc.setForeground(cBoardEdge);
        gc.setLineWidth(1);
        gc.fillRoundRectangle(bx, by, boxW, boxH, 10, 10);
        gc.drawRoundRectangle(bx, by, boxW, boxH, 10, 10);
        for (int i = 0; i < labels.length; i++) {
            int yy = by + pad + i * rowH;
            gc.setBackground(cols[i]);
            gc.setForeground(cSelEdge);
            gc.fillOval(bx + pad, yy + 3, dot, dot);
            gc.drawOval(bx + pad, yy + 3, dot, dot);
            gc.setForeground(cText);
            gc.drawText(labels[i], bx + pad + dot + 8, yy + 2, true);
        }
    }

    /**
     * 차량 후방(고정) — 트렁크(리어 폭) 구간을 {@code trunkH} 에 맞추고, 후면을 {@code rearX} 에,
     * 트렁크 세로 중심을 {@code cy} 에 정렬. 앞부분은 왼쪽으로 넘쳐 잘림. 이미지 없으면 도형으로.
     * @param rearX 트렁크 후면 x  @param cy 세로 중심  @param trunkH 트렁크 세로 폭(고정)
     */
    private void drawCar(GC gc, int rearX, int cy, int trunkH) {
        if (trunkH < 20) {
            return;
        }
        if (carImg != null && !carImg.isDisposed()) {
            Rectangle ib = carImg.getBounds();
            double trunkFrac = CAR_TRUNK_BOT - CAR_TRUNK_TOP;      // 이미지 세로 중 트렁크 폭 비율
            int dh = Math.max(1, (int) (trunkH / trunkFrac));      // 트렁크 구간이 trunkH가 되도록
            double s = dh / (double) ib.height;
            int dw = Math.max(1, (int) (ib.width * s));
            int dx = rearX - dw;                                   // 후면(오른쪽) = 트렁크 위치
            double trunkCenter = (CAR_TRUNK_TOP + CAR_TRUNK_BOT) / 2.0;
            int dy = cy - (int) (trunkCenter * dh);                // 트렁크 중심 = cy
            gc.drawImage(carImg, 0, 0, ib.width, ib.height, dx, dy, dw, dh);
            return;
        }
        // 대체 도형(탑뷰, 후면이 오른쪽) — 트렁크폭=trunkH, 후면=rearX
        int bodyH = trunkH;
        int by = cy - bodyH / 2;
        int bw = (int) (bodyH * 1.4);
        int bxx = rearX - bw;
        int arc = Math.min(bw, bodyH) / 3;
        gc.setBackground(cCar);
        gc.setForeground(cCarEdge);
        gc.setLineWidth(2);
        gc.fillRoundRectangle(bxx, by, bw, bodyH, arc, arc);
        gc.drawRoundRectangle(bxx, by, bw, bodyH, arc, arc);
        int rw = Math.max(6, bw / 6);
        gc.setLineWidth(1);
        gc.drawLine(rearX - rw, by + 6, rearX - rw, by + bodyH - 6);
        gc.setForeground(cText);
        gc.drawText("REAR", rearX - rw + 2, cy - 6, true);
    }

    // ── 입력 ─────────────────────────────────────────────────────────────────

    /**
     * (mx, my)가 어느 점(동그라미) 위인지 — 점 중심에서 히트 반경 안이면 {c, r}, 아니면 null.
     * 셀 사각형 전체가 아니라 <b>점 근처</b>에서만 유효(클릭·커서 판정 일치).
     */
    private int[] hitCell(int mx, int my) {
        if (grid == null || cell <= 0) {
            return null;
        }
        int c = (mx - gx0) / cell;
        int r = (my - gy0) / cell;
        if (mx < gx0 || my < gy0 || c < 0 || c >= grid.getCols() || r < 0 || r >= grid.getRows()) {
            return null;
        }
        int cx = gx0 + c * cell + cell / 2;
        int cy = gy0 + r * cell + cell / 2;
        int hitR = Math.max(4, (int) (cell * 0.42));   // 점 반경보다 살짝 크게(클릭 편의)
        int dx = mx - cx;
        int dy = my - cy;
        return (dx * dx + dy * dy <= hitR * hitR) ? new int[] { c, r } : null;
    }

    private void onClick(int mx, int my) {
        int[] hit = hitCell(mx, my);
        if (hit == null) {
            return;
        }
        grid.selectSingle(new Point(hit[0], hit[1]));   // 한 번에 하나만
        redraw();
        if (onChange != null) {
            onChange.run();
        }
    }

    /** 클릭 가능한 점 위에서만 손가락 커서, 그 밖은 기본 커서. */
    private void updateCursor(int mx, int my) {
        setCursor(hitCell(mx, my) != null ? getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
