import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.ui.widget.settings.audio.AudioMeasureBar;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;

/**
 * AudioScope 조립 예시 — 파형 그래프(버튼 없음, MeasureBar가 데이터 공급).
 *
 * <p>경로: {@code apx-settings-demo/examples/components/AudioScopeExample.java}
 */
public final class AudioScopeExample {

    private AudioScopeExample() {
    }

    public static AudioScope buildDefault(Composite parent, AudioMeasureBar measure) {
        AudioScope scope = new AudioScope(parent, 5000.0); // Y축 최대 Hz
        scope.setShowPitch(false);
        scope.setShowTrend(false);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.minimumHeight = 180;
        scope.setLayoutData(gd);
        if (measure != null) {
            measure.setScope(scope);
        }
        return scope;
    }

    public static AudioScope buildCustom(Composite parent, AudioMeasureBar measure) {
        AudioScope scope = buildDefault(parent, null);
        scope.setTickMs(1000);
        scope.setPassColor(0x2ecb5a);
        scope.setPassAlpha(90);
        scope.setWaveTitle("측정 파형 (커스텀)");
        if (measure != null) {
            measure.setScope(scope);
        }
        return scope;
    }
}
