package com.suresofttech.apx.client.view;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * Kickoff에서 여는 설정 다이얼로그. 확인 시 {@link ApxSettings} 기준으로 모니터 View 갱신.
 */
public class SettingsDialog extends Dialog {

    public SettingsDialog(Shell parentShell) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("측정 설정");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        SettingsForm form = new SettingsForm(area);
        form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "확인", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "취소", false);
    }

    @Override
    protected Point getInitialSize() {
        return new Point(1100, 720);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }
}
