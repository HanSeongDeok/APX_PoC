package com.suresofttech.apx.client;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * 측정 Kickoff + 음향/클러스터/기어봉/후방 모니터 중심 배치.
 * 하단 폴더: 결과 + 설정 View.
 */
public class ClientPerspective implements IPerspectiveFactory {

    public static final String ID = "com.suresofttech.apx.client.perspective";

    private static final String VIEW_KICKOFF = "com.suresofttech.apx.client.view.kickoff";
    private static final String VIEW_AUDIO = "com.suresofttech.apx.client.view.audioMonitor";
    private static final String VIEW_CLUSTER = "com.suresofttech.apx.client.view.visionMonitor";
    private static final String VIEW_GEAR = "com.suresofttech.apx.client.view.gearVisionMonitor";
    private static final String VIEW_REAR = "com.suresofttech.apx.client.view.rearMonitor";
    private static final String VIEW_RESULT = "com.suresofttech.apx.client.view.result";
    private static final String VIEW_DEFAULT = "com.suresofttech.apx.client.view.settings";
    private static final String VIEW_CUSTOM = "com.suresofttech.apx.client.view.settings2";

    public void createInitialLayout(IPageLayout layout) {
        String editor = layout.getEditorArea();
        layout.setEditorAreaVisible(false);

        // 상단: Kickoff
        layout.addView(VIEW_KICKOFF, IPageLayout.TOP, 0.14f, editor);

        // Kickoff 아래: 음향 | 클러스터 | 기어봉 | 후방 — 비전 웹캠이 거의 같게 넓다
        layout.addView(VIEW_AUDIO, IPageLayout.BOTTOM, 0.78f, VIEW_KICKOFF);
        layout.addView(VIEW_CLUSTER, IPageLayout.RIGHT, 0.78f, VIEW_AUDIO);
        layout.addView(VIEW_GEAR, IPageLayout.RIGHT, 0.64f, VIEW_CLUSTER);
        layout.addView(VIEW_REAR, IPageLayout.RIGHT, 0.44f, VIEW_GEAR);

        // 결과 / 설정 - 하단 폴더(탭)
        IFolderLayout bottom = layout.createFolder(
                "com.suresofttech.apx.client.folder.settings",
                IPageLayout.BOTTOM, 0.28f, VIEW_AUDIO);
        bottom.addView(VIEW_RESULT);
        bottom.addView(VIEW_DEFAULT);
        bottom.addView(VIEW_CUSTOM);
    }
}
