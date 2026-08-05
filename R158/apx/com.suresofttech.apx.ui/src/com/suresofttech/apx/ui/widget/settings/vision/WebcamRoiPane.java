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
    private String visionFingerprint;

    /** 폴링 주기(ms) — 비전 검증 루프(ClusterView)와 동일. 카메라 fps보다 촘촘히 폴링해 양자화 지연 제거,
     *  중복 프레임은 캐시로 스킵. 장비 fps가 바뀌면(30→60fps) 자동으로 따라간다. */
    private static final int POLL_MS = 4;
    private java.awt.image.BufferedImage lastBi;   // 마지막 처리 프레임(새 프레임 판별용)

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
                // ROI는 기준 영상 실제 크기에 맞춰 정규화→픽셀 변환 (640 고정 없음)
                det = new RoiMatchDetector(path, null, settings.getSimThr());
                det.setRoi(settings.getRoi(det.canonWidth(), det.canonHeight()));
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
            return;
        }
        try {
            int[] roi = settings.getRoi(bi.getWidth(), bi.getHeight());
            det = new RoiMatchDetector(bi, roi, settings.getSimThr());
            // 설정 라이브 기준: ORB 정렬 생략 — 특징점 없는 장면(흰 벽 등)에서도 NCC가 aligning에 갇히지 않음
            det.setAlignEnabled(false);
            det.setSimThr(settings.getSimThr());
            // 등록 직후 동일 프레임으로 NCC 1회 — 탭 오픈/OFF 전환 시 새 프레임을 기다리지 않음
            RoiMatchResult r = det.process(bi);
            last = r;
            lastBi = bi;
            preview.setFrame(bi);
            fireMatch(r);
            preview.redraw();
            msg("기준 이미지 미사용 (현재 화면을 기준으로 ROI/NCC 측정)");
        } catch (Exception ex) {
            det = null;
            last = null;
            fireMatch(null);
            msg("라이브 기준 등록 실패: " + ex.getMessage());
        }
    }

    private void startCameraPoll() {
        polling = true;
        display.timerExec(POLL_MS, new Runnable() {
            public void run() {
                if (!polling || preview == null || preview.isDisposed()) {
                    return;
                }
                display.timerExec(POLL_MS, this);   // 재예약을 작업 앞으로 → 주기 = max(POLL_MS, 작업시간)
                pollTick();
            }
        });
    }

    /** 한 틱 — 새 프레임일 때만 표시·검출(중복 프레임은 스킵). 프레임 도착 속도(=카메라 fps)에 자동으로 맞춰짐. */
    private void pollTick() {
        java.awt.image.BufferedImage bi = CameraService.get().latest();
        if (bi == lastBi) {
            return;   // 새 프레임 없음 — setFrame/process 낭비 방지
        }
        lastBi = bi;
        preview.setFrame(bi);                    // 항상 원본 프레임(크기 일정)

        // 기준 이미지 OFF: 탭 오픈 직후엔 프레임이 없어 det=null → 첫 프레임이 오면 즉시 라이브 기준 등록+NCC
        if (!settings.isUseReferenceImage() && det == null && bi != null) {
            ensureLiveReferenceDetector();
            return;   // ensure 안에서 이미 process·표시 완료
        }

        // 웹캠 해상도가 바뀌면(장치/모드) 라이브 기준을 현재 프레임 크기로 재등록
        if (!settings.isUseReferenceImage() && det != null && bi != null
                && (bi.getWidth() != det.canonWidth() || bi.getHeight() != det.canonHeight())) {
            ensureLiveReferenceDetector();
            return;
        }

        if (det != null && bi != null) {
            RoiMatchResult r = det.process(bi);  // NCC는 기준 영상 실제 해상도 좌표계
            last = r;
            fireMatch(r);
            preview.redraw();                    // HUD NCC·ROI 색 즉시 반영
        } else if (last != null) {
            last = null;
            fireMatch(null);
            preview.redraw();
        }
    }

    private void commitRoiFromDrag() {
        java.awt.image.BufferedImage disp = preview.getFrame();
        if (disp == null) {
            preview.redraw();
            return;
        }
        // 우발 클릭 무시 — 화면(위젯) 드래그 거리 기준(창을 키워 업스케일돼도 일관). 이미지 픽셀 기준(X)
        if (Math.abs(dragX1 - dragX0) < 12 || Math.abs(dragY1 - dragY0) < 12) {
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
        int y2 = Math.max(a[1], b[1]) + 1;   // exclusive end (submat 관례)
        int x1 = Math.min(a[0], b[0]);
        int x2 = Math.max(a[0], b[0]) + 1;
        y2 = Math.min(disp.getHeight(), Math.max(y1 + 1, y2));
        x2 = Math.min(disp.getWidth(), Math.max(x1 + 1, x2));
        // 이미지 픽셀 좌표 그대로 — 640 환산 없음. 정규화로 저장해 해상도 변경에 대비
        int[] roi = new int[] { y1, y2, x1, x2 };
        settings.setRoi(roi, disp.getWidth(), disp.getHeight());

        if (!settings.isUseReferenceImage()) {
            ensureLiveReferenceDetector();
            if (det != null) {
                det.setRoi(roi);
            }
        } else if (det != null) {
            // 파일 기준: 표시 프레임≠기준 크기일 수 있음 → 기준 크기로 재매핑
            det.setRoi(settings.getRoi(det.canonWidth(), det.canonHeight()));
        }
        preview.redraw();
        fireMatch(last);
        msg("ROI 지정: " + roiText(roi));
    }

    private void drawOverlay(GC gc, double scale, int dx, int dy) {
        RoiMatchResult r = last;
        java.awt.image.BufferedImage disp = preview.getFrame();
        int[] roi = (r != null && r.roi != null) ? r.roi
                : (disp != null ? settings.getRoi(disp.getWidth(), disp.getHeight()) : null);
        if (roi != null && disp != null) {
            // ROI = 표시 프레임(또는 정렬된 기준) 픽셀 좌표 → 위젯 좌표 (scale/dx/dy는 CameraCanvas와 동일)
            // 라이브: 표시=원본 프레임, ROI도 원본 픽셀. 파일 기준+정렬 표시 시엔 det ROI를 프레임에 스케일.
            double sx = 1.0;
            double sy = 1.0;
            if (det != null && (disp.getWidth() != det.canonWidth() || disp.getHeight() != det.canonHeight())) {
                sx = disp.getWidth() / (double) det.canonWidth();
                sy = disp.getHeight() / (double) det.canonHeight();
            }
            int wx = (int) Math.round(dx + roi[2] * sx * scale);
            int wy = (int) Math.round(dy + roi[0] * sy * scale);
            int ww = (int) Math.round((roi[3] - roi[2]) * sx * scale);
            int wh = (int) Math.round((roi[1] - roi[0]) * sy * scale);
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

    private void msg(String m) {
        // 상태 표시 제거(미니멀)
    }

    /**
     * 기준/ROI 등 detector 재생성 트리거 필드만 (simThr 제외 — 임계는 setSimThr로 충분).
     * 음향 설정 변경 시 불필요 재빌드 방지.
     */
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
