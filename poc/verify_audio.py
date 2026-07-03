"""
음향 일치 검증 PoC
- 입력: 경고음 WAV
- 처리:
  (1) 단시간 에너지(RMS) 기반 onset 검출
  (2) FFT(STFT)로 경고음 주파수 대역(예: 2kHz) 에너지가 솟는 시점 검출 -> 노이즈 강건
- 출력: 경고음 시작 시각(초)
"""
import numpy as np
import os
from scipy.io import wavfile

TARGET_FREQ = 2000   # 경고음 중심 주파수(Hz)
BAND = 200           # ±대역(Hz)
FRAME_MS = 5         # 분석 프레임 길이(ms)
ENERGY_FACTOR = 6.0  # 배경 대비 몇 배 이상이면 onset으로 판단


def detect_beep(wav_path, target=TARGET_FREQ, band=BAND):
    sr, data = wavfile.read(wav_path)
    if data.ndim > 1:
        data = data[:, 0]
    x = data.astype(np.float32) / 32768.0
    fl = int(sr * FRAME_MS / 1000)            # 프레임 샘플 수
    n = len(x) // fl
    freqs = np.fft.rfftfreq(fl, 1 / sr)
    mask = (freqs >= target - band) & (freqs <= target + band)
    band_energy = np.zeros(n)
    for i in range(n):
        seg = x[i * fl:(i + 1) * fl] * np.hanning(fl)
        spec = np.abs(np.fft.rfft(seg))
        band_energy[i] = spec[mask].sum()
    # 배경 수준 추정(앞 10%) 후 임계 초과 첫 프레임
    bg = np.median(band_energy[: max(1, n // 10)]) + 1e-9
    thr = bg * ENERGY_FACTOR
    idx = np.argmax(band_energy > thr)
    onset_time = idx * FRAME_MS / 1000.0 if band_energy[idx] > thr else None
    return {
        "sr": sr,
        "frame_ms": FRAME_MS,
        "onset_time_s": onset_time,
        "peak_band_energy": float(band_energy.max()),
        "bg_level": float(bg),
    }


if __name__ == "__main__":
    base = os.path.join(os.path.dirname(__file__), "samples")
    r = detect_beep(os.path.join(base, "warning.wav"))
    print("[음향 일치 검출]")
    print(f"  sr={r['sr']}, frame={r['frame_ms']}ms")
    print(f"  경고음 시작 시각: {r['onset_time_s']:.4f} s")
    print(f"  대역 에너지 peak: {r['peak_band_energy']:.2f} (배경 {r['bg_level']:.4f})")
