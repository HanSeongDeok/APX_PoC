package com.suresofttech.apx.ui;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.ActionFactory.IWorkbenchAction;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;

/** 메뉴/툴바 — [보기] 메뉴에 '퍼스펙티브 초기화'(레이아웃 깨질 때 복구용). */
public class ApplicationActionBarAdvisor extends ActionBarAdvisor {

    private IWorkbenchAction resetPerspective;

    public ApplicationActionBarAdvisor(IActionBarConfigurer configurer) {
        super(configurer);
    }

    protected void makeActions(IWorkbenchWindow window) {
        resetPerspective = ActionFactory.RESET_PERSPECTIVE.create(window);
        resetPerspective.setText("퍼스펙티브 초기화");
        register(resetPerspective);
    }

    protected void fillMenuBar(IMenuManager menuBar) {
        MenuManager view = new MenuManager("보기(&V)", "view");
        view.add(resetPerspective);
        menuBar.add(view);
    }
}
