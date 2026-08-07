import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.audio.AudioThresholdBar;

/**
 * AudioThresholdBar 조립 예시 — 주파수·파형 임계를 한 값으로 조절.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/AudioThresholdBarExample.java}
 */
public final class AudioThresholdBarExample {

    private AudioThresholdBarExample() {
    }

    public static AudioThresholdBar buildDefault(Composite parent) {
        return new AudioThresholdBar(parent); // 기본 0.90 / step 0.05
    }

    public static AudioThresholdBar buildCustom(Composite parent) {
        AudioThresholdBar.Cfg cfg = new AudioThresholdBar.Cfg();
        cfg.defaultThr = 0.90;
        cfg.step = 0.05;
        cfg.descText = "PASS 기준 임계 (커스텀)";
        cfg.minusText = "− 완화";
        cfg.plusText = "+ 엄격";
        return new AudioThresholdBar(parent, cfg);
    }
}
