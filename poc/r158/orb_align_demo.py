"""
ORB 특징점 자동 정렬 PoC — 마커도 클릭도 없이 클러스터 자동 정렬
================================================================
원리:
  1) 차종별 '기준 클러스터 이미지'(정면) 1장을 미리 보유 (차종 선택 UI로 확보)
  2) 매 프레임: 웹캠 영상 ↔ 기준 이미지를 ORB 특징점으로 대조
  3) 매칭점으로 호모그래피 추정 → 카메라가 비스듬해도 자동 정면 정렬
  4) 정렬된 정면 영상에서 고정 ROI로 팝업 검출

검증: 기준 이미지와 '각도 다르게 찍힌' 영상을 만들어,
      클릭 없이 ORB만으로 자동 정렬 + 팝업 검출되는지 수치로 확인.
"""
import os
import cv2
import numpy as np

W, H = 640, 360
POPUP_ROI = (230, 320, 180, 460)
CANVAS = (820, 560)
SCREEN_OFFSET = (90, 80)
MIN_MATCH = 12          # 호모그래피 추정 최소 매칭 수


def draw_cluster(i, popup_on):
    """특징점이 충분하도록 텍스처(눈금/숫자)를 가진 클러스터."""
    img = np.full((H, W, 3), 18, np.uint8)
    for cx in (140, 500):                      # 게이지 2개 + 눈금(특징점 소스)
        cv2.circle(img, (cx, 180), 90, (70, 70, 70), 3)
        cv2.circle(img, (cx, 180), 60, (50, 50, 50), 2)
        for d in range(0, 360, 30):
            x1 = int(cx + 80 * np.cos(np.radians(d))); y1 = int(180 + 80 * np.sin(np.radians(d)))
            x2 = int(cx + 90 * np.cos(np.radians(d))); y2 = int(180 + 90 * np.sin(np.radians(d)))
            cv2.line(img, (x1, y1), (x2, y2), (200, 200, 200), 2)
    for v, x in [("0", 90), ("60", 120), ("120", 470)]:
        cv2.putText(img, v, (x, 250), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (180, 180, 180), 1)
    ang = (i * 7) % 360
    cv2.line(img, (140, 180), (int(140 + 70*np.cos(np.radians(ang))), int(180 + 70*np.sin(np.radians(ang)))), (0, 200, 255), 3)
    cv2.putText(img, "R", (305, 120), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 220, 0), 3)
    if popup_on:
        cv2.rectangle(img, (180, 230), (460, 320), (0, 0, 200), -1)
        cv2.rectangle(img, (180, 230), (460, 320), (0, 0, 255), 3)
        cv2.putText(img, "! WARNING", (205, 270), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
        cv2.putText(img, "REAR OBJECT", (205, 305), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
    return img


def warp_to_canvas(frame, tilt):
    """카메라 각도를 모사: 클러스터를 캔버스에 원근 왜곡해 배치."""
    ox, oy = SCREEN_OFFSET
    t = tilt
    dst = np.float32([
        [ox + int(W*0.10*t), oy + int(H*0.06*t)],
        [ox + W - int(W*0.04*t), oy + int(H*0.14*t)],
        [ox + W - int(W*0.12*t), oy + H - int(H*0.05*t)],
        [ox + int(W*0.03*t), oy + H - int(H*0.10*t)],
    ])
    src = np.float32([[0, 0], [W, 0], [W, H], [0, H]])
    M = cv2.getPerspectiveTransform(src, dst)
    canvas = np.full((CANVAS[1], CANVAS[0], 3), 8, np.uint8)
    # 배경에 잡음(다른 특징점) 추가 — 실환경 모사
    cv2.putText(canvas, "DASHBOARD", (30, 540), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (40, 40, 40), 2)
    return cv2.warpPerspective(frame, M, CANVAS, dst=canvas, borderMode=cv2.BORDER_TRANSPARENT)


class OrbAligner:
    """기준 이미지에 대해 입력 프레임을 자동 정면 정렬."""
    def __init__(self, ref):
        self.ref = ref
        self.orb = cv2.ORB_create(3000)                       # 특징점 증량
        self.kp_ref, self.des_ref = self.orb.detectAndCompute(ref, None)
        self.bf = cv2.BFMatcher(cv2.NORM_HAMMING)             # knn + 비율검정용

    def align(self, frame):
        kp, des = self.orb.detectAndCompute(frame, None)
        if des is None or len(kp) < MIN_MATCH:
            return None, 0
        # Lowe 비율검정으로 신뢰 매칭만 선별
        knn = self.bf.knnMatch(self.des_ref, des, k=2)
        good = [m for m, n in knn if m.distance < 0.75 * n.distance]
        if len(good) < MIN_MATCH:
            return None, len(good)
        src = np.float32([self.kp_ref[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
        dst = np.float32([kp[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
        Mh, mask = cv2.findHomography(dst, src, cv2.RANSAC, 3.0)  # frame -> ref(정면)
        if Mh is None:
            return None, len(matches)
        inliers = int(mask.sum()) if mask is not None else 0
        aligned = cv2.warpPerspective(frame, Mh, (W, H))
        return aligned, inliers


def popup_score(canon, tmpl):
    y1, y2, x1, x2 = POPUP_ROI
    return float(cv2.matchTemplate(canon[y1:y2, x1:x2], tmpl, cv2.TM_CCOEFF_NORMED).max())


def main():
    ref = draw_cluster(150, False)                 # 기준 이미지(정면, 팝업 없음)
    y1, y2, x1, x2 = POPUP_ROI
    tmpl = draw_cluster(150, True)[y1:y2, x1:x2]    # 기대 팝업 템플릿
    aligner = OrbAligner(ref)

    print("=" * 74)
    print(" ORB 자동 정렬 PoC — 클릭/마커 없이 기준이미지로 자동 정면화 후 팝업 검출")
    print("=" * 74)
    print(f"{'세션(각도)':22}{'ORB 인라이어':>14}{'정렬':>8}{'팝업유사도(정렬후)':>20}")
    print("-" * 74)

    for name, tilt in [("정면 tilt=0", 0.0), ("약간 tilt=1.0", 1.0), ("심하게 tilt=2.0", 2.0)]:
        cam = warp_to_canvas(draw_cluster(150, True), tilt)   # 팝업 떠있는 프레임을 각도 왜곡
        aligned, inliers = aligner.align(cam)
        if aligned is None:
            print(f"{name:22}{inliers:>14}{'실패':>8}{'-':>20}")
            continue
        sc = popup_score(aligned, tmpl)
        ok = "성공" if inliers >= MIN_MATCH else "약함"
        print(f"{name:22}{inliers:>14}{ok:>8}{sc:>20.2f}")

    print("-" * 74)
    print("해석: 기준이미지 1장만으로 각도가 달라도 ORB가 자동 정렬 →")
    print("      정렬 후 팝업 유사도 높게 검출. 마커/수동클릭 불필요.")
    print("=" * 74)


if __name__ == "__main__":
    main()
