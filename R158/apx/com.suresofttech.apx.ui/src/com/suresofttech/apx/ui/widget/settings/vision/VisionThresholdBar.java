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
import com.suresofttech.apx.core.vision.RoiMatchDetector;
import com.suresofttech.apx.core.vision.RoiMatchResult;

/**
 * NCC 임계 + 매칭도 라벨 - {@link ApxSettings#setSimThr}.
 * 매칭 소스는 {@link #setRoiNcc(RoiNcc)}.
 */
public class VisionThresholdBar extends Composite {

    public static final class Cfg {
        public double defaultThr = RoiMatchDetector.DEFAULT_SIM;
        public double step = 0.05;
        public String minusText = "임계 -";
        public String plusText = "임계 +";
    }

    private final ApxSettings settings = ApxSettings.get();
    private final Cfg cfg;
    private final Label matchLabel;
    private final ApxSettings.Listener settingsListener;
    private RoiNcc roiNcc;
    private RoiMatchResult last;

    public VisionThresholdBar(Composite parent) {
        this(parent, new Cfg());
    }

    public VisionThresholdBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        this.cfg = (cfg != null) ? cfg : new Cfg();
        settings.setSimThr(this.cfg.defaultThr);
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
        thrMinus.setText(this.cfg.minusText);
        thrMinus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        thrMinus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setSimThr(settings.getSimThr() - cfg.step);
                if (roiNcc != null) {
                    roiNcc.applySimThrFromSettings();
                }
                updateMatchLabel(last);
            }
        });
        Button thrPlus = new Button(thrRow, SWT.PUSH);
        thrPlus.setText(this.cfg.plusText);
        thrPlus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        thrPlus.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setSimThr(settings.getSimThr() + cfg.step);
                if (roiNcc != null) {
                    roiNcc.applySimThrFromSettings();
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

    public void setRoiNcc(RoiNcc roi) {
        this.roiNcc = roi;
        if (roi != null) {
            roi.setMatchListener(new RoiNcc.MatchListener() {
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
            matchLabel.setText(String.format("매칭도 (NCC) --  /  임계치 %.0f%%", thr * 100));
            return;
        }
        String cmp = r.ncc >= thr ? ">" : "<";
        matchLabel.setText(String.format("매칭도 (NCC) %.0f%% %s 임계치 %.0f%%",
                r.ncc * 100, cmp, thr * 100));
    }
}
