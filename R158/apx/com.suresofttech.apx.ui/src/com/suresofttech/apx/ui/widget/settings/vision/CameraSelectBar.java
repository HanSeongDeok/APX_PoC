package com.suresofttech.apx.ui.widget.settings.vision;

import java.awt.image.BufferedImage;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.vision.CameraService;

/**
 * 웹캠 콤보 + 새로고침 — {@link CameraService} 사용.
 * 화면은 공용 {@link CameraCanvas}에 {@link #setCanvas}로 연결한다 (라이브 폴링 포함).
 */
public class CameraSelectBar extends Composite {

    private static final int POLL_MS = 4;

    private final Combo camCombo;
    private final Display display;
    private List<CameraService.Cam> cams;
    private CameraCanvas canvas;
    private boolean polling;
    private BufferedImage lastBi;

    public CameraSelectBar(Composite parent) {
        super(parent, SWT.NONE);
        display = getDisplay();
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        camCombo = new Combo(this, SWT.READ_ONLY | SWT.DROP_DOWN);
        camCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        camCombo.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                openSelectedCamera();
            }
        });
        Button refresh = new Button(this, SWT.PUSH);
        refresh.setText("새로고침");
        refresh.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                refreshCameras();
            }
        });

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                polling = false;
            }
        });
    }

    /** 웹캠 화면({@link CameraCanvas}) — 장치 열기 후 여기로 프레임을 넣는다. */
    public void setCanvas(CameraCanvas canvas) {
        this.canvas = canvas;
        lastBi = null;
        startPoll();
    }

    public void refreshCameras() {
        cams = CameraService.get().list();
        camCombo.removeAll();
        for (CameraService.Cam c : cams) {
            camCombo.add(c.name);
        }
        if (!cams.isEmpty()) {
            int cur = CameraService.get().currentIndex();
            int sel = 0;
            for (int i = 0; i < cams.size(); i++) {
                if (cams.get(i).index == cur) {
                    sel = i;
                    break;
                }
            }
            camCombo.select(sel);
            openSelectedCamera();
        } else if (canvas != null && !canvas.isDisposed()) {
            canvas.setPlaceholder("연결된 웹캠 없음");
            lastBi = null;
            canvas.setFrame(null);
        }
    }

    public void openSelectedCamera() {
        if (cams == null || cams.isEmpty()) {
            return;
        }
        int idx = cams.get(Math.max(0, camCombo.getSelectionIndex())).index;
        boolean ok = CameraService.get().open(idx);
        lastBi = null;
        // 캔버스 없어도 장치를 열어 두고 latest() keepalive로 프레임을 돌린다
        // (RoiNcc 등 다른 화면이 CameraService 피드를 이어 받을 수 있게)
        startPoll();
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setPlaceholder(ok ? "(신호 대기…)" : "웹캠 열기 실패");
            if (!ok) {
                canvas.setFrame(null);
            }
        }
    }

    public boolean setFocusToCombo() {
        if (camCombo != null && !camCombo.isDisposed()) {
            return camCombo.setFocus();
        }
        return false;
    }

    /**
     * CameraService 프레임 폴링.
     * 캔버스가 있으면 표시, 없어도 {@link CameraService#latest()}를 호출해
     * 캡처 스레드/캐시를 유지한다 (다른 View·데모 탭이 이어 받기 위함).
     */
    private void startPoll() {
        if (polling) {
            return;
        }
        polling = true;
        display.timerExec(POLL_MS, new Runnable() {
            public void run() {
                if (!polling || isDisposed()) {
                    polling = false;
                    return;
                }
                display.timerExec(POLL_MS, this);
                BufferedImage bi = CameraService.get().latest();
                if (canvas == null || canvas.isDisposed()) {
                    return;
                }
                if (bi == lastBi) {
                    return;
                }
                lastBi = bi;
                canvas.setFrame(bi);
            }
        });
    }
}
