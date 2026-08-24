package com.suresofttech.apx.ui.widget.settings.rear;

import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.rear.RearGrid;
import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.rear.VerdictResult;

/**
 * 후방 최소 단위 - 차량(후방 그림) + 검증 포인트 격자.
 * Select 클릭({@link RearGrid#selectSingle}). 범례는 {@link #setShowLegend}로 on/off.
 * 모니터용: {@link #setInteractive(false)}, {@link #setCellVerdict}.
 * 판정 스냅샷: {@link #setSnapshotDir}/{@link #saveVerdictSnapshot}/{@link #getCombinedSnapshot}.
 */
public class RearGridCanvas extends Canvas {

    /** 기본 차량 후방 이미지 파일명 (고정). {@code com.suresofttech.apx.ui/ref/} 하위. */
    public static final String DEFAULT_CAR_IMAGE_NAME = "차량 후방 레이아웃_Default.png";

    /** 설정 / 모니터 공통 범례 이름 (선택 / 측정중 / 합격 / 불합격). */
    public static final String[] DEFAULT_LEGEND_NAMES =
            new String[] { "선택", "측정중", "합격", "불합격" };

    /** 설정 / 모니터 공통 범례 색. */
    public static RGB[] defaultLegendColors() {
        return new RGB[] {
                new RGB(135, 206, 250),
                new RGB(230, 200, 40),
                new RGB(40, 170, 70),
                new RGB(200, 40, 40)
        };
    }

    /** 파일명: {@code <tcId>_c<col>_r<row>_<VERDICT>_<cols>x<rows>.png} */
    private static final Pattern SNAP_NAME = Pattern.compile(
            "^(.+)_c(\\d+)_r(\\d+)_(NONE|MEASURING|PASS|FAIL)_(\\d+)x(\\d+)\\.png$");

    /** 기준 이미지 폴더 - 플러그인 루트 바로 아래({@code com.suresofttech.apx.ui/ref}). */
    private static final String REF_REL =
            "com.suresofttech.apx.ui" + File.separator + "ref";

    private RearGrid grid;
    private Runnable onChange;
    private Image carImg;
    private boolean showLegend = true;
    private boolean interactive = true;
    private Verdict[][] cellVerdicts;
    /** 판정 스냅샷 저장 폴더(없으면 임시폴더/rear_snapshots). */
    private File snapshotDir;

    // 상태 범례(클라이언트 커스텀 가능) - 기본 이름/색은 생성자에서 설정.
    private String[] legendLabels;
    private Color[] legendColors;      // 기본은 cSel/cMeas/cPass/cFail 공유(미소유). setLegend 시 새 Color(소유).
    private boolean legendColorsOwned;

    // 이미지 세로에서 트렁크(리어 폭)가 차지하는 구간(비율).
    private static final double CAR_TRUNK_TOP = 0.21;
    private static final double CAR_TRUNK_BOT = 0.79;
    private static final double TRUNK_H_FRAC = 0.6;
    private static final double TRUNK_REAR_X_FRAC = 0.36;
    private static final int GAP = 4;
    private static final int PAD = 8;
    /** 범례 이상적 폭 - 남는 폭이 이보다 작으면 범례만 축소(차량 / 격자 우선). */
    private static final int LEGEND_IDEAL_W = 130;
    private static final int LEGEND_MIN_W = 40;

    private int gx0, gy0, cell;

    private final Color cBg;
    private final Color cBoard;
    private final Color cBoardEdge;
    private final Color cDot;
    private final Color cDotEdge;
    private final Color cSel;
    private final Color cSelEdge;
    private final Color cCar;
    private final Color cCarEdge;
    private final Color cText;
    private final Color cPass;
    private final Color cFail;
    private final Color cMeas;

