package com.suresofttech.apx.ui.widget.settings.vision;

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
 * 비율 유지 / 중앙 정렬. 프레임마다 임시 SWT Image를 만들고 즉시 dispose(리소스 누수 방지).
 */
public class CameraCanvas extends Canvas {

    /** 프레임 위에 그릴 오버레이. (scale, dx, dy)로 canon 좌표→위젯 좌표 변환:
     *  widgetX = dx + canonX*scale, widgetY = dy + canonY*scale. */
    public interface Overlay {
        void paint(GC gc, double scale, int dx, int dy);
    }

    /** {@link #setFrame}으로 새 프레임이 들어올 때 (null 제외). */
    public interface FrameListener {
        void onFrame(BufferedImage bi);
    }

    private volatile BufferedImage frame;
    private String placeholder = "(카메라 없음)";
    private Overlay overlay;
    private FrameListener frameListener;
    /** 추가 구독자 - 매칭(RoiNcc) 외에 녹화 등이 같은 프레임을 받아간다. */
    private final java.util.List<FrameListener> extraListeners =
            new java.util.concurrent.CopyOnWriteArrayList<FrameListener>();

    public void setOverlay(Overlay o) {
        this.overlay = o;
    }

    /** 주 구독자(매칭). 하나만 유지된다 - 추가 구독은 {@link #addFrameListener}. */
    public void setFrameListener(FrameListener l) {
        this.frameListener = l;
    }

    /** 프레임 추가 구독 - 주 구독자를 밀어내지 않는다(녹화 tap 등). */
    public void addFrameListener(FrameListener l) {
        if (l != null && !extraListeners.contains(l)) {
            extraListeners.add(l);
        }
    }

    public void removeFrameListener(FrameListener l) {
        extraListeners.remove(l);
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
        if (f == null) {
            return;
        }
        if (frameListener != null) {
            frameListener.onFrame(f);
        }
        for (FrameListener l : extraListeners) {
            try {
                l.onFrame(f);
            } catch (Exception ignored) {
                // 구독자 하나가 죽어도 표시 / 매칭은 계속
            }
        }
    }

    /** 현재 표시 중인 프레임 (없으면 null). */
    public BufferedImage getFrame() {
        return frame;
    }

    /**
     * 위젯 좌표 → 이미지 픽셀 좌표.
     * {@link #paint} 와 동일하게 비율 유지 / 중앙 정렬 변환을 쓴다.
     * @return {x, y} 이미지 좌표 (이미지 없으면 null)
     */
    public int[] widgetToImage(int wx, int wy) {
        BufferedImage f = frame;
        if (f == null || f.getWidth() <= 0 || f.getHeight() <= 0) {
            return null;
        }
        Point sz = getSize();
        int iw = f.getWidth();
        int ih = f.getHeight();
        double s = Math.min(sz.x / (double) iw, sz.y / (double) ih);
        if (s <= 0) {
            return null;
        }
        int dw = Math.max(1, (int) (iw * s));
        int dh = Math.max(1, (int) (ih * s));
        int dx = (sz.x - dw) / 2;
        int dy = (sz.y - dh) / 2;
        int ix = (int) Math.round((wx - dx) / s);
        int iy = (int) Math.round((wy - dy) / s);
        // 이미지 유효 픽셀은 0..iw-1 / 0..ih-1 (포함 클램프 시 경계 넘침 방지)
        ix = Math.max(0, Math.min(iw - 1, ix));
        iy = Math.max(0, Math.min(ih - 1, iy));
        return new int[] { ix, iy };
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
                overlay.paint(gc, s, dx, dy);      // canon 좌표계로 박스 / HUD
            }
        } finally {
            img.dispose();
        }
    }

    /** AWT BufferedImage → SWT ImageData (24bit RGB). 팔레트는 R/G/B 추출.
     *  <p>웹캠/처리 프레임은 TYPE_3BYTE_BGR - 래스터 바이트(B,G,R 순)를 직접 int로 디코드해
     *  {@code getRGB()}의 픽셀당 ColorModel 변환(640²에서 20~40ms)을 제거한다. 그 외 타입은
     *  안전한 getRGB 폴백. 채움은 검증된 setPixels(팔레트) 경로 그대로 사용. */
    public static ImageData toImageData(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data = new ImageData(w, h, 24, palette);
        int[] argb;
        if (bi.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            byte[] src = ((java.awt.image.DataBufferByte) bi.getRaster().getDataBuffer()).getData();
            argb = new int[w * h];
            for (int p = 0, i = 0; p < argb.length; p++, i += 3) {
                argb[p] = ((src[i + 2] & 0xFF) << 16)   // R
                        | ((src[i + 1] & 0xFF) << 8)    // G
                        | (src[i] & 0xFF);              // B
            }
        } else {
            argb = bi.getRGB(0, 0, w, h, null, 0, w);   // 임의 타입 폴백(느림)
        }
        for (int y = 0; y < h; y++) {
            data.setPixels(0, y, w, argb, y * w);
        }
        return data;
    }

    /** 현재 화면 PNG. dispose/비표시면 null. */
    public byte[] capturePng() {
        return com.suresofttech.apx.ui.widget.SwtCapture.toPng(this);
    }
}
