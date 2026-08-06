package com.suresofttech.apx.ui.widget.settings.vision;

import java.awt.image.BufferedImage;
import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.RoiMatchDetector;
import com.suresofttech.apx.core.vision.RoiMatchResult;

/**
 * ROI 드래그 + NCC 오버레이 — 공용 {@link CameraCanvas}에 붙인다.
 * (웹캠 화면 표시·폴링은 View/{@link CameraSelectBar} 쪽 책임.)
 */
public final class RoiNcc {

    public interface MatchListener {
        void onMatch(RoiMatchResult r);
    }

    public static final class Style {
        public RGB hit = new RGB(0, 255, 0);
        public RGB miss = new RGB(255, 255, 0);
        public RGB drag = new RGB(0, 255, 255);
        public int roiLineWidth = 2;
        public int dragThickness = 1;
    }

    private final CameraCanvas canvas;
    private final Display display;
    private final ApxSettings settings = ApxSettings.get();
    private final ApxSettings.Listener settingsListener;

    private RoiMatchDetector det;
    private volatile RoiMatchResult last;
    private boolean dragging;
    private int dragX0, dragY0, dragX1, dragY1;
    private MatchListener matchListener;
    private String visionFingerprint;

    private Color hitColor, missColor, dragColor;
    private int roiLineWidth = 2;
    private int dragThickness = 1;

    public RoiNcc(CameraCanvas canvas) {
        this(canvas, new Style());
    }

