package com.suresofttech.apx.ui.widget.settings.audio;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 주파수 / 파형 임계 ± - {@link ApxSettings#setAudioThresholds}.
 * 기본값 / step / 라벨은 {@link Cfg} 로 클라이언트가 주입한다.
 */
public class AudioThresholdBar extends Composite {

    /** 클라이언트 주입 파라미터 - 기본값 유지, 필요한 것만 덮어쓴다.
     *  주파수 / 파형 임계는 <b>동일 값</b>으로 관리(단일 임계 / 단일 step). */
    public static final class Cfg {
        public double defaultThr = 0.90;   // 주파수 / 파형 공통 임계 기본값
        public double step = 0.05;         // 주파수 / 파형 공통 ± 증감폭
        public String descText = "음향 탭 측정 시 비교 기준으로 사용";
        public String minusText = "임계 -";
        public String plusText = "임계 +";
    }

    private final ApxSettings settings = ApxSettings.get();
    private final Cfg cfg;
    private final Label thrLabel;
    private final ApxSettings.Listener settingsListener;

    public AudioThresholdBar(Composite parent) {
        this(parent, new Cfg());
    }

    public AudioThresholdBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        this.cfg = (cfg != null) ? cfg : new Cfg();
        settings.setAudioThresholds(this.cfg.defaultThr, this.cfg.defaultThr);   // 주파수 / 파형 동일 기본값 시드
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label desc = new Label(this, SWT.WRAP);
        desc.setText(this.cfg.descText);
        desc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        desc.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        thrLabel = new Label(this, SWT.NONE);
        thrLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite thrRow = new Composite(this, SWT.NONE);
        thrRow.setLayout(new GridLayout(2, true));
        thrRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button minus = new Button(thrRow, SWT.PUSH);
        minus.setText(this.cfg.minusText);
        minus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        minus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setAudioThresholds(
                        settings.getAudioFreqThr() - cfg.step,
                        settings.getAudioWaveThr() - cfg.step);
                updateLabel();
            }
        });
        Button plus = new Button(thrRow, SWT.PUSH);
        plus.setText(this.cfg.plusText);
        plus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        plus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setAudioThresholds(
                        settings.getAudioFreqThr() + cfg.step,
                        settings.getAudioWaveThr() + cfg.step);
                updateLabel();
            }
        });

        settingsListener = new ApxSettings.Listener() {
            public void onSettingsChanged(ApxSettings s) {
                if (isDisposed()) {
                    return;
                }
                getDisplay().asyncExec(new Runnable() {
                    public void run() {
                        if (!isDisposed()) {
                            updateLabel();
                        }
                    }
                });
            }
        };
        settings.addListener(settingsListener);
        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                settings.removeListener(settingsListener);
            }
        });
        updateLabel();
    }

    private void updateLabel() {
        if (thrLabel == null || thrLabel.isDisposed()) {
            return;
        }
        double avg = (settings.getAudioFreqThr() + settings.getAudioWaveThr()) / 2.0;
        thrLabel.setText(String.format("주파수 및 파형 임계치 %.0f%%", avg * 100));
    }
}
