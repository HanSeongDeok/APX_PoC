package com.suresofttech.apx.client;

import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

public class ClientWorkbenchAdvisor extends WorkbenchAdvisor {

    public String getInitialWindowPerspectiveId() {
        return ClientPerspective.ID;
    }

    public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(
            IWorkbenchWindowConfigurer configurer) {
        return new WorkbenchWindowAdvisor(configurer) {
            public void preWindowOpen() {
                configurer.setTitle("APX 이솝 클라이언트 - 예시 프로그램");
                configurer.setShowCoolBar(false);
                configurer.setShowStatusLine(true);
                configurer.setInitialSize(new org.eclipse.swt.graphics.Point(1100, 720));
            }
        };
    }
}