    public RoiNcc(CameraCanvas canvas, Style style) {
        if (canvas == null) {
            throw new IllegalArgumentException("canvas");
        }
        this.canvas = canvas;
        this.display = canvas.getDisplay();
        applyStyle(style);

        canvas.setOverlay(new CameraCanvas.Overlay() {
            public void paint(GC gc, double scale, int dx, int dy) {
                drawOverlay(gc, scale, dx, dy);
            }
        });
        canvas.addMouseListener(new MouseAdapter() {
            public void mouseDown(MouseEvent e) {
                dragging = true;
                dragX0 = dragX1 = e.x;
                dragY0 = dragY1 = e.y;
            }

            public void mouseUp(MouseEvent e) {
                if (!dragging) {
                    return;
                }
                dragging = false;
                dragX1 = e.x;
                dragY1 = e.y;
                commitRoiFromDrag();
            }
        });
        canvas.addMouseMoveListener(new MouseMoveListener() {
            public void mouseMove(MouseEvent e) {
                if (dragging) {
                    dragX1 = e.x;
                    dragY1 = e.y;
                    canvas.redraw();
                }
            }
        });
        canvas.setFrameListener(new CameraCanvas.FrameListener() {
            public void onFrame(BufferedImage bi) {
                onNewFrame(bi);
            }
        });

        settingsListener = new ApxSettings.Listener() {
            public void onSettingsChanged(ApxSettings s) {
                if (canvas.isDisposed()) {
                    return;
                }
                final String fp = visionFingerprintOf(s);
                display.asyncExec(new Runnable() {
                    public void run() {
                        if (canvas.isDisposed()) {
                            return;
                        }
                        if (fp.equals(visionFingerprint)) {
                            if (det != null) {
                                det.setSimThr(settings.getSimThr());
                            }
                            return;
                        }
                        rebuildDetectorFromSettings();
                    }
                });
            }
        };
        settings.addListener(settingsListener);
        canvas.addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                settings.removeListener(settingsListener);
                disposeStyleColors();
            }
        });

        rebuildDetectorFromSettings();
    }

    public void setMatchListener(MatchListener l) {
        this.matchListener = l;
    }

    public void setStyle(Style style) {
        applyStyle(style);
        if (!canvas.isDisposed()) {
            canvas.redraw();
        }
    }

    public void applySimThrFromSettings() {
        if (det != null) {
            det.setSimThr(settings.getSimThr());
        }
        fireMatch(last);
    }

    public void rebuildDetectorFromSettings() {
        visionFingerprint = visionFingerprintOf(settings);
        if (settings.isUseReferenceImage()) {
            String path = settings.getVisionRefPath();
            if (path == null || !new File(path).isFile()) {
                det = null;
                last = null;
                fireMatch(null);
                return;
            }
            try {
                det = new RoiMatchDetector(path, null, settings.getSimThr());
                det.setRoi(settings.getRoi(det.canonWidth(), det.canonHeight()));
                det.setSimThr(settings.getSimThr());
            } catch (Exception ex) {
                det = null;
            }
            fireMatch(null);
            return;
        }
        ensureLiveReferenceDetector();
    }

    private void applyStyle(Style s) {
        if (s == null) {
            s = new Style();
        }
        disposeStyleColors();
        hitColor = new Color(display, s.hit);
        missColor = new Color(display, s.miss);
        dragColor = new Color(display, s.drag);
        roiLineWidth = Math.max(1, s.roiLineWidth);
        dragThickness = Math.max(1, s.dragThickness);
    }

    private void disposeStyleColors() {
        if (hitColor != null && !hitColor.isDisposed()) {
            hitColor.dispose();
        }
        if (missColor != null && !missColor.isDisposed()) {
            missColor.dispose();
        }
        if (dragColor != null && !dragColor.isDisposed()) {
            dragColor.dispose();
        }
    }

    private void ensureLiveReferenceDetector() {
        BufferedImage bi = CameraService.get().latest();
        if (bi == null) {
            det = null;
            last = null;
            fireMatch(null);
            return;
        }
        try {
            int[] roi = settings.getRoi(bi.getWidth(), bi.getHeight());
            det = new RoiMatchDetector(bi, roi, settings.getSimThr());
            det.setAlignEnabled(false);
            det.setSimThr(settings.getSimThr());
            RoiMatchResult r = det.process(bi);
            last = r;
            fireMatch(r);
            canvas.redraw();
        } catch (Exception ex) {
            det = null;
            last = null;
            fireMatch(null);
        }
    }

    private void onNewFrame(BufferedImage bi) {
        if (!settings.isUseReferenceImage() && det == null && bi != null) {
            ensureLiveReferenceDetector();
            return;
        }
        if (!settings.isUseReferenceImage() && det != null && bi != null
                && (bi.getWidth() != det.canonWidth() || bi.getHeight() != det.canonHeight())) {
            ensureLiveReferenceDetector();
            return;
        }
        if (det != null && bi != null) {
            RoiMatchResult r = det.process(bi);
            last = r;
            fireMatch(r);
            canvas.redraw();
        } else if (last != null) {
            last = null;
            fireMatch(null);
            canvas.redraw();
        }
    }

    private void commitRoiFromDrag() {
        BufferedImage disp = canvas.getFrame();
        if (disp == null) {
            canvas.redraw();
            return;
        }
        if (Math.abs(dragX1 - dragX0) < 12 || Math.abs(dragY1 - dragY0) < 12) {
            canvas.redraw();
            return;
        }
        int[] a = canvas.widgetToImage(dragX0, dragY0);
        int[] b = canvas.widgetToImage(dragX1, dragY1);
        if (a == null || b == null) {
            canvas.redraw();
            return;
        }
        int y1 = Math.min(a[1], b[1]);
        int y2 = Math.max(a[1], b[1]) + 1;
        int x1 = Math.min(a[0], b[0]);
        int x2 = Math.max(a[0], b[0]) + 1;
        y2 = Math.min(disp.getHeight(), Math.max(y1 + 1, y2));
        x2 = Math.min(disp.getWidth(), Math.max(x1 + 1, x2));
        int[] roi = new int[] { y1, y2, x1, x2 };
        settings.setRoi(roi, disp.getWidth(), disp.getHeight());

        if (!settings.isUseReferenceImage()) {
            ensureLiveReferenceDetector();
            if (det != null) {
                det.setRoi(roi);
            }
        } else if (det != null) {
            det.setRoi(settings.getRoi(det.canonWidth(), det.canonHeight()));
        }
        canvas.redraw();
        fireMatch(last);
    }

    private void drawOverlay(GC gc, double scale, int dx, int dy) {
        RoiMatchResult r = last;
        BufferedImage disp = canvas.getFrame();
        boolean roiInCanon = (r != null && r.roi != null);
        int[] roi = roiInCanon ? r.roi
                : (disp != null ? settings.getRoi(disp.getWidth(), disp.getHeight()) : null);
        if (roi != null && disp != null) {
            double sx = 1.0;
            double sy = 1.0;
            if (roiInCanon && det != null
                    && (disp.getWidth() != det.canonWidth() || disp.getHeight() != det.canonHeight())) {
                sx = disp.getWidth() / (double) det.canonWidth();
                sy = disp.getHeight() / (double) det.canonHeight();
            }
            int wx = (int) Math.round(dx + roi[2] * sx * scale);
            int wy = (int) Math.round(dy + roi[0] * sy * scale);
            int ww = (int) Math.round((roi[3] - roi[2]) * sx * scale);
            int wh = (int) Math.round((roi[1] - roi[0]) * sy * scale);
            boolean hit = r != null && "ok".equals(r.state) && r.ncc >= settings.getSimThr();
            gc.setForeground(hit ? hitColor : missColor);
            gc.setLineWidth(roiLineWidth);
            gc.drawRectangle(wx, wy, ww, wh);
        }
        if (dragging) {
            gc.setForeground(dragColor);
            gc.setLineWidth(dragThickness);
            gc.drawRectangle(Math.min(dragX0, dragX1), Math.min(dragY0, dragY1),
                    Math.abs(dragX1 - dragX0), Math.abs(dragY1 - dragY0));
        }
        Point sz = canvas.getSize();
        String ncc = (r != null && "ok".equals(r.state))
                ? String.format("NCC %.2f", r.ncc) : "NCC --";
        String hud = ncc + "   ROI " + roiText(roi) + "   드래그로 ROI 지정";
        gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
        gc.drawText(hud, 8, Math.max(8, sz.y - 22), true);
    }

    private void fireMatch(RoiMatchResult r) {
        if (matchListener != null) {
            matchListener.onMatch(r);
        }
    }

    private static String visionFingerprintOf(ApxSettings s) {
        double[] n = s.getRoiNorm();
        StringBuilder sb = new StringBuilder();
        sb.append(s.isUseReferenceImage()).append('|');
        sb.append(s.getVisionRefPath()).append('|');
        if (n == null) {
            sb.append("null");
        } else {
            for (int i = 0; i < n.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.format("%.6f", n[i]));
            }
        }
        return sb.toString();
    }

    private static String roiText(int[] roi) {
        if (roi == null) {
            return "--";
        }
        int w = Math.max(0, roi[3] - roi[2]);
        int h = Math.max(0, roi[1] - roi[0]);
        return w + "x" + h;
    }
}
