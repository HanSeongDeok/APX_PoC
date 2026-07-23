package com.suresofttech.apx.ui.widget;

import java.io.File;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.rear.RearGrid;

/**
 * 후방 검증 완제품 패널 — 컨트롤바(고정 크기 콤보 + 범례 토글) + {@link RearGridCanvas}.
 * <b>워크벤치(ViewPart) 없이도</b> 아무 SWT 컨테이너(다이얼로그·기존 View·Shell)에 한 줄로 붙는다:
 * {@code new RearGridPanel(parent)}.
 *
 * <p>재사용 구조([[plugin-library-promotion]]): 모델 {@link RearGrid}(core, SWT無) + 위젯
 * {@link RearGridCanvas} + 이 조립 패널.
 */
public class RearGridPanel extends Composite {

    /** 고정 격자 크기 프리셋 {열(cols), 행(rows)}. */
    private static final int[][] PRESETS = { { 4, 6 }, { 8, 12 }, { 10, 14 } };

    private static final String CAR_DIR = "c:/DEV/apx/poc/r158/expected/";
    private static final String[] DEFAULT_CAR_CANDIDATES = {
            CAR_DIR + "car_rear_white.png",
            CAR_DIR + "차량 후방 레이아웃.png",
            CAR_DIR + "car_topview.png",
    };

    private final RearGridCanvas canvas;
    private final Combo sizeCombo;

    public RearGridPanel(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new GridLayout(1, false));

        // ── 상단 컨트롤 바 ──
        Composite bar = new Composite(this, SWT.NONE);
        bar.setLayout(new GridLayout(3, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(bar, SWT.NONE).setText("격자 크기");
        sizeCombo = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (int[] p : PRESETS) {
            sizeCombo.add(p[0] + " x " + p[1]);   // 열 x 행
        }
        sizeCombo.select(0);
        sizeCombo.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                int[] p = PRESETS[sizeCombo.getSelectionIndex()];
                canvas.setGrid(new RearGrid(p[0], p[1]));
            }
        });

        final Button legendChk = new Button(bar, SWT.CHECK);
        legendChk.setText("범례");
        legendChk.setSelection(true);
        legendChk.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                canvas.setShowLegend(legendChk.getSelection());
            }
        });

        // ── 격자 캔버스 (첫 프리셋으로 시작) ──
        canvas = new RearGridCanvas(this, new RearGrid(PRESETS[0][0], PRESETS[0][1]));
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // 기본 차량 그림(후보 중 먼저 존재하는 것)
        for (String path : DEFAULT_CAR_CANDIDATES) {
            try {
                File f = new File(path);
                if (f.isFile()) {
                    canvas.setCarImage(new Image(canvas.getDisplay(), f.getAbsolutePath()));
                    break;
                }
            } catch (Exception ex) {
                // 다음 후보
            }
        }
    }

    /** 내부 캔버스(외부에서 판정색 반영·리스너 연결 등). */
    public RearGridCanvas getCanvas() {
        return canvas;
    }

    /** 현재 격자 모델(지정 포인트 저장용). */
    public RearGrid getGrid() {
        return canvas.getGrid();
    }

    /** TC 복원 — 저장된 격자 크기·지정 포인트로 화면 재현. 프리셋과 일치하면 콤보도 갱신. */
    public void restore(int cols, int rows, List<int[]> points) {
        RearGrid g = new RearGrid(cols, rows);
        g.selectPoints(points);
        canvas.setGrid(g);
        int idx = presetIndex(g.getCols(), g.getRows());
        if (idx >= 0) {
            sizeCombo.select(idx);
        } else {
            sizeCombo.deselectAll();
        }
    }

    private static int presetIndex(int cols, int rows) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i][0] == cols && PRESETS[i][1] == rows) {
                return i;
            }
        }
        return -1;
    }
}
