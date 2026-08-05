package com.suresofttech.apx.client;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;

/** 이솝 RCP 진입점 - APX 모듈 JAR는 Bundle-ClassPath로 로드. */
public class ClientApplication implements IApplication {

    public Object start(IApplicationContext context) {
        Display display = PlatformUI.createDisplay();
        try {
            int code = PlatformUI.createAndRunWorkbench(display, new ClientWorkbenchAdvisor());
            return code == PlatformUI.RETURN_RESTART
                    ? IApplication.EXIT_RESTART : IApplication.EXIT_OK;
        } finally {
            display.dispose();
        }
    }

    public void stop() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        final IWorkbench wb = PlatformUI.getWorkbench();
        final Display display = wb.getDisplay();
        display.syncExec(new Runnable() {
            public void run() {
                if (!display.isDisposed()) {
                    wb.close();
                }
            }
        });
    }
}
