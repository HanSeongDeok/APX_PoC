package com.suresofttech.apx.client.view;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Kickoff에서 여는 설정 다이얼로그. 확인 시 {@link ApxSettings} 기준으로 모니터 View 갱신.
 */
public class SettingsDialog extends Dialog {

    private static SettingsDialog openDialog;

    private SettingsForm form;
    private Runnable applyCallback;

    private SettingsDialog(Shell parentShell) {
        super(parentShell);
        int style = getShellStyle()
                & ~(SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL);
        setShellStyle(style | SWT.SHELL_TRIM | SWT.RESIZE | SWT.MAX);
        setBlockOnOpen(false);
    }

    /** 설정 창을 하나만 비모달로 열고, 이미 열려 있으면 앞으로 가져온다. */
    public static void openNonModal(Shell parentShell, Runnable applyCallback) {
        if (openDialog != null && openDialog.getShell() != null
                && !openDialog.getShell().isDisposed()) {
            openDialog.applyCallback = applyCallback;
            openDialog.getShell().setActive();
            return;
        }
        SettingsDialog dialog = new SettingsDialog(parentShell);
        dialog.applyCallback = applyCallback;
        openDialog = dialog;
        dialog.open();
    }

    /** 측정 중에는 공유 카메라와 마이크 설정을 바꾸지 못하게 한다. */
    public static void setEditingEnabled(boolean enabled) {
        if (openDialog == null || openDialog.form == null || openDialog.form.isDisposed()) {
            return;
        }
        openDialog.form.setEnabled(enabled);
    }

    /** 소유 View가 닫힐 때 남은 callback과 설정 창을 함께 정리한다. */
    public static void closeOpenDialog() {
        if (openDialog == null) {
            return;
        }
        openDialog.applyCallback = null;
        Shell shell = openDialog.getShell();
        if (shell != null && !shell.isDisposed()) {
            shell.close();
        }
        openDialog = null;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("측정 설정");
        newShell.addListener(SWT.Dispose, new Listener() {
            public void handleEvent(Event event) {
                if (openDialog == SettingsDialog.this) {
                    openDialog = null;
                }
            }
        });
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        form = new SettingsForm(area);
        form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "확인", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "취소", false);
    }

    @Override
    protected void okPressed() {
        Runnable callback = applyCallback;
        super.okPressed();
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    protected Point getInitialSize() {
        return new Point(1400, 900);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }
}
