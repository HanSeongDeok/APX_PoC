package com.suresofttech.apx.ui.widget.settings.audio;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 마이크 콤보 + 새로고침 (장치 선택 전용). 입력 레벨 / 테스트는 {@link MicTestBar}.
 */
public class MicSelectBar extends Composite {

    private final ApxSettings settings = ApxSettings.get();
    private final Combo micCombo;
    private List<AudioCapture.Device> micDevices;

    public MicSelectBar(Composite parent) {
        super(parent, SWT.NONE);
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

    private void msg(String m) {
        // 상태 표시 제거(미니멀)
    }
}
