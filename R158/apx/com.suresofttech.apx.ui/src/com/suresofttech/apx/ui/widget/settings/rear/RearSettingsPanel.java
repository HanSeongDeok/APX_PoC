package com.suresofttech.apx.ui.widget.settings.rear;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * 후방 설정 조립 패널 (예약) — 후방 설정 조각이 생기면 여기서 조합한다.
 * <pre>new RearSettingsPanel(parent);</pre>
 */
public class RearSettingsPanel extends Composite {

    public RearSettingsPanel(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new GridLayout(1, false));
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label placeholder = new Label(this, SWT.WRAP);
        placeholder.setText("후방 설정 컴포넌트 예약 영역");
        placeholder.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        placeholder.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }
}
