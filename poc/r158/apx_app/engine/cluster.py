"""
클러스터 팝업 일치 검출 엔진 (순수 로직, UI 없음)
================================================================
Java(OpenCV Java + SSIM 직접구현) 이식 대상.
정렬 : ORB (frame -> 기준영상 좌표계).
판정 : POPUP_ROI 영역을 기대 팝업 템플릿과 NCC/SSIM 비교, max >= 임계.
지연 : 프레임 도착(실측 간격) + 분석 시간.
"""
import time
import cv2
import numpy as np

from .evidence import EvidenceCapture

try:
    from skimage.metrics import structural_similarity as _ssim
except Exception:      # skimage 없으면 NCC만
    _ssim = None

# ---- 상수 (Java 상수로 이식) ----
CW, CH = 640, 640                   # 정면 캔버스(정사각 — 클러스터 이미지가 정사각)
DEFAULT_POPUP_ROI = (207, 368, 226, 406)  # (y1,y2,x1,x2) 드래그 지정 전 기본 팝업 영역
POPUP_SIM = 0.70                    # 팝업 일치 임계
MIN_MATCH = 12
LOCK_INLIERS = 25


def imread_kr(path):
    d = np.fromfile(path, np.uint8)
    return cv2.imdecode(d, cv2.IMREAD_COLOR) if d.size else None


