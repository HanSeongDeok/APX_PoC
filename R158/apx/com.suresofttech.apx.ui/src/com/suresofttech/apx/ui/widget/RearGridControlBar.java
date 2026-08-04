package com.suresofttech.apx.ui.widget;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;

import com.suresofttech.apx.core.rear.RearGrid;

/**
 * 후방 격자 커스텀 크기 컨트롤바 (선택 위젯).
 * 가로·세로 Spinner + 격자 생성 / 지정 해제 / 범례.
 */
public class RearGridControlBar extends Composite {

    private final RearGridCanvas target;
    private final Spinner colSpin;
    private final Spinner rowSpin;
    private final Button legendChk;

    public RearGridControlBar(Composite parent, RearGridCanvas target) {
        super(parent, SWT.NONE);
        if (target == null) {
            throw new IllegalArgumentException("target");
        }
        this.target = target;
        setLayout(new GridLayout(8, false));

        RearGrid g = target.getGrid();
        int cols = (g != null) ? g.getCols() : 9;
        int rows = (g != null) ? g.getRows() : 7;

        new Label(this, SWT.NONE).setText("가로(열)");
        colSpin = new Spinner(this, SWT.BORDER);
        colSpin.setValues(cols, RearGrid.MIN_DIM, RearGrid.MAX_DIM, 0, 1, 5);

        new Label(this, SWT.NONE).setText("세로(행)");
        rowSpin = new Spinner(this, SWT.BORDER);
        rowSpin.setValues(rows, RearGrid.MIN_DIM, RearGrid.MAX_DIM, 0, 1, 5);

        Button make = new Button(this, SWT.PUSH);
        make.setText("격자 생성");
        make.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                apply();
            }
        });

        Button clear = new Button(this, SWT.PUSH);
        clear.setText("지정 해제");
        clear.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                clearSelection();
            }
        });

        legendChk = new Button(this, SWT.CHECK);
        legendChk.setText("범례");
        legendChk.setSelection(true);
        legendChk.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                setShowLegend(legendChk.getSelection());
            }
        });
    }

    /** 스피너 값으로 격자 생성 ({@code setGrid}). */
    public void apply() {
        target.setGrid(new RearGrid(colSpin.getSelection(), rowSpin.getSelection()));
    }

    /** 지정 포인트 전부 해제. */
    public void clearSelection() {
        RearGrid g = target.getGrid();
        if (g != null) {
            g.clearAll();
            target.redraw();
        }
    }

    /** 범례 표시 on/off. */
    public void setShowLegend(boolean on) {
        if (legendChk != null && !legendChk.isDisposed()) {
            legendChk.setSelection(on);
        }
        target.setShowLegend(on);
    }
}
