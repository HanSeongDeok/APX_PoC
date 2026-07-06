"""
기어 R단 검출 엔진 (순수 로직, UI 없음)
================================================================
Java(Eclipse RCP + OpenCV Java) 이식 대상 — cv2 창/HUD/argparse 없음.
위치찾기: 기준영상 채도(색무관) → R 앵커 → 국소 Otsu 블롭 4칸.
판정   : 라이브 밝기 상대비교 (R칸이 4칸 중 최대 + margin).
지연   : 프레임 도착(실측 간격) + 분석 시간.
"""
import time
import cv2
import numpy as np

from .evidence import EvidenceCapture

# ---- 상수 (Java 상수로 그대로 이식) ----
GC = 640            # 정면 캔버스 크기(정사각)
MIN_MATCH = 12
VTH = 190           # 하이라이트로 볼 밝기(Value) 하한
STRIP = 22          # 클릭/앵커 x 주변 반폭(px)
YWIN = 95           # 앵커 y 주변 반높이(px)
CELL = 13           # 칸 반쪽 크기(px)
GEAR_ORDER = ["P", "R", "N", "D"]
LOCK_INLIERS = 25   # ORB 정렬 고정 임계


def imread_kr(path):
    """한글 경로 대응 imread."""
    d = np.fromfile(path, np.uint8)
    return cv2.imdecode(d, cv2.IMREAD_COLOR) if d.size else None


