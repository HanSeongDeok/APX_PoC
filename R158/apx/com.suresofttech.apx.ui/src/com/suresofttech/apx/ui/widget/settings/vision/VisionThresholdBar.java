package com.suresofttech.apx.ui.widget.settings.vision;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.ui.widget.settings.SettingsUi;

/**
 * NCC 임계 ± + 매칭도 라벨 — {@link ApxSettings#setSimThr}.
 */
public class VisionThresholdBar extends Composite {

    private final ApxSettings settings = ApxSettings.get();
    private final Label matchLabel;
    private final ApxSettings.Listener settingsListener;
    private WebcamRoiPane roiPane;
    private RoiMatchResult last;

    public VisionThresholdBar(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        matchLabel = new Label(this, SWT.NONE);
        matchLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite thrRow = new Composite(this, SWT.NONE);
        thrRow.setLayout(new GridLayout(2, true));
        thrRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button thrMinus = new Button(thrRow, SWT.PUSH);
        thrMinus.setText("임계 -");
        thrMinus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        thrMinus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setSimThr(settings.getSimThr() - SettingsUi.THR_STEP);
                if (roiPane != null) {
                    roiPane.applySimThrFromSettings();
                }
                updateMatchLabel(last);
            }
        });
        Button thrPlus = new Button(thrRow, SWT.PUSH);
        thrPlus.setText("임계 +");
        thrPlus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        thrPlus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setSimThr(settings.getSimThr() + SettingsUi.THR_STEP);
                if (roiPane != null) {
                    roiPane.applySimThrFromSettings();
                }
                updateMatchLabel(last);
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
                            updateMatchLabel(last);
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
        updateMatchLabel(null);
    }

    public void setRoiPane(WebcamRoiPane pane) {
        this.roiPane = pane;
        if (pane != null) {
            pane.setMatchListener(new WebcamRoiPane.MatchListener() {
                public void onMatch(RoiMatchResult r) {
                    last = r;
                    if (!isDisposed()) {
                        updateMatchLabel(r);
                    }
                }
            });
        }
    }

    public void updateMatchLabel(RoiMatchResult r) {
        if (matchLabel == null || matchLabel.isDisposed()) {
            return;
        }
        double thr = settings.getSimThr();
        if (r == null || !"ok".equals(r.state)) {
            matchLabel.setText(String.format("매칭도 (NCC) —  ·  임계치 %.0f%%", thr * 100));
            return;
        }
        String cmp = r.ncc >= thr ? ">" : "<";
        matchLabel.setText(String.format("매칭도 (NCC) %.0f%% %s 임계치 %.0f%%",
                r.ncc * 100, cmp, thr * 100));
    }
}
