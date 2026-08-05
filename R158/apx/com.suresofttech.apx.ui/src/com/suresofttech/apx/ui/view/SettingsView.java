package com.suresofttech.apx.ui.view;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.ui.widget.SettingsPanel;

/**
 * ① 설정 View — {@link SettingsPanel} 래퍼.
 * 제품 RCP에서는 View로, 외부 클라이언트(JAR)에서는 Panel을 직접 붙인다.
 */
public class SettingsView extends ViewPart {

    private SettingsPanel panel;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        panel = new SettingsPanel(parent);
    }

    @Override
    public void setFocus() {
        if (panel != null && !panel.isDisposed()) {
            panel.setFocus();
        }
    }
}
