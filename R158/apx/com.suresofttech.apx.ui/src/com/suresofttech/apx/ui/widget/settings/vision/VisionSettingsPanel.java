package com.suresofttech.apx.ui.widget.settings.vision;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;

import com.suresofttech.apx.ui.widget.settings.StatusLabel;
import com.suresofttech.apx.ui.widget.settings.StatusSink;

/**
 * 비전 설정 조립 패널 — {@link CameraSelectBar}·{@link WebcamRoiPane}·
 * {@link ReferenceImageBar}·{@link VisionThresholdBar} 조합.
 * <pre>new VisionSettingsPanel(parent);</pre>
 */
public class VisionSettingsPanel extends Composite {

    private final CameraSelectBar cameraSelect;

    public VisionSettingsPanel(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new GridLayout(1, false));
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group webcam = new Group(this, SWT.NONE);
        webcam.setText("웹캠");
        webcam.setLayout(new GridLayout(1, false));
        webcam.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        cameraSelect = new CameraSelectBar(webcam);
        cameraSelect.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        WebcamRoiPane roiPane = new WebcamRoiPane(webcam);
        roiPane.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        cameraSelect.setRoiPane(roiPane);

        final StatusLabel visionStatus = new StatusLabel(webcam, "웹캠·기준이미지를 설정하세요.");
        visionStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        StatusSink visionSink = new StatusSink() {
            public void setMessage(String msg) {
                visionStatus.setMessage(msg);
            }
        };
        cameraSelect.setStatusSink(visionSink);
        roiPane.setStatusSink(visionSink);

        Composite refBlock = new Composite(this, SWT.NONE);
        refBlock.setLayout(new GridLayout(1, false));
        refBlock.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        ReferenceImageBar refBar = new ReferenceImageBar(refBlock);
        refBar.setStatusSink(visionSink);
        refBar.ensureDefaultRefIfMissing();

        VisionThresholdBar visionThr = new VisionThresholdBar(refBlock);
        visionThr.setRoiPane(roiPane);

        cameraSelect.refreshCameras();
    }

    public CameraSelectBar getCameraSelect() {
        return cameraSelect;
    }

    @Override
    public boolean setFocus() {
        if (cameraSelect != null && !cameraSelect.isDisposed()) {
            return cameraSelect.setFocusToCombo();
        }
        return super.setFocus();
    }
}
