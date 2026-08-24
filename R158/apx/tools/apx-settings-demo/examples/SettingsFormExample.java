import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.client.view.SettingsForm;
import com.suresofttech.apx.ui.widget.settings.vision.CameraSelectBar;

/**
 * 클라이언트 설정 UI 전체 조립 예시 - {@link SettingsForm}.
 *
 * <p>비전 / 음향 / 후방 3열을 한 Composite에 붙인다. View({@code SettingsClientView})와
 * Dialog({@code SettingsDialog})가 이 폼을 그대로 쓴다.
 *
 * <p>경로: {@code apx-settings-demo/examples/SettingsFormExample.java}
 * <p>※ 컴파일 대상이 아닌 읽기용 샘플. 실제 조립은 SettingsForm 소스 참고.
 */
public final class SettingsFormExample {

    private SettingsFormExample() {
    }

    /** Kickoff 설정 Dialog / SettingsClientView 와 동일 - SettingsForm 한 줄. */
    public static SettingsForm build(Composite parent) {
        SettingsForm form = new SettingsForm(parent);
        form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        return form;
    }

    /** 웹캠 목록 갱신(폼 생성 직후). */
    public static void refreshCameras(SettingsForm form) {
        if (form == null) {
            return;
        }
        CameraSelectBar cam = form.getCameraSelect();
        if (cam != null && !cam.isDisposed()) {
            cam.refreshCameras();
        }
    }
}
