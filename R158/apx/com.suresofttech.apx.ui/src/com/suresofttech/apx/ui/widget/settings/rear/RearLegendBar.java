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
 * 후방 범례 on/off.
 * 기본은 {@link ApxSettings#setRearShowLegend}와 연동(설정·모니터 동일).
 * 로컬만 토글하려면 {@link Cfg#bindToSettings}=false.
 */
public class RearLegendBar extends Composite {

    /** 클라이언트 주입 라벨. */
    public static final class Cfg {
        public String legendText = "범례";
        /** false면 ApxSettings에 쓰지 않고 캔버스만 토글(모니터용). */
        public boolean bindToSettings = true;
        /** bindToSettings=false일 때 초기 체크 상태. */
        public boolean initialShow = true;
    }

    private final ApxSettings settings = ApxSettings.get();
    private final boolean bindToSettings;
    private final Button legendChk;
    private RearGridCanvas canvas;

    public RearLegendBar(Composite parent) {
        this(parent, new Cfg());
    }

    public RearLegendBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        Cfg c = (cfg != null) ? cfg : new Cfg();
        this.bindToSettings = c.bindToSettings;
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        legendChk = new Button(this, SWT.CHECK);
        legendChk.setText(c.legendText);
        if (bindToSettings) {
            legendChk.setSelection(settings.isRearShowLegend());
        } else {
            legendChk.setSelection(c.initialShow);
        }
        legendChk.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                boolean on = legendChk.getSelection();
                if (bindToSettings) {
                    settings.setRearShowLegend(on);
                }
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
        boolean on = bindToSettings ? settings.isRearShowLegend() : legendChk.getSelection();
        legendChk.setSelection(on);
        canvas.setShowLegend(on);
    }

    public boolean isLegendOn() {
        return legendChk != null && !legendChk.isDisposed() && legendChk.getSelection();
    }
}
