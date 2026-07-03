"""
각도 변동 대응 PoC — "세션 시작 1회 원근 보정 → 실시간 매칭"

시나리오: 물리 고정 불가, 테스트(세션)마다 웹캠 각도가 바뀜 (세션 내에선 고정).
검증: 세션 시작 시 화면 4모서리로 1회 원근 보정(rectify)하면
      각도가 달라도 기존 템플릿 매칭이 그대로 동작함을 수치로 입증.

각 세션 각도에 대해:
  - 보정 X: 기울어진 웹캠 영상에 바로 템플릿 매칭   (깨짐 예상)
  - 보정 O: 세션 시작 시 정면으로 펴고 템플릿 매칭   (복원 예상)
"""
import cv2
import numpy as np

# ---- 파라미터 ----
W, H = 640, 360          # 클러스터(화면) 정면 해상도
FPS = 30
N = 150                  # 5초
POPUP_FRAME = 60         # t=2.0s 에 팝업
CANVAS = (820, 560)      # 웹캠이 보는 전체 프레임(화면+주변)
THRESH = 0.7             # 팝업 등장 판정 임계 유사도
SCREEN_OFFSET = (90, 80) # 캔버스 내 화면 좌상단 기준 위치
POPUP_ROI = (230, 320, 180, 460)  # (y1,y2,x1,x2) 정면 기준 팝업 영역


def draw_cluster(i, popup_on):
    img = np.full((H, W, 3), 18, np.uint8)
    cv2.circle(img, (140, 180), 90, (60, 60, 60), 3)
    cv2.circle(img, (500, 180), 90, (60, 60, 60), 3)
    ang = (i * 7) % 360
    nx = int(140 + 70 * np.cos(np.radians(ang)))
    ny = int(180 + 70 * np.sin(np.radians(ang)))
    cv2.line(img, (140, 180), (nx, ny), (0, 200, 255), 3)
    cv2.putText(img, "R", (305, 120), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 220, 0), 3)
    if popup_on:
        cv2.rectangle(img, (180, 230), (460, 320), (0, 0, 200), -1)
        cv2.rectangle(img, (180, 230), (460, 320), (0, 0, 255), 3)
        cv2.putText(img, "! WARNING", (205, 270), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
        cv2.putText(img, "REAR OBJECT", (205, 305), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
    return img


def make_template():
    f = draw_cluster(POPUP_FRAME, True)
    y1, y2, x1, x2 = POPUP_ROI
    return f[y1:y2, x1:x2].copy()


def session_corners(tilt):
    """세션별 웹캠 각도를 모사한 화면 4모서리(캔버스 좌표).
    tilt=0 이면 정면, 커질수록 사다리꼴(원근 기울기)."""
    ox, oy = SCREEN_OFFSET
    t = tilt
    # 정면 사각형
    tl = [ox, oy]
    tr = [ox + W, oy]
    br = [ox + W, oy + H]
    bl = [ox, oy + H]
    # 원근 기울기: 위쪽을 좁히고 한쪽으로 시프트 (카메라가 비스듬히 봄)
    tl = [ox + int(W * 0.10 * t), oy + int(H * 0.06 * t)]
    tr = [ox + W - int(W * 0.04 * t), oy + int(H * 0.14 * t)]
    br = [ox + W - int(W * 0.12 * t), oy + H - int(H * 0.05 * t)]
    bl = [ox + int(W * 0.03 * t), oy + H - int(H * 0.10 * t)]
    return np.float32([tl, tr, br, bl])


def warp_to_canvas(frame, corners):
    src = np.float32([[0, 0], [W, 0], [W, H], [0, H]])
    M = cv2.getPerspectiveTransform(src, corners)
    canvas = np.full((CANVAS[1], CANVAS[0], 3), 8, np.uint8)
    warped = cv2.warpPerspective(frame, M, CANVAS, dst=canvas, borderMode=cv2.BORDER_TRANSPARENT)
    return warped


def rectify(canvas, corners):
    """세션 시작 시 잡은 4모서리로 정면(W x H) 복원."""
    dst = np.float32([[0, 0], [W, 0], [W, H], [0, H]])
    Minv = cv2.getPerspectiveTransform(corners, dst)
    return cv2.warpPerspective(canvas, Minv, (W, H))


def detect(frames, template, thresh=THRESH):
    """프레임 시퀀스에서 팝업 등장 프레임/최대유사도."""
    onset = None
    best = 0.0
    for idx, fr in enumerate(frames):
        if fr.shape[0] < template.shape[0] or fr.shape[1] < template.shape[1]:
            continue
        res = cv2.matchTemplate(fr, template, cv2.TM_CCOEFF_NORMED)
        s = float(res.max())
        best = max(best, s)
        if onset is None and s >= thresh:
            onset = idx
    return onset, best


def main():
    template = make_template()
    sessions = {"세션1 (정면 tilt=0)": 0.0,
                "세션2 (약간 tilt=1.0)": 1.0,
                "세션3 (심하게 tilt=2.0)": 2.0}

    print("=" * 72)
    print(" 각도 변동 PoC : 세션 시작 1회 보정 효과 (팝업 정답 frame =", POPUP_FRAME, ")")
    print("=" * 72)
    print(f"{'세션':24}{'보정X onset/score':>22}{'보정O onset/score':>22}")
    print("-" * 72)

    for name, tilt in sessions.items():
        corners = session_corners(tilt)
        # 웹캠이 보는 영상(각 프레임 원근 왜곡)
        cam_frames = [warp_to_canvas(draw_cluster(i, i >= POPUP_FRAME), corners)
                      for i in range(N)]

        # (보정 X) 왜곡 영상에 바로 매칭
        on_raw, sc_raw = detect(cam_frames, template)
        # (보정 O) 세션 시작 시 잡은 corners 로 매 프레임 정면 복원 후 매칭
        rect_frames = [rectify(f, corners) for f in cam_frames]
        on_rec, sc_rec = detect(rect_frames, template)

        def fmt(on, sc):
            return (f"{on}f/{sc:.2f}" if on is not None else f"미검출/{sc:.2f}")
        print(f"{name:24}{fmt(on_raw, sc_raw):>22}{fmt(on_rec, sc_rec):>22}")

    print("-" * 72)
    print("해석: 보정X는 tilt 커질수록 유사도 급락→미검출(각도에 깨짐).")
    print("      보정O는 모든 세션에서 정답 frame 검출(각도 무관). 세션당 보정 1회면 충분.")
    print("=" * 72)


if __name__ == "__main__":
    main()
