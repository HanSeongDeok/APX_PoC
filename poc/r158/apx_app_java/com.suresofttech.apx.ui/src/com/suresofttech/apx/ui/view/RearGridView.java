package com.suresofttech.apx.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.rear.RearGrid;
import com.suresofttech.apx.ui.widget.RearGridCanvas;
import com.suresofttech.apx.ui.widget.RearGridPanel;

/**
 * ⑥ 후방 검증 결과 시각화 View — {@link RearGridPanel} 을 감싼 얇은 RCP 래퍼.
 * 실제 기능(컨트롤바 + 차량 그림 + 포인트 판)은 모두 패널이 담당하며, 이 클래스는
 * 워크벤치(Eclipse RCP)에서 "통째로 얹기" 위한 껍데기다.
 *
 * <p>워크벤치가 없는 곳(다이얼로그·순수 SWT)에서는 이 View 대신 {@link RearGridPanel} 을 직접 쓴다.
 */
public class RearGridView extends ViewPart {

    private RearGridPanel panel;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        panel = new RearGridPanel(parent);
        panel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    /** 지정 포인트 조회용 API — 클라이언트가 테스트 시 저장(격자 크기 + 이 목록). */
    public RearGrid getGrid() {
        return panel != null ? panel.getGrid() : null;
    }

    /** 격자 캔버스 접근(외부에서 결과 반영·판정색 등). */
    public RearGridCanvas getCanvas() {
        return panel != null ? panel.getCanvas() : null;
    }

    /** TC 복원 — 저장된 격자 크기·지정 포인트로 화면 재현. */
    public void restore(int cols, int rows, java.util.List<int[]> points) {
        if (panel != null) {
            panel.restore(cols, rows, points);
        }
    }

    @Override
    public void setFocus() {
        if (panel != null && !panel.isDisposed()) {
            panel.setFocus();
        }
    }
}
