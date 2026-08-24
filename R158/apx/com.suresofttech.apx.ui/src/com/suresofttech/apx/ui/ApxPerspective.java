package com.suresofttech.apx.ui;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * R158 검증 퍼스펙티브 - 파이썬 4탭(설정 / 기어 / 클러스터 / 음향)을 4 View 로 배치.
 * 레거시 RCP(Eclipse 3.x) IPerspectiveFactory 방식.
 */
public class ApxPerspective implements IPerspectiveFactory {

    private static final String[] VIEWS = {
            "com.suresofttech.apx.ui.view.settings",
            "com.suresofttech.apx.ui.view.gear",
            "com.suresofttech.apx.ui.view.cluster",
            "com.suresofttech.apx.ui.view.audio",
            "com.suresofttech.apx.ui.view.sync",
            "com.suresofttech.apx.ui.view.rear",
    };

    public void createInitialLayout(IPageLayout layout) {
        String editor = layout.getEditorArea();
        layout.setEditorAreaVisible(false);
        // 뷰는 자유롭게 이동 / 닫기 가능(기본값). 닫아서 사라지면 [보기]>퍼스펙티브 초기화로 복구.
        layout.addView(VIEWS[0], IPageLayout.LEFT, 0.25f, editor);
        layout.addView(VIEWS[1], IPageLayout.TOP, 0.5f, VIEWS[0]);
        layout.addView(VIEWS[2], IPageLayout.RIGHT, 0.5f, editor);
        layout.addView(VIEWS[3], IPageLayout.BOTTOM, 0.5f, VIEWS[2]);
        layout.addView(VIEWS[4], IPageLayout.BOTTOM, 0.6f, VIEWS[0]);   // ⑤ 동기화 - 설정 아래
        layout.addView(VIEWS[5], IPageLayout.BOTTOM, 0.5f, VIEWS[2]);   // ⑥ 후방 검증 - 클러스터 아래
    }
}
