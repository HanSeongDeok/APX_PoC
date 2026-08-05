package com.suresofttech.apx.ui.widget.settings.vision;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.RoiMatchDetector;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.ui.widget.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.SettingsUi;
import com.suresofttech.apx.ui.widget.settings.StatusSink;

/**
 * 웹캠 프리뷰 + ROI 드래그 + NCC 오버레이 — detector·라이브 루프 내장.
 * {@link ApxSettings} 리스너로 기준/ROI/임계 변경을 반영한다.
 */
public class WebcamRoiPane extends Composite {

    public interface MatchListener {
        void onMatch(RoiMatchResult r);
    }

    private final Display display;
    private final ApxSettings settings = ApxSettings.get();
    private final CameraCanvas preview;
    private final ApxSettings.Listener settingsListener;

    private RoiMatchDetector det;
    private volatile RoiMatchResult last;
    private boolean dragging;
    private int dragX0, dragY0, dragX1, dragY1;
    private boolean polling;
    private MatchListener matchListener;
    private StatusSink status;
    private String visionFingerprint;

    public WebcamRoiPane(Composite parent) {
        super(parent, SWT.NONE);
        display = getDisplay();
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        preview = new CameraCanvas(this);
        preview.setPlaceholder("웹캠을 선택하세요");
        GridData pd = new GridData(SWT.FILL, SWT.FILL, true, true);
        pd.heightHint = 320;
        pd.minimumHeight = 240;
        preview.setLayoutData(pd);
        preview.setOverlay(new CameraCanvas.Overlay() {
            public void paint(GC gc, double scale, int dx, int dy) {
                drawOverlay(gc, scale, dx, dy);
            }
        });
        preview.addMouseListener(new MouseAdapter() {
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
        preview.addMouseMoveListener(new MouseMoveListener() {
            public void mouseMove(MouseEvent e) {
                if (dragging) {
                    dragX1 = e.x;
                    dragY1 = e.y;
                    preview.redraw();
                }
            }
        });

        settingsListener = new ApxSettings.Listener() {
            public void onSettingsChanged(ApxSettings s) {
                if (isDisposed()) {
                    return;
                }
                final String fp = visionFingerprintOf(s);
                display.asyncExec(new Runnable() {
                    public void run() {
                        if (isDisposed()) {
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
        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                polling = false;
                settings.removeListener(settingsListener);
            }
        });

        rebuildDetectorFromSettings();
        startCameraPoll();
    }

    public void setMatchListener(MatchListener l) {
        this.matchListener = l;
    }

    public void setStatusSink(StatusSink sink) {
        this.status = sink;
    }

    public void setPlaceholder(String text) {
        if (preview != null && !preview.isDisposed()) {
            preview.setPlaceholder(text);
        }
    }

    public void clearFrame() {
        if (preview != null && !preview.isDisposed()) {
            preview.setFrame(null);
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
                det = new RoiMatchDetector(path, settings.getRoi(), settings.getSimThr());
                if (settings.getRoi() != null) {
                    det.setRoi(settings.getRoi());
                }
                det.setSimThr(settings.getSimThr());
            } catch (Exception ex) {
                det = null;
                msg("기준 이미지 로드 실패: " + ex.getMessage());
            }
            fireMatch(null);
            return;
        }
        ensureLiveReferenceDetector();
    }

    private void ensureLiveReferenceDetector() {
        java.awt.image.BufferedImage bi = CameraService.get().latest();
        if (bi == null) {
            det = null;
            last = null;
            fireMatch(null);
            msg("웹캠 프레임이 없습니다 — 웹캠을 켠 뒤 ROI를 지정하세요");
            return;
        }
        try {
            det = new RoiMatchDetector(bi, settings.getRoi(), settings.getSimThr());
            if (settings.getRoi() != null) {
                det.setRoi(settings.getRoi());
            }
            det.setSimThr(settings.getSimThr());
            msg("기준 이미지 미사용 — 현재 화면을 기준으로 ROI/NCC 측정");
        } catch (Exception ex) {
            det = null;
            msg("라이브 기준 등록 실패: " + ex.getMessage());
        }
        fireMatch(null);
    }

    private void startCameraPoll() {
        polling = true;
        display.timerExec(60, new Runnable() {
            public void run() {
                if (!polling || preview == null || preview.isDisposed()) {
                    return;
                }
                java.awt.image.BufferedImage bi = CameraService.get().latest();
                if (det != null && bi != null) {
                    RoiMatchResult r = det.process(bi);
                    last = r;
                    preview.setFrame(r.canonImage != null ? r.canonImage : bi);
                    fireMatch(r);
                } else {
                    preview.setFrame(bi);
                    if (last != null) {
                        last = null;
                        fireMatch(null);
                    }
                }
                if (polling && !isDisposed()) {
                    display.timerExec(60, this);
                }
            }
        });
    }

    private void commitRoiFromDrag() {
        java.awt.image.BufferedImage disp = preview.getFrame();
        if (disp == null) {
            preview.redraw();
            return;
        }
        int[] a = preview.widgetToImage(dragX0, dragY0);
        int[] b = preview.widgetToImage(dragX1, dragY1);
        if (a == null || b == null) {
            preview.redraw();
            return;
        }
        int y1 = Math.min(a[1], b[1]);
        int y2 = Math.max(a[1], b[1]);
        int x1 = Math.min(a[0], b[0]);
        int x2 = Math.max(a[0], b[0]);
        if (y2 - y1 < 6 || x2 - x1 < 6) {
            preview.redraw();
            return;
        }
        int[] roi = toCanonRoi(y1, y2, x1, x2, disp.getWidth(), disp.getHeight());
        settings.setRoi(roi);

        if (!settings.isUseReferenceImage()) {
            ensureLiveReferenceDetector();
            if (det != null) {
                det.setRoi(roi);
            }
        } else if (det != null) {
            det.setRoi(roi);
        }
        preview.redraw();
        fireMatch(last);
        msg("ROI 지정: " + roiText(roi));
    }

    private void drawOverlay(GC gc, double scale, int dx, int dy) {
        RoiMatchResult r = last;
        int[] roi = (r != null && r.roi != null) ? r.roi : settings.getRoi();
        java.awt.image.BufferedImage disp = preview.getFrame();
        boolean canonFrame = disp != null && disp.getWidth() == SettingsUi.CANON
                && disp.getHeight() == SettingsUi.CANON;
        if (roi != null && canonFrame) {
            int wx = (int) Math.round(dx + roi[2] * scale);
            int wy = (int) Math.round(dy + roi[0] * scale);
            int ww = (int) Math.round((roi[3] - roi[2]) * scale);
            int wh = (int) Math.round((roi[1] - roi[0]) * scale);
            boolean hit = r != null && "ok".equals(r.state) && r.ncc >= settings.getSimThr();
            gc.setForeground(display.getSystemColor(hit ? SWT.COLOR_GREEN : SWT.COLOR_YELLOW));
            gc.setLineWidth(2);
            gc.drawRectangle(wx, wy, ww, wh);
        }
        if (dragging) {
            gc.setForeground(display.getSystemColor(SWT.COLOR_CYAN));
            gc.setLineWidth(1);
            gc.drawRectangle(Math.min(dragX0, dragX1), Math.min(dragY0, dragY1),
                    Math.abs(dragX1 - dragX0), Math.abs(dragY1 - dragY0));
        }
        Point sz = preview.getSize();
        String ncc = (r != null && "ok".equals(r.state))
                ? String.format("NCC %.2f", r.ncc) : "NCC —";
        String hud = ncc + "   ROI " + roiText(roi) + "   드래그로 ROI 지정";
        gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
        gc.drawText(hud, 8, Math.max(8, sz.y - 22), true);
    }

    private void fireMatch(RoiMatchResult r) {
        if (matchListener != null) {
            matchListener.onMatch(r);
        }
    }

    private void msg(String m) {
        if (status != null) {
            status.setMessage(m);
        }
    }

    /**
     * 기준/ROI 등 detector 재생성 트리거 필드만 (simThr 제외 — 임계는 setSimThr로 충분).
     * 음향 설정 변경 시 불필요 재빌드 방지.
     */
    private static String visionFingerprintOf(ApxSettings s) {
        int[] roi = s.getRoi();
        StringBuilder sb = new StringBuilder();
        sb.append(s.isUseReferenceImage()).append('|');
        sb.append(s.getVisionRefPath()).append('|');
        if (roi == null) {
            sb.append("null");
        } else {
            for (int i = 0; i < roi.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(roi[i]);
            }
        }
        return sb.toString();
    }

    private static int[] toCanonRoi(int y1, int y2, int x1, int x2, int imgW, int imgH) {
        if (imgW == SettingsUi.CANON && imgH == SettingsUi.CANON) {
            return new int[] { y1, y2, x1, x2 };
        }
        int cy1 = (int) Math.round(y1 * (double) SettingsUi.CANON / imgH);
        int cy2 = (int) Math.round(y2 * (double) SettingsUi.CANON / imgH);
        int cx1 = (int) Math.round(x1 * (double) SettingsUi.CANON / imgW);
        int cx2 = (int) Math.round(x2 * (double) SettingsUi.CANON / imgW);
        cy1 = clamp(cy1, 0, SettingsUi.CANON - 1);
        cy2 = clamp(cy2, cy1 + 1, SettingsUi.CANON);
        cx1 = clamp(cx1, 0, SettingsUi.CANON - 1);
        cx2 = clamp(cx2, cx1 + 1, SettingsUi.CANON);
        return new int[] { cy1, cy2, cx1, cx2 };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String roiText(int[] roi) {
        if (roi == null) {
            return "—";
        }
        int w = Math.max(0, roi[3] - roi[2]);
        int h = Math.max(0, roi[1] - roi[0]);
        return w + "x" + h;
    }
}
