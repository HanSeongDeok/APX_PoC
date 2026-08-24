import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.client.monitor.VisionReadout;

/**
 * VisionReadout 조립 예시 - 유사도 / ROI / 프레임 / ±1f 증거 판독값.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/VisionReadoutExample.java}
 */
public final class VisionReadoutExample {

    private VisionReadoutExample() {
    }

    public static VisionReadout build(Composite parent) {
        VisionReadout readout = new VisionReadout(parent);
        readout.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        readout.setReference(false, null, 0.75);
        return readout;
    }

    /** 측정 중 프레임 결과 반영. */
    public static void onMatch(VisionReadout readout, RoiMatchResult r, double fps) {
        readout.update(r, fps);
    }
}
