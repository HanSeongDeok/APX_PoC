import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.audio.ExpectedWavBar;

/**
 * ExpectedWavBar 조립 예시 - 기대 경고음(.wav) 경로 선택.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/ExpectedWavBarExample.java}
 */
public final class ExpectedWavBarExample {

    private ExpectedWavBarExample() {
    }

    public static ExpectedWavBar buildDefault(Composite parent) {
        return new ExpectedWavBar(parent);
    }

    public static ExpectedWavBar buildCustom(Composite parent) {
        ExpectedWavBar.Cfg cfg = new ExpectedWavBar.Cfg();
        cfg.titleText = "기대 경고음 파일 (.wav)";
        cfg.placeholderText = "경고음 .wav를 선택하세요";
        return new ExpectedWavBar(parent, cfg);
    }
}
