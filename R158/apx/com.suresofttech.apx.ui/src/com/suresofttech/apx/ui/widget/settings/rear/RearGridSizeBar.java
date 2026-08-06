package com.suresofttech.apx.ui.widget.settings.rear;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.rear.RearGrid;

/**
 * 후방 격자 크기 — 프리셋 라디오+콤보 / 커스텀 스피너 → {@link ApxSettings} + 연결 캔버스.
 * Select 클릭도 {@link #setCanvas} 시 {@code ApxSettings.rearSelectedPoints}에 동기화한다.
 * 모드에 따라 프리셋·커스텀 편집 UI를 같은 자리에 하나만 표시한다.
 */
public class RearGridSizeBar extends Composite {

    /** 클라이언트 주입 라벨·프리셋 — 기본값 유지, 필요한 것만 덮어쓴다. */
    public static final class Cfg {
        public String presetText = "프리셋";
        public String customText = "커스텀";
        public String sizeLabelText = "격자 크기";
        public String colsLabelText = "가로(열)";
        public String rowsLabelText = "세로(행)";
        public String applyText = "적용";
        /** 고정크기(프리셋) 목록 {열,행}. null이면 기본 4x6·8x12·10x14·9x7. */
        public int[][] presets;
    }

    private static final int[][] DEFAULT_PRESETS = { { 4, 6 }, { 8, 12 }, { 10, 14 }, { 9, 7 } };

    private final ApxSettings settings = ApxSettings.get();
    private final Cfg cfg;
    /** 프리셋(고정크기) 목록 — cfg.presets 또는 기본값. */
    private final int[][] presets;
    private final Button presetRadio;
    private final Button customRadio;
    private final Composite editorHost;
    private final StackLayout editorStack;
    private final Composite presetRow;
    private final Composite customRow;
    private final Combo presetCombo;
    private final Spinner colSpin;
    private final Spinner rowSpin;

    private RearGridCanvas canvas;
    private boolean syncing;

    public RearGridSizeBar(Composite parent) {
        this(parent, new Cfg());
    }

    public RearGridSizeBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        this.cfg = (cfg != null) ? cfg : new Cfg();
        this.presets = (this.cfg.presets != null && this.cfg.presets.length > 0)
                ? this.cfg.presets : DEFAULT_PRESETS;
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite modeRow = new Composite(this, SWT.NONE);
        modeRow.setLayout(new GridLayout(2, false));
        modeRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        presetRadio = new Button(modeRow, SWT.RADIO);
        presetRadio.setText(this.cfg.presetText);
        customRadio = new Button(modeRow, SWT.RADIO);
        customRadio.setText(this.cfg.customText);

        // 프리셋·커스텀 편집 UI — 동일 위치, 하나만 표시
        editorHost = new Composite(this, SWT.NONE);
        editorStack = new StackLayout();
        editorHost.setLayout(editorStack);
        editorHost.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        presetRow = new Composite(editorHost, SWT.NONE);
        presetRow.setLayout(new GridLayout(2, false));
        new Label(presetRow, SWT.NONE).setText(this.cfg.sizeLabelText);
        presetCombo = new Combo(presetRow, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (int i = 0; i < presets.length; i++) {
            presetCombo.add(presets[i][0] + " x " + presets[i][1]);
        }

        customRow = new Composite(editorHost, SWT.NONE);
        customRow.setLayout(new GridLayout(5, false));
        new Label(customRow, SWT.NONE).setText(this.cfg.colsLabelText);
        colSpin = new Spinner(customRow, SWT.BORDER);
        colSpin.setValues(4, RearGrid.MIN_DIM, RearGrid.MAX_DIM, 0, 1, 5);
        new Label(customRow, SWT.NONE).setText(this.cfg.rowsLabelText);
        rowSpin = new Spinner(customRow, SWT.BORDER);
        rowSpin.setValues(6, RearGrid.MIN_DIM, RearGrid.MAX_DIM, 0, 1, 5);
        Button apply = new Button(customRow, SWT.PUSH);
        apply.setText(this.cfg.applyText);

        SelectionAdapter modeListener = new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (syncing) {
                    return;
                }
                updateModeUi();
                if (presetRadio.getSelection()) {
                    applyPresetSelection();
                } else {
                    applyCustom();
                }
            }
        };
        presetRadio.addSelectionListener(modeListener);
        customRadio.addSelectionListener(modeListener);
        presetCombo.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (!syncing && presetRadio.getSelection()) {
                    applyPresetSelection();
                }
            }
        });
        apply.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (!syncing) {
                    applyCustom();
                }
            }
        });

        loadFromSettings();
    }

    /** 캔버스 연결 — 크기 변경·Select 클릭을 Settings와 동기화. */
    public void setCanvas(RearGridCanvas canvas) {
        this.canvas = canvas;
        if (canvas == null) {
            return;
        }
        applyGridToCanvas();
        canvas.setOnChange(new Runnable() {
            public void run() {
                if (canvas == null || canvas.isDisposed()) {
                    return;
                }
                RearGrid g = canvas.getGrid();
                if (g != null) {
                    settings.setRearSelectedPoints(g.selectedPoints());
                }
            }
        });
    }

    public RearGridCanvas getCanvas() {
        return canvas;
    }

    private void loadFromSettings() {
        syncing = true;
        try {
            boolean custom = ApxSettings.REAR_MODE_CUSTOM.equals(settings.getRearSizeMode());
            presetRadio.setSelection(!custom);
            customRadio.setSelection(custom);
            int cols = settings.getRearCols();
            int rows = settings.getRearRows();
            colSpin.setSelection(cols);
            rowSpin.setSelection(rows);
            int idx = indexOfPreset(cols, rows);
            presetCombo.select(idx >= 0 ? idx : 0);
            updateModeUi();
        } finally {
            syncing = false;
        }
    }

    /** 선택 모드에 맞는 편집 UI만 같은 자리에 표시. */
    private void updateModeUi() {
        boolean custom = customRadio.getSelection();
        editorStack.topControl = custom ? customRow : presetRow;
        editorHost.layout();
        layout(true, true);
        Composite p = getParent();
        if (p != null && !p.isDisposed()) {
            p.layout(true, true);
        }
    }

    private void applyPresetSelection() {
        int i = presetCombo.getSelectionIndex();
        if (i < 0) {
            i = 0;
            presetCombo.select(0);
        }
        int cols = presets[i][0];
        int rows = presets[i][1];
        colSpin.setSelection(cols);
        rowSpin.setSelection(rows);
        settings.setRearGridSize(cols, rows, ApxSettings.REAR_MODE_PRESET);
        applyGridToCanvas();
    }

    private void applyCustom() {
        int cols = colSpin.getSelection();
        int rows = rowSpin.getSelection();
        settings.setRearGridSize(cols, rows, ApxSettings.REAR_MODE_CUSTOM);
        applyGridToCanvas();
    }

    /** Settings 크기·Select를 캔버스에 반영 (크기 변경 시 Settings가 포인트를 비움). */
    private void applyGridToCanvas() {
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        RearGrid g = new RearGrid(settings.getRearCols(), settings.getRearRows());
        g.selectPoints(settings.getRearSelectedPoints());
        canvas.setGrid(g);
    }

    private int indexOfPreset(int cols, int rows) {
        for (int i = 0; i < presets.length; i++) {
            if (presets[i][0] == cols && presets[i][1] == rows) {
                return i;
            }
        }
        return -1;
    }
}
