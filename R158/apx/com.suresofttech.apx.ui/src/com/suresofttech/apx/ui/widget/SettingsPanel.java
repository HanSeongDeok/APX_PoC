package com.suresofttech.apx.ui.widget;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.ui.widget.settings.audio.AudioSettingsPanel;
import com.suresofttech.apx.ui.widget.settings.vision.VisionSettingsPanel;

/**
 * 설정 탭 완제품 패널 — {@link VisionSettingsPanel} + {@link AudioSettingsPanel} 조합.
 * <pre>new SettingsPanel(parent);</pre>
 * 도메인만 필요하면 각 SettingsPanel을 직접 붙인다.
 * 값은 {@link ApxSettings}에 저장된다.
 */
public class SettingsPanel extends Composite {

    private final ApxSettings settings = ApxSettings.get();
    private final VisionSettingsPanel visionPanel;

    public SettingsPanel(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new GridLayout(2, true));
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        visionPanel = new VisionSettingsPanel(this);
        new AudioSettingsPanel(this);
    }

    /** 클라이언트가 설정 값을 읽을 때 사용. */
    public ApxSettings getSettings() {
        return settings;
    }

    public VisionSettingsPanel getVisionPanel() {
        return visionPanel;
    }

    @Override
    public boolean setFocus() {
        if (visionPanel != null && !visionPanel.isDisposed()) {
            return visionPanel.setFocus();
        }
        return super.setFocus();
    }
}
