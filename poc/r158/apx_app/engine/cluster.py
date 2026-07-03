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

try:
    from skimage.metrics import structural_similarity as _ssim
except Exception:      # skimage 없으면 NCC만
    _ssim = None

# ---- 상수 (Java 상수로 이식) ----
CW, CH = 640, 640                   # 정면 캔버스(정사각 — 클러스터 이미지가 정사각)
POPUP_ROI = (207, 368, 226, 406)    # (y1,y2,x1,x2) 후방카메라 팝업 영역
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


def popup_scores(canon, tmpl):
    """POPUP_ROI 영역을 기대 팝업 템플릿과 비교 (NCC, SSIM) 분리 반환."""
    y1, y2, x1, x2 = POPUP_ROI
    roi = canon[y1:y2, x1:x2]
    if roi.shape[0] < 3 or roi.shape[1] < 3:
        return 0.0, 0.0
    t = tmpl if tmpl.shape[:2] == roi.shape[:2] else cv2.resize(tmpl, (roi.shape[1], roi.shape[0]))
    ncc = float(cv2.matchTemplate(roi, t, cv2.TM_CCOEFF_NORMED).max())
    sval = 0.0
    if _ssim is not None:
        g1 = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        g2 = cv2.cvtColor(t, cv2.COLOR_BGR2GRAY)
        try:
            sval = float(_ssim(g1, g2))
        except Exception:
            sval = 0.0
    return ncc, sval


class ClusterDetector:
    """클러스터 팝업 검출기 — 정렬 상태 보유, 프레임 처리 → 결과 dict.
    기어 R->D 체크 없이 '팝업만 인식'. (Java 이식 시 구조 유지)"""

    def __init__(self, ref_path, popup_ref_path, popup_sim=POPUP_SIM):
        ref = imread_kr(ref_path)
        popup_ref = imread_kr(popup_ref_path)
        if ref is None or popup_ref is None:
            raise FileNotFoundError(f"{ref_path} / {popup_ref_path}")
        self.ref_canon = cv2.resize(ref, (CW, CH))
        self.aligner = OrbAligner(self.ref_canon)
        y1, y2, x1, x2 = POPUP_ROI
        self.tmpl = cv2.resize(popup_ref, (CW, CH))[y1:y2, x1:x2].copy()
        self.popup_sim = popup_sim
        self.locked_M = None
        self.lock_inliers = None
        self.lock_ang = None
        self._popup_latched = False
        self._t_prev_arrive = None

    def reset(self):
        self._popup_latched = False

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
        ncc, ssim = popup_scores(canon, self.tmpl)
        psc = max(ncc, ssim)
        popup_hit = psc >= self.popup_sim

        pass_ms = analysis_ms = None
        if popup_hit and not self._popup_latched:     # 팝업 뜬 첫 프레임
            analysis_ms = (time.perf_counter() - t_arrive) * 1000.0
            pass_ms = gap_ms + analysis_ms
            self._popup_latched = True

        return {
            "state": "ok", "canon": canon, "roi": POPUP_ROI,
            "ncc": ncc, "ssim": ssim, "psc": psc, "popup_hit": popup_hit,
            "popup_sim": self.popup_sim,
            "frame_gap_ms": gap_ms, "analysis_ms": analysis_ms, "pass_ms": pass_ms,
            "lock_inliers": self.lock_inliers, "lock_ang": self.lock_ang,
        }
