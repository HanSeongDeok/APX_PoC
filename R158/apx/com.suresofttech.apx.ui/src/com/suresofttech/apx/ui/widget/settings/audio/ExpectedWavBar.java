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

/**
 * 기대 경고음 .wav 경로 + 파일 선택 - {@link ApxSettings}.
 * 라벨 / 버튼명은 {@link Cfg} 로 클라이언트가 주입한다.
 */
public class ExpectedWavBar extends Composite {

    /** 클라이언트 주입 라벨 - 기본값 유지, 필요한 것만 덮어쓴다. */
    public static final class Cfg {
        public String titleText = "기대 경고음 (.wav)";
        public String pickText = "파일...";
        public String placeholderText = "wav 파일을 선택하세요";
    }

    private final ApxSettings settings = ApxSettings.get();
    private final Cfg cfg;
    private final Text wavPathText;
    private final ApxSettings.Listener settingsListener;

    public ExpectedWavBar(Composite parent) {
        this(parent, new Cfg());
    }

    public ExpectedWavBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        this.cfg = (cfg != null) ? cfg : new Cfg();
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label title = new Label(this, SWT.NONE);
        title.setText(this.cfg.titleText);
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        wavPathText = new Text(this, SWT.BORDER | SWT.READ_ONLY | SWT.SINGLE);
        wavPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button pick = new Button(this, SWT.PUSH);
        pick.setText(this.cfg.pickText);
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


    private void refreshPathText() {
        if (wavPathText == null || wavPathText.isDisposed()) {
            return;
        }
        String p = settings.getExpectedWavPath();
        wavPathText.setText(p == null ? cfg.placeholderText : new File(p).getName());
    }

    private void msg(String m) {
        // 상태 표시 제거(미니멀)
    }
}
