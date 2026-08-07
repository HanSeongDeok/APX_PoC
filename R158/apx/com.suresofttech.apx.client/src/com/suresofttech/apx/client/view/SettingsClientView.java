package com.suresofttech.apx.client.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/**
 * 설정 View — {@link SettingsForm} 조립.
 */
public class SettingsClientView extends ViewPart {

    private SettingsForm form;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        form = new SettingsForm(parent);
        form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    @Override
    public void setFocus() {
        if (form != null && !form.isDisposed() && form.getCameraSelect() != null) {
            form.getCameraSelect().setFocusToCombo();
        }
    }
}
