package com.suresofttech.apx.core.rear;

import java.awt.Point;

/**
 * 후방 검증 포인트 판정 결과 — 위치 + 판정. SWT無(core).
 * TC/DB 개념은 클라 영역. 위치는 {@link #getPoint()} ({@code x=col}, {@code y=row}).
 */
public final class VerdictResult {

    private final int col;
    private final int row;
    private final Verdict verdict;

    public VerdictResult(int col, int row, Verdict verdict) {
        this.col = col;
        this.row = row;
        this.verdict = (verdict != null) ? verdict : Verdict.NONE;
    }

    /** Point 편의 생성자. (java.awt.Point: x=col, y=row) */
    public VerdictResult(Point p, Verdict verdict) {
        this(p.x, p.y, verdict);
    }

    /** 위치. x=col, y=row. */
    public Point getPoint() {
        return new Point(col, row);
    }

    public Verdict getVerdict() {
        return verdict;
    }
}
