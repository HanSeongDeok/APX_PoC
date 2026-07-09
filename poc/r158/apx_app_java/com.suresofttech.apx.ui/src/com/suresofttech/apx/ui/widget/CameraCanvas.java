package com.suresofttech.apx.ui.widget;

import java.awt.image.BufferedImage;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/**
 * 웹캠/처리 프레임(AWT {@link BufferedImage})을 SWT로 그리는 캔버스.
 * 비율 유지·중앙 정렬. 프레임마다 임시 SWT Image를 만들고 즉시 dispose(리소스 누수 방지).
 * 기어·클러스터 View도 이 위젯으로 canon 프레임을 표시하고 위에 오버레이를 그린다.
 */
public class CameraCanvas extends Canvas {

    /** 프레임 위에 그릴 오버레이. (scale, dx, dy)로 canon 좌표→위젯 좌표 변환:
     *  widgetX = dx + canonX*scale, widgetY = dy + canonY*scale. */
    public interface Overlay {
        void paint(GC gc, double scale, int dx, int dy);
    }

    private volatile BufferedImage frame;
    private String placeholder = "(카메라 없음)";
    private Overlay overlay;

    public void setOverlay(Overlay o) {
        this.overlay = o;
    }

    public CameraCanvas(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                paint(e.gc);
            }
        });
    }

    public void setPlaceholder(String text) {
        this.placeholder = text;
    }

    /** 표시할 프레임 설정 → 다시 그림. null 이면 안내문. */
    public void setFrame(BufferedImage f) {
        this.frame = f;
        if (!isDisposed()) {
            redraw();
        }
    }

    private void paint(GC gc) {
        Point sz = getSize();
        gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_BLACK));
        gc.fillRectangle(0, 0, sz.x, sz.y);

        BufferedImage f = frame;
        if (f == null || f.getWidth() <= 0 || f.getHeight() <= 0) {
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_GRAY));
            Point ext = gc.textExtent(placeholder);
            gc.drawText(placeholder, (sz.x - ext.x) / 2, (sz.y - ext.y) / 2, true);
            return;
        }
        Image img = new Image(getDisplay(), toImageData(f));
        try {
            int iw = img.getBounds().width;
            int ih = img.getBounds().height;
            double s = Math.min(sz.x / (double) iw, sz.y / (double) ih);
            int dw = Math.max(1, (int) (iw * s));
            int dh = Math.max(1, (int) (ih * s));
            int dx = (sz.x - dw) / 2;
            int dy = (sz.y - dh) / 2;
            gc.setInterpolation(SWT.HIGH);
            gc.drawImage(img, 0, 0, iw, ih, dx, dy, dw, dh);
            if (overlay != null) {
                overlay.paint(gc, s, dx, dy);      // canon 좌표계로 박스·HUD
            }
        } finally {
            img.dispose();
        }
    }

    /** AWT BufferedImage → SWT ImageData (24bit RGB). 타입 무관.
     *  getRGB로 ARGB를 한 번에 읽고 행 단위 벌크 setPixels로 채움(픽셀당 setPixel 루프 제거).
     *  ARGB의 상위 알파바이트는 depth 24라 자동 절단, 팔레트가 R/G/B 추출. */
    public static ImageData toImageData(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data = new ImageData(w, h, 24, palette);
        int[] argb = bi.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            data.setPixels(0, y, w, argb, y * w);
        }
        return data;
    }
}
