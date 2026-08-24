import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.audio.MicSelectBar;

/**
 * MicSelectBar 조립 예시 - 마이크 선택(ApxSettings.micName).
 *
 * <p>경로: {@code apx-settings-demo/examples/components/MicSelectBarExample.java}
 */
public final class MicSelectBarExample {

    private MicSelectBarExample() {
    }

    public static MicSelectBar build(Composite parent) {
        MicSelectBar micBar = new MicSelectBar(parent);
        micBar.refreshMics();
        return micBar;
    }
}
