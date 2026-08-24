import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.audio.MicTestBar;

/**
 * MicTestBar 조립 예시 - 입력 레벨 막대 + 테스트 토글.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/MicTestBarExample.java}
 */
public final class MicTestBarExample {

    private MicTestBarExample() {
    }

    public static MicTestBar build(Composite parent) {
        // 장치는 ApxSettings.micName으로 해석 (MicSelectBar가 저장)
        return new MicTestBar(parent);
    }
}
