package com.suresofttech.apx.client;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.ActionFactory.IWorkbenchAction;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;

import com.suresofttech.apx.ui.widget.TestPlayerDialog;

/** 메뉴바 - 보기 + 클러스터/기어봉 테스트 화면. */
public class ClientActionBarAdvisor extends ActionBarAdvisor {

    private IWorkbenchAction resetPerspective;
    private IWorkbenchAction quit;
    private IAction clusterTest;
    private IAction gearTest;

    public ClientActionBarAdvisor(IActionBarConfigurer configurer) {
        super(configurer);
    }

    @Override
    protected void makeActions(final IWorkbenchWindow window) {
        resetPerspective = ActionFactory.RESET_PERSPECTIVE.create(window);
        resetPerspective.setText("퍼스펙티브 초기화");
        register(resetPerspective);

        quit = ActionFactory.QUIT.create(window);
        register(quit);

        clusterTest = new Action("클러스터 테스트 화면(&C)") {
            public void run() {
                TestPlayerDialog.openCluster(window.getShell());
            }
        };
        clusterTest.setId("com.suresofttech.apx.client.clusterTest");
        clusterTest.setToolTipText("일반/팝업 테스트 이미지 (1 / 2, Space 자동순환)");
        register(clusterTest);

        gearTest = new Action("기어봉 테스트 화면(&G)") {
            public void run() {
                TestPlayerDialog.openGear(window.getShell());
            }
        };
        gearTest.setId("com.suresofttech.apx.client.gearTest");
        gearTest.setToolTipText("P/R/N/D 테스트 이미지 (1~4, Space 자동순환)");
        register(gearTest);
    }

    @Override
    protected void fillMenuBar(IMenuManager menuBar) {
        MenuManager viewMenu = new MenuManager("보기(&V)", "view");
        viewMenu.add(resetPerspective);
        viewMenu.add(quit);
        menuBar.add(viewMenu);

        MenuManager testMenu = new MenuManager("테스트(&T)", "test");
        testMenu.add(clusterTest);
        testMenu.add(gearTest);
        menuBar.add(testMenu);
    }
}