    public RearGridCanvas(Composite parent, RearGrid grid) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        this.grid = grid;
        Display d = getDisplay();
        cBg = new Color(d, 255, 255, 255);
        cBoard = new Color(d, 255, 255, 255);
        cBoardEdge = new Color(d, 120, 120, 130);
        cDot = new Color(d, 220, 220, 224);
        cDotEdge = new Color(d, 170, 170, 178);
        cSel = new Color(d, 130, 130, 138);
        cSelEdge = new Color(d, 20, 20, 20);
        cCar = new Color(d, 255, 255, 255);
        cCarEdge = new Color(d, 120, 120, 128);
        cText = new Color(d, 90, 90, 90);
        cPass = new Color(d, 40, 170, 70);
        cFail = new Color(d, 150, 20, 20);
        cMeas = new Color(d, 230, 200, 40);

        // 기본 범례 - 이름/색(선택 / 측정중 / 합격 / 불합격). setLegend로 교체 가능.
        legendLabels = new String[] { "SELECT", "MEASURING", "PASS", "FAIL" };
        legendColors = new Color[] { cSel, cMeas, cPass, cFail };
        legendColorsOwned = false;

        addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                paintScene(e.gc);
            }
        });
        addMouseListener(new MouseAdapter() {
            public void mouseDown(MouseEvent e) {
                if (!interactive) {
                    return;
                }
                if (e.button == 1) {
                    onClick(e.x, e.y);
                }
            }
        });
        addMouseMoveListener(new org.eclipse.swt.events.MouseMoveListener() {
            public void mouseMove(MouseEvent e) {
                if (!interactive) {
                    setCursor(getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
                    return;
                }
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
            disposeLegendColorsIfOwned();
            if (carImg != null && !carImg.isDisposed()) {
                carImg.dispose();
            }
        });
    }

    /** 변경(클릭) 콜백 - Select → Settings 동기화 등. */
    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public RearGrid getGrid() {
        return grid;
    }

    /** 새 격자 모델 주입 후 다시 그림. */
    public void setGrid(RearGrid g) {
        if (g != null) {
            this.grid = g;
            if (!isDisposed()) {
                redraw();
            }
        }
    }

    /** 상태 범례 표시 on/off. */
    public void setShowLegend(boolean on) {
        if (this.showLegend == on) {
            return;
        }
        this.showLegend = on;
        if (!isDisposed()) {
            redraw();
        }
    }

    public boolean isShowLegend() {
        return showLegend;
    }

    /** false면 클릭 Select 잠금(모니터 View). */
    public void setInteractive(boolean on) {
        this.interactive = on;
        if (!on && !isDisposed()) {
            setCursor(getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
        }
    }

    public boolean isInteractive() {
        return interactive;
    }

    /** 셀 판정 색(MEASURING/PASS/FAIL). Select 점 위에 표시. */
    public void setCellVerdict(int col, int row, Verdict v) {
        if (grid == null) {
            return;
        }
        ensureVerdictGrid();
        if (col < 0 || row < 0 || col >= cellVerdicts.length || row >= cellVerdicts[col].length) {
            return;
        }
        cellVerdicts[col][row] = v == null ? Verdict.NONE : v;
        if (!isDisposed()) {
            redraw();
        }
    }

    /** {@link VerdictResult} 편의 - {@link #setCellVerdict} 위임. */
    public void setVerdict(VerdictResult r) {
        if (r == null) {
            return;
        }
        Point p = r.getPoint();
        setCellVerdict(p.x, p.y, r.getVerdict());
    }

    /**
     * 여러 포인트 판정을 한 번에 반영(측정 중단 시 클라 PASS/FAIL).
     * 기존 판정만 지우고 Select는 유지한 채 결과색을 입힌다.
     */
    public void setVerdicts(List<VerdictResult> results) {
        if (grid == null) {
            return;
        }
        ensureVerdictGrid();
        for (int c = 0; c < cellVerdicts.length; c++) {
            for (int r = 0; r < cellVerdicts[c].length; r++) {
                cellVerdicts[c][r] = Verdict.NONE;
            }
        }
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                VerdictResult vr = results.get(i);
                if (vr == null) {
                    continue;
                }
                Point p = vr.getPoint();
                int c = p.x;
                int rr = p.y;
                if (c >= 0 && c < cellVerdicts.length
                        && rr >= 0 && rr < cellVerdicts[c].length) {
                    cellVerdicts[c][rr] = vr.getVerdict();
                    if (!grid.isSelected(c, rr)) {
                        grid.setSelected(c, rr, true);
                    }
                }
            }
        }
        if (!isDisposed()) {
            redraw();
        }
    }

    public void clearVerdicts() {
        cellVerdicts = null;
        if (!isDisposed()) {
            redraw();
        }
    }

    /** 설정과 동일한 기본 범례 이름 / 색 적용. */
    public void applyDefaultLegend() {
        setLegend(DEFAULT_LEGEND_NAMES, defaultLegendColors());
    }

    /** 해당 셀 판정. 없으면 null. */
    public VerdictResult getVerdict(int col, int row) {
        if (grid == null || cellVerdicts == null
                || col < 0 || col >= cellVerdicts.length
                || row < 0 || row >= cellVerdicts[col].length) {
            return null;
        }
        Verdict v = cellVerdicts[col][row];
        if (v == null || v == Verdict.NONE) {
            return null;
        }
        return new VerdictResult(col, row, v);
    }

    /** Point 규약: x=col, y=row. */
    public VerdictResult getVerdict(Point p) {
        return (p == null) ? null : getVerdict(p.x, p.y);
    }

    /** 판정된(NONE 아닌) 포인트 목록. */
    public List<VerdictResult> getVerdicts() {
        List<VerdictResult> out = new ArrayList<VerdictResult>();
        if (cellVerdicts == null) {
            return out;
        }
        for (int c = 0; c < cellVerdicts.length; c++) {
            for (int r = 0; r < cellVerdicts[c].length; r++) {
                Verdict v = cellVerdicts[c][r];
                if (v != null && v != Verdict.NONE) {
                    out.add(new VerdictResult(c, r, v));
                }
            }
        }
        return out;
    }

    /**
     * 현재 판정 상태 PNG (메모리 / Result용).
     * 화면 paint와 무관하게 {@link #renderImage} 오프스크린으로 그린다.
     */
    public byte[] capturePng() {
        if (isDisposed() || grid == null) {
            return null;
        }
        Image img = renderImage(grid.getCols(), grid.getRows(), getVerdicts());
        try {
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { img.getImageData() };
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            loader.save(bos, SWT.IMAGE_PNG);
            return bos.toByteArray();
        } catch (Exception ex) {
            return null;
        } finally {
            img.dispose();
        }
    }

    // ── 판정 스냅샷 = 파일 저장 + TC 이름으로 조회 ──────────────────────────────

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
        return s == null ? "" : s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 파일명: {@code <tcId>_c<col>_r<row>_<VERDICT>_<cols>x<rows>.png} */
    private static String snapshotName(String tcId, VerdictResult r, int cols, int rows) {
        Point p = r.getPoint();
        return safe(tcId) + "_c" + p.x + "_r" + p.y + "_" + r.getVerdict().name()
                + "_" + cols + "x" + rows + ".png";
    }

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
     * 파일명에 Point / Verdict / 격자크기를 넣어 DB 없이도 통합 재렌더 가능.
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

    /** TC 1개 → 저장된 스냅샷 파일. 없으면 null. */
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
     * 여러 TC 스냅샷 파일명에서 Point / Verdict / 격자크기를 읽어 한 판에 합친 통합 이미지.
     *
     * <p><b>동일 격자 규격만 통합된다.</b> 서로 다른 {@code <cols>x<rows>}가 섞이면
     * 부분 통합본을 조용히 돌려주지 않고 {@link IllegalArgumentException}을 던진다
     * (섞인 걸 모르고 증거로 제출하는 사고 방지).
     *
     * @return {@code combined_<tcId>_....png} (해당 파일 없으면 null)
     * @throws IllegalArgumentException 격자 크기가 다른 스냅샷이 섞인 경우
     */
    public File getCombinedSnapshot(List<String> tcIds) {
        if (tcIds == null || tcIds.isEmpty()) {
            return null;
        }
        List<VerdictResult> merged = new ArrayList<VerdictResult>();
        StringBuilder key = new StringBuilder("combined");
        int cols = -1;
        int rows = -1;
        String specOwner = null;
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
                specOwner = id;
            } else if (cols != meta.cols || rows != meta.rows) {
                throw new IllegalArgumentException(String.format(
                        "격자 크기가 다릅니다 - 동일 규격만 통합할 수 있습니다: %s=%dx%d, %s=%dx%d",
                        specOwner, Integer.valueOf(cols), Integer.valueOf(rows),
                        id, Integer.valueOf(meta.cols), Integer.valueOf(meta.rows)));
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
        Verdict[][] vs = new Verdict[cols][rows];
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                vs[c][r] = Verdict.NONE;
            }
        }
        if (results != null) {
            for (VerdictResult r : results) {
                if (r == null) {
                    continue;
                }
                Point p = r.getPoint();
                int c = p.x;
                int rr = p.y;
                if (c >= 0 && c < cols && rr >= 0 && rr < rows) {
                    vs[c][rr] = r.getVerdict();
                    g.setSelected(c, rr, true);
                }
            }
        }
        int w = Math.max(1, getSize().x > 0 ? getSize().x : 480);
        int h = Math.max(1, getSize().y > 0 ? getSize().y : 320);
        Image img = new Image(getDisplay(), w, h);
        GC gc = new GC(img);
        try {
            paintBoard(gc, new Rectangle(0, 0, w, h), g, vs, false);
        } finally {
            gc.dispose();
        }
        return img;
    }

    private void writePng(Image img, File f) {
        try {
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { img.getImageData() };
            loader.save(f.getAbsolutePath(), SWT.IMAGE_PNG);
        } finally {
            img.dispose();
        }
    }

    private void ensureVerdictGrid() {
        int cols = grid.getCols();
        int rows = grid.getRows();
        if (cellVerdicts == null || cellVerdicts.length != cols
                || (cols > 0 && cellVerdicts[0].length != rows)) {
            cellVerdicts = new Verdict[cols][rows];
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    cellVerdicts[c][r] = Verdict.NONE;
                }
            }
        }
    }

    private Color verdictColor(Verdict v) {
        if (v == null || v == Verdict.NONE) {
            return null;
        }
        if (v == Verdict.PASS) {
            return cPass;
        }
        if (v == Verdict.FAIL) {
            return cFail;
        }
        if (v == Verdict.MEASURING) {
            return cMeas;
        }
        return null;
    }

    /**
     * 상태 범례 항목(이름 / 색)을 커스텀 지정. names/colors 중 null은 기존값 유지.
     * colors는 이 위젯이 {@link Color}로 만들어 소유(교체 / dispose 시 자동 해제).
     * 이름과 색의 개수가 다르면 더 적은 개수만큼만 표시한다.
     */
    public void setLegend(String[] names, RGB[] colors) {
        if (names != null && names.length > 0) {
            legendLabels = names.clone();
        }
        if (colors != null && colors.length > 0) {
            disposeLegendColorsIfOwned();
            Color[] cc = new Color[colors.length];
            for (int i = 0; i < colors.length; i++) {
                cc[i] = new Color(getDisplay(), colors[i]);
            }
            legendColors = cc;
            legendColorsOwned = true;
        }
        if (!isDisposed()) {
            redraw();
        }
    }

    /** 선택 점 색 - 범례 SELECT(첫 항목) 색과 연동. setLegend로 바꾸면 선택 점도 그 색이 된다. */
    private Color selDotColor() {
        if (legendColors != null && legendColors.length > 0
                && legendColors[0] != null && !legendColors[0].isDisposed()) {
            return legendColors[0];
        }
        return cSel;
    }

    private void disposeLegendColorsIfOwned() {
        if (legendColorsOwned && legendColors != null) {
            for (int i = 0; i < legendColors.length; i++) {
                if (legendColors[i] != null && !legendColors[i].isDisposed()) {
                    legendColors[i].dispose();
                }
            }
        }
        legendColorsOwned = false;
    }

    /** 차량 그림 지정. null이면 도형. 기존 Image는 dispose. */
    public void setCarImage(Image img) {
        if (carImg != null && !carImg.isDisposed()) {
            carImg.dispose();
        }
        carImg = img;
        if (!isDisposed()) {
            redraw();
        }
    }

    /**
     * 기본 차량 이미지({@code com.suresofttech.apx.ui/ref/}{@link #DEFAULT_CAR_IMAGE_NAME}) 로드.
     * 밝은 회색 배경은 흰색으로 치환. 없으면 도형 유지.
     */
    public void loadDefaultCarImage() {
        Image raw = null;
        File f = resolveDefaultCarImageFile();
        try {
            if (f != null) {
                raw = new Image(getDisplay(), f.getAbsolutePath());
            } else {
                // 플러그인이 jar 로 묶이면 파일이 아니라 클래스패스 리소스로만 존재한다.
                java.io.InputStream in = RearGridCanvas.class.getResourceAsStream(
                        "/ref/" + DEFAULT_CAR_IMAGE_NAME);
                if (in == null) {
                    return;                  // 못 찾으면 도형으로 그린다
                }
                try {
                    raw = new Image(getDisplay(), in);
                } finally {
                    in.close();
                }
            }
            Image whitened = whitenCarBackground(raw);
            setCarImage(whitened);
        } catch (Exception ex) {
            // 도형 유지
        } finally {
            if (raw != null && !raw.isDisposed()) {
                raw.dispose();
            }
        }
    }

    /**
     * {@code com.suresofttech.apx.ui/ref/차량 후방 레이아웃_Default.png} 탐색.
     *
     * <p>실행 위치가 워크스페이스 루트일 수도, 플러그인 폴더일 수도, 배포본일 수도 있어
     * 현재 디렉터리에서 위로 올라가며 찾는다. 없으면 null(도형으로 그린다).
     */
    public static File resolveDefaultCarImageFile() {
        String name = DEFAULT_CAR_IMAGE_NAME;
        String sep = File.separator;

        // (1) 클래스가 실제로 로드된 위치 기준 - RCP 로 띄우면 user.dir 이 작업 디렉터리라
        //     저장소 구조와 무관해진다. 그래서 클래스 위치에서 플러그인 루트를 거슬러 찾는다.
        //       개발:  .../com.suresofttech.apx.ui/bin  → 한 단계 위가 플러그인 루트
        //       배포:  .../plugins/com.suresofttech.apx.ui_0.1.0/  또는 .jar
        File byCode = findNearCodeSource(name);
        if (byCode != null) {
            return byCode;
        }

        // (2) 현재 작업 디렉터리에서 위로 올라가며 - 데모/CLI 실행용
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 10 && dir != null; depth++) {
            // 워크스페이스/저장소 루트에서 본 경로
            File underWs = new File(dir, REF_REL + sep + name);
            if (underWs.isFile()) {
                return underWs;
            }
            // 저장소 루트에서 R158/apx 를 거쳐 본 경로
            File underApx = new File(dir, "R158" + sep + "apx" + sep + REF_REL + sep + name);
            if (underApx.isFile()) {
                return underApx;
            }
            // 플러그인 폴더 안(혹은 배포본)에서 본 경로
            File underPlugin = new File(dir, "ref" + sep + name);
            if (underPlugin.isFile()) {
                return underPlugin;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * 이 클래스가 로드된 경로에서 위로 올라가며 {@code ref/<name>} 을 찾는다.
     * OSGi API 를 쓰지 않으므로 RCP 안에서도, 데모(순수 SWT)에서도 같이 동작한다.
     */
    private static File findNearCodeSource(String name) {
        try {
            java.security.CodeSource cs =
                    RearGridCanvas.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            String path = java.net.URLDecoder.decode(cs.getLocation().getPath(), "UTF-8");
            File at = new File(path);
            if (at.isFile()) {
                at = at.getParentFile();     // .jar 이면 담고 있는 폴더부터
            }
            for (int depth = 0; depth < 5 && at != null; depth++) {
                File f = new File(at, "ref" + File.separator + name);
                if (f.isFile()) {
                    return f;
                }
                at = at.getParentFile();
            }
        } catch (Exception ignored) {
            // 보안 매니저 등으로 못 읽으면 다음 방법으로 넘어간다
        }
        return null;
    }

    private static Image whitenCarBackground(Image src) {
        ImageData data = src.getImageData();
        int white = data.palette.getPixel(new RGB(255, 255, 255));
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                RGB rgb = data.palette.getRGB(data.getPixel(x, y));
                int max = Math.max(rgb.red, Math.max(rgb.green, rgb.blue));
                int min = Math.min(rgb.red, Math.min(rgb.green, rgb.blue));
                if (min >= 160 && (max - min) <= 45) {
                    data.setPixel(x, y, white);
                }
            }
        }
        return new Image(src.getDevice(), data);
    }

    private void paintScene(GC gc) {
        paintBoard(gc, getClientArea(), grid, cellVerdicts, true);
    }

    /**
     * 보드 그리기. liveLayout=true면 클릭 히트용 gx0/gy0/cell 갱신.
     * 스냅샷 오프스크린은 liveLayout=false.
     */
    private void paintBoard(GC gc, Rectangle ca, RearGrid g, Verdict[][] vs, boolean liveLayout) {
        gc.setBackground(cBg);
        gc.fillRectangle(ca);
        if (g == null || ca.width < 40 || ca.height < 40) {
            return;
        }
        gc.setAntialias(SWT.ON);

        int cols = g.getCols();
        int rows = g.getRows();
        int ccy = ca.y + ca.height / 2;
        int rearX = ca.x + Math.max(60, (int) (ca.width * TRUNK_REAR_X_FRAC));
        int trunkH0 = Math.max(40, (int) (ca.height * TRUNK_H_FRAC));
        int availW = Math.max(cols * 5, ca.width - (rearX - ca.x) - GAP - 2 * PAD);
        int lcell = Math.max(5, Math.min(trunkH0 / rows, availW / cols));
        int trunkH = lcell * rows;
        int usedW = lcell * cols;
        int usedH = lcell * rows;
        int lgx0 = rearX + GAP + PAD;
        int lgy0 = ccy - usedH / 2;
        if (liveLayout) {
            cell = lcell;
            gx0 = lgx0;
            gy0 = lgy0;
        }

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
                if (g.isSelected(c, r)) {
                    Color fill = selDotColor();
                    if (vs != null && c < vs.length && r < vs[c].length) {
                        Color vc = verdictColor(vs[c][r]);
                        if (vc != null) {
                            fill = vc;
                        }
                    }
                    gc.setBackground(fill);
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
            int remainW = (ca.x + ca.width) - (bx + bw) - 4;
            drawLegend(gc, ca, bx + bw, remainW);
        }
    }

    /**
     * 상태 범례 - 판 오른쪽 <b>남는 폭 안에서만</b> 그린다.
     * 창이 좁으면 범례만 축소. 남는 폭이 최소치 미만이면 그리지 않음(격자 침범 금지).
     */
    private void drawLegend(GC gc, Rectangle ca, int boardRight, int remainW) {
        final int gap = 4;
        int maxW = remainW - gap;
        if (maxW < LEGEND_MIN_W) {
            return;
        }
        String[] labels = legendLabels;
        Color[] cols = legendColors;
        int n = Math.min(labels.length, cols.length);
        if (n <= 0) {
            return;
        }
        int boxW = Math.min(LEGEND_IDEAL_W, maxW);
        float scale = boxW / (float) LEGEND_IDEAL_W;
        int pad = Math.max(3, Math.round(12 * scale));
        int rowH = Math.max(10, Math.round(24 * scale));
        int dot = Math.max(5, Math.round(14 * scale));
        int gapDotText = Math.max(3, Math.round(8 * scale));
        int boxH = pad * 2 + rowH * n;
        int maxH = Math.max(rowH * n + 6, ca.height - 16);
        if (boxH > maxH) {
            float hs = maxH / (float) boxH;
            pad = Math.max(2, Math.round(pad * hs));
            rowH = Math.max(9, Math.round(rowH * hs));
            dot = Math.max(4, Math.round(dot * hs));
            boxH = pad * 2 + rowH * n;
        }
        // 판 오른쪽 밖 + 캔버스 안 - 격자 영역과 겹치지 않음
        int bx = boardRight + gap;
        if (bx + boxW > ca.x + ca.width - 2) {
            boxW = Math.max(LEGEND_MIN_W, (ca.x + ca.width - 2) - bx);
            if (boxW < LEGEND_MIN_W) {
                return;
            }
            scale = boxW / (float) LEGEND_IDEAL_W;
            pad = Math.max(3, Math.round(12 * scale));
            rowH = Math.max(10, Math.round(24 * scale));
            dot = Math.max(5, Math.round(14 * scale));
            gapDotText = Math.max(3, Math.round(8 * scale));
            boxH = pad * 2 + rowH * n;
        }
        int by = ca.y + Math.max(0, (ca.height - boxH) / 2);

        org.eclipse.swt.graphics.Font oldFont = gc.getFont();
        org.eclipse.swt.graphics.Font scaledFont = null;
        if (scale < 0.92f) {
            org.eclipse.swt.graphics.FontData[] fds = oldFont.getFontData();
            for (int i = 0; i < fds.length; i++) {
                int h = Math.max(7, Math.round(fds[i].getHeight() * scale));
                fds[i].setHeight(h);
            }
            scaledFont = new org.eclipse.swt.graphics.Font(gc.getDevice(), fds);
            gc.setFont(scaledFont);
        }

        gc.setBackground(cBg);
        gc.setForeground(cBoardEdge);
        gc.setLineWidth(1);
        int arc = Math.max(4, Math.round(10 * scale));
        gc.fillRoundRectangle(bx, by, boxW, boxH, arc, arc);
        gc.drawRoundRectangle(bx, by, boxW, boxH, arc, arc);
        for (int i = 0; i < n; i++) {
            int yy = by + pad + i * rowH;
            int dy = yy + Math.max(0, (rowH - dot) / 2);
            gc.setBackground(cols[i]);
            gc.setForeground(cSelEdge);
            gc.fillOval(bx + pad, dy, dot, dot);
            gc.drawOval(bx + pad, dy, dot, dot);
            gc.setForeground(cText);
            String label = labels[i];
            if (boxW < 72 && label.length() > 4) {
                label = label.substring(0, 1); // 매우 좁으면 이니셜
            }
            gc.drawText(label, bx + pad + dot + gapDotText, yy + Math.max(0, (rowH - 12) / 2), true);
        }

        if (scaledFont != null) {
            gc.setFont(oldFont);
            scaledFont.dispose();
        }
    }

    private void drawCar(GC gc, int rearX, int cy, int trunkH) {
        if (trunkH < 20) {
            return;
        }
        if (carImg != null && !carImg.isDisposed()) {
            Rectangle ib = carImg.getBounds();
            double trunkFrac = CAR_TRUNK_BOT - CAR_TRUNK_TOP;
            int dh = Math.max(1, (int) (trunkH / trunkFrac));
            double s = dh / (double) ib.height;
            int dw = Math.max(1, (int) (ib.width * s));
            int dx = rearX - dw;
            double trunkCenter = (CAR_TRUNK_TOP + CAR_TRUNK_BOT) / 2.0;
            int dy = cy - (int) (trunkCenter * dh);
            gc.drawImage(carImg, 0, 0, ib.width, ib.height, dx, dy, dw, dh);
            return;
        }
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
        int hitR = Math.max(4, (int) (cell * 0.42));
        int dx = mx - cx;
        int dy = my - cy;
        return (dx * dx + dy * dy <= hitR * hitR) ? new int[] { c, r } : null;
    }

    private void onClick(int mx, int my) {
        int[] hit = hitCell(mx, my);
        if (hit == null) {
            return;
        }
        grid.selectSingle(new Point(hit[0], hit[1]));
        redraw();
        if (onChange != null) {
            onChange.run();
        }
    }

    private void updateCursor(int mx, int my) {
        setCursor(getDisplay().getSystemCursor(
                hitCell(mx, my) != null ? SWT.CURSOR_HAND : SWT.CURSOR_ARROW));
    }
}
