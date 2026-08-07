package com.suresofttech.apx.client.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.measure.MeasureConfigSnapshot;
import com.suresofttech.apx.core.measure.MeasureSession;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.vision.CameraSelectBar;
import com.suresofttech.apx.ui.widget.settings.vision.RoiNcc;

/**
 * 비전 모니터 — CameraCanvas + read-only RoiNcc.
 * 매칭 결과는 Session에 보고한다.
 */
public class VisionMonitorView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.visionMonitor";

    private CameraSelectBar cameraSelect;
    private CameraCanvas canvas;
    private RoiNcc roiNcc;
    private boolean reporting;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        cameraSelect = new CameraSelectBar(parent);
        cameraSelect.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        canvas = new CameraCanvas(parent);
        canvas.setPlaceholder("웹캠을 선택하세요");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 280;
        gd.minimumHeight = 200;
        canvas.setLayoutData(gd);
        cameraSelect.setCanvas(canvas);

        roiNcc = new RoiNcc(canvas);
        roiNcc.setInteractive(false);
        roiNcc.setMatchListener(new RoiNcc.MatchListener() {
            public void onMatch(RoiMatchResult r) {
                if (reporting && MeasureSession.get().isRunning()) {
                    MeasureSession.get().reportVisionMatch(r);
                }
            }
        });

        cameraSelect.refreshCameras();
    }

    /** 측정 시작 — 스냅샷 ROI 고정, 보고 on. */
    public void onMeasureStarted(MeasureSession session) {
        reporting = true;
        MeasureConfigSnapshot snap = session == null ? null : session.getSnapshot();
        if (roiNcc == null || snap == null) {
            return;
        }
        roiNcc.setInteractive(false);
        roiNcc.setFixedConfig(new RoiNcc.FixedVisionConfig(
                snap.useReferenceImage,
                snap.visionRefPath,
                snap.roiNorm,
                snap.simThr));
    }

    public void onMeasureStopped() {
        reporting = false;
        if (roiNcc != null) {
            roiNcc.setFixedConfig(null);
            roiNcc.setInteractive(false);
        }
    }

    /**
     * 측정 중단 직전 — RoiNcc의 ±3프레임 증거를 Session에 넘긴다.
     * {@link #onMeasureStopped()}가 detector를 리빌드하기 전에 호출해야 한다.
     */
    public void harvestEvidenceToSession(MeasureSession session) {
        if (roiNcc == null || session == null) {
            return;
        }
        roiNcc.flushEvidence();
        session.acceptVisionFrameEvidence(roiNcc.getEvidence());
    }

    public byte[] capturePng() {
        if (canvas == null || canvas.isDisposed()) {
            return null;
        }
        canvas.update();
        return canvas.capturePng();
    }

    /** 설정 다이얼로그 확인 후 — 측정 중이 아니면 ApxSettings 기준으로 ROI 재구축. */
    public void applyFromSettings() {
        if (roiNcc == null || MeasureSession.get().isRunning()) {
            return;
        }
        roiNcc.setFixedConfig(null);
        roiNcc.setInteractive(false);
        roiNcc.rebuildDetectorFromSettings();
    }

    @Override
    public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setFocus();
        }
    }
}
