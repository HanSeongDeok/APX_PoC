import org.eclipse.swt.graphics.RGB;

import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.vision.RoiNcc;

/**
 * RoiNcc 조립 예시 — Canvas 위 ROI 오버레이(기본/커스텀 스타일).
 *
 * <p>경로: {@code apx-settings-demo/examples/components/RoiNccExample.java}
 */
public final class RoiNccExample {

    private RoiNccExample() {
    }

    /** 기본 색·선 두께. */
    public static RoiNcc buildDefault(CameraCanvas canvas) {
        return new RoiNcc(canvas);
    }

    /** 일치/불일치/드래그 색·두께 커스텀. */
    public static RoiNcc buildCustom(CameraCanvas canvas) {
        RoiNcc.Style st = new RoiNcc.Style();
        st.hit = new RGB(0, 200, 0);
        st.miss = new RGB(220, 60, 60);
        st.drag = new RGB(0, 160, 255);
        st.roiLineWidth = 3;
        st.dragThickness = 2;
        return new RoiNcc(canvas, st);
    }
}
