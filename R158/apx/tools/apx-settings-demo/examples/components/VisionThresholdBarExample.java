import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.vision.RoiNcc;
import com.suresofttech.apx.ui.widget.settings.vision.VisionThresholdBar;

/**
 * VisionThresholdBar 조립 예시 - simThr ± 조절 + RoiNcc 점수 연결.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/VisionThresholdBarExample.java}
 */
public final class VisionThresholdBarExample {

    private VisionThresholdBarExample() {
    }

    public static VisionThresholdBar buildDefault(Composite parent, RoiNcc roi) {
        VisionThresholdBar thr = new VisionThresholdBar(parent);
        thr.setRoiNcc(roi);
        return thr;
    }

    public static VisionThresholdBar buildCustom(Composite parent, RoiNcc roi) {
        VisionThresholdBar.Cfg cfg = new VisionThresholdBar.Cfg();
        cfg.defaultThr = 0.75;
        cfg.step = 0.05;
        cfg.minusText = "− 정밀도";
        cfg.plusText = "+ 정밀도";
        VisionThresholdBar thr = new VisionThresholdBar(parent, cfg);
        thr.setRoiNcc(roi);
        return thr;
    }
}
