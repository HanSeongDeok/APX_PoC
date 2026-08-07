import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.rear.RearGrid;
import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.rear.VerdictResult;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;

/**
 * RearGridCanvas 조립 예시 — 격자·범례·판정 스냅샷 API.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/RearGridCanvasExample.java}
 */
public final class RearGridCanvasExample {

    private RearGridCanvasExample() {
    }

    public static RearGridCanvas buildDefault(Composite parent) {
        RearGrid g = new RearGrid(4, 6);
        RearGridCanvas canvas = new RearGridCanvas(parent, g);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 300;
        canvas.setLayoutData(gd);
        canvas.loadDefaultCarImage();
        canvas.setShowLegend(false);
        return canvas;
    }

    public static RearGridCanvas buildFromSettings(Composite parent) {
        ApxSettings s = ApxSettings.get();
        RearGrid g = new RearGrid(s.getRearCols(), s.getRearRows());
        g.selectPoints(s.getRearSelectedPoints());
        RearGridCanvas canvas = new RearGridCanvas(parent, g);
        canvas.loadDefaultCarImage();
        canvas.setShowLegend(s.isRearShowLegend());
        canvas.applyDefaultLegend();
        canvas.setOnChange(new Runnable() {
            public void run() {
                s.setRearSelectedPoints(canvas.getGrid().selectedPoints());
            }
        });
        canvas.setLegend(
                new String[] { "선택", "측정중", "합격", "불합격" },
                new RGB[] { new RGB(0, 120, 255), new RGB(230, 200, 40),
                        new RGB(40, 170, 70), new RGB(200, 40, 40) });
        return canvas;
    }

    /** PASS/FAIL 스냅샷 저장·조회 (증거 폴더 rear/). */
    public static void snapshotDemo(RearGridCanvas canvas, File evidenceRoot) {
        canvas.setSnapshotDir(new File(evidenceRoot, "rear"));
        File saved = canvas.saveVerdictSnapshot(
                new VerdictResult(2, 3, Verdict.PASS), "TC_REAR_01");
        File one = canvas.getSnapshot("TC_REAR_01");
        List<File> many = canvas.getSnapshots(Arrays.asList("TC_REAR_01", "TC_REAR_02"));
        File combined = canvas.getCombinedSnapshot(Arrays.asList("TC_REAR_01", "TC_REAR_02"));
        System.out.println("saved=" + saved + " one=" + one
                + " many=" + many.size() + " combined=" + combined);
    }
}
