import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;
import com.suresofttech.apx.ui.widget.settings.rear.RearLegendBar;

/**
 * RearLegendBar 조립 예시 - 범례 on/off 체크 + Canvas 연결.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/RearLegendBarExample.java}
 */
public final class RearLegendBarExample {

    private RearLegendBarExample() {
    }

    public static RearLegendBar buildDefault(Composite parent, RearGridCanvas canvas) {
        RearLegendBar legend = new RearLegendBar(parent);
        if (canvas != null) {
            legend.setCanvas(canvas);
        }
        return legend;
    }

    public static RearLegendBar buildCustom(Composite parent, RearGridCanvas canvas) {
        RearLegendBar.Cfg cfg = new RearLegendBar.Cfg();
        cfg.legendText = "상태 범례";
        RearLegendBar legend = new RearLegendBar(parent, cfg);
        if (canvas != null) {
            legend.setCanvas(canvas);
        }
        return legend;
    }
}
