package com.suresofttech.apx.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

/**
 * ② 기어봉 View (스켈레톤) — 파이썬 main_window.py 기어 탭 대응.
 * TODO: 웹캠 프레임 표시, 기어 P/R/N/D 판정(com.suresofttech.apx.core.vision.Gear).
 */
public class GearView extends ViewPart {
    @Override
    public void createPartControl(Composite parent) {
        new Label(parent, SWT.NONE).setText("② 기어봉 (TODO)");
    }

    @Override
    public void setFocus() {
    }
}
