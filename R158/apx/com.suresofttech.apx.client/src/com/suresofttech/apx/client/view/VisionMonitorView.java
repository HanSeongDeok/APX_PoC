package com.suresofttech.apx.client.view;

import java.awt.image.BufferedImage;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.measure.MeasureConfigSnapshot;
import com.suresofttech.apx.core.measure.MeasureSession;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.core.vision.VisionChannel;
import com.suresofttech.apx.ui.widget.TestPlayerDialog;
import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.vision.RoiNcc;

/**
 * 단일 채널 비전 모니터. 기본 View는 클러스터 채널이며,
 * 기어봉은 {@link GearVisionMonitorView}가 이 구현을 재사용한다.
 * 웹캠 선택 UI는 설정({@code CameraSelectBar}) 전용. 여기서는 영상 / ROI만 표시한다.
 */
public class VisionMonitorView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.visionMonitor";

    /** 캡처는 전용 스레드. UI는 최신 프레임만 가져오면 되므로 60fps(~16ms)면 충분하다. */
    private static final int FRAME_POLL_MS = 16;
    private static final int IDLE_POLL_MS = 200;
    private static final int STALL_CHECK_MS = 500;
    private static final long STALL_TIMEOUT_MS = 1500;

    private static final class Pane {
        final VisionChannel channel;
        CameraCanvas canvas;
        Label statusLbl;
        RoiNcc roiNcc;
        BufferedImage lastBi;
        volatile long lastFrameNanos;
        boolean stallReported;

        Pane(VisionChannel channel) {
            this.channel = channel;
        }
    }

    private final VisionChannel channel;
    private Pane pane;
    private Display display;
    private boolean framePolling;
    private boolean reporting;
    private MeasureSession.Listener stateListener;
    private MeasureSession.GearTriggerListener gearTriggerListener;

    public VisionMonitorView() {
        this(VisionChannel.CLUSTER);
    }

    protected VisionMonitorView(VisionChannel channel) {
        this.channel = channel;
    }

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        pane = addPane(parent, channel);

        // 시작 때 카메라를 열지 않는다. VideoCapture/read 가 UI에서 행하면 화면이 멈춘다.
        if (CameraService.of(channel).isOpen()) {
            pane.canvas.setPlaceholder("(신호 대기…)");
        }
        startFramePoll();
        startStallWatchdog();
        startStateListener();
        if (channel == VisionChannel.GEAR) {
            gearTriggerListener = new MeasureSession.GearTriggerListener() {
                public void onGearTrigger() {
                    TestPlayerDialog.showClusterPopup(getSite().getShell());
                }
            };
            MeasureSession.get().setGearTriggerListener(gearTriggerListener);
        }
        MeasureSession session = MeasureSession.get();
        if (session.isRunning()) {
            onMeasureStarted(session);
        }
    }

    private Pane addPane(Composite parent, VisionChannel ch) {
        Pane p = new Pane(ch);

        Group g = new Group(parent, SWT.NONE);
        g.setText(ch.label + " 화면");
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        p.statusLbl = new Label(g, SWT.WRAP);
        p.statusLbl.setText(ch.label + ": 대기");
        p.statusLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        p.canvas = new CameraCanvas(g);
        p.canvas.setPlaceholder("설정에서 " + ch.label + " 웹캠을 연결하세요");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.minimumHeight = 320;
        p.canvas.setLayoutData(gd);

        final VisionChannel channel = ch;
        final Pane pane = p;
        p.canvas.addFrameListener(new CameraCanvas.FrameListener() {
            public void onFrame(BufferedImage bi) {
                pane.lastFrameNanos = System.nanoTime();
                MeasureSession.get().recordVisionFrame(channel, bi);
            }
        });

        // style=null → RoiStyles 공용 스타일. 설정에서 클라이언트가 넣은 ROI 색·굵기가
        // 여기에도 그대로 반영되고, 나중에 바뀌어도 따라간다.
        // interactive=false 는 생성자에서 줘야 한다 — 나중에 끄면 첫 구축이 이미 끝나서
        // 모니터가 기준 프레임을 덮어쓴다.
        p.roiNcc = new RoiNcc(p.canvas, null, ch, false);
        p.roiNcc.setMatchListener(new RoiNcc.MatchListener() {
            public void onMatch(RoiMatchResult r) {
                if (reporting && MeasureSession.get().isRunning()) {
                    MeasureSession.get().reportVisionMatch(channel, r);
                }
            }
        });
        p.lastFrameNanos = System.nanoTime();
        return p;
    }

    /** 설정에서 이미 연 장치만 표시. 여기서는 열지 않는다. */
    private void ensureCameraOpen(Pane p) {
        if (p == null || p.canvas == null) {
            return;
        }
        CameraService svc = CameraService.of(p.channel);
        if (svc.isOpen()) {
            p.canvas.setPlaceholder("(신호 대기…)");
        } else {
            p.canvas.setPlaceholder("설정에서 " + p.channel.label + " 웹캠을 연결하세요");
        }
    }

    private void startFramePoll() {
        if (framePolling || display == null) {
            return;
        }
        framePolling = true;
        display.timerExec(FRAME_POLL_MS, new Runnable() {
            public void run() {
                if (!framePolling) {
                    return;
                }
                if (!pollPane(pane)) {
                    framePolling = false;
                    return;
                }
                boolean open = pane != null && CameraService.of(pane.channel).isOpen();
                display.timerExec(open ? FRAME_POLL_MS : IDLE_POLL_MS, this);
            }
        });
    }

    private boolean pollPane(Pane p) {
        if (p == null || p.canvas == null || p.canvas.isDisposed()) {
            return false;
        }
        BufferedImage bi = CameraService.of(p.channel).latest();
        if (bi != null && bi != p.lastBi) {
            p.lastBi = bi;
            p.canvas.setFrame(bi);
        }
        return true;
    }

    /** 측정 시작 - 스냅샷 ROI 고정, 보고 on. */
    public void onMeasureStarted(MeasureSession session) {
        reporting = true;
        MeasureConfigSnapshot snap = session == null ? null : session.getSnapshot();
        applyFixed(pane, snap);
    }

    private void applyFixed(Pane p, MeasureConfigSnapshot snap) {
        if (p == null || p.roiNcc == null || snap == null) {
            return;
        }
        p.roiNcc.setInteractive(false);
        p.roiNcc.setFixedConfig(new RoiNcc.FixedVisionConfig(
                snap.useReferenceImage(p.channel),
                snap.visionRefPath(p.channel),
                snap.roiNorm(p.channel),
                snap.simThr(p.channel)));
        String judge = snap.visionJudge(p.channel);
        if (judge == null) {
            judge = "NCC";
        }
        setStatusText(p, p.channel.label + ": 측정 중 (" + judge + ")");
    }

    public void onMeasureStopped() {
        reporting = false;
        clearFixed(pane);
    }

    private static void clearFixed(Pane p) {
        if (p == null || p.roiNcc == null) {
            return;
        }
        p.roiNcc.setFixedConfig(null);
        p.roiNcc.setInteractive(false);
    }

    /**
     * 측정 중단 직전 - RoiNcc의 ±3프레임 증거를 Session에 넘긴다.
     * {@link #onMeasureStopped()}가 detector를 리빌드하기 전에 호출해야 한다.
     */
    public void harvestEvidenceToSession(MeasureSession session) {
        if (session == null) {
            return;
        }
        harvest(pane, session, channel == VisionChannel.GEAR);
    }

    private static void harvest(Pane p, MeasureSession session, boolean gear) {
        if (p == null || p.roiNcc == null) {
            return;
        }
        p.roiNcc.flushEvidence();
        if (gear) {
            session.acceptGearVisionFrameEvidence(p.roiNcc.getEvidence());
        } else {
            session.acceptVisionFrameEvidence(p.roiNcc.getEvidence());
        }
    }

    public byte[] capturePng() {
        if (pane == null || pane.canvas == null || pane.canvas.isDisposed()) {
            return null;
        }
        pane.canvas.update();
        return pane.canvas.capturePng();
    }

    /** 설정 다이얼로그 확인 후 - 측정 중이 아니면 ApxSettings 기준으로 ROI 재구축. */
    public void applyFromSettings() {
        if (MeasureSession.get().isRunning()) {
            return;
        }
        rebuildFromSettings(pane);
        ensureCameraOpen(pane);
    }

    private static void rebuildFromSettings(Pane p) {
        if (p == null || p.roiNcc == null) {
            return;
        }
        p.roiNcc.setFixedConfig(null);
        p.roiNcc.setInteractive(false);
        p.roiNcc.rebuildDetectorFromSettings();
    }

    private void startStallWatchdog() {
        if (pane != null) {
            pane.lastFrameNanos = System.nanoTime();
        }
        if (display == null) {
            return;
        }
        display.timerExec(STALL_CHECK_MS, new Runnable() {
            public void run() {
                if (!checkStall(pane)) {
                    return;
                }
                display.timerExec(STALL_CHECK_MS, this);
            }
        });
    }

    private boolean checkStall(Pane p) {
        if (p == null || p.canvas == null || p.canvas.isDisposed()) {
            return false;
        }
        if (!CameraService.of(p.channel).isOpen()) {
            return true;
        }
        long idleMs = (System.nanoTime() - p.lastFrameNanos) / 1000000L;
        if (p.lastBi != null && idleMs > STALL_TIMEOUT_MS) {
            if (!p.stallReported) {
                p.canvas.setPlaceholder("입력 끊김 - 마지막 프레임 "
                        + (idleMs / 1000) + "초 전");
                p.stallReported = true;
            }
        } else if (p.stallReported) {
            p.canvas.setPlaceholder("설정에서 " + p.channel.label + " 웹캠을 연결하세요");
            p.stallReported = false;
        }
        return true;
    }

    private void startStateListener() {
        stateListener = new MeasureSession.Listener() {
            public void onAudioTick(MatchResult match, double[] waveBuf, double elapsedSec) {
            }

            public void onVisionMatch(RoiMatchResult result) {
            }

            public void onState(boolean audioPass, boolean visionPass, boolean overallPass) {
                if (display == null || display.isDisposed()) {
                    return;
                }
                display.asyncExec(new Runnable() {
                    public void run() {
                        refreshStatus();
                    }
                });
            }
        };
        MeasureSession.get().addListener(stateListener);
    }

    private void refreshStatus() {
        refreshPaneStatus(pane);
    }

    private void refreshPaneStatus(Pane p) {
        if (p == null || p.statusLbl == null || p.statusLbl.isDisposed()) {
            return;
        }
        MeasureSession s = MeasureSession.get();
        if (s.isRunning()) {
            MeasureConfigSnapshot snap = s.getSnapshot();
            String j = (snap != null && snap.visionJudge(p.channel) != null)
                    ? snap.visionJudge(p.channel) : "NCC";
            setStatusText(p, p.channel.label + ": 측정 중 (" + j + ")");
            return;
        }
        boolean pass = p.channel == VisionChannel.GEAR ? s.isGearPass() : s.isClusterPass();
        if (pass) {
            setStatusText(p, p.channel.label + ": 대기");
        } else {
            setStatusText(p, p.channel.label + ": FAIL (미검출)");
        }
    }

    private static void setStatusText(Pane p, String text) {
        if (p != null && p.statusLbl != null && !p.statusLbl.isDisposed()) {
            p.statusLbl.setText(text);
        }
    }

    @Override
    public void setFocus() {
        if (pane != null && pane.canvas != null && !pane.canvas.isDisposed()) {
            pane.canvas.setFocus();
        }
    }

    @Override
    public void dispose() {
        framePolling = false;
        if (stateListener != null) {
            MeasureSession.get().removeListener(stateListener);
            stateListener = null;
        }
        if (gearTriggerListener != null) {
            MeasureSession.get().clearGearTriggerListener(gearTriggerListener);
            gearTriggerListener = null;
        }
        super.dispose();
    }
}
