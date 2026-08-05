package com.suresofttech.apx.ui.widget.settings.audio;

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
import com.suresofttech.apx.ui.widget.settings.StatusSink;

/**
 * 기대 경고음 .wav 경로 + 파일 선택 — {@link ApxSettings}.
 */
public class ExpectedWavBar extends Composite {

    private final ApxSettings settings = ApxSettings.get();
    private final Text wavPathText;
    private final ApxSettings.Listener settingsListener;
    private StatusSink status;

    public ExpectedWavBar(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label title = new Label(this, SWT.NONE);
        title.setText("기대 경고음 (.wav)");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        wavPathText = new Text(this, SWT.BORDER | SWT.READ_ONLY | SWT.SINGLE);
        wavPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button pick = new Button(this, SWT.PUSH);
        pick.setText("파일...");
        pick.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                FileDialog dlg = new FileDialog(getShell(), SWT.OPEN);
                dlg.setFilterExtensions(new String[] { "*.wav" });
                dlg.setFilterNames(new String[] { "WAV (*.wav)" });
                String p = dlg.open();
                if (p != null) {
                    settings.setExpectedWavPath(p);
                    refreshPathText();
                    msg("기대 경고음 등록: " + new File(p).getName());
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
        refreshPathText();
    }

    public void setStatusSink(StatusSink sink) {
        this.status = sink;
    }

    private void refreshPathText() {
        if (wavPathText == null || wavPathText.isDisposed()) {
            return;
        }
        String p = settings.getExpectedWavPath();
        wavPathText.setText(p == null ? "wav 파일을 선택하세요" : new File(p).getName());
    }

    private void msg(String m) {
        if (status != null) {
            status.setMessage(m);
        }
    }
}
