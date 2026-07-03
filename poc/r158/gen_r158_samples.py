"""
UN R158 (PDW) 시나리오 샘플 생성기
- 차량 PDW가 거리 판단 → 우리는 'R단 변속 후 경고가 제때/일치하게 떴는가'만 검증
- 정답을 아는 통제 샘플로 3가지 시나리오 생성:
    PASS        : 0.6초 이내 + 30ms 이내 동기
    FAIL_LATE   : 경고가 0.6초 초과 (지연)
    FAIL_DESYNC : 팝업-경고음 동기 오차 30ms 초과

각 시나리오 폴더에 cluster.mp4 / warning.wav / can_log.json 생성.
공통 기대값(expected/): 기대 팝업 템플릿, 기대 경고음 주파수.

영상 FPS=100 (분해능 10ms) — 30ms 판정을 의미있게 보기 위함.
실제 웹캠은 30/60fps라 분해능 한계가 있으며, 이는 별도 보정/보간 이슈(티켓 A).
"""
import os
import json
import cv2
import numpy as np
from scipy.io import wavfile

HERE = os.path.dirname(__file__)
W, H = 640, 360
FPS = 100
DUR = 3.0
SR = 48000
BEEP_FREQ = 2000
BEEP_DUR = 0.30
POPUP_ROI = (230, 320, 180, 460)  # y1,y2,x1,x2

# 시나리오: (T0 R단변속, 팝업시각, 경고음시각, 경고CAN시각)
SCENARIOS = {
    "PASS":        dict(t0=1.000, popup=1.450, beep=1.455, warn_can=1.450),
    "FAIL_LATE":   dict(t0=1.000, popup=1.680, beep=1.685, warn_can=1.680),
    "FAIL_DESYNC": dict(t0=1.000, popup=1.450, beep=1.520, warn_can=1.450),
}


def draw_cluster(i, popup_on, gear):
    img = np.full((H, W, 3), 18, np.uint8)
    # 게이지 2개 + 눈금/숫자 (ORB 특징점 소스)
    for cx in (140, 500):
        cv2.circle(img, (cx, 180), 90, (70, 70, 70), 3)
        cv2.circle(img, (cx, 180), 60, (50, 50, 50), 2)
        for d in range(0, 360, 30):
            x1 = int(cx + 80 * np.cos(np.radians(d))); y1 = int(180 + 80 * np.sin(np.radians(d)))
            x2 = int(cx + 90 * np.cos(np.radians(d))); y2 = int(180 + 90 * np.sin(np.radians(d)))
            cv2.line(img, (x1, y1), (x2, y2), (200, 200, 200), 2)
    for v, x in [("0", 90), ("60", 120), ("120", 470)]:
        cv2.putText(img, v, (x, 250), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (180, 180, 180), 1)
    ang = (i * 7) % 360
    nx = int(140 + 70 * np.cos(np.radians(ang)))
    ny = int(180 + 70 * np.sin(np.radians(ang)))
    cv2.line(img, (140, 180), (nx, ny), (0, 200, 255), 3)
    cv2.putText(img, gear, (300, 120), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 220, 0), 3)
    if popup_on:
        cv2.rectangle(img, (180, 230), (460, 320), (0, 0, 200), -1)
        cv2.rectangle(img, (180, 230), (460, 320), (0, 0, 255), 3)
        cv2.putText(img, "! WARNING", (205, 270), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
        cv2.putText(img, "REAR OBJECT", (205, 305), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
    return img


def gen_video(path, t0, popup_t):
    n = int(DUR * FPS)
    p0 = int(round(popup_t * FPS))
    g0 = int(round(t0 * FPS))
    vw = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), FPS, (W, H))
    for i in range(n):
        gear = "R" if i >= g0 else "D"
        vw.write(draw_cluster(i, i >= p0, gear))
    vw.release()


def gen_audio(path, beep_t):
    n = int(DUR * SR)
    audio = np.random.normal(0, 0.01, n).astype(np.float32)
    s = int(beep_t * SR)
    blen = int(BEEP_DUR * SR)
    t = np.arange(blen) / SR
    env = np.minimum(1.0, np.minimum(t * 50, (BEEP_DUR - t) * 50))
    audio[s:s + blen] += (0.5 * np.sin(2 * np.pi * BEEP_FREQ * t) * env).astype(np.float32)
    wavfile.write(path, SR, (np.clip(audio, -1, 1) * 32767).astype(np.int16))


def save_template():
    exp = os.path.join(HERE, "expected")
    os.makedirs(exp, exist_ok=True)
    f = draw_cluster(150, True, "R")
    y1, y2, x1, x2 = POPUP_ROI
    cv2.imwrite(os.path.join(exp, "popup_template.png"), f[y1:y2, x1:x2])
    # ORB 정렬용 기준 클러스터(정면, 팝업 없음)
    cv2.imwrite(os.path.join(exp, "ref_cluster.png"), draw_cluster(150, False, "D"))
    # 정합필터용 기대 경고음(노이즈 없는 순수 beep 파형)
    t = np.arange(int(BEEP_DUR * SR)) / SR
    env = np.minimum(1.0, np.minimum(t * 50, (BEEP_DUR - t) * 50))
    beep = (0.5 * np.sin(2 * np.pi * BEEP_FREQ * t) * env).astype(np.float32)
    wavfile.write(os.path.join(exp, "beep_template.wav"), SR, (beep * 32767).astype(np.int16))
    json.dump({"beep_freq": BEEP_FREQ, "popup_roi": POPUP_ROI, "sr": SR},
              open(os.path.join(exp, "expected.json"), "w"), indent=2)


def main():
    save_template()
    for name, sc in SCENARIOS.items():
        d = os.path.join(HERE, "samples", name)
        os.makedirs(d, exist_ok=True)
        gen_video(os.path.join(d, "cluster.mp4"), sc["t0"], sc["popup"])
        gen_audio(os.path.join(d, "warning.wav"), sc["beep"])
        # CAN 로그: 차량이 제공하는 사실(R단 변속시점, 경고 CAN 시점)
        can = {
            "fps": FPS, "sr": SR,
            "events": [
                {"t": sc["t0"], "signal": "GEAR", "value": "R", "desc": "R단 변속"},
                {"t": sc["warn_can"], "signal": "PDW_WARN_CAN", "value": 1, "desc": "경고 CAN 신호"},
            ],
            "_truth": sc,
        }
        json.dump(can, open(os.path.join(d, "can_log.json"), "w", encoding="utf-8"), indent=2, ensure_ascii=False)
        print(f"생성: {name}  (T0={sc['t0']}s, popup={sc['popup']}s, beep={sc['beep']}s, warnCAN={sc['warn_can']}s)")
    print("완료. 샘플 위치:", os.path.join(HERE, "samples"))


if __name__ == "__main__":
    main()
