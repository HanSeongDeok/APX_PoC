import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.suresofttech.apx.client.view.SettingsForm;

/**
 * 클라이언트가 Kickoff에서 설정을 Dialog로 여는 예시.
 *
 * <p>실제 클라 경로:
 * <ul>
 *   <li>{@code KickoffView.openSettings()} → {@code SettingsDialog} (JFace)</li>
 *   <li>{@code SettingsDialog} → {@link SettingsForm} 조립</li>
 *   <li>확인(OK) 시 모니터 {@code applyFromSettings()}</li>
 * </ul>
 *
 * <p>경로: {@code apx-settings-demo/examples/SettingsDialogExample.java}
 * <p>※ 데모는 JFace 없이 {@link #openAsShell}로 동일 폼을 띄운다.
 */
public final class SettingsDialogExample {

    private SettingsDialogExample() {
    }

    /**
     * KickoffView.openSettings 골자 (Eclipse 클라 / JFace).
     *
     * <pre>
     * SettingsDialog dlg = new SettingsDialog(getSite().getShell());
     * if (dlg.open() == Window.OK) {
     *     vision.applyFromSettings();
     *     rear.applyFromSettings();
     * }
     * </pre>
     */
    public static String kickoffSnippet() {
        return ""
                + "SettingsDialog dlg = new SettingsDialog(getSite().getShell());\n"
                + "if (dlg.open() == Window.OK) {\n"
                + "    vision.applyFromSettings();\n"
                + "    rear.applyFromSettings();\n"
                + "}\n";
    }

    /**
     * 데모용 - JFace 없이 {@link SettingsForm}을 modal Shell에 띄운다.
     * 값은 위젯이 {@code ApxSettings}에 즉시 쓴다. 확인/취소는 창만 닫는다.
     *
     * @return SWT.OK 또는 SWT.CANCEL
     */
    public static int openAsShell(Shell parent) {
        final Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        shell.setText("측정 설정");
        shell.setSize(1100, 720);
        shell.setLayout(new GridLayout(1, false));

        SettingsForm form = new SettingsForm(shell);
        form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label hint = new Label(shell, SWT.WRAP);
        hint.setText("확인 시 ApxSettings 값이 유지됩니다(위젯이 이미 즉시 반영). "
                + "클라 Kickoff는 OK 후 모니터 applyFromSettings()를 호출합니다.");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite bar = new Composite(shell, SWT.NONE);
        bar.setLayout(new GridLayout(2, true));
        bar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        final int[] result = new int[] { SWT.CANCEL };
        Button ok = new Button(bar, SWT.PUSH);
        ok.setText("확인");
        ok.setLayoutData(new GridData(100, SWT.DEFAULT));
        ok.addListener(SWT.Selection, e -> {
            result[0] = SWT.OK;
            shell.close();
        });
        Button cancel = new Button(bar, SWT.PUSH);
        cancel.setText("취소");
        cancel.setLayoutData(new GridData(100, SWT.DEFAULT));
        cancel.addListener(SWT.Selection, e -> {
            result[0] = SWT.CANCEL;
            shell.close();
        });

        shell.open();
        Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        return result[0];
    }
}
