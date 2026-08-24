import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.vision.CameraSelectBar;

/**
 * CameraCanvas 조립 예시 - 표시 전용 화면 + SelectBar 연결.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/CameraCanvasExample.java}
 */
public final class CameraCanvasExample {

    private CameraCanvasExample() {
    }

    public static CameraCanvas build(Composite parent, CameraSelectBar cam) {
        CameraCanvas canvas = new CameraCanvas(parent);
        canvas.setPlaceholder("웹캠을 선택하세요");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 280;
        canvas.setLayoutData(gd);
        if (cam != null) {
            cam.setCanvas(canvas);
        }
        return canvas;
    }
}
