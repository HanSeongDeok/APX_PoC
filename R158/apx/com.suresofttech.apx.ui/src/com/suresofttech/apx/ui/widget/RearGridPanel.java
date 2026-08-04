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
import org.eclipse.swt.widgets.Spinner;

import com.suresofttech.apx.core.rear.RearGrid;
import com.suresofttech.apx.core.rear.VerdictResult;

/**
 * 후방 검증 완제품 패널 — 컨트롤바(크기 지정 방식 선택 + 범례 토글) + {@link RearGridCanvas}.
 * <b>워크벤치(ViewPart) 없이도</b> 아무 SWT 컨테이너에 한 줄로 붙는다: {@code new RearGridPanel(parent)}.
 *
 * <p>크기 지정은 사용자가 <b>프리셋 콤보</b>(4×6/8×12/10×14)와 <b>커스텀 입력</b>(열×행 스피너) 중
 * 선택 가능(라디오). 모든 TC 포인트 통합 표시는 {@link #showAll(List)}.
 *
 * <p>재사용 구조([[plugin-library-promotion]]): 모델 {@link RearGrid}(core, SWT無) + 위젯
 * {@link RearGridCanvas} + 이 조립 패널.
 */
public class RearGridPanel extends Composite {

    /** 고정 격자 크기 프리셋 {열(cols), 행(rows)}. */
    private static final int[][] PRESETS = { { 4, 6 }, { 8, 12 }, { 10, 14 } };

    /** 차량 후방 이미지 — PoC 자산 경로 유지 (`poc/r158/expected`). */
    private static final String CAR_DIR = "c:/DEV/apx/poc/r158/expected/";
    private static final String[] DEFAULT_CAR_CANDIDATES = {
            CAR_DIR + "car_rear_white.png",
            CAR_DIR + "차량 후방 레이아웃.png",
            CAR_DIR + "car_topview.png",
    };

    private final RearGridCanvas canvas;
    private final Button presetRadio;
    private final Button customRadio;
    private final Combo sizeCombo;
    private final Spinner colSpin;
    private final Spinner rowSpin;

    public RearGridPanel(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new GridLayout(1, false));

        // ── 상단 컨트롤 바 ──
        Composite bar = new Composite(this, SWT.NONE);
        bar.setLayout(new GridLayout(9, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(bar, SWT.NONE).setText("격자 크기");

        // 방식 선택(라디오): 프리셋 vs 커스텀 — 사용자 선택
        presetRadio = new Button(bar, SWT.RADIO);
        presetRadio.setText("콤보박스");
        presetRadio.setSelection(true);
        customRadio = new Button(bar, SWT.RADIO);
        customRadio.setText("커스텀");

        // 프리셋 콤보
        sizeCombo = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (int[] p : PRESETS) {
            sizeCombo.add(p[0] + " x " + p[1]);   // 열 x 행
        }
        sizeCombo.select(0);

        // 커스텀 입력: 열 x 행 스피너
        colSpin = mkSpin(bar, PRESETS[0][0]);
        new Label(bar, SWT.NONE).setText("x");
        rowSpin = mkSpin(bar, PRESETS[0][1]);

        // 범례 토글
        final Button legendChk = new Button(bar, SWT.CHECK);
        legendChk.setText("범례");
        legendChk.setSelection(true);

        // ── 격자 캔버스 (첫 프리셋으로 시작) ──
        canvas = new RearGridCanvas(this, new RearGrid(PRESETS[0][0], PRESETS[0][1]));
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // ── 리스너 ──
        SelectionAdapter modeChange = new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                applyMode();
            }
        };
        presetRadio.addSelectionListener(modeChange);
        customRadio.addSelectionListener(modeChange);
        sizeCombo.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (presetRadio.getSelection()) {
                    applyPreset();
                }
            }
        });
        SelectionAdapter customChange = new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (customRadio.getSelection()) {
                    applyCustom();
                }
            }
        };
        colSpin.addSelectionListener(customChange);
        rowSpin.addSelectionListener(customChange);
        legendChk.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                canvas.setShowLegend(legendChk.getSelection());
            }
        });
        applyMode();   // 초기 활성/비활성 세팅

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

    private Spinner mkSpin(Composite parent, int val) {
        Spinner s = new Spinner(parent, SWT.BORDER);
        s.setMinimum(1);      // RearGrid.MIN_DIM
        s.setMaximum(60);     // RearGrid.MAX_DIM
        s.setSelection(val);
        return s;
    }

    /** 프리셋/커스텀 라디오에 따라 해당 컨트롤만 활성화하고 그 크기로 적용. */
    private void applyMode() {
        boolean custom = customRadio.getSelection();
        sizeCombo.setEnabled(!custom);
        colSpin.setEnabled(custom);
        rowSpin.setEnabled(custom);
        if (custom) {
            applyCustom();
        } else {
            applyPreset();
        }
    }

    private void applyPreset() {
        int[] p = PRESETS[Math.max(0, sizeCombo.getSelectionIndex())];
        canvas.setGrid(new RearGrid(p[0], p[1]));
    }

    private void applyCustom() {
        canvas.setGrid(new RearGrid(colSpin.getSelection(), rowSpin.getSelection()));
    }

    /** 내부 캔버스(외부에서 판정색 반영·리스너 연결 등). */
    public RearGridCanvas getCanvas() {
        return canvas;
    }

    /** 현재 격자 모델(지정 포인트 저장용). */
    public RearGrid getGrid() {
        return canvas.getGrid();
    }

    /** <b>모든 TC 포인트 한 번에 출력</b> — 여러 TC 결과를 현재 격자에 통합 표시(→ {@link RearGridCanvas#setVerdicts}). */
    public void showAll(List<VerdictResult> results) {
        canvas.setVerdicts(results);
    }

    /** TC 복원 — 저장된 격자 크기·지정 포인트로 화면 재현. 프리셋과 일치하면 콤보, 아니면 커스텀으로 표시. */
    public void restore(int cols, int rows, List<int[]> points) {
        RearGrid g = new RearGrid(cols, rows);
        g.selectPoints(points);
        canvas.setGrid(g);
        int idx = presetIndex(g.getCols(), g.getRows());
        if (idx >= 0) {
            presetRadio.setSelection(true);
            customRadio.setSelection(false);
            sizeCombo.select(idx);
        } else {
            presetRadio.setSelection(false);
            customRadio.setSelection(true);
            colSpin.setSelection(g.getCols());
            rowSpin.setSelection(g.getRows());
        }
        sizeCombo.setEnabled(idx >= 0);
        colSpin.setEnabled(idx < 0);
        rowSpin.setEnabled(idx < 0);
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
