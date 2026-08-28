package com.suresofttech.apx.ui.widget.settings.vision;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 기준 이미지 사용 체크 + 경로 + 파일 선택 - {@link ApxSettings} 연동.
 *
 * @deprecated <b>사용하지 않는 것을 권장한다.</b> 기준 화면은 설정 탭에서 ROI 를 드래그할 때
 *             <b>라이브 캡처</b>로 잡는 것으로 클라이언트와 협의되었다. 이 바로 파일을 등록하면
 *             정답과 촬영의 카메라 위치·각도가 어긋나 ROI 가 엉뚱한 곳을 가리키고,
 *             그것을 보정하려고 ORB 정렬이 켜져 {@code aligning} 상태에 갇힐 수 있다.
 *             <p>설정 화면에 이 바를 붙이지 않으면 라이브 캡처로만 동작한다.
 *             코드는 되돌릴 여지를 남겨 두기 위해 삭제하지 않았다.
 */
@Deprecated
public class ReferenceImageBar extends Composite {

    private final ApxSettings settings = ApxSettings.get();
    private final Button useRefChk;
    private final Text refPathText;
    private final Button refPickBtn;
    private final ApxSettings.Listener settingsListener;

    public ReferenceImageBar(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        useRefChk = new Button(this, SWT.CHECK);
        useRefChk.setText("기준 이미지 사용");
        useRefChk.setSelection(settings.isUseReferenceImage());
        useRefChk.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        useRefChk.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                settings.setUseReferenceImage(useRefChk.getSelection());
                applyUseRefUi();
                msg(useRefChk.getSelection()
                        ? "기준 이미지 모드 ON"
                        : "기준 이미지 모드 OFF  (드래그로 ROI 지정)");
            }
        });

        Label title = new Label(this, SWT.NONE);
        title.setText("기준 이미지 (R 체결 정면)");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        ensureDefaultRefIfMissing();

        Label desc = new Label(this, SWT.WRAP);
        desc.setText("비전/후방 탭에서 비교할 기준 이미지");
        desc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        desc.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        refPathText = new Text(this, SWT.BORDER | SWT.READ_ONLY | SWT.SINGLE);
        refPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        refPickBtn = new Button(this, SWT.PUSH);
        refPickBtn.setText("파일...");
        refPickBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                FileDialog dlg = new FileDialog(getShell(), SWT.OPEN);
                dlg.setFilterExtensions(new String[] { "*.png;*.jpg;*.jpeg;*.bmp" });
                dlg.setFilterNames(new String[] { "이미지" });
                String p = dlg.open();
                if (p != null) {
                    settings.setVisionRefPath(p);
                    refreshPathText();
                    msg("기준 이미지: " + new File(p).getName());
                }
            }
        });

        settingsListener = new ApxSettings.Listener() {
            public void onSettingsChanged(ApxSettings s) {
                if (isDisposed()) {
                    return;
                }
                getDisplay().asyncExec(new Runnable() {
                    public void run() {
                        if (!isDisposed()) {
                            useRefChk.setSelection(settings.isUseReferenceImage());
                            applyUseRefUi();
                            refreshPathText();
                        }
                    }
                });
            }
        };
        settings.addListener(settingsListener);
        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                settings.removeListener(settingsListener);
            }
        });

        applyUseRefUi();
        refreshPathText();
    }


    public void ensureDefaultRefIfMissing() {
        if (settings.getVisionRefPath() == null) {
            settings.setVisionRefPath("png 파일을 선택하세요");
            refreshPathText();
        }
    }

    private void refreshPathText() {
        if (refPathText == null || refPathText.isDisposed()) {
            return;
        }
        String p = settings.getVisionRefPath();
        refPathText.setText(p == null ? "" : new File(p).getName());
    }

    private void applyUseRefUi() {
        boolean on = settings.isUseReferenceImage();
        if (refPickBtn != null && !refPickBtn.isDisposed()) {
            refPickBtn.setEnabled(on);
        }
        if (refPathText != null && !refPathText.isDisposed()) {
            refPathText.setEnabled(on);
        }
    }

    private void msg(String m) {
        // 상태 표시 제거(미니멀)
    }
}
