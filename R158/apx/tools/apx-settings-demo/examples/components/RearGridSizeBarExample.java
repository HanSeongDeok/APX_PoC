import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridSizeBar;

/**
 * RearGridSizeBar 조립 예시 — 격자 크기(프리셋/커스텀) + Canvas 연결.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/RearGridSizeBarExample.java}
 */
public final class RearGridSizeBarExample {

    private RearGridSizeBarExample() {
    }

    public static RearGridSizeBar buildDefault(Composite parent, RearGridCanvas canvas) {
        RearGridSizeBar size = new RearGridSizeBar(parent);
        if (canvas != null) {
            size.setCanvas(canvas);
        }
        return size;
    }

    public static RearGridSizeBar buildCustom(Composite parent, RearGridCanvas canvas) {
        RearGridSizeBar.Cfg cfg = new RearGridSizeBar.Cfg();
        cfg.presetText = "고정 크기";
        cfg.customText = "직접 입력";
        cfg.sizeLabelText = "격자 크기";
        cfg.colsLabelText = "가로(열)";
        cfg.rowsLabelText = "세로(행)";
        cfg.applyText = "격자 적용";
        cfg.presets = new int[][] { { 4, 6 }, { 3, 4 }, { 5, 7 }, { 6, 10 } };
        RearGridSizeBar size = new RearGridSizeBar(parent, cfg);
        if (canvas != null) {
            size.setCanvas(canvas);
        }
        return size;
    }
}
