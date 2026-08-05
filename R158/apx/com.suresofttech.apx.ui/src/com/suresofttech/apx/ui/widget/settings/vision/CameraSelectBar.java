package com.suresofttech.apx.ui.widget.settings.vision;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.ui.widget.settings.StatusSink;

/**
 * 웹캠 콤보 + 새로고침 — {@link CameraService} 사용.
 */
public class CameraSelectBar extends Composite {

    private final Combo camCombo;
    private List<CameraService.Cam> cams;
    private WebcamRoiPane roiPane;
    private StatusSink status;

    public CameraSelectBar(Composite parent) {
        super(parent, SWT.NONE);
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
    }

    public void setRoiPane(WebcamRoiPane pane) {
        this.roiPane = pane;
    }

    public void setStatusSink(StatusSink sink) {
        this.status = sink;
    }

    public void refreshCameras() {
        cams = CameraService.get().list();
        camCombo.removeAll();
        for (CameraService.Cam c : cams) {
            camCombo.add(c.name);
        }
        if (!cams.isEmpty()) {
            int sel = CameraService.get().currentIndex();
            if (sel < 0 || sel >= cams.size()) {
                sel = 0;
            }
            camCombo.select(sel);
            openSelectedCamera();
        } else {
            if (roiPane != null) {
                roiPane.setPlaceholder("연결된 웹캠 없음");
                roiPane.clearFrame();
            }
            msg("연결된 웹캠 없음");
        }
    }

    public void openSelectedCamera() {
        if (cams == null || cams.isEmpty()) {
            return;
        }
        int idx = cams.get(Math.max(0, camCombo.getSelectionIndex())).index;
        boolean ok = CameraService.get().open(idx);
        if (roiPane != null) {
            roiPane.setPlaceholder(ok ? "(신호 대기…)" : "웹캠 열기 실패");
        }
        msg(ok ? "웹캠·기준이미지를 설정하세요." : "웹캠 열기 실패");
    }

    public boolean setFocusToCombo() {
        if (camCombo != null && !camCombo.isDisposed()) {
            return camCombo.setFocus();
        }
        return false;
    }

    private void msg(String m) {
        if (status != null) {
            status.setMessage(m);
        }
    }
}
