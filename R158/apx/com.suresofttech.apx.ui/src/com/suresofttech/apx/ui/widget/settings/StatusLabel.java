package com.suresofttech.apx.ui.widget.settings;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * 설정 상태 한 줄 - 이솝이 원하는 그룹에 배치.
 */
public class StatusLabel extends Composite {

    private final Label label;

    public StatusLabel(Composite parent, String initial) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        label = new Label(this, SWT.WRAP);
        label.setText(initial == null ? "" : initial);
        label.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.verticalIndent = 6;
        label.setLayoutData(gd);
    }

    public void setMessage(String msg) {
        if (label != null && !label.isDisposed()) {
            label.setText(msg == null ? "" : msg);
        }
    }
}
