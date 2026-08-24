package com.suresofttech.apx.core.rear;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 후방 검증 포인트 격자 모델 - SWT/RCP 무의존(core). 다른 제품/RCP에서 그대로 재사용.
 *
 * <p>차량 후방 영역을 {@code cols × rows} 격자로 나누고, 사용자가 <b>검증 포인트</b>를 지정(선택)한다.
 * 판정(PASS/FAIL) 개념은 없다 - 이 모델은 "어느 셀이 지정되었나"만 보관한다. 시각화(UI)는 이
 * 모델만 읽어 그리고, 클라이언트는 {@link #selectedPoints()} 로 지정 포인트를 저장했다가
 * 나중에 TC 를 열 때 {@link #selectPoints(List)} 로 격자 / 포인트를 그대로 복원한다.
 *
 * <p>셀 인덱스는 (c, r): c=열(0..cols-1, 좌→우), r=행(0..rows-1, 위→아래). 내부 저장은 행 우선.
 */
public final class RearGrid {

    public static final int MIN_DIM = 1;
    public static final int MAX_DIM = 60;

    private int cols;
    private int rows;
    private boolean[] sel;   // 행 우선: idx = r * cols + c, true=지정 포인트

    public RearGrid(int cols, int rows) {
        reSize(cols, rows);
    }

    /** 격자 크기 변경(사용자 가로×세로 입력). 값은 [MIN_DIM, MAX_DIM]로 클램프. 지정은 전부 해제. */
    public void reSize(int cols, int rows) {
        this.cols = clamp(cols);
        this.rows = clamp(rows);
        this.sel = new boolean[this.cols * this.rows];
    }

    private static int clamp(int v) {
        return Math.max(MIN_DIM, Math.min(MAX_DIM, v));
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public int cellCount() {
        return cols * rows;
    }

    private boolean inRange(int c, int r) {
        return c >= 0 && c < cols && r >= 0 && r < rows;
    }

    /** 셀이 지정 포인트인가. */
    public boolean isSelected(int c, int r) {
        return inRange(c, r) && sel[r * cols + c];
    }

    /** 셀이 지정 포인트인가 (java.awt.Point: x=col, y=row). */
    public boolean isSelected(Point p) {
        return p != null && isSelected(p.x, p.y);
    }

    /** 셀 지정/해제 직접 지정. */
    public void setSelected(int c, int r, boolean selected) {
        if (inRange(c, r)) {
            sel[r * cols + c] = selected;
        }
    }

    /** 셀 지정 토글(클릭용) - 지정↔해제. */
    public void toggle(int c, int r) {
        if (inRange(c, r)) {
            sel[r * cols + c] = !sel[r * cols + c];
        }
    }

    /**
     * 단일 선택 - 다른 지정 모두 지우고 {@code p} 하나만 지정. 한 번에 하나만 찍는 모드.
     * 이미 그 셀만 지정돼 있었으면 해제(다시 클릭 = 취소). null / 범위 밖이면 변화 없음.
     * (java.awt.Point: x=col, y=row) - 저장 복원 / 클릭 지정 공용.
     */
    public void selectSingle(Point p) {
        if (p == null || !inRange(p.x, p.y)) {
            return;
        }
        boolean was = sel[p.y * cols + p.x];
        Arrays.fill(sel, false);
        if (!was) {
            sel[p.y * cols + p.x] = true;
        }
    }

    /** 전체 지정 해제(크기 유지). */
    public void clearAll() {
        Arrays.fill(sel, false);
    }

    /** 지정 포인트 개수. */
    public int selectedCount() {
        int n = 0;
        for (boolean b : sel) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /**
     * 지정 포인트 목록 - 저장/전달용. 각 원소는 {@code int[]{col, row}} (행 우선 순서).
     * 클라이언트는 이 목록과 {@link #getCols()}/{@link #getRows()} 를 저장했다가 복원한다.
     */
    public List<int[]> selectedPoints() {
        List<int[]> out = new ArrayList<int[]>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (sel[r * cols + c]) {
                    out.add(new int[] { c, r });
                }
            }
        }
        return out;
    }

    /**
     * 지정 포인트(단일) 조회 - 지정된 첫 포인트를 {@link Point} 로. 없으면 {@code null}.
     * 한 번에 하나만 지정하는 모드({@link #selectSingle})의 저장용.
     */
    public Point getSelectedPoint() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (sel[r * cols + c]) {
                    return new Point(c, r);
                }
            }
        }
        return null;
    }

    /**
     * 지정 포인트 복원 - 현재 지정을 모두 지우고 {@code points}({col,row})를 적용.
     * 범위 밖 좌표는 무시. TC 재현({@link #reSize} 로 크기 맞춘 뒤 호출).
     */
    public void selectPoints(List<int[]> points) {
        Arrays.fill(sel, false);
        if (points == null) {
            return;
        }
        for (int[] p : points) {
            if (p != null && p.length >= 2 && inRange(p[0], p[1])) {
                sel[p[1] * cols + p[0]] = true;
            }
        }
    }
}
