"""
촬영용 재생기 — 클러스터 영상 + 경고음을 루프 재생
(차/클러스터 없이 폰으로 찍을 '대상'을 만들어 줌)

사용:
  python loop_av.py                      # PASS 시나리오 반복 재생
  python loop_av.py --scenario FAIL_LATE
이 창을 한 모니터에 띄우고, 폰(가상 웹캠)으로 이 창을 촬영하세요.
영상 frame0 과 동시에 warning.wav 를 재생 → 경고음 타이밍 동기.
"""
import os
import argparse
import time
import cv2
import numpy as np
from scipy.io import wavfile

HERE = os.path.dirname(__file__)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", default="PASS")
    args = ap.parse_args()
    d = os.path.join(HERE, "samples", args.scenario)
    vpath = os.path.join(d, "cluster.mp4")
    wpath = os.path.join(d, "warning.wav")

    # 오디오 로드
    try:
        import sounddevice as sd
        sr, wav = wavfile.read(wpath)
        wav = wav.astype(np.float32) / 32768.0
        has_audio = True
    except Exception as e:
        print("오디오 재생 불가(영상만):", e); has_audio = False

    cap = cv2.VideoCapture(vpath)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30
    frames = []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        frames.append(f)
    cap.release()
    delay = max(1, int(1000 / fps))
    win = f"PLAY [{args.scenario}] - point your phone here  (q quit)"
    print(f"재생: {args.scenario}  fps={fps:.0f}  frames={len(frames)}  (q 종료)")

    while True:
        if has_audio:
            sd.stop(); sd.play(wav, sr)      # 루프 시작마다 경고음 동기 재생
        for f in frames:
            cv2.imshow(win, f)
            if (cv2.waitKey(delay) & 0xFF) == ord('q'):
                if has_audio: sd.stop()
                cv2.destroyAllWindows(); return


if __name__ == "__main__":
    main()
