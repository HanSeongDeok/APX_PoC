"""경고음 .wav 생성기 (핸드폰 재생용).

1) warning_proximity.wav : 주차센서처럼 삐- 주기가 점점 짧아지다 연속음이 되는 파형.
2) warning_single.wav    : 검출속도 측정용 단일 연속음(삐~). 소리를 먼저 틀어놓고
                            측정 시작을 누르는 콜드스타트 시나리오용 (에너지 일정).

사용:  python gen_warning_wav.py
"""
import os
import numpy as np
from scipy.io import wavfile

from apx_app.engine.tone import proximity_warning

SR = 44100  # 핸드폰/스피커 표준 샘플레이트
ASSETS = os.path.join(os.path.dirname(__file__), "assets")
OUT = os.path.join(ASSETS, "warning_proximity.wav")
OUT_SINGLE = os.path.join(ASSETS, "warning_single.wav")


def single_tone(sr=SR, freq=2000.0, sec=3.0, amp=0.8, fade_ms=5.0):
    """단일 연속음(삐~). 클릭 방지용 짧은 페이드 인/아웃."""
    n = int(sec * sr)
    t = np.arange(n) / sr
    wave = np.sin(2 * np.pi * freq * t).astype(np.float32)
    f = max(1, int(sr * fade_ms / 1000.0))
    if 2 * f < n:
        env = np.ones(n, np.float32)
        env[:f] = np.linspace(0, 1, f)
        env[-f:] = np.linspace(1, 0, f)
        wave *= env
    return wave * amp


def main():
    os.makedirs(ASSETS, exist_ok=True)

    wave = proximity_warning(
        sr=SR, freq=2000.0, beep_ms=90,
        gap_start_ms=600, gap_end_ms=60, steps=9,
        solid_ms=1500, amp=0.8,
    )
    wavfile.write(OUT, SR, (np.clip(wave, -1, 1) * 32767).astype(np.int16))
    print(f"생성: {OUT}  ({len(wave) / SR:.2f}s, {SR}Hz)")

    single = single_tone()
    wavfile.write(OUT_SINGLE, SR, (np.clip(single, -1, 1) * 32767).astype(np.int16))
    print(f"생성: {OUT_SINGLE}  ({len(single) / SR:.2f}s, {SR}Hz, 2000Hz 단일음)")


if __name__ == "__main__":
    main()
