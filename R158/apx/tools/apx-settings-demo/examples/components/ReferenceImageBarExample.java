import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.vision.ReferenceImageBar;

/**
 * ReferenceImageBar 조립 예시 - 기준 이미지 사용 여부 / 경로(ApxSettings).
 *
 * <p>경로: {@code apx-settings-demo/examples/components/ReferenceImageBarExample.java}
 */
public final class ReferenceImageBarExample {

    private ReferenceImageBarExample() {
    }

    public static ReferenceImageBar build(Composite parent) {
        return new ReferenceImageBar(parent);
    }
}
