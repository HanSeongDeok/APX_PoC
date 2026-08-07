import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.rear.VerdictResult;
import com.suresofttech.apx.client.monitor.RearReadout;

/**
 * RearReadout 조립 예시 — 격자·지정·판정 집계·증거 규약 판독값.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/RearReadoutExample.java}
 */
public final class RearReadoutExample {

    private RearReadoutExample() {
    }

    public static RearReadout build(Composite parent, int cols, int rows,
            List<int[]> selected) {
        RearReadout readout = new RearReadout(parent);
        readout.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        readout.setGrid(cols, rows, selected);
        return readout;
    }

    public static void onStarted(RearReadout readout, int cols, int rows,
            List<int[]> selected) {
        readout.onStarted(cols, rows, selected);
    }

    public static void onVerdicts(RearReadout readout, List<VerdictResult> results) {
        readout.setVerdicts(results);
    }

    public static void onFinal(RearReadout readout, boolean pass, Long overallMs,
            String summary) {
        readout.setFinal(pass, overallMs, summary);
    }
}
