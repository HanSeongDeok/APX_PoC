package com.suresofttech.apx.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.ui.widget.settings.audio.AudioThresholdBar;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedAudioMeasurePane;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedTonePlayBar;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedWavBar;
import com.suresofttech.apx.ui.widget.settings.audio.MicExclusive;
import com.suresofttech.apx.ui.widget.settings.audio.MicSelectBar;
import com.suresofttech.apx.ui.widget.settings.vision.CameraSelectBar;
import com.suresofttech.apx.ui.widget.settings.vision.ReferenceImageBar;
import com.suresofttech.apx.ui.widget.settings.vision.VisionThresholdBar;
import com.suresofttech.apx.ui.widget.settings.vision.WebcamRoiPane;

/**
 * ① 설정 View — <b>개별 단위 컴포넌트를 직접 조합</b>(통짜 패널·상태 라벨 미사용).
 */
public class SettingsView extends ViewPart {

    private CameraSelectBar cameraSelect;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(2, true));   // 좌: 비전 / 우: 음향
        buildVisionColumn(parent);
        buildAudioColumn(parent);
    }

    private void buildVisionColumn(Composite parent) {
        Composite col = new Composite(parent, SWT.NONE);
        col.setLayout(new GridLayout(1, false));
        col.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group webcam = new Group(col, SWT.NONE);
        webcam.setText("웹캠");
        webcam.setLayout(new GridLayout(1, false));
        webcam.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        cameraSelect = new CameraSelectBar(webcam);
        cameraSelect.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        WebcamRoiPane roiPane = new WebcamRoiPane(webcam);
        roiPane.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        cameraSelect.setRoiPane(roiPane);

        Composite refBlock = new Composite(col, SWT.NONE);
        refBlock.setLayout(new GridLayout(1, false));
        refBlock.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        ReferenceImageBar refBar = new ReferenceImageBar(refBlock);
        refBar.ensureDefaultRefIfMissing();

        VisionThresholdBar visionThr = new VisionThresholdBar(refBlock);
        visionThr.setRoiPane(roiPane);

        cameraSelect.refreshCameras();
    }

    private void buildAudioColumn(Composite parent) {
        Composite col = new Composite(parent, SWT.NONE);
        col.setLayout(new GridLayout(1, false));
        col.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group mic = new Group(col, SWT.NONE);
        mic.setText("마이크");
        mic.setLayout(new GridLayout(1, false));
        mic.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        MicSelectBar micBar = new MicSelectBar(mic);
        micBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Group expected = new Group(col, SWT.NONE);
        expected.setText("기대 음향");
        expected.setLayout(new GridLayout(1, false));
        expected.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        new ExpectedWavBar(expected);

        ExpectedAudioMeasurePane measurePane = new ExpectedAudioMeasurePane(expected);
        measurePane.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        ExpectedTonePlayBar playBar = new ExpectedTonePlayBar(measurePane.getActionRow());
        playBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new AudioThresholdBar(expected);

        new MicExclusive().bind(micBar, measurePane);

        micBar.refreshMics();
    }

    @Override
    public void setFocus() {
        if (cameraSelect != null && !cameraSelect.isDisposed()) {
            cameraSelect.setFocusToCombo();
        }
    }
}
