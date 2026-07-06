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
DEFAULT_POPUP_ROI = (207, 368, 226, 406)  # (y1,y2,x1,x2) 자동검출 실패 시 폴백
POPUP_SIM = 0.70                    # 팝업 일치 임계
MIN_MATCH = 12
LOCK_INLIERS = 25
# 팝업 자동검출 밴드(좌우 원형게이지 제외, 중앙 정보/카메라 영역) — 캔버스 비율
POPUP_BAND = (0.28, 0.62, 0.34, 0.66)     # (y1,y2,x1,x2) 비율
DIFF_THR = 40                       # 두 기준영상 픽셀차 임계


def auto_popup_roi(base_canon, popup_canon):
    """일반/팝업 두 기준영상의 차이로 팝업 네모(ROI)를 자동 산출.
    좌우 원형게이지(속도·rpm) 변화에 안 속게 중앙 밴드로 제한한 뒤,
    변화 픽셀의 경계상자를 ROI로 삼는다. 실패 시 None."""
    h, w = base_canon.shape[:2]
    d = cv2.absdiff(base_canon, popup_canon).sum(2)
    by1, by2, bx1, bx2 = (int(h * POPUP_BAND[0]), int(h * POPUP_BAND[1]),
                          int(w * POPUP_BAND[2]), int(w * POPUP_BAND[3]))
    mask = (d > DIFF_THR)
    band = mask[by1:by2, bx1:bx2]
    ys, xs = band.nonzero()
    if len(xs) < 50:
        return None
    import numpy as np
    x1 = int(np.percentile(xs, 1)) + bx1
    x2 = int(np.percentile(xs, 99)) + bx1
    y1 = int(np.percentile(ys, 1)) + by1
    y2 = int(np.percentile(ys, 99)) + by1
    p = 4
    return (max(0, y1 - p), min(h, y2 + p), max(0, x1 - p), min(w, x2 + p))


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


def popup_scores(canon, tmpl):
    """팝업 템플릿을 화면 전체에서 검색(matchTemplate) → 위치 무관하게 최적 위치를 찾고
    그 위치의 (NCC, SSIM, 찾은박스) 반환. 팝업이 중앙이 아니어도 자동 추적."""
    th, tw = tmpl.shape[:2]
    H, W = canon.shape[:2]
    if th < 3 or tw < 3 or th > H or tw > W:
        return 0.0, 0.0, None
    res = cv2.matchTemplate(canon, tmpl, cv2.TM_CCOEFF_NORMED)
    _, ncc, _, loc = cv2.minMaxLoc(res)      # 최고 일치 위치
    x, y = loc
    box = (y, y + th, x, x + tw)
    sval = 0.0
    if _ssim is not None:
        g1 = cv2.cvtColor(canon[y:y + th, x:x + tw], cv2.COLOR_BGR2GRAY)
        g2 = cv2.cvtColor(tmpl, cv2.COLOR_BGR2GRAY)
        try:
            sval = float(_ssim(g1, g2))
        except Exception:
            sval = 0.0
    return float(ncc), sval, box


class ClusterDetector:
    """클러스터 팝업 검출기 — 정렬 상태 보유, 프레임 처리 → 결과 dict.
    기어 R->D 체크 없이 '팝업만 인식'. (Java 이식 시 구조 유지)"""

    def __init__(self, ref_path, popup_ref_path, popup_sim=POPUP_SIM):
        ref = imread_kr(ref_path)
        popup_ref = imread_kr(popup_ref_path)
        if ref is None or popup_ref is None:
            raise FileNotFoundError(f"{ref_path} / {popup_ref_path}")
        self.ref_canon = cv2.resize(ref, (CW, CH))
        self.popup_canon = cv2.resize(popup_ref, (CW, CH))   # 드래그 지정용 보관
        self.aligner = OrbAligner(self.ref_canon)
        # 두 기준영상 차이로 팝업 ROI 자동 산출 (실패 시 폴백 상수)
        auto = auto_popup_roi(self.ref_canon, self.popup_canon)
        self.roi_auto = auto is not None
        self.roi = auto or DEFAULT_POPUP_ROI
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
        y1, y2, x1, x2 = self.roi
        self.tmpl = self.popup_canon[y1:y2, x1:x2].copy()

    def set_roi(self, roi):
        """팝업 ROI 수동 지정(드래그) — 자동 산출 오버라이드."""
        self.roi = roi
        self.roi_auto = False
        self._crop_tmpl()

    def set_template(self, crop_bgr):
        """팝업만 잘라둔 이미지를 템플릿으로 직접 지정(파일 미리 추가).
        검색은 화면 전체이므로 위치·중앙여부 무관. self.roi는 표시용 기본값."""
        self.tmpl = crop_bgr.copy()
        self.roi_auto = False
        th, tw = crop_bgr.shape[:2]
        cy, cx = CH // 2, CW // 2         # 팝업 없을 때 표시용(중앙 기본)
        self.roi = (max(0, cy - th // 2), min(CH, cy + th // 2),
                    max(0, cx - tw // 2), min(CW, cx + tw // 2))

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

        # 판정 = 사용자가 지정한 팝업(경고창) 이미지와 일치하면 PASS. (기어 R/Not-R 검출 안 함)
        ncc, ssim, found = popup_scores(canon, self.tmpl)   # 화면 전체 검색
        psc = max(ncc, ssim)
        popup_hit = psc >= self.popup_sim
        draw_box = found if (popup_hit and found is not None) else self.roi

        if popup_hit and not self._popup_latched:     # 팝업 뜨고 기대값 일치 = PASS
            analysis_ms = (time.perf_counter() - t_arrive) * 1000.0
            self._pass = (gap_ms + analysis_ms, gap_ms, analysis_ms)
            self._popup_latched = True
            self._ev.trigger()                              # 판단 순간 → 전후 수집 시작
        else:
            self._ev.step_after(canon.copy(), t_arrive)     # 판단 이후 프레임 채움

        pm = self._pass    # 전환 순간 값을 이후 프레임에도 유지(보고서용)
        return {
            "state": "ok", "canon": canon, "roi": draw_box, "roi_auto": self.roi_auto,
            "ncc": ncc, "ssim": ssim, "psc": psc, "popup_hit": popup_hit,
            "popup_sim": self.popup_sim,
            "frame_gap_ms": pm[1] if pm else gap_ms,
            "analysis_ms": pm[2] if pm else None,
            "pass_ms": pm[0] if pm else None,           # 팝업 등장→PASS 처리 지연
            "lock_inliers": self.lock_inliers, "lock_ang": self.lock_ang,
        }
