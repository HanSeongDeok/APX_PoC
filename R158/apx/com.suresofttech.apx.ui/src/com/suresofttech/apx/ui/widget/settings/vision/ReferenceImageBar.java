package com.suresofttech.apx.ui.widget.settings.vision;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 기준 이미지 사용 체크 + 경로 + 파일 선택 — {@link ApxSettings} 연동.
 */
public class ReferenceImageBar extends Composite {

    private final ApxSettings settings = ApxSettings.get();
    private final Button useRefChk;
    private final Text refPathText;
    private final Button refPickBtn;
    private final ApxSettings.Listener settingsListener;

    public ReferenceImageBar(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        useRefChk = new Button(this, SWT.CHECK);
        useRefChk.setText("기준 이미지 사용");
        useRefChk.setSelection(settings.isUseReferenceImage());
        useRefChk.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        useRefChk.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setUseReferenceImage(useRefChk.getSelection());
                applyUseRefUi();
                msg(useRefChk.getSelection()
                        ? "기준 이미지 모드 ON"
                        : "기준 이미지 모드 OFF  (드래그로 ROI 지정)");
            }
        });

        Label title = new Label(this, SWT.NONE);
        title.setText("기준 이미지 (R 체결 정면)");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label desc = new Label(this, SWT.WRAP);
        desc.setText("비전/후방 탭에서 비교할 기준 이미지");
        desc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        desc.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        refPathText = new Text(this, SWT.BORDER | SWT.READ_ONLY | SWT.SINGLE);
        refPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        refPickBtn = new Button(this, SWT.PUSH);
        refPickBtn.setText("파일...");
        refPickBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                FileDialog dlg = new FileDialog(getShell(), SWT.OPEN);
                dlg.setFilterExtensions(new String[] { "*.png;*.jpg;*.jpeg;*.bmp" });
                dlg.setFilterNames(new String[] { "이미지" });
                String p = dlg.open();
                if (p != null) {
                    settings.setVisionRefPath(p);
                    refreshPathText();
                    msg("기준 이미지: " + new File(p).getName());
                }
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
                            useRefChk.setSelection(settings.isUseReferenceImage());
                            applyUseRefUi();
                            refreshPathText();
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

        applyUseRefUi();
        refreshPathText();
    }


    public void ensureDefaultRefIfMissing() {
        if (settings.getVisionRefPath() == null) {
            settings.setVisionRefPath("png 파일을 선택하세요");
            refreshPathText();
        }
    }

    private void refreshPathText() {
        if (refPathText == null || refPathText.isDisposed()) {
            return;
        }
        String p = settings.getVisionRefPath();
        refPathText.setText(p == null ? "" : new File(p).getName());
    }

    private void applyUseRefUi() {
        boolean on = settings.isUseReferenceImage();
        if (refPickBtn != null && !refPickBtn.isDisposed()) {
            refPickBtn.setEnabled(on);
        }
        if (refPathText != null && !refPathText.isDisposed()) {
            refPathText.setEnabled(on);
        }
    }

    private void msg(String m) {
        // 상태 표시 제거(미니멀)
    }
}
