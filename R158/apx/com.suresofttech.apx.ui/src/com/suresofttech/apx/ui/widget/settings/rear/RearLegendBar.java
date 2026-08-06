package com.suresofttech.apx.ui.widget.settings.rear;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 후방 범례 on/off → {@link ApxSettings#setRearShowLegend} + 연결 {@link RearGridCanvas#setShowLegend}.
 */
public class RearLegendBar extends Composite {

    /** 클라이언트 주입 라벨. */
    public static final class Cfg {
        public String legendText = "범례";
    }

    private final ApxSettings settings = ApxSettings.get();
    private final Button legendChk;
    private RearGridCanvas canvas;

    public RearLegendBar(Composite parent) {
        this(parent, new Cfg());
    }

    public RearLegendBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        Cfg c = (cfg != null) ? cfg : new Cfg();
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        legendChk = new Button(this, SWT.CHECK);
        legendChk.setText(c.legendText);
        legendChk.setSelection(settings.isRearShowLegend());
        legendChk.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                boolean on = legendChk.getSelection();
                settings.setRearShowLegend(on);
                if (canvas != null && !canvas.isDisposed()) {
                    canvas.setShowLegend(on);
                }
            }
        });
    }

    /** Canvas 연결 — 체크 ↔ 범례 표시 동기화. */
    public void setCanvas(RearGridCanvas canvas) {
        this.canvas = canvas;
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        boolean on = settings.isRearShowLegend();
        legendChk.setSelection(on);
        canvas.setShowLegend(on);
    }
}
