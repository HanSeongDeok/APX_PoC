package com.suresofttech.apx.core.rear;

/**
 * 후방 검증 포인트의 판정 상태. SWT無(core).
 *
 * <p>지정(selected)과는 별개 층 - 지정은 "어느 포인트를 검증하나", 판정은 "그 결과가 뭔가".
 * 색 매핑(예): NONE=지정색(빨강) / MEASURING=노랑 / PASS=초록 / FAIL=진빨강.
 */
public enum Verdict {
    NONE,        // 판정 없음(지정색 그대로)
    MEASURING,   // 측정 중
    PASS,        // 합격
    FAIL         // 불합격
}
