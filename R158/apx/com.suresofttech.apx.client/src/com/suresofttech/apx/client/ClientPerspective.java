package com.suresofttech.apx.client;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/** 설정 View만 전체 배치. */
public class ClientPerspective implements IPerspectiveFactory {

    public static final String ID = "com.suresofttech.apx.client.perspective";

    public void createInitialLayout(IPageLayout layout) {
        String editor = layout.getEditorArea();
        layout.setEditorAreaVisible(false);
        layout.addView("com.suresofttech.apx.client.view.settings",
                IPageLayout.LEFT, 1.0f, editor);
    }
}
