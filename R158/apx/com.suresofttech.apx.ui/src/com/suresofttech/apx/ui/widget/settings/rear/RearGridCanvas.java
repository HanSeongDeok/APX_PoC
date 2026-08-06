package com.suresofttech.apx.ui.widget.settings.rear;

import java.awt.Point;
import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.rear.RearGrid;

/**
 * 후방 최소 단위 — 차량(후방 그림) + 검증 포인트 격자.
 * Select 클릭({@link RearGrid#selectSingle}). 범례는 {@link #setShowLegend}로 on/off.
 */
public class RearGridCanvas extends Canvas {

    /** 기본 차량 후방 이미지 파일명 (고정). {@code ui/ref/} 하위. */
    public static final String DEFAULT_CAR_IMAGE_NAME = "차량 후방 레이아웃_Default.png";

    private static final String REF_REL =
            "com.suresofttech.apx.ui" + File.separator + "src" + File.separator
                    + "com" + File.separator + "suresofttech" + File.separator + "apx"
                    + File.separator + "ui" + File.separator + "ref";

    private RearGrid grid;
    private Runnable onChange;
    private Image carImg;
    private boolean showLegend = true;

    // 상태 범례(클라이언트 커스텀 가능) — 기본 이름/색은 생성자에서 설정.
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
    /** 범례 이상적 폭 — 남는 폭이 이보다 작으면 범례만 축소(차량·격자 우선). */
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

        // 기본 범례 — 이름/색(선택·측정중·합격·불합격). setLegend로 교체 가능.
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
                if (e.button == 1) {
                    onClick(e.x, e.y);
                }
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
            disposeLegendColorsIfOwned();
            if (carImg != null && !carImg.isDisposed()) {
                carImg.dispose();
            }
        });
    }

    /** 변경(클릭) 콜백 — Select → Settings 동기화 등. */
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

    /**
     * 상태 범례 항목(이름·색)을 커스텀 지정. names/colors 중 null은 기존값 유지.
     * colors는 이 위젯이 {@link Color}로 만들어 소유(교체·dispose 시 자동 해제).
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

    /** 선택 점 색 — 범례 SELECT(첫 항목) 색과 연동. setLegend로 바꾸면 선택 점도 그 색이 된다. */
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
     * 기본 차량 이미지({@code ui/ref/}{@link #DEFAULT_CAR_IMAGE_NAME}) 로드.
     * 밝은 회색 배경은 흰색으로 치환. 없으면 도형 유지.
     */
    public void loadDefaultCarImage() {
        File f = resolveDefaultCarImageFile();
        if (f == null) {
            return;
        }
        try {
            Image raw = new Image(getDisplay(), f.getAbsolutePath());
            Image whitened = whitenCarBackground(raw);
            raw.dispose();
            setCarImage(whitened);
        } catch (Exception ex) {
            // 도형 유지
        }
    }

    /** {@code .../ui/ref/차량 후방 레이아웃_Default.png} 탐색. */
    public static File resolveDefaultCarImageFile() {
        String name = DEFAULT_CAR_IMAGE_NAME;
        String sep = File.separator;
        String[] candidates = {
                "c:/DEV/apx/R158/apx/" + REF_REL.replace(File.separatorChar, '/') + "/" + name,
                System.getProperty("user.dir") + sep + ".." + sep + ".." + sep + REF_REL + sep + name,
                System.getProperty("user.dir") + sep + ".." + sep + REF_REL + sep + name,
                System.getProperty("user.dir") + sep + REF_REL + sep + name,
        };
        for (int i = 0; i < candidates.length; i++) {
            File f = new File(candidates[i]);
            if (f.isFile()) {
                return f;
            }
        }
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 10 && dir != null; depth++) {
            File inRef = new File(dir, "src" + sep + "com" + sep + "suresofttech" + sep
                    + "apx" + sep + "ui" + sep + "ref" + sep + name);
            if (inRef.isFile()) {
                return inRef;
            }
            File underUi = new File(dir, REF_REL + sep + name);
            if (underUi.isFile()) {
                return underUi;
            }
            File underApx = new File(dir, "R158" + sep + "apx" + sep + REF_REL + sep + name);
            if (underApx.isFile()) {
                return underApx;
            }
            dir = dir.getParentFile();
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
        Rectangle ca = getClientArea();
        gc.setBackground(cBg);
        gc.fillRectangle(ca);
        if (grid == null || ca.width < 40 || ca.height < 40) {
            return;
        }
        gc.setAntialias(SWT.ON);

        int cols = grid.getCols();
        int rows = grid.getRows();
        int ccy = ca.height / 2;
        int rearX = Math.max(60, (int) (ca.width * TRUNK_REAR_X_FRAC));
        int trunkH0 = Math.max(40, (int) (ca.height * TRUNK_H_FRAC));
        // 범례는 레이아웃에서 폭을 빼지 않음 — 차량·격자가 남는 폭을 우선 사용
        int availW = Math.max(cols * 5, ca.width - rearX - GAP - 2 * PAD);
        int lcell = Math.max(5, Math.min(trunkH0 / rows, availW / cols));
        int trunkH = lcell * rows;
        int usedW = lcell * cols;
        int usedH = lcell * rows;
        int lgx0 = rearX + GAP + PAD;
        int lgy0 = ccy - usedH / 2;
        cell = lcell;
        gx0 = lgx0;
        gy0 = lgy0;

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
                if (grid.isSelected(c, r)) {
                    gc.setBackground(selDotColor());
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
            // 격자(판) 오른쪽 남는 폭만 사용 — 침범 금지
            int remainW = (ca.x + ca.width) - (bx + bw) - 4;
            drawLegend(gc, ca, bx + bw, remainW);
        }
    }

    /**
     * 상태 범례 — 판 오른쪽 <b>남는 폭 안에서만</b> 그린다.
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
        // 판 오른쪽 밖 + 캔버스 안 — 격자 영역과 겹치지 않음
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
