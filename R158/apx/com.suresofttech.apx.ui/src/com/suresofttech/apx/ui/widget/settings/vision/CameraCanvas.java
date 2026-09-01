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

    /** 표시할 프레임 설정 → 다시 그림. null 이면 안내문.
     * 숨은 설정 탭은 NCC 구독만 건너뛴다. {@code redraw}는 항상 걸어 두어야
     * 결과 탭처럼 나중에 보일 때도 마지막 프레임이 나온다. */
    public void setFrame(BufferedImage f) {
        this.frame = f;
        if (isDisposed()) {
            return;
        }
        redraw();
        if (f == null || !isVisible()) {
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
        int iw = f.getWidth();
        int ih = f.getHeight();
        double s = Math.min(sz.x / (double) iw, sz.y / (double) ih);
        if (s <= 0) {
            return;
        }
        int dw = Math.max(1, (int) (iw * s));
        int dh = Math.max(1, (int) (ih * s));
        int dx = (sz.x - dw) / 2;
        int dy = (sz.y - dh) / 2;
        Image img = new Image(getDisplay(), toImageData(f, dw, dh));
        try {
            gc.drawImage(img, dx, dy);
            if (overlay != null) {
                overlay.paint(gc, s, dx, dy);      // canon 좌표계로 박스 / HUD
            }
        } finally {
            img.dispose();
        }
    }

    /** AWT BufferedImage → SWT ImageData (24bit RGB). 위젯 크기로 줄여 변환한다. */
    public static ImageData toImageData(BufferedImage bi) {
        return toImageData(bi, bi.getWidth(), bi.getHeight());
    }

    /**
     * {@code outW}×{@code outH}로 샘플링해 변환. 1080p 원본을 모니터 칸(≈160px)에
     * 그릴 때 전체 픽셀을 SWT로 옮기지 않는다.
     */
    public static ImageData toImageData(BufferedImage bi, int outW, int outH) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        outW = Math.max(1, outW);
        outH = Math.max(1, outH);
        PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data = new ImageData(outW, outH, 24, palette);
        int[] argb = new int[outW * outH];
        if (bi.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            byte[] src = ((java.awt.image.DataBufferByte) bi.getRaster().getDataBuffer()).getData();
            if (outW == w && outH == h) {
                for (int p = 0, i = 0; p < argb.length; p++, i += 3) {
                    argb[p] = ((src[i + 2] & 0xFF) << 16)
                            | ((src[i + 1] & 0xFF) << 8)
                            | (src[i] & 0xFF);
                }
            } else {
                for (int y = 0; y < outH; y++) {
                    int sy = y * h / outH;
                    int row = sy * w;
                    int dst = y * outW;
                    for (int x = 0; x < outW; x++) {
                        int i = (row + (x * w / outW)) * 3;
                        argb[dst + x] = ((src[i + 2] & 0xFF) << 16)
                                | ((src[i + 1] & 0xFF) << 8)
                                | (src[i] & 0xFF);
                    }
                }
            }
        } else if (outW == w && outH == h) {
            argb = bi.getRGB(0, 0, w, h, null, 0, w);
        } else {
            for (int y = 0; y < outH; y++) {
                int sy = Math.min(h - 1, y * h / outH);
                int dst = y * outW;
                for (int x = 0; x < outW; x++) {
                    argb[dst + x] = bi.getRGB(Math.min(w - 1, x * w / outW), sy);
                }
            }
        }
        for (int y = 0; y < outH; y++) {
            data.setPixels(0, y, outW, argb, y * outW);
        }
        return data;
    }

    /** 현재 화면 PNG. dispose/비표시면 null. */
    public byte[] capturePng() {
        return com.suresofttech.apx.ui.widget.SwtCapture.toPng(this);
    }
}