class Aligner:
    """ORB 특징점 정렬 (frame -> 기준영상 좌표계 homography)."""

    def __init__(self, ref):
        self.orb = cv2.ORB_create(3000)
        self.kp, self.des = self.orb.detectAndCompute(ref, None)
        self.bf = cv2.BFMatcher(cv2.NORM_HAMMING)

    def homography(self, frame):
        kp, des = self.orb.detectAndCompute(frame, None)
        if des is None or len(kp) < MIN_MATCH:
            return None, 0
        knn = self.bf.knnMatch(self.des, des, k=2)
        good = [m for m, n in knn if m.distance < 0.75 * n.distance]
        if len(good) < MIN_MATCH:
            return None, len(good)
        src = np.float32([self.kp[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
        dst = np.float32([kp[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
        M, mask = cv2.findHomography(dst, src, cv2.RANSAC, 3.0)
        if M is None:
            return None, len(good)
        return M, (int(mask.sum()) if mask is not None else 0)


def homography_angles(M):
    """정렬 지표: roll(좌우기울기°), perspective(비스듬함), scale(줌)."""
    if M is None:
        return None
    a, b = M[0, 0], M[0, 1]
    d, e = M[1, 0], M[1, 1]
    roll = float(np.degrees(np.arctan2(d, a)))
    scale = (float(np.hypot(a, d)) + float(np.hypot(b, e))) / 2.0
    persp = float(np.hypot(M[2, 0], M[2, 1]) * 1000.0)
    return {"roll": roll, "scale": scale, "persp": persp}


def brightpix(bgr):
    """칸 안 밝은픽셀 비율(0~1) — V>VTH."""
    if bgr.shape[0] < 3 or bgr.shape[1] < 3:
        return 0.0
    v = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)[:, :, 2]
    return float((v > VTH).mean())


def calibrate_from_click(canon, click):
    """앵커(x,y) 주변 국소 Otsu → 글자칸 4개 + R 인덱스 반환."""
    cx, cy = click
    H, W = canon.shape[:2]
    x0, x1 = max(0, cx - STRIP), min(W, cx + STRIP)
    y0, y1 = max(0, cy - YWIN), min(H, cy + YWIN)
    strip = canon[y0:y1, x0:x1]
    g = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    th = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]
    n, _, stats, cent = cv2.connectedComponentsWithStats(th)
    cells = []
    for i in range(1, n):
        a = stats[i, cv2.CC_STAT_AREA]
        bw, bh = stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]
        if 15 <= a <= 400 and bw <= 40 and bh <= 40:
            cells.append((int(cent[i][0]) + x0, int(cent[i][1]) + y0))
    if not cells:
        return None, None
    cells.sort(key=lambda c: c[1])
    ci = min(range(len(cells)), key=lambda i: abs(cells[i][1] - cy))
    start = max(0, min(ci - 1, len(cells) - 4)) if len(cells) >= 4 else 0
    picked = cells[start:start + 4] if len(cells) >= 4 else cells
    r_idx = min(range(len(picked)),
                key=lambda i: (picked[i][0] - cx) ** 2 + (picked[i][1] - cy) ** 2)
    return picked, r_idx


def find_R_on_ref(ref_canon):
    """기준영상에서 '가장 큰 채도 높은 글자 덩어리'=R (색조 무관)."""
    hsv = cv2.cvtColor(ref_canon, cv2.COLOR_BGR2HSV)
    S, V = hsv[:, :, 1], hsv[:, :, 2]
    colorful = ((S > 60) & (V > 80)).astype(np.uint8) * 255
    n, _, stats, cent = cv2.connectedComponentsWithStats(colorful)
    best, best_area = None, 0
    for i in range(1, n):
        a = stats[i, cv2.CC_STAT_AREA]
        w, h = stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]
        if 40 <= a <= 1200 and w <= 60 and h <= 60 and a > best_area:
            best_area, best = a, (int(cent[i][0]), int(cent[i][1]))
    return best


def calibrate_from_ref(ref_canon):
    """기준영상 R 자동검출 + 4칸 확정."""
    rc = find_R_on_ref(ref_canon)
    if rc is None:
        return None, None
    return calibrate_from_click(ref_canon, rc)


def ratios(canon, cells):
    """4칸 각각 밝은픽셀 비율."""
    out = []
    for (x, y) in cells:
        roi = canon[max(0, y - CELL):y + CELL, max(0, x - CELL):x + CELL]
        out.append(brightpix(roi))
    return out


class GearDetector:
    """기어 R 검출기 — 상태(정렬/칸/margin) 보유, 프레임 처리 → 결과 dict.
    UI는 이 결과만 그리면 됨. (Java 이식 시 이 클래스 구조 그대로)"""

    def __init__(self, ref_path, margin=0.02):
        ref = imread_kr(ref_path)
        if ref is None:
            raise FileNotFoundError(ref_path)
        self.ref_canon = cv2.resize(ref, (GC, GC))
        self.aligner = Aligner(self.ref_canon)
        self.margin = margin
        self.cells, self.r_idx = calibrate_from_ref(self.ref_canon)
        self.locked_M = None
        self.lock_inliers = None
        self.lock_ang = None
        self._prev_is_R = False
        self._pass = None      # 전환 순간 (pass_ms, frame_gap, analysis) 기억
        self._t_prev_arrive = None
        self._ev = EvidenceCapture(before=3, after=3)   # 판단 전후 ±3프레임 스냅샷

    def recalibrate_auto(self):
        self.cells, self.r_idx = calibrate_from_ref(self.ref_canon)
        return self.cells is not None

    def recalibrate_click(self, canon_xy):
        """정렬된(canon) 좌표에서 R 글자 클릭 → 수동 보정."""
        c, r = calibrate_from_click(self._last_canon, canon_xy) \
            if self._last_canon is not None else (None, None)
        if c:
            self.cells, self.r_idx = c, r
        return c is not None

    def reset_alignment(self):
        self.locked_M = None

    def reset_judgment(self):
        """다음 not-R -> R 전환을 새로 측정."""
        self._prev_is_R = False
        self._pass = None
        self._ev.reset()

    def get_evidence(self):
        return self._ev.evidence

    _last_canon = None

    def process(self, frame):
        """한 프레임 처리 → 결과 dict. 프레임 도착 시각 기준 지연 측정."""
        t_arrive = time.perf_counter()
        gap_ms = (t_arrive - self._t_prev_arrive) * 1000.0 if self._t_prev_arrive else 0.0
        self._t_prev_arrive = t_arrive

        if self.locked_M is None:
            M, inl = self.aligner.homography(frame)
            if M is not None and inl >= LOCK_INLIERS:
                self.locked_M = M
                self.lock_inliers = inl
                self.lock_ang = homography_angles(M)

        if self.locked_M is None:
            return {"state": "aligning", "canon": cv2.resize(frame, (GC, GC))}

        canon = cv2.warpPerspective(frame, self.locked_M, (GC, GC))
        self._last_canon = canon
        if self.cells is None:
            return {"state": "need_calib", "canon": canon}

        self._ev.push(canon.copy(), t_arrive)     # 증거용 링버퍼
        rt = ratios(canon, self.cells)
        order = sorted(range(len(rt)), key=lambda i: rt[i], reverse=True)
        top, second = order[0], (order[1] if len(order) > 1 else order[0])
        r_ratio = rt[self.r_idx]
        r_gap = rt[self.r_idx] - rt[second]
        is_R = (top == self.r_idx) and (r_gap >= self.margin)

        if is_R and not self._prev_is_R:          # not-R -> R 전환 프레임
            analysis_ms = (time.perf_counter() - t_arrive) * 1000.0
            self._pass = (gap_ms + analysis_ms, gap_ms, analysis_ms)
            self._ev.trigger()                    # 판단 순간 → 전후 수집 시작
        else:
            self._ev.step_after(canon.copy(), t_arrive)
        self._prev_is_R = is_R

        pm = self._pass    # 전환 순간 값을 이후 프레임에도 유지(보고서용)
        return {
            "state": "ok", "canon": canon, "cells": self.cells, "r_idx": self.r_idx,
            "ratios": rt, "is_R": is_R, "r_ratio": r_ratio, "r_gap": r_gap,
            "second_ratio": rt[second], "margin": self.margin,
            "frame_gap_ms": pm[1] if pm else gap_ms,
            "analysis_ms": pm[2] if pm else None,
            "pass_ms": pm[0] if pm else None,
            "lock_inliers": self.lock_inliers, "lock_ang": self.lock_ang,
        }
