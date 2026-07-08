package com.suresofttech.apx.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

/**
 * ③ 클러스터 View (스켈레톤) — 파이썬 main_window.py 클러스터 탭 대응.
 * TODO: 웹캠 프레임 표시, 팝업 경고창 검출(com.suresofttech.apx.core.vision.Cluster).
 */
public class ClusterView extends ViewPart {
    @Override
    public void createPartControl(Composite parent) {
        new Label(parent, SWT.NONE).setText("③ 클러스터 (TODO)");
    }

    @Override
    public void setFocus() {
    }
}
