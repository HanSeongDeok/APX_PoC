"""
영상 일치 검증 PoC
- 입력: 클러스터 영상(MP4) + 기대 팝업 템플릿(PNG)
- 처리: 프레임별 템플릿 매칭(정규화 상관계수) -> 유사도가 임계값을 처음 넘는 프레임 = 팝업 등장 시점
- 출력: 등장 프레임 / 시각(초) / 최대 유사도
"""
import cv2
import numpy as np
import os

THRESH = 0.7  # 정규화 상관계수 임계값 (기대 화면 일치 판단)


def detect_popup(video_path, template_path, thresh=THRESH):
    tmpl = cv2.imread(template_path)
    cap = cv2.VideoCapture(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS)
    idx = 0
    onset_frame = None
    scores = []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        res = cv2.matchTemplate(frame, tmpl, cv2.TM_CCOEFF_NORMED)
        score = float(res.max())
        scores.append(score)
        if onset_frame is None and score >= thresh:
            onset_frame = idx
        idx += 1
    cap.release()
    onset_time = onset_frame / fps if onset_frame is not None else None
    return {
        "fps": fps,
        "onset_frame": onset_frame,
        "onset_time_s": onset_time,
        "max_score": max(scores) if scores else None,
        "n_frames": idx,
    }


if __name__ == "__main__":
    base = os.path.join(os.path.dirname(__file__), "samples")
    r = detect_popup(
        os.path.join(base, "cluster.mp4"),
        os.path.join(base, "popup_template.png"),
    )
    print("[영상 일치 검출]")
    print(f"  fps={r['fps']:.1f}, frames={r['n_frames']}")
    print(f"  팝업 등장 프레임: {r['onset_frame']}")
    print(f"  팝업 등장 시각  : {r['onset_time_s']:.4f} s")
    print(f"  최대 유사도     : {r['max_score']:.4f}")
