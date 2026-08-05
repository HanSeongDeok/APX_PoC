package com.suresofttech.apx.client.view;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.ui.widget.SettingsPanel;

/**
 * 이솝 RCP View — 기본은 통짜 {@link SettingsPanel}
 * (= {@code VisionSettingsPanel} + {@code AudioSettingsPanel}).
 *
 * <p>도메인/최소 단위 조립은 {@code com.suresofttech.apx.ui/docs/SETTINGS_COMPONENTS.md}.
 */
public class SettingsClientView extends ViewPart {

    private SettingsPanel panel;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        panel = new SettingsPanel(parent);
    }

    public ApxSettings getSettings() {
        return panel != null ? panel.getSettings() : ApxSettings.get();
    }

    @Override
    public void setFocus() {
        if (panel != null && !panel.isDisposed()) {
            panel.setFocus();
        }
    }
}
