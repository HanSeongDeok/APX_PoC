package com.suresofttech.apx.ui;

import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

import com.suresofttech.apx.core.vision.CameraService;

/** 초기 퍼스펙티브 지정 + 종료 시 공유 카메라 해제. */
public class ApplicationWorkbenchAdvisor extends WorkbenchAdvisor {

    private static final String PERSPECTIVE_ID = "com.suresofttech.apx.ui.perspective";

    public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(
            IWorkbenchWindowConfigurer configurer) {
        return new ApplicationWorkbenchWindowAdvisor(configurer);
    }

    public String getInitialWindowPerspectiveId() {
        return PERSPECTIVE_ID;
    }

    public void postShutdown() {
        CameraService.get().close();   // 공유 웹캠 해제(모든 View 종료 후)
    }
}
