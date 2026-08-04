package com.suresofttech.apx.ui.widget;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.rear.RearGrid;

/**
 * 후방 격자 고정 크기 프리셋 콤보 (선택 위젯).
 * 항목 선택 시 즉시 {@link RearGridCanvas#setGrid}.
 */
public class RearGridPresetCombo extends Composite {

    /** 기본 프리셋 {열(cols), 행(rows)}. */
    private static final int[][] DEFAULT_PRESETS = { { 4, 6 }, { 8, 12 }, { 10, 14 }, { 9, 7 } };

    private final RearGridCanvas target;
    private final Combo combo;
    private final int[][] presets;

    public RearGridPresetCombo(Composite parent, RearGridCanvas target) {
        super(parent, SWT.NONE);
        if (target == null) {
            throw new IllegalArgumentException("target");
        }
        this.target = target;
        this.presets = DEFAULT_PRESETS;
        setLayout(new GridLayout(2, false));

        new Label(this, SWT.NONE).setText("격자 크기");
        combo = new Combo(this, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (int[] p : presets) {
            combo.add(p[0] + " x " + p[1]);
        }

        int idx = indexOf(target.getGrid());
        combo.select(idx >= 0 ? idx : 0);
        if (idx < 0) {
            applyIndex(0);
        }

        combo.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                int i = combo.getSelectionIndex();
                if (i >= 0) {
                    applyIndex(i);
                }
            }
        });
    }

    private void applyIndex(int i) {
        int[] p = presets[i];
        target.setGrid(new RearGrid(p[0], p[1]));
    }

    private int indexOf(RearGrid g) {
        if (g == null) {
            return -1;
        }
        int c = g.getCols();
        int r = g.getRows();
        for (int i = 0; i < presets.length; i++) {
            if (presets[i][0] == c && presets[i][1] == r) {
                return i;
            }
        }
        return -1;
    }
}
