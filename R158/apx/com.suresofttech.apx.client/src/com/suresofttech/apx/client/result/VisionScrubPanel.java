package com.suresofttech.apx.client.result;

import java.awt.image.BufferedImage;
import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.vision.VisionPlayer;
import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;

/**
 * 결과 탭 비전 스크럽 패널 — FULL 녹화본에서 <b>그 시각의 프레임</b>을 바로 그린다.
 *
 * <p>표시는 측정 모니터와 같은 {@link CameraCanvas}를 재사용하고, 시각→프레임 변환은
 * {@link VisionPlayer}(=frames.csv 인덱스)에 맡긴다. 같은 프레임이 다시 요청되면
 * 디코딩하지 않으므로 슬라이더를 잘게 흔들어도 부하가 없다.
 */
public class VisionScrubPanel extends Composite {

    private final CameraCanvas canvas;
    private final Label infoLbl;

    private VisionPlayer player;
    private int lastFrame = -1;

    public VisionScrubPanel(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        gl.verticalSpacing = 2;
        setLayout(gl);

        canvas = new CameraCanvas(this);
        canvas.setPlaceholder("비전 녹화본 없음 (full.avi)");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 220;
        gd.minimumHeight = 140;
        canvas.setLayoutData(gd);

        infoLbl = new Label(this, SWT.NONE);
        infoLbl.setText("—");
        infoLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                closePlayer();
            }
        });
    }

    /**
     * 증거 폴더의 {@code vision/}을 연다.
     * @return 녹화본이 있으면 true
     */
    public boolean open(File visionDir) {
        closePlayer();
        player = VisionPlayer.open(visionDir);
        lastFrame = -1;
        if (player == null) {
            canvas.setFrame(null);
            canvas.setPlaceholder("비전 녹화본 없음 (full.avi)");
            infoLbl.setText("녹화본 없음 — 이번 측정 이후 저장분부터 스크럽됩니다");
            return false;
        }
        infoLbl.setText(String.format("녹화 %d프레임 · %.2f s%s",
                Integer.valueOf(player.getFrameCount()),
                Double.valueOf(player.durationMs() / 1000.0),
                player.hasIndex() ? " · 시각 인덱스 사용" : " · 인덱스 없음(fps 근사)"));
        showAt(0);
        return true;
    }

    /** 녹화 길이(ms). 없으면 0. */
    public double durationMs() {
        return player == null ? 0 : player.durationMs();
    }

    public boolean hasVideo() {
        return player != null;
    }

    /** 그 시각의 프레임을 그린다. 프레임이 안 바뀌면 아무 일도 하지 않는다. */
    public void showAt(double tMs) {
        if (player == null || canvas.isDisposed()) {
            return;
        }
        int frame = player.frameAt(tMs);
        if (frame == lastFrame) {
            return;
        }
        BufferedImage bi = player.frameImage(frame);
        if (bi == null) {
            return;
        }
        lastFrame = frame;
        canvas.setFrame(bi);
        infoLbl.setText(String.format("프레임 %d / %d · 실제 %.0f ms (요청 %.0f ms)",
                Integer.valueOf(frame), Integer.valueOf(player.getFrameCount()),
                Double.valueOf(player.timeOf(frame)), Double.valueOf(tMs)));
    }

    public CameraCanvas getCanvas() {
        return canvas;
    }

    private void closePlayer() {
        if (player != null) {
            player.close();
            player = null;
        }
        lastFrame = -1;
    }
}
