package com.suresofttech.apx.client.view;

import java.awt.image.BufferedImage;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.measure.MeasureConfigSnapshot;
import com.suresofttech.apx.core.measure.MeasureSession;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.vision.RoiNcc;

/**
 * 비전 모니터 — {@link CameraCanvas} + read-only {@link RoiNcc}.
 * 웹캠 선택 UI는 설정({@code CameraSelectBar}) 전용. 여기서는 영상·ROI만 표시한다.
 */
public class VisionMonitorView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.visionMonitor";

    private static final int FRAME_POLL_MS = 4;
    /** 프레임 끊김 감시 주기·판정 시간 — 30fps면 33ms 간격이라 1.5초면 확실히 끊긴 것. */
    private static final int STALL_CHECK_MS = 500;
    private static final long STALL_TIMEOUT_MS = 1500;

    private CameraCanvas canvas;
    private RoiNcc roiNcc;
    private Display display;
    private boolean framePolling;
    private BufferedImage lastBi;
    private boolean reporting;
    /** 마지막 프레임 수신 시각 — 웹캠 분리 감지용. */
    private volatile long lastFrameNanos;
    private boolean stallReported;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        canvas = new CameraCanvas(parent);
        canvas.setPlaceholder("설정에서 웹캠을 연결하세요");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 280;
        gd.minimumHeight = 200;
        canvas.setLayoutData(gd);

        // FULL 녹화 tap — 매칭(RoiNcc)과 같은 프레임을 그대로 받아 세션 녹화기에 넘긴다.
        canvas.addFrameListener(new CameraCanvas.FrameListener() {
            public void onFrame(BufferedImage bi) {
                lastFrameNanos = System.nanoTime();
                MeasureSession.get().recordVisionFrame(bi);
            }
        });

        roiNcc = new RoiNcc(canvas);
        roiNcc.setInteractive(false);
        roiNcc.setMatchListener(new RoiNcc.MatchListener() {
            public void onMatch(RoiMatchResult r) {
                if (reporting && MeasureSession.get().isRunning()) {
                    MeasureSession.get().reportVisionMatch(r);
                }
            }
        });

        ensureCameraOpen();
        startFramePoll();
        startStallWatchdog();
    }

    /** 설정에서 이미 연 장치를 쓰고, 없으면 목록 첫 장치를 연다(선택 UI 없음). */
    private void ensureCameraOpen() {
        CameraService svc = CameraService.get();
        if (svc.isOpen()) {
            canvas.setPlaceholder("(신호 대기…)");
            return;
        }
        String wanted = svc.currentName();
        if (wanted != null && svc.reopenByName(wanted)) {
            canvas.setPlaceholder("(신호 대기…)");
            return;
        }
        List<CameraService.Cam> cams = svc.list();
        if (!cams.isEmpty() && svc.open(cams.get(0).index)) {
            canvas.setPlaceholder("(신호 대기…)");
        } else {
            canvas.setPlaceholder("설정에서 웹캠을 연결하세요");
        }
    }

    /** {@link CameraService#latest()} → canvas. SelectBar 없이 모니터만 프레임을 받는다. */
    private void startFramePoll() {
        if (framePolling || display == null) {
            return;
        }
        framePolling = true;
        display.timerExec(FRAME_POLL_MS, new Runnable() {
            public void run() {
                if (!framePolling || canvas == null || canvas.isDisposed()) {
                    framePolling = false;
                    return;
                }
                display.timerExec(FRAME_POLL_MS, this);
                BufferedImage bi = CameraService.get().latest();
                if (bi == null || bi == lastBi) {
                    return;
                }
                lastBi = bi;
                canvas.setFrame(bi);
            }
        });
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

    /**
     * 프레임 끊김 감시 — 웹캠이 빠지면 {@code onMatch}가 아예 안 불린다.
     * 플레이스홀더로 끊김을 표시한다.
     */
    private void startStallWatchdog() {
        lastFrameNanos = System.nanoTime();
        final Display d = canvas.getDisplay();
        d.timerExec(STALL_CHECK_MS, new Runnable() {
            public void run() {
                if (canvas == null || canvas.isDisposed()) {
                    return;
                }
                long idleMs = (System.nanoTime() - lastFrameNanos) / 1000000L;
                if (idleMs > STALL_TIMEOUT_MS) {
                    if (!stallReported) {
                        canvas.setPlaceholder("입력 끊김 — 마지막 프레임 "
                                + (idleMs / 1000) + "초 전");
                        stallReported = true;
                    }
                } else {
                    if (stallReported) {
                        canvas.setPlaceholder("설정에서 웹캠을 연결하세요");
                        stallReported = false;
                    }
                }
                d.timerExec(STALL_CHECK_MS, this);
            }
        });
    }

    @Override
    public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setFocus();
        }
    }

    @Override
    public void dispose() {
        framePolling = false;
        super.dispose();
    }
}
