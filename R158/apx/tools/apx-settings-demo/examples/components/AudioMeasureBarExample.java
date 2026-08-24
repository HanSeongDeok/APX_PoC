import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.audio.AudioMeasureBar;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedTonePlayBar;

/**
 * AudioMeasureBar 조립 예시 - 측정/초기화 + Scope / Tone 연결.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/AudioMeasureBarExample.java}
 */
public final class AudioMeasureBarExample {

    private AudioMeasureBarExample() {
    }

    public static AudioMeasureBar build(Composite parent, AudioScope scope) {
        AudioMeasureBar measure = new AudioMeasureBar(parent);
        new ExpectedTonePlayBar(measure.getActionRow()); // 3번째 칸
        if (scope != null) {
            measure.setScope(scope);
        }
        return measure;
    }

    public static AudioMeasureBar buildCustom(Composite parent, AudioScope scope) {
        AudioMeasureBar.Cfg cfg = new AudioMeasureBar.Cfg();
        cfg.measureText = "측정 시작";
        cfg.measuringText = "측정 중지";
        cfg.resetText = "리셋";
        AudioMeasureBar measure = new AudioMeasureBar(parent, cfg);
        measure.setScope(scope);
        return measure;
    }
}
