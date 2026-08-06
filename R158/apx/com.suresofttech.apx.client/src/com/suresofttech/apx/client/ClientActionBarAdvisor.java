package com.suresofttech.apx.client;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.ActionFactory.IWorkbenchAction;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;

/** 메뉴바 — "퍼스펙티브 초기화"(레이아웃을 createInitialLayout 상태로 복구) 제공. */
public class ClientActionBarAdvisor extends ActionBarAdvisor {

    private IWorkbenchAction resetPerspective;
    private IWorkbenchAction quit;

    public ClientActionBarAdvisor(IActionBarConfigurer configurer) {
        super(configurer);
    }

    @Override
    protected void makeActions(IWorkbenchWindow window) {
        resetPerspective = ActionFactory.RESET_PERSPECTIVE.create(window);
        resetPerspective.setText("퍼스펙티브 초기화");
        register(resetPerspective);

        quit = ActionFactory.QUIT.create(window);
        register(quit);
    }

    @Override
    protected void fillMenuBar(IMenuManager menuBar) {
        MenuManager viewMenu = new MenuManager("보기(&V)", "view");
        viewMenu.add(resetPerspective);
        viewMenu.add(quit);
        menuBar.add(viewMenu);
    }
}
