package com.suresofttech.apx.client.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.rear.RearGrid;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.VisionChannel;
import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.audio.AudioMeasureBar;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;
import com.suresofttech.apx.ui.widget.settings.audio.AudioThresholdBar;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedTonePlayBar;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedWavBar;
import com.suresofttech.apx.ui.widget.settings.audio.MicSelectBar;
import com.suresofttech.apx.ui.widget.settings.audio.MicTestBar;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridSizeBar;
import com.suresofttech.apx.ui.widget.settings.rear.RearLegendBar;
import com.suresofttech.apx.ui.widget.settings.vision.CameraSelectBar;
import com.suresofttech.apx.ui.widget.settings.vision.RoiNcc;
import com.suresofttech.apx.ui.widget.settings.vision.RoiStyles;
import com.suresofttech.apx.ui.widget.settings.vision.VisionJudgeBar;
import com.suresofttech.apx.ui.widget.settings.vision.VisionThresholdBar;

/**
 * 이솝 RCP 설정 View (커스텀판) - Cfg/Style 파라미터 주입 조립 예시.
 */
public class SettingsClientView2 extends ViewPart {

    private CameraSelectBar cameraSelect;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(4, true));
        buildVisionColumn(parent);
        buildAudioColumn(parent);
        buildRearColumn(parent);
    }

    private void buildVisionColumn(Composite parent) {
        Composite col = new Composite(parent, SWT.NONE);
        col.setLayout(new GridLayout(2, true));
        col.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        RoiNcc.Style roiStyle = new RoiNcc.Style();
        roiStyle.hit = new RGB(0, 200, 0);
        roiStyle.miss = new RGB(220, 60, 60);
        roiStyle.drag = new RGB(0, 160, 255);
        roiStyle.roiLineWidth = 3;
        roiStyle.dragThickness = 2;
        // 라이브 모니터도 같은 스타일로 그리도록 공용 기준에 심는다
        RoiStyles.set(roiStyle);

        cameraSelect = addVisionGroup(col, "클러스터 설정", VisionChannel.CLUSTER, roiStyle);
        addVisionGroup(col, "기어봉 설정", VisionChannel.GEAR, roiStyle);
    }

    private CameraSelectBar addVisionGroup(Composite col, String title,
        VisionChannel ch, RoiNcc.Style roiStyle) {
        Group webcam = new Group(col, SWT.NONE);
        webcam.setText(title);
        webcam.setLayout(new GridLayout(1, false));
        webcam.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        CameraSelectBar bar = new CameraSelectBar(webcam, CameraService.of(ch));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        CameraCanvas canvas = new CameraCanvas(webcam);
        canvas.setPlaceholder(title + " 웹캠을 선택하세요");
        GridData canvasGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        canvasGd.heightHint = 160;
        canvasGd.minimumHeight = 120;
        canvas.setLayoutData(canvasGd);
        bar.setCanvas(canvas);

        RoiNcc roiNcc = new RoiNcc(canvas, roiStyle, ch);

        VisionThresholdBar.Cfg vThr = new VisionThresholdBar.Cfg();
        vThr.defaultThr = 0.75;
        vThr.step = 0.05;
        vThr.minusText = "− 정밀도";
        vThr.plusText = "+ 정밀도";
        VisionThresholdBar visionThr = new VisionThresholdBar(webcam, vThr, ch);
        visionThr.setRoiNcc(roiNcc);

        new VisionJudgeBar(webcam, ch);
        bar.refreshCameras();
        return bar;
    }

    private void buildAudioColumn(Composite parent) {
        Composite col = new Composite(parent, SWT.NONE);
        col.setLayout(new GridLayout(1, false));
        col.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group mic = new Group(col, SWT.NONE);
        mic.setText("마이크 (커스텀)");
        mic.setLayout(new GridLayout(1, false));
        mic.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        MicSelectBar micBar = new MicSelectBar(mic);
        micBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        MicTestBar micTest = new MicTestBar(mic);

        Group expected = new Group(col, SWT.NONE);
        expected.setText("기대 음향 (커스텀)");
        expected.setLayout(new GridLayout(1, false));
        expected.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        ExpectedWavBar.Cfg wavCfg = new ExpectedWavBar.Cfg();
        wavCfg.titleText = "기대 경고음 파일 (.wav)";
        wavCfg.placeholderText = "경고음 .wav를 선택하세요";
        new ExpectedWavBar(expected, wavCfg);

        AudioMeasureBar.Cfg measCfg = new AudioMeasureBar.Cfg();
        measCfg.measureText = "측정 시작";
        measCfg.measuringText = "측정 중지";
        measCfg.resetText = "리셋";
        AudioMeasureBar measureBar = new AudioMeasureBar(expected, measCfg);

        ExpectedTonePlayBar.Cfg toneCfg = new ExpectedTonePlayBar.Cfg();
        toneCfg.playText = "기대음 듣기";
        toneCfg.playingText = "재생 정지";
        ExpectedTonePlayBar playBar = new ExpectedTonePlayBar(measureBar.getActionRow(), toneCfg);
        playBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        AudioScope scope = new AudioScope(expected, 5000.0);
        scope.setShowPitch(false);
        scope.setShowTrend(false);
        scope.setTickMs(1000);
        scope.setPassColor(0x2ecb5a);
        scope.setPassAlpha(90);
        scope.setWaveTitle("측정 파형 (커스텀)");
        GridData scopeGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        scopeGd.minimumHeight = 180;
        scope.setLayoutData(scopeGd);
        measureBar.setScope(scope);

        AudioThresholdBar.Cfg aThr = new AudioThresholdBar.Cfg();
        aThr.defaultThr = 0.90;
        aThr.step = 0.05;
        aThr.descText = "PASS 기준 임계 (커스텀)";
        aThr.minusText = "− 완화";
        aThr.plusText = "+ 엄격";
        new AudioThresholdBar(expected, aThr);


        micBar.refreshMics();
    }

    private void buildRearColumn(Composite parent) {
        Composite col = new Composite(parent, SWT.NONE);
        col.setLayout(new GridLayout(1, false));
        col.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group rear = new Group(col, SWT.NONE);
        rear.setText("후방 (커스텀 조립)");
        rear.setLayout(new GridLayout(1, false));
        rear.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        ApxSettings s = ApxSettings.get();
        RearGridSizeBar.Cfg sizeCfg = new RearGridSizeBar.Cfg();
        sizeCfg.presetText = "고정 크기";
        sizeCfg.customText = "직접 입력";
        sizeCfg.presets = new int[][] { { 4, 6 }, { 3, 4 }, { 5, 7 }, { 6, 10 } };   // 고정크기 목록 커스텀
        RearGridSizeBar sizeBar = new RearGridSizeBar(rear, sizeCfg);

        RearLegendBar.Cfg legendCfg = new RearLegendBar.Cfg();
        legendCfg.legendText = "상태 범례";
        RearLegendBar legendBar = new RearLegendBar(rear, legendCfg);

        RearGrid grid = new RearGrid(s.getRearCols(), s.getRearRows());
        grid.selectPoints(s.getRearSelectedPoints());

        RearGridCanvas canvas = new RearGridCanvas(rear, grid);
        GridData canvasGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        canvasGd.heightHint = 280;
        canvasGd.minimumHeight = 200;
        canvas.setLayoutData(canvasGd);
        canvas.loadDefaultCarImage();
        canvas.applyDefaultLegend(); // 모니터와 동일 범례
        sizeBar.setCanvas(canvas);
        legendBar.setCanvas(canvas);
    }

    public ApxSettings getSettings() {
        return ApxSettings.get();
    }

    @Override
    public void setFocus() {
        if (cameraSelect != null && !cameraSelect.isDisposed()) {
            cameraSelect.setFocusToCombo();
        }
    }
}
