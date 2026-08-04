package com.suresofttech.apx.ui.view;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.MicMeter;
import com.suresofttech.apx.core.audio.Tone;
import com.suresofttech.apx.core.audio.TonePlayer;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.ui.widget.CameraCanvas;

/**
 * ① 설정 View — 파이썬 apx_app/ui/settings_tab.py 이식.
 * 웹캠(선택·미리보기, webcam-capture)·마이크(선택·레벨·경고음 테스트) 모두 순수 자바.
 * 카메라는 공유 {@link CameraService}로 한 대만 열고 각 View가 최신 프레임을 폴링한다.
 */
public class SettingsView extends ViewPart {

    private Display display;
    private final MicMeter meter = new MicMeter();
    private final TonePlayer tonePlayer = new TonePlayer();

    private Combo camCombo;
    private List<CameraService.Cam> cams;
    private CameraCanvas preview;

    private Combo micCombo;
    private List<AudioCapture.Device> micDevices;
    private ProgressBar levelBar;
    private Button micTestBtn;
    private Label status;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        Composite top = new Composite(parent, SWT.NONE);
        top.setLayout(new GridLayout(2, true));
        top.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        buildWebcamGroup(top);
        buildMicGroup(top);

        status = new Label(parent, SWT.NONE);
        status.setText("웹캠·마이크를 선택해 확인하세요. (기준 이미지는 각 탭에서 설정)");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        refreshMics();
        startLevelPoll();
        refreshCameras();
        startCameraPoll();
    }

    // ---- 웹캠 ----
    private void buildWebcamGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("웹캠");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        camCombo = new Combo(g, SWT.READ_ONLY | SWT.DROP_DOWN);
        camCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        camCombo.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                openSelectedCamera();
            }
        });
        Button refresh = new Button(g, SWT.PUSH);
        refresh.setText("새로고침");
        refresh.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                refreshCameras();
            }
        });

        preview = new CameraCanvas(g);
        preview.setPlaceholder("웹캠을 선택하세요");
        GridData pd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        pd.minimumHeight = 280;
        preview.setLayoutData(pd);
    }

    private void refreshCameras() {
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
            preview.setPlaceholder("연결된 웹캠 없음");
            preview.setFrame(null);
        }
    }

    private void openSelectedCamera() {
        if (cams == null || cams.isEmpty()) {
            return;
        }
        int idx = cams.get(Math.max(0, camCombo.getSelectionIndex())).index;
        boolean ok = CameraService.get().open(idx);
        preview.setPlaceholder(ok ? "(신호 대기…)" : "웹캠 열기 실패");
    }

    private void startCameraPoll() {
        display.timerExec(60, new Runnable() {
            public void run() {
                if (preview == null || preview.isDisposed()) {
                    return;
                }
                preview.setFrame(CameraService.get().latest());
                display.timerExec(60, this);
            }
        });
    }

    // ---- 마이크 ----
    private void buildMicGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("마이크");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, true));

        micCombo = new Combo(g, SWT.READ_ONLY | SWT.DROP_DOWN);
        micCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button refresh = new Button(g, SWT.PUSH);
        refresh.setText("새로고침");
        refresh.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                refreshMics();
            }
        });

        Label lv = new Label(g, SWT.NONE);
        lv.setText("입력 레벨");
        levelBar = new ProgressBar(g, SWT.HORIZONTAL);
        levelBar.setMinimum(0);
        levelBar.setMaximum(100);
        levelBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        micTestBtn = new Button(g, SWT.TOGGLE);
        micTestBtn.setText("마이크 테스트 시작");
        micTestBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        micTestBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                onMicTest(micTestBtn.getSelection());
            }
        });

        Button toneTest = new Button(g, SWT.PUSH);
        toneTest.setText("경고음 테스트");
        toneTest.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        toneTest.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                tonePlayer.play(Tone.proximityWarning(44100), 44100);
            }
        });
    }

    // ---- 동작 ----
    private void refreshMics() {
        micDevices = AudioCapture.listInputDevices();
        micCombo.removeAll();
        for (AudioCapture.Device d : micDevices) {
            micCombo.add(d.name);
        }
        if (!micDevices.isEmpty()) {
            micCombo.select(0);
            status.setText("마이크 " + micDevices.size() + "개 발견");
        } else {
            status.setText("연결된 마이크 없음");
        }
    }

    private AudioCapture.Device selectedMic() {
        if (micDevices == null || micDevices.isEmpty()) {
            return null;
        }
        return micDevices.get(Math.max(0, micCombo.getSelectionIndex()));
    }

    private void onMicTest(boolean on) {
        if (on) {
            AudioCapture.Device dev = selectedMic();
            if (dev == null || !meter.start(dev.info)) {
                micTestBtn.setSelection(false);
                status.setText("마이크 열기 실패");
                return;
            }
            micTestBtn.setText("마이크 테스트 정지");
        } else {
            meter.stop();
            micTestBtn.setText("마이크 테스트 시작");
        }
    }

    private void startLevelPoll() {
        display.timerExec(60, new Runnable() {
            public void run() {
                if (levelBar == null || levelBar.isDisposed()) {
                    return;
                }
                int v = micTestBtn.getSelection()
                        ? (int) Math.min(100, meter.getLevel() * 400) : 0;   // RMS→표시(파이썬과 동일 스케일)
                levelBar.setSelection(v);
                display.timerExec(60, this);
            }
        });
    }

    @Override
    public void dispose() {
        meter.stop();
        tonePlayer.stop();
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (micCombo != null && !micCombo.isDisposed()) {
            micCombo.setFocus();
        }
    }
}
