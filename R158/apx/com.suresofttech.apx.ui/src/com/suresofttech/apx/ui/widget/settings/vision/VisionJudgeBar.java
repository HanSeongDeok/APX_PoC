package com.suresofttech.apx.ui.widget.settings.vision;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 비전 판정 방식 선택 — 기준영상 대조(NCC) / 학습모델(YOLO). {@link ApxSettings} 연동.
 *
 * <p>고르는 즉시 {@code VisionJudges} 팩토리가 바뀌고, 설정 프리뷰와 측정 세션이
 * 새로 만드는 판정기부터 그 방식을 쓴다. 임계 바는 두 방식에서 그대로 쓰인다
 * (NCC 는 유사도, YOLO 는 확률 — 둘 다 0~1).
 *
 * <p>YOLO 를 고르면 아래 세 값이 필요하다. 학습 결과와 반드시 맞춰야 한다.
 * <ul>
 *   <li><b>모델</b> — {@code best.onnx}</li>
 *   <li><b>입력 크기</b> — 학습 시 {@code imgsz} 와 동일. 다르면 결과가 엉뚱해진다</li>
 *   <li><b>PASS 클래스</b> — 모델의 {@code names} 순서에서 R 의 번호</li>
 * </ul>
 * 값은 {@code tools/yolo-cls/inspect_onnx.py} 로 모델에서 직접 확인할 수 있다.
 */
public class VisionJudgeBar extends Composite {

    private final ApxSettings settings = ApxSettings.get();

    private final Button nccRadio;
    private final Button yoloRadio;
    private final Text modelText;
    private final Button pickBtn;
    private final Spinner inputSizeSpin;
    private final Spinner hitClassSpin;
    private final Label hintLabel;

    /** UI 가 설정을 되받아 다시 쓰는 순환을 막는다. */
    private boolean updating;

    public VisionJudgeBar(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label title = new Label(this, SWT.NONE);
        title.setText("판정 방식");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Composite radios = new Composite(this, SWT.NONE);
        GridLayout rl = new GridLayout(2, false);
        rl.marginWidth = 0;
        rl.marginHeight = 0;
        radios.setLayout(rl);
        radios.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        nccRadio = new Button(radios, SWT.RADIO);
        nccRadio.setText("기준영상 대조 (NCC)");
        yoloRadio = new Button(radios, SWT.RADIO);
        yoloRadio.setText("학습모델 (YOLO)");

        SelectionAdapter onPick = new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (updating || !((Button) e.widget).getSelection()) {
                    return;
                }
                apply();
            }
        };
        nccRadio.addSelectionListener(onPick);
        yoloRadio.addSelectionListener(onPick);

        Label modelLabel = new Label(this, SWT.NONE);
        modelLabel.setText("모델");
        modelText = new Text(this, SWT.BORDER);
        modelText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modelText.addModifyListener(new ModifyListener() {
            public void modifyText(ModifyEvent e) {
                if (!updating) {
                    apply();
                }
            }
        });
        pickBtn = new Button(this, SWT.PUSH);
        pickBtn.setText("찾기…");
        pickBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                FileDialog dlg = new FileDialog(getShell(), SWT.OPEN);
                dlg.setText("YOLO 분류 모델 선택 (.onnx)");
                dlg.setFilterExtensions(new String[] { "*.onnx", "*.*" });
                dlg.setFilterNames(new String[] { "ONNX 모델 (*.onnx)", "모든 파일" });
                String cur = modelText.getText().trim();
                if (cur.length() > 0) {
                    File f = new File(cur);
                    if (f.getParentFile() != null) {
                        dlg.setFilterPath(f.getParent());
                    }
                }
                String sel = dlg.open();
                if (sel != null) {
                    modelText.setText(sel);   // ModifyListener 가 apply() 한다
                }
            }
        });

        Label sizeLabel = new Label(this, SWT.NONE);
        sizeLabel.setText("입력 크기");
        inputSizeSpin = new Spinner(this, SWT.BORDER);
        inputSizeSpin.setValues(settings.getYoloInputSize(), 32, 1024, 0, 32, 32);
        inputSizeSpin.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        inputSizeSpin.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (!updating) {
                    apply();
                }
            }
        });
        Label sizeHint = new Label(this, SWT.NONE);
        sizeHint.setText("학습 imgsz 와 동일");
        sizeHint.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));

        Label clsLabel = new Label(this, SWT.NONE);
        clsLabel.setText("PASS 클래스");
        hitClassSpin = new Spinner(this, SWT.BORDER);
        hitClassSpin.setValues(settings.getYoloHitClassId(), 0, 99, 0, 1, 1);
        hitClassSpin.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        hitClassSpin.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (!updating) {
                    apply();
                }
            }
        });
        Label clsHint = new Label(this, SWT.NONE);
        clsHint.setText("모델 names 순서의 R 번호");
        clsHint.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));

        hintLabel = new Label(this, SWT.WRAP);
        hintLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));
        hintLabel.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));

        final ApxSettings.Listener listener = new ApxSettings.Listener() {
            public void onSettingsChanged(final ApxSettings s) {
                if (isDisposed()) {
                    return;
                }
                getDisplay().asyncExec(new Runnable() {
                    public void run() {
                        if (!isDisposed()) {
                            reload();
                        }
                    }
                });
            }
        };
        settings.addListener(listener);
        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                settings.removeListener(listener);
            }
        });

        reload();
    }

    /** UI → 설정. 라디오/경로/숫자 중 무엇이 바뀌든 여기로 모인다. */
    private void apply() {
        String judge = yoloRadio.getSelection() ? ApxSettings.JUDGE_YOLO : ApxSettings.JUDGE_NCC;
        String path = modelText.getText().trim();
        settings.setVisionJudge(judge, path.length() == 0 ? null : path,
                inputSizeSpin.getSelection(), hitClassSpin.getSelection());
        refreshEnabled();
    }

    /** 설정 → UI. 다른 화면에서 바뀐 값도 따라온다. */
    private void reload() {
        updating = true;
        try {
            boolean yolo = settings.isYoloJudge();
            nccRadio.setSelection(!yolo);
            yoloRadio.setSelection(yolo);
            String path = settings.getYoloModelPath();
            String cur = (path == null) ? "" : path;
            if (!cur.equals(modelText.getText())) {
                modelText.setText(cur);
            }
            inputSizeSpin.setSelection(settings.getYoloInputSize());
            hitClassSpin.setSelection(settings.getYoloHitClassId());
        } finally {
            updating = false;
        }
        refreshEnabled();
    }

    private void refreshEnabled() {
        boolean yolo = yoloRadio.getSelection();
        modelText.setEnabled(yolo);
        pickBtn.setEnabled(yolo);
        inputSizeSpin.setEnabled(yolo);
        hitClassSpin.setEnabled(yolo);
        hintLabel.setText(statusText(yolo));
        layout(true, true);
    }

    private String statusText(boolean yolo) {
        if (!yolo) {
            return "기준 이미지와 픽셀을 비교한다. 유사도가 임계 이상이면 PASS.";
        }
        String path = modelText.getText().trim();
        if (path.length() == 0) {
            return "모델(.onnx)을 지정하세요. 없으면 판정이 비활성됩니다.";
        }
        if (!new File(path).isFile()) {
            return "모델 파일이 없습니다: " + path;
        }
        return "학습 모델이 확률을 내고, 임계 이상이면 PASS. 기준 이미지는 쓰지 않는다.";
    }
}
