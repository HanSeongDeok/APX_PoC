"""
합성 샘플 생성기 (R158 시나리오 모사)
- 클러스터 영상(MP4): 특정 시점에 경고 팝업 등장
- 경고 팝업 템플릿(PNG): 영상 일치 검증용 기대 이미지
- 경고음(WAV): 특정 시점에 beep 발화 (영상 팝업과 약간의 시차)
- ground_truth.json: 정답 시각 저장 (검출 오차 측정용)

목적: 외부 데이터 없이, 정답을 아는 통제된 샘플로 영상/음향 일치 검출 PoC 검증
"""
import cv2
import numpy as np
import json
import os
from scipy.io import wavfile

OUT = os.path.join(os.path.dirname(__file__), "samples")
os.makedirs(OUT, exist_ok=True)

# ---- 파라미터 ----
FPS = 60                 # 웹캠 프레임레이트 (분해능 = 1000/60 ≈ 16.7ms)
DURATION = 5.0           # 영상 길이(초)
W, H = 640, 360
POPUP_T = 2.000          # 팝업이 화면에 뜨는 정답 시각(초)
BEEP_T = 2.020           # 경고음이 울리는 정답 시각(초) -> 영상과 20ms 시차
SR = 48000               # 오디오 샘플레이트
BEEP_FREQ = 2000         # 경고음 주파수(Hz)
BEEP_DUR = 0.30          # 경고음 길이(초)


def draw_cluster(frame_idx, popup_on):
    """클러스터 계기판 화면을 단순 모사. popup_on=True면 경고 팝업 그림."""
    img = np.full((H, W, 3), 18, np.uint8)  # 어두운 클러스터 배경
    # 좌/우 게이지(원) 모사
    cv2.circle(img, (140, 180), 90, (60, 60, 60), 3)
    cv2.circle(img, (500, 180), 90, (60, 60, 60), 3)
    # 회전하는 바늘(움직임 -> 정적 매칭 강건성 테스트)
    ang = (frame_idx * 7) % 360
    nx = int(140 + 70 * np.cos(np.radians(ang)))
    ny = int(180 + 70 * np.sin(np.radians(ang)))
    cv2.line(img, (140, 180), (nx, ny), (0, 200, 255), 3)
    # 기어 표시
    cv2.putText(img, "R", (305, 120), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 220, 0), 3)
    if popup_on:
        # 경고 팝업: 빨간 테두리 박스 + 경고 텍스트 (기대 이미지)
        cv2.rectangle(img, (180, 230), (460, 320), (0, 0, 200), -1)
        cv2.rectangle(img, (180, 230), (460, 320), (0, 0, 255), 3)
        cv2.putText(img, "! WARNING", (205, 270), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
        cv2.putText(img, "REAR OBJECT", (205, 305), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
    return img


def main():
    # ---- 영상 생성 ----
    n_frames = int(DURATION * FPS)
    popup_frame = int(round(POPUP_T * FPS))
    video_path = os.path.join(OUT, "cluster.mp4")
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    vw = cv2.VideoWriter(video_path, fourcc, FPS, (W, H))
    template = None
    for i in range(n_frames):
        on = i >= popup_frame
        f = draw_cluster(i, on)
        vw.write(f)
        if i == popup_frame:
            # 팝업 영역만 크롭해서 템플릿으로 저장
            template = f[230:320, 180:460].copy()
    vw.release()
    cv2.imwrite(os.path.join(OUT, "popup_template.png"), template)

    # ---- 음향 생성 ----
    n_samp = int(DURATION * SR)
    audio = np.random.normal(0, 0.01, n_samp).astype(np.float32)  # 배경 노이즈
    beep_start = int(BEEP_T * SR)
    beep_len = int(BEEP_DUR * SR)
    t = np.arange(beep_len) / SR
    env = np.minimum(1.0, np.minimum(t * 50, (BEEP_DUR - t) * 50))  # fade in/out
    beep = 0.5 * np.sin(2 * np.pi * BEEP_FREQ * t) * env
    audio[beep_start:beep_start + beep_len] += beep.astype(np.float32)
    audio = np.clip(audio, -1, 1)
    wavfile.write(os.path.join(OUT, "warning.wav"), SR, (audio * 32767).astype(np.int16))

    # ---- 정답 저장 ----
    gt = {
        "fps": FPS, "duration": DURATION, "sr": SR,
        "popup_time_s": POPUP_T, "popup_frame": popup_frame,
        "beep_time_s": BEEP_T,
        "video_audio_gap_ms": round((BEEP_T - POPUP_T) * 1000, 1),
        "frame_resolution_ms": round(1000.0 / FPS, 2),
    }
    with open(os.path.join(OUT, "ground_truth.json"), "w", encoding="utf-8") as fp:
        json.dump(gt, fp, indent=2, ensure_ascii=False)

    print("생성 완료:")
    print("  video :", video_path, f"({n_frames} frames @ {FPS}fps)")
    print("  audio :", os.path.join(OUT, "warning.wav"))
    print("  template:", os.path.join(OUT, "popup_template.png"))
    print("  ground truth:", json.dumps(gt, ensure_ascii=False))


if __name__ == "__main__":
    main()
