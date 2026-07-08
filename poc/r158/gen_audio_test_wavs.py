"""음향 일치도 테스트용 .wav 생성기.

기준: expected/warning_proximity.wav (2000 Hz, 사인 근접경고음)
  1) 동일 주파수·다른 파형  → 주파수 일치도 높음 / 파형 일치도 낮음 기대
  2) 다른 주파수·동일 파형  → 주파수 일치도 낮음 / 파형 일치도 상대적 높음 기대

[1]의 "파형만 다르게": 방파(square)는 2000 Hz 고조파를 그대로 실어 정합필터
상관도(파형 일치도)가 0.9까지 올라간다(사인과 파형이 사실상 닮음). 그래서
2000 Hz 중심의 **협대역 노이즈**를 쓴다. 크기 스펙트럼은 사인과 비슷(주파수
일치도 유지)하지만 위상이 무작위라 파형(시간영역)은 완전히 달라져 정합필터
상관도가 임계 아래로 떨어진다.  freq_sim≈0.55(PASS) / wave_sim≈0.42(FAIL).

사용:  python gen_audio_test_wavs.py
"""
import os

import numpy as np
from scipy import signal
from scipy.io import wavfile

from apx_app.engine.tone import proximity_warning

SR = 44100
BASE_FREQ = 2000.0
ALT_FREQ = 1500.0
NOISE_BW = 120.0          # 협대역 노이즈 반대역폭(Hz): 작을수록 주파수 일치도↑
OUT_DIR = os.path.join(os.path.dirname(__file__), "expected")

KW = dict(
    sr=SR, beep_ms=90,
    gap_start_ms=600, gap_end_ms=60, steps=9,
    solid_ms=1500, amp=0.8,
)


def _nb_noise_beep(sr, freq, ms, seed, bw_hz=NOISE_BW, fade_ms=5.0):
    """2000 Hz 중심 협대역 노이즈 비프.

    사인과 크기 스펙트럼은 유사(주파수 일치도 유지)하나 위상이 무작위 →
    시간영역 파형이 사인과 완전히 달라 정합필터 파형 일치도가 낮다.
    """
    n = max(1, int(sr * ms / 1000.0))
    rng = np.random.RandomState(seed)          # 결정적 재현
    x = rng.randn(n)
    lo = max(1.0, freq - bw_hz) / (sr / 2.0)
    hi = min(sr / 2.0 - 1.0, freq + bw_hz) / (sr / 2.0)
    b, a = signal.butter(4, [lo, hi], btype="band")
    wave = signal.lfilter(b, a, x).astype(np.float32)
    wave /= (np.max(np.abs(wave)) + 1e-9)      # 진폭 정규화
    f = max(1, int(sr * fade_ms / 1000.0))
    if 2 * f < n:
        env = np.ones(n, np.float32)
        env[:f] = np.linspace(0, 1, f)
        env[-f:] = np.linspace(1, 0, f)
        wave *= env
    return wave


def proximity_warning_noise(sr, freq, beep_ms=90.0,
                            gap_start_ms=600.0, gap_end_ms=60.0,
                            steps=9, solid_ms=1500.0, amp=0.5):
    """tone.proximity_warning 과 동일 타이밍, 비프만 협대역 노이즈로 교체."""
    parts = []
    for i in range(steps):
        frac = i / max(1, steps - 1)
        gap_ms = gap_start_ms + (gap_end_ms - gap_start_ms) * frac
        parts.append(_nb_noise_beep(sr, freq, beep_ms, seed=i))
        parts.append(np.zeros(int(sr * gap_ms / 1000.0), np.float32))
    parts.append(_nb_noise_beep(sr, freq, solid_ms, seed=99))
    return (np.concatenate(parts) * amp).astype(np.float32)


def _write(path, wave):
    wavfile.write(path, SR, (np.clip(wave, -1, 1) * 32767).astype(np.int16))
    print(f"  {path}  ({len(wave) / SR:.2f}s, peak={np.max(np.abs(wave)):.3f})")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    # 앱에서 실제로 쓰는 한글 파일명으로 저장
    same_freq = os.path.join(OUT_DIR, "test_파형다름_주파수같음.wav")
    diff_freq = os.path.join(OUT_DIR, "test_주파수다름_파형같음.wav")

    print("생성 중 (기준: warning_proximity.wav = 2000 Hz 사인)")
    _write(
        same_freq,
        proximity_warning_noise(freq=BASE_FREQ, **KW),
    )
    _write(
        diff_freq,
        proximity_warning(freq=ALT_FREQ, **KW),
    )
    print(f"  [1] {os.path.basename(same_freq)}: {BASE_FREQ:.0f} Hz 협대역노이즈"
          f"(동일 주파수·파형 완전히 다름, ±{NOISE_BW:.0f}Hz)")
    print(f"  [2] {os.path.basename(diff_freq)}: {ALT_FREQ:.0f} Hz 사인(다른 주파수·동일 파형)")


if __name__ == "__main__":
    main()
