package com.suresofttech.apx.client;

import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

public class ClientWorkbenchAdvisor extends WorkbenchAdvisor {

    public String getInitialWindowPerspectiveId() {
        return ClientPerspective.ID;
    }

    /** 저장된 레이아웃을 복원하지 않음 - perspective(createInitialLayout)를 매 실행마다 새로 적용.
     *  (뷰를 추가/변경해도 이전 워크스페이스 상태에 가려지지 않게 함) */
    @Override
    public void initialize(IWorkbenchConfigurer configurer) {
        super.initialize(configurer);
        configurer.setSaveAndRestore(false);
    }

    public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(
            IWorkbenchWindowConfigurer configurer) {
        return new WorkbenchWindowAdvisor(configurer) {
            public void preWindowOpen() {
                configurer.setTitle("APX 클라이언트 - 예시 프로그램");
                configurer.setShowCoolBar(false);
                configurer.setShowMenuBar(true);
                configurer.setShowStatusLine(true);
                configurer.setInitialSize(new org.eclipse.swt.graphics.Point(1100, 720));
            }

            @Override
            public ActionBarAdvisor createActionBarAdvisor(IActionBarConfigurer barConfigurer) {
                return new ClientActionBarAdvisor(barConfigurer);
            }
        };
    }
}
