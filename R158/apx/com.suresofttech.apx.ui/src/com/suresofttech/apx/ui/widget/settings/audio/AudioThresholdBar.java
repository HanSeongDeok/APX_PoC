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
import com.suresofttech.apx.ui.widget.settings.SettingsUi;

/**
 * 주파수·파형 임계 ± — {@link ApxSettings#setAudioThresholds}.
 */
public class AudioThresholdBar extends Composite {

    private final ApxSettings settings = ApxSettings.get();
    private final Label thrLabel;
    private final ApxSettings.Listener settingsListener;

    public AudioThresholdBar(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label desc = new Label(this, SWT.WRAP);
        desc.setText("음향 탭 측정 시 비교 기준으로 사용");
        desc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        desc.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        thrLabel = new Label(this, SWT.NONE);
        thrLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite thrRow = new Composite(this, SWT.NONE);
        thrRow.setLayout(new GridLayout(2, true));
        thrRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button minus = new Button(thrRow, SWT.PUSH);
        minus.setText("임계 -");
        minus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        minus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setAudioThresholds(
                        settings.getAudioFreqThr() - SettingsUi.THR_STEP,
                        settings.getAudioWaveThr() - SettingsUi.THR_STEP);
                updateLabel();
            }
        });
        Button plus = new Button(thrRow, SWT.PUSH);
        plus.setText("임계 +");
        plus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        plus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setAudioThresholds(
                        settings.getAudioFreqThr() + SettingsUi.THR_STEP,
                        settings.getAudioWaveThr() + SettingsUi.THR_STEP);
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
