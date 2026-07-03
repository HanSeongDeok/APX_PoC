"""
음향 일치 검증 PoC (librosa 보강판)
- 입력: 경고음 WAV
- 처리:
  (1) librosa onset detection (스펙트럴 플럭스 기반) - 음향 onset 표준 기법
  (2) 특정 주파수 대역(경고음 2kHz) 에너지로 onset 후보 필터링 -> 노이즈 강건
- 출력: 경고음 시작 시각(초), onset 후보 목록
기존 verify_audio.py(직접 FFT 구현)와 동일 인터페이스(detect_beep) 제공.
"""
import os
import numpy as np
import librosa

TARGET_FREQ = 2000   # 경고음 중심 주파수(Hz)
BAND = 200           # ±대역(Hz)
HOP = 256            # STFT hop (시간 분해능 = hop/sr)


def detect_beep(wav_path, target=TARGET_FREQ, band=BAND, sr=None):
    # librosa 로드 (원본 샘플레이트 유지)
    y, sr = librosa.load(wav_path, sr=sr, mono=True)

    # 1) 표준 onset 검출 (스펙트럴 플럭스 onset envelope -> peak picking)
    onset_env = librosa.onset.onset_strength(y=y, sr=sr, hop_length=HOP)
    onset_frames = librosa.onset.onset_detect(
        onset_envelope=onset_env, sr=sr, hop_length=HOP, backtrack=True
    )
    onset_times = librosa.frames_to_time(onset_frames, sr=sr, hop_length=HOP)

    # 2) 각 onset 후보가 경고음 대역(2kHz) 에너지를 동반하는지로 필터링
    S = np.abs(librosa.stft(y, hop_length=HOP))
    freqs = librosa.fft_frequencies(sr=sr)
    band_mask = (freqs >= target - band) & (freqs <= target + band)
    band_energy = S[band_mask, :].sum(axis=0)
    bg = np.median(band_energy[: max(1, len(band_energy) // 10)]) + 1e-9

    beep_time = None
    for f, t in zip(onset_frames, onset_times):
        if f < band_energy.shape[0] and band_energy[f] > bg * 5.0:
            beep_time = float(t)
            break

    return {
        "sr": sr,
        "method": "librosa.onset + 2kHz band filter",
        "onset_time_s": beep_time,
        "all_onsets_s": [round(float(t), 4) for t in onset_times],
        "n_onsets": int(len(onset_times)),
    }


if __name__ == "__main__":
    base = os.path.join(os.path.dirname(__file__), "samples")
    r = detect_beep(os.path.join(base, "warning.wav"))
    print("[음향 일치 검출 - librosa]")
    print(f"  sr={r['sr']}, method={r['method']}")
    print(f"  검출된 onset 후보: {r['all_onsets_s']} (총 {r['n_onsets']}개)")
    if r["onset_time_s"] is not None:
        print(f"  경고음(2kHz) 시작 시각: {r['onset_time_s']:.4f} s")
    else:
        print("  경고음 대역 onset 미검출")
