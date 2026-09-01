package com.suresofttech.apx.ui;

import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.VisionChannel;

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
        CameraService.get().close();
        CameraService.of(VisionChannel.GEAR).close();
    }
}
