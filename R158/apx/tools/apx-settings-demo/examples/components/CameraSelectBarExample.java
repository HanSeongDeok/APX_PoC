import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.vision.CameraSelectBar;

/**
 * CameraSelectBar 조립 예시 — 웹캠 선택 + Canvas 연결.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/CameraSelectBarExample.java}
 */
public final class CameraSelectBarExample {

    private CameraSelectBarExample() {
    }

    public static CameraSelectBar build(Composite parent, CameraCanvas canvas) {
        CameraSelectBar cam = new CameraSelectBar(parent);
        canvas.setPlaceholder("웹캠을 선택하세요");
        cam.setCanvas(canvas); // 프레임 → canvas
        cam.refreshCameras();  // 장치 목록 로드
        return cam;
    }
}
