package com.suresofttech.apx.client;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/** 설정 View(기본 및 커스텀)를 좌/우로 나란히 배치. */
public class ClientPerspective implements IPerspectiveFactory {

    public static final String ID = "com.suresofttech.apx.client.perspective";

    private static final String VIEW_DEFAULT = "com.suresofttech.apx.client.view.settings";
    private static final String VIEW_CUSTOM = "com.suresofttech.apx.client.view.settings2";

    public void createInitialLayout(IPageLayout layout) {
        String editor = layout.getEditorArea();
        layout.setEditorAreaVisible(false);
        layout.addView(VIEW_DEFAULT, IPageLayout.LEFT, 0.5f, editor);         // 좌: 기본
        layout.addView(VIEW_CUSTOM, IPageLayout.RIGHT, 0.5f, VIEW_DEFAULT);   // 우: 커스텀
    }
}
