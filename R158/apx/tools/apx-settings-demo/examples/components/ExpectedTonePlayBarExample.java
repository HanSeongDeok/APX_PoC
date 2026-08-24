import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.audio.AudioMeasureBar;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedTonePlayBar;

/**
 * ExpectedTonePlayBar 조립 예시 - 기대음 재생(보통 MeasureBar 액션칸).
 *
 * <p>경로: {@code apx-settings-demo/examples/components/ExpectedTonePlayBarExample.java}
 */
public final class ExpectedTonePlayBarExample {

    private ExpectedTonePlayBarExample() {
    }

    /** parent = measure.getActionRow() 권장. */
    public static ExpectedTonePlayBar buildDefault(Composite parent) {
        ExpectedTonePlayBar play = new ExpectedTonePlayBar(parent);
        play.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return play;
    }

    public static ExpectedTonePlayBar attachToMeasure(AudioMeasureBar measure) {
        return buildDefault(measure.getActionRow());
    }

    public static ExpectedTonePlayBar buildCustom(Composite parent) {
        ExpectedTonePlayBar.Cfg cfg = new ExpectedTonePlayBar.Cfg();
        cfg.playText = "기대음 듣기";
        cfg.playingText = "재생 정지";
        return new ExpectedTonePlayBar(parent, cfg);
    }
}
