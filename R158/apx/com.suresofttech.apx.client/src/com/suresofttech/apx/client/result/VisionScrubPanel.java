package com.suresofttech.apx.client.result;

import java.awt.image.BufferedImage;
import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.vision.VisionMatchLog;
import com.suresofttech.apx.core.vision.VisionPlayer;
import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;

/**
 * 결과 탭 비전 스크럽 — FULL 녹화 프레임 + 측정 당시 ROI PASS/FAIL 색.
 *
 * <p>{@code matches.csv}의 hit 시계열과 meta ROI로, 스크럽 시각에 모니터와 같은
 * 초록(PASS)/노랑(FAIL) ROI 박스를 그린다.
 */
public class VisionScrubPanel extends Composite {

    private final CameraCanvas canvas;
    private final Label infoLbl;
    private final Color hitColor;
    private final Color missColor;

    private VisionPlayer player;
    private VisionMatchLog matchLog = new VisionMatchLog();
    private double[] roiNorm;
    private double simThr = 0.85;
    private double currentMs;
    private int lastFrame = -1;
    private Boolean lastHit;

    public VisionScrubPanel(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        gl.verticalSpacing = 2;
        setLayout(gl);

        hitColor = new Color(parent.getDisplay(), 0, 255, 0);
        missColor = new Color(parent.getDisplay(), 255, 255, 0);

        canvas = new CameraCanvas(this);
        canvas.setPlaceholder("비전 녹화본 없음 (full.avi)");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 220;
        gd.minimumHeight = 140;
        canvas.setLayoutData(gd);
        canvas.setOverlay(new CameraCanvas.Overlay() {
            public void paint(GC gc, double scale, int dx, int dy) {
                paintRoi(gc, scale, dx, dy);
            }
        });

        infoLbl = new Label(this, SWT.NONE);
        infoLbl.setText("—");
        infoLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                closePlayer();
                if (!hitColor.isDisposed()) {
                    hitColor.dispose();
                }
                if (!missColor.isDisposed()) {
                    missColor.dispose();
                }
            }
        });
    }

    /**
     * 증거 폴더의 {@code vision/}을 연다.
     * @return 녹화본이 있으면 true
     */
    public boolean open(File visionDir) {
        closePlayer();
        matchLog = VisionMatchLog.load(visionDir);
        player = VisionPlayer.open(visionDir);
        lastFrame = -1;
        lastHit = null;
        currentMs = 0;
        if (player == null) {
            canvas.setFrame(null);
            canvas.setPlaceholder("비전 녹화본 없음 (full.avi)");
            infoLbl.setText(matchLog.isEmpty()
                    ? "녹화본 없음 — 이번 측정 이후 저장분부터 스크럽됩니다"
                    : "녹화본 없음 · ROI 시계열만 있음");
            return false;
        }
        infoLbl.setText(String.format("녹화 %d프레임 · %.2f s%s%s",
                Integer.valueOf(player.getFrameCount()),
                Double.valueOf(player.durationMs() / 1000.0),
                player.hasIndex() ? " · 시각 인덱스" : " · fps 근사",
                matchLog.isEmpty() ? " · ROI 로그 없음" : " · ROI 로그 " + matchLog.size()));
        showAt(0);
        return true;
    }

    /** 측정 스냅샷 ROI — meta의 roiNorm / simThr. */
    public void setRoiConfig(double[] roiNorm, double simThr) {
        this.roiNorm = roiNorm == null || roiNorm.length < 4 ? null : roiNorm.clone();
        this.simThr = simThr;
        if (!canvas.isDisposed()) {
            canvas.redraw();
        }
    }

    /** 녹화 길이(ms). 없으면 0. */
    public double durationMs() {
        return player == null ? 0 : player.durationMs();
    }

    public boolean hasVideo() {
        return player != null;
    }

    public VisionMatchLog getMatchLog() {
        return matchLog;
    }

    /** 그 시각의 프레임 + ROI 색을 그린다. */
    public void showAt(double tMs) {
        currentMs = tMs;
        VisionMatchLog.Sample sample = matchLog.at(tMs);
        Boolean hit = sample == null ? null : Boolean.valueOf(sample.hit);
        boolean hitChanged = hit == null ? lastHit != null
                : lastHit == null || hit.booleanValue() != lastHit.booleanValue();
        lastHit = hit;

        if (player == null || canvas.isDisposed()) {
            if (hitChanged && !canvas.isDisposed()) {
                canvas.redraw();
            }
            return;
        }
        int frame = player.frameAt(tMs);
        if (frame != lastFrame) {
            BufferedImage bi = player.frameImage(frame);
            if (bi == null) {
                return;
            }
            lastFrame = frame;
            canvas.setFrame(bi);
        } else if (hitChanged) {
            canvas.redraw();
        }
        String pass = hit == null ? "ROI —"
                : (hit.booleanValue() ? "ROI PASS" : "ROI FAIL");
        String ncc = sample == null ? ""
                : String.format(" · NCC %.2f (thr %.2f)",
                        Double.valueOf(sample.ncc), Double.valueOf(simThr));
        infoLbl.setText(String.format("프레임 %d / %d · %.0f ms · %s%s",
                Integer.valueOf(frame), Integer.valueOf(player.getFrameCount()),
                Double.valueOf(tMs), pass, ncc));
    }

    public CameraCanvas getCanvas() {
        return canvas;
    }

    private void paintRoi(GC gc, double scale, int dx, int dy) {
        if (roiNorm == null) {
            return;
        }
        BufferedImage f = canvas.getFrame();
        if (f == null) {
            return;
        }
        int[] roi = normToRoi(roiNorm, f.getWidth(), f.getHeight());
        if (roi == null) {
            return;
        }
        int wx = (int) Math.round(dx + roi[2] * scale);
        int wy = (int) Math.round(dy + roi[0] * scale);
        int ww = (int) Math.round((roi[3] - roi[2]) * scale);
        int wh = (int) Math.round((roi[1] - roi[0]) * scale);
        VisionMatchLog.Sample sample = matchLog.at(currentMs);
        if (sample == null) {
            // 구 증거(로그 없음) — ROI 위치만 회색으로
            gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_GRAY));
        } else {
            gc.setForeground(sample.hit ? hitColor : missColor);
        }
        gc.setLineWidth(3);
        gc.drawRectangle(wx, wy, ww, wh);

        Point sz = canvas.getSize();
        String hud = sample == null ? "ROI (로그 없음)"
                : String.format("%s  NCC %.2f",
                        sample.hit ? "PASS" : "FAIL", Double.valueOf(sample.ncc));
        gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_WHITE));
        gc.drawText(hud, 8, Math.max(8, sz.y - 22), true);
    }

    private static int[] normToRoi(double[] n, int w, int h) {
        if (n == null || n.length < 4 || w <= 0 || h <= 0) {
            return null;
        }
        int y1 = (int) Math.round(n[0] * h);
        int y2 = (int) Math.round(n[1] * h);
        int x1 = (int) Math.round(n[2] * w);
        int x2 = (int) Math.round(n[3] * w);
        y1 = Math.max(0, Math.min(h - 1, y1));
        y2 = Math.max(y1 + 1, Math.min(h, y2));
        x1 = Math.max(0, Math.min(w - 1, x1));
        x2 = Math.max(x1 + 1, Math.min(w, x2));
        return new int[] { y1, y2, x1, x2 };
    }

    private void closePlayer() {
        if (player != null) {
            player.close();
            player = null;
        }
        lastFrame = -1;
        lastHit = null;
    }
}
