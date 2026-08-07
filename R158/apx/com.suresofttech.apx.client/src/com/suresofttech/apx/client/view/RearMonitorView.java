package com.suresofttech.apx.client.view;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.measure.MeasureConfigSnapshot;
import com.suresofttech.apx.core.measure.MeasureSession;
import com.suresofttech.apx.core.rear.RearGrid;
import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.rear.VerdictResult;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;
import com.suresofttech.apx.ui.widget.settings.rear.RearLegendBar;

/**
 * 후방 모니터 — 설정과 동일 범례({@link RearGridCanvas#applyDefaultLegend} +
 * {@link ApxSettings#isRearShowLegend}).
 * 측정 중 Select는 MEASURING만. PASS/FAIL은 중단 시 클라 {@link #setVerdicts}.
 */
public class RearMonitorView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.rearMonitor";

    private RearLegendBar legendBar;
    private RearGridCanvas canvas;
    private RearGrid grid;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        RearLegendBar.Cfg legendCfg = new RearLegendBar.Cfg();
        legendCfg.legendText = "상태 범례";
        legendCfg.bindToSettings = true; // 설정 탭과 ApxSettings.rearShowLegend 동기
        legendBar = new RearLegendBar(parent, legendCfg);

        grid = new RearGrid(4, 6);
        canvas = new RearGridCanvas(parent, grid);
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        canvas.setInteractive(false);
        canvas.loadDefaultCarImage();
        canvas.applyDefaultLegend();
        legendBar.setCanvas(canvas);

        applyFromSettings();
    }

    /** 설정 확인 후 — 격자·Select·범례(설정과 동일). */
    public void applyFromSettings() {
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        if (MeasureSession.get().isRunning()) {
            return;
        }
        ApxSettings s = ApxSettings.get();
        grid = new RearGrid(s.getRearCols(), s.getRearRows());
        List<int[]> pts = s.getRearSelectedPoints();
        if (pts != null && !pts.isEmpty()) {
            grid.selectPoints(pts);
        }
        canvas.setGrid(grid);
        canvas.setInteractive(false);
        canvas.clearVerdicts();
        canvas.applyDefaultLegend();
        canvas.setShowLegend(s.isRearShowLegend());
        if (legendBar != null && !legendBar.isDisposed()) {
            legendBar.setCanvas(canvas); // 체크 상태도 settings와 재동기
        }
    }

    /** 측정 시작 — 스냅샷 격자·Select, Select 포인트는 MEASURING. */
    public void onMeasureStarted(MeasureSession session) {
        MeasureConfigSnapshot snap = session == null ? null : session.getSnapshot();
        if (canvas == null || canvas.isDisposed() || snap == null) {
            return;
        }
        grid = new RearGrid(snap.rearCols, snap.rearRows);
        List<int[]> pts = snap.rearSelectedPoints;
        if (pts != null && !pts.isEmpty()) {
            grid.selectPoints(pts);
        }
        canvas.setGrid(grid);
        canvas.setInteractive(false);
        canvas.clearVerdicts();
        canvas.applyDefaultLegend();
        canvas.setShowLegend(snap.rearShowLegend);
        if (legendBar != null && !legendBar.isDisposed()) {
            legendBar.setCanvas(canvas);
        }
        // 중단 전까지 무조건 MEASURING
        syncVerdictsFromSession(session);
    }

    public void onMeasureStopped() {
        // keep last PASS/FAIL frame
    }

    public void syncVerdictsFromSession(MeasureSession session) {
        if (canvas == null || canvas.isDisposed() || session == null || grid == null) {
            return;
        }
        List<int[]> pts = grid.selectedPoints();
        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i);
            Verdict v = session.getRearVerdict(p[0], p[1]);
            canvas.setCellVerdict(p[0], p[1], v);
        }
        canvas.redraw();
    }

    /**
     * 측정 중단 시 클라 최종 판정 — PASS/FAIL 색 반영.
     * ResultView·증거 스냅샷은 이 호출 이후 캡처한다.
     */
    public void setVerdicts(List<VerdictResult> results) {
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        canvas.setVerdicts(results);
        MeasureSession session = MeasureSession.get();
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                VerdictResult r = results.get(i);
                if (r == null) {
                    continue;
                }
                session.setRearVerdict(r.getPoint().x, r.getPoint().y, r.getVerdict());
            }
        }
    }

    /** Select 포인트에 동일 판정 일괄 적용 편의. */
    public void setVerdictsAllSelected(Verdict verdict) {
        if (grid == null || verdict == null) {
            return;
        }
        List<int[]> pts = grid.selectedPoints();
        List<VerdictResult> list = new ArrayList<VerdictResult>();
        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i);
            list.add(new VerdictResult(p[0], p[1], verdict));
        }
        setVerdicts(list);
    }

    public RearGridCanvas getCanvas() {
        return canvas;
    }

    public byte[] capturePng() {
        return canvas == null || canvas.isDisposed() ? null : canvas.capturePng();
    }

    @Override
    public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setFocus();
        }
    }
}