class OrbAligner:
    def __init__(self, ref):
        self.ref = ref
        self.orb = cv2.ORB_create(3000)
        self.kp_ref, self.des_ref = self.orb.detectAndCompute(ref, None)
        self.bf = cv2.BFMatcher(cv2.NORM_HAMMING)

    def homography(self, frame):
        kp, des = self.orb.detectAndCompute(frame, None)
        if des is None or len(kp) < MIN_MATCH:
            return None, 0
        knn = self.bf.knnMatch(self.des_ref, des, k=2)
        good = [m for m, n in knn if m.distance < 0.75 * n.distance]
        if len(good) < MIN_MATCH:
            return None, len(good)
        src = np.float32([self.kp_ref[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
        dst = np.float32([kp[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
        Mh, mask = cv2.findHomography(dst, src, cv2.RANSAC, 3.0)
        if Mh is None:
            return None, len(good)
        return Mh, (int(mask.sum()) if mask is not None else 0)


def homography_angles(M):
    if M is None:
        return None
    a, b = M[0, 0], M[0, 1]
    d, e = M[1, 0], M[1, 1]
    roll = float(np.degrees(np.arctan2(d, a)))
    scale = (float(np.hypot(a, d)) + float(np.hypot(b, e))) / 2.0
    persp = float(np.hypot(M[2, 0], M[2, 1]) * 1000.0)
    return {"roll": roll, "scale": scale, "persp": persp}


def sane_homography(M):
    """완전히 망가진 정렬만 거부 (뒤집힘·붕괴). 강한 원근은 허용."""
    if M is None or not np.all(np.isfinite(M)):
        return False
    det = M[0, 0] * M[1, 1] - M[0, 1] * M[1, 0]
    return det > 0 and 0.01 < det < 100.0


def roi_match(canon, roi, tmpl):
    """드래그로 지정한 고정 영역만 비교: 정렬화면 canon[roi] vs 기준 팝업 크롭(tmpl)의
    NCC/SSIM. 검색·배율보정 없음 — ORB로 같은 좌표계라 위치·크기가 이미 일치.
    반환 (NCC, SSIM)."""
    y1, y2, x1, x2 = roi
    H, W = canon.shape[:2]
    y1, x1 = max(0, y1), max(0, x1)
    y2, x2 = min(H, y2), min(W, x2)
    live = canon[y1:y2, x1:x2]
    if live.size == 0:
        return 0.0, 0.0
    th, tw = tmpl.shape[:2]
    if live.shape[0] != th or live.shape[1] != tw:   # 안전망(경계 잘림 등)
        live = cv2.resize(live, (tw, th))
    res = cv2.matchTemplate(live, tmpl, cv2.TM_CCOEFF_NORMED)   # 동일크기 → 1×1
    ncc = float(res[0, 0])
    sval = 0.0
    if _ssim is not None:
        g1 = cv2.cvtColor(live, cv2.COLOR_BGR2GRAY)
        g2 = cv2.cvtColor(tmpl, cv2.COLOR_BGR2GRAY)
        try:
            sval = float(_ssim(g1, g2))
        except Exception:
            sval = 0.0
    return ncc, sval


class ClusterDetector:
    """클러스터 팝업 검출기 — 정렬 상태 보유, 프레임 처리 → 결과 dict.
    기어 R->D 체크 없이 '팝업만 인식'. (Java 이식 시 구조 유지)"""

    def __init__(self, ref_path, popup_sim=POPUP_SIM):
        # ORB 정렬 기준 = 팝업 뜬 화면. 팝업 템플릿은 이 화면에서 드래그로 크롭(같은 좌표계).
        ref = imread_kr(ref_path)
        if ref is None:
            raise FileNotFoundError(f"{ref_path}")
        self.ref_canon = cv2.resize(ref, (CW, CH))
        self.aligner = OrbAligner(self.ref_canon)
        self.roi = DEFAULT_POPUP_ROI      # 기본값 — 드래그로 팝업 영역 지정
        self._crop_tmpl()
        self.popup_sim = popup_sim
        self.locked_M = None
        self.lock_inliers = None
        self.lock_ang = None
        self._popup_latched = False
        self._pass = None      # 전환 순간 (pass_ms, frame_gap, analysis) 기억
        self._t_prev_arrive = None
        self._ev = EvidenceCapture(before=3, after=3)   # 판단 전후 ±3프레임 스냅샷

    def _crop_tmpl(self):
        """팝업 템플릿 = ORB 기준(팝업 화면)에서 ROI 크롭 → 정렬과 같은 좌표계."""
        y1, y2, x1, x2 = self.roi
        self.tmpl = self.ref_canon[y1:y2, x1:x2].copy()

    def set_roi(self, roi):
        """팝업 영역 지정(드래그) — 기준 화면에서 판정 템플릿을 잘라냄."""
        self.roi = roi
        self._crop_tmpl()

    def reset(self):
        self._popup_latched = False
        self._pass = None      # 전환 순간 (pass_ms, frame_gap, analysis) 기억
        self._ev.reset()

    def get_evidence(self):
        return self._ev.evidence

    def reset_alignment(self):
        self.locked_M = None

    def process(self, frame):
        t_arrive = time.perf_counter()
        gap_ms = (t_arrive - self._t_prev_arrive) * 1000.0 if self._t_prev_arrive else 0.0
        self._t_prev_arrive = t_arrive

        if self.locked_M is None:
            M, inl = self.aligner.homography(frame)
            if M is not None and inl >= LOCK_INLIERS and sane_homography(M):
                self.locked_M = M
                self.lock_inliers = inl
                self.lock_ang = homography_angles(M)

        if self.locked_M is None:
            return {"state": "aligning", "canon": cv2.resize(frame, (CW, CH))}

        canon = cv2.warpPerspective(frame, self.locked_M, (CW, CH))
        self._ev.push(canon.copy(), t_arrive)               # 증거용 링버퍼

        # 판정 = 드래그로 지정한 팝업 영역(roi)만 고정 비교. 라이브 canon[roi] vs 스냅샷 크롭.
        #   ORB로 같은 좌표계라 위치·크기가 이미 일치 → 검색·배율보정 없이 그 영역만 유사도.
        ncc, ssim = roi_match(canon, self.roi, self.tmpl)
        psc = max(ncc, ssim)
        popup_hit = psc >= self.popup_sim
        draw_box = self.roi                                  # 판정 영역 고정 표시

        if popup_hit and not self._popup_latched:     # 팝업 뜨고 기대값 일치 = PASS
            analysis_ms = (time.perf_counter() - t_arrive) * 1000.0
            self._pass = (gap_ms + analysis_ms, gap_ms, analysis_ms)
            self._popup_latched = True
            self._ev.trigger()                              # 판단 순간 → 전후 수집 시작
        else:
            self._ev.step_after(canon.copy(), t_arrive)     # 판단 이후 프레임 채움

        pm = self._pass    # 전환 순간 값을 이후 프레임에도 유지(보고서용)
        return {
            "state": "ok", "canon": canon, "roi": draw_box,
            "ncc": ncc, "ssim": ssim, "psc": psc, "popup_hit": popup_hit,
            "popup_sim": self.popup_sim,
            "frame_gap_ms": pm[1] if pm else gap_ms,
            "analysis_ms": pm[2] if pm else None,
            "pass_ms": pm[0] if pm else None,           # 팝업 등장→PASS 처리 지연
            "lock_inliers": self.lock_inliers, "lock_ang": self.lock_ang,
        }
