package com.suresofttech.apx.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

/**
 * ① 설정 View (스켈레톤) — 파이썬 settings_tab.py 대응.
 * TODO: 웹캠 선택·미리보기, 마이크 선택·레벨, 경고음 테스트(Tone) 재생.
 */
public class SettingsView extends ViewPart {
    @Override
    public void createPartControl(Composite parent) {
        new Label(parent, SWT.NONE).setText("① 설정 (TODO)");
    }

    @Override
    public void setFocus() {
    }
}
