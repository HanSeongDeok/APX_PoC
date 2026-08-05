package com.suresofttech.apx.ui.widget.settings.audio;

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
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.MicMeter;
import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 마이크 콤보 + 새로고침 + 입력 레벨 + 테스트 — {@link MicMeter}.
 */
public class MicSelectBar extends Composite implements MicDeviceProvider {

    private final Display display;
    private final ApxSettings settings = ApxSettings.get();
    private final MicMeter meter = new MicMeter();
    private final Combo micCombo;
    private final ProgressBar levelBar;
    private final Button micTestBtn;
    private List<AudioCapture.Device> micDevices;
    private Runnable beforeTestStart;
    private boolean levelPolling;

    public MicSelectBar(Composite parent) {
        super(parent, SWT.NONE);
        display = getDisplay();
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        micCombo = new Combo(this, SWT.READ_ONLY | SWT.DROP_DOWN);
        micCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        micCombo.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                AudioCapture.Device d = selectedDevice();
                if (d != null) {
                    settings.setMicName(d.name);
                    msg("마이크 선택: " + d.name);
                }
            }
        });
        Button refresh = new Button(this, SWT.PUSH);
        refresh.setText("새로고침");
        refresh.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                refreshMics();
            }
        });

        Composite levelRow = new Composite(this, SWT.NONE);
        GridLayout levelGl = new GridLayout(2, false);
        levelGl.marginWidth = 0;
        levelGl.marginHeight = 0;
        levelGl.horizontalSpacing = 8;
        levelRow.setLayout(levelGl);
        levelRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Label lv = new Label(levelRow, SWT.NONE);
        lv.setText("입력 레벨");
        levelBar = new ProgressBar(levelRow, SWT.HORIZONTAL | SWT.SMOOTH);
        levelBar.setMinimum(0);
        levelBar.setMaximum(100);
        levelBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        micTestBtn = new Button(this, SWT.TOGGLE);
        micTestBtn.setText("마이크 테스트 시작");
        micTestBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        micTestBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                onMicTest(micTestBtn.getSelection());
            }
        });

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                levelPolling = false;
                meter.stop();
            }
        });
        startLevelPoll();
    }


    public void setBeforeTestStart(Runnable r) {
        this.beforeTestStart = r;
    }

    /** 측정 측에서 호출 — 테스트 중이면 정지. */
    public void stopTest() {
        if (micTestBtn != null && !micTestBtn.isDisposed() && micTestBtn.getSelection()) {
            micTestBtn.setSelection(false);
            onMicTest(false);
        }
    }

    public void refreshMics() {
        micDevices = AudioCapture.listInputDevices();
        micCombo.removeAll();
        for (AudioCapture.Device d : micDevices) {
            micCombo.add(d.name);
        }
        if (!micDevices.isEmpty()) {
            int sel = 0;
            String want = settings.getMicName();
            if (want != null) {
                for (int i = 0; i < micDevices.size(); i++) {
                    if (want.equals(micDevices.get(i).name)) {
                        sel = i;
                        break;
                    }
                }
            }
            micCombo.select(sel);
            settings.setMicName(micDevices.get(sel).name);
        } else {
            msg("연결된 마이크 없음");
        }
    }

    public AudioCapture.Device selectedDevice() {
        if (micDevices == null || micDevices.isEmpty()) {
            return null;
        }
        return micDevices.get(Math.max(0, micCombo.getSelectionIndex()));
    }

    private void onMicTest(boolean on) {
        if (on) {
            if (beforeTestStart != null) {
                beforeTestStart.run();
            }
            AudioCapture.Device dev = selectedDevice();
            if (dev == null || !meter.start(dev.info)) {
                micTestBtn.setSelection(false);
                msg("마이크 열기 실패");
                return;
            }
            settings.setMicName(dev.name);
            micTestBtn.setText("마이크 테스트 정지");
        } else {
            meter.stop();
            micTestBtn.setText("마이크 테스트 시작");
        }
    }

    private void startLevelPoll() {
        levelPolling = true;
        display.timerExec(60, new Runnable() {
            public void run() {
                if (!levelPolling || levelBar == null || levelBar.isDisposed()) {
                    return;
                }
                int v = micTestBtn.getSelection()
                        ? (int) Math.min(100, meter.getLevel() * 400) : 0;
                levelBar.setSelection(v);
                if (levelPolling && !isDisposed()) {
                    display.timerExec(60, this);
                }
            }
        });
    }

    private void msg(String m) {
        // 상태 표시 제거(미니멀)
    }
}
