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
 *
 * <p>크기는 파라미터로 받지 않는다. 바 전체 폭은 클라이언트가
 * {@code setLayoutData()} 로 정하면 되고, 새로고침 버튼 폭은 {@link Cfg#refreshText}
 * 길이를 따라간다. 자세한 방법은 {@code CameraSelectBar} 주석 참고.
 */
public class MicSelectBar extends Composite {

    /** 클라이언트 주입 문구 - 기본값 유지, 필요한 것만 덮어쓴다. */
    public static final class Cfg {
        public String refreshText = "새로고침";
        /** 콤보 툴팁. null 이면 툴팁 없음. */
        public String comboTooltip;
        /** 새로고침 버튼 툴팁. null 이면 툴팁 없음. */
        public String refreshTooltip;
    }

    private final ApxSettings settings = ApxSettings.get();
    private final Combo micCombo;
    private List<AudioCapture.Device> micDevices;

    public MicSelectBar(Composite parent) {
        this(parent, new Cfg());
    }

    public MicSelectBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        Cfg c = (cfg != null) ? cfg : new Cfg();
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        micCombo = new Combo(this, SWT.READ_ONLY | SWT.DROP_DOWN);
        micCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (c.comboTooltip != null) {
            micCombo.setToolTipText(c.comboTooltip);
        }
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
        refresh.setText(c.refreshText);
        if (c.refreshTooltip != null) {
            refresh.setToolTipText(c.refreshTooltip);
        }
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
