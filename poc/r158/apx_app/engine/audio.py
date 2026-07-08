"""
기대 beep 음향 일치 검출 엔진 (순수, 결정적)
================================================================
주파수(FFT) + 파형(정합필터) 각각 일치도를 내고, 둘 다 각자 임계 이상이면 PASS(AND).
  주파수 일치도 = 라이브 스펙트럼 vs 기대 beep 스펙트럼 코사인 유사도 [0,1]
  파형 일치도  = 기대 beep과 정규화 상호상관(정합필터) NCC [0,1]
  판정        = (주파수 >= freq_thr) AND (파형 >= wave_thr) 이면 BEEP=PASS
onset_t(perf_counter): 30ms 동기검증용 검출 시각.
Java 이식: JTransforms(FFT) + 상호상관 직접구현.
"""
import numpy as np

try:
    from scipy import signal as _sig
    from scipy.io import wavfile as _wav
except Exception:
    _sig = None
    _wav = None


def load_beep(path):
    """기대 beep .wav 로드 → (float32 mono 파형, sr). scipy 없으면 (None, 0)."""
    if _wav is None:
        return None, 0
    sr, data = _wav.read(path)
    x = data.astype(np.float32)
    if x.ndim > 1:
        x = x[:, 0]
    if data.dtype == np.int16:
        x = x / 32768.0
    elif data.dtype == np.int32:
        x = x / 2147483648.0
    return x, int(sr)


class BeepMatcher:
    """블록 단위 feed() → 주파수·파형 일치도 + 합산 PASS 판정."""

    def __init__(self, template, sr, band=150.0, energy_factor=4.0,
                 freq_thr=0.5, wave_thr=0.5, pulse_sec=0.12):
        self.sr = int(sr)
        self.band = band
        self.energy_factor = energy_factor      # 트리거용 대역에너지 급증 배수
        self.freq_thr = freq_thr                 # 주파수 일치도 PASS 임계 [0~1]
        self.wave_thr = wave_thr                 # 파형 일치도 PASS 임계 [0~1]
        t = template.astype(np.float32)
        t = t - t.mean()
        # 파형 템플릿 = '삐 한 번' 단일 펄스 (에너지 최대 구간).
        #   주기가 변하는 근접경고음도 각 펄스 톤·파형은 동일 → 주기 무관 매칭.
        plen = min(len(t), max(int(0.03 * sr), int(pulse_sec * sr)))
        if len(t) > plen:
            e = np.convolve(t.astype(np.float64) ** 2, np.ones(plen), "valid")
            st = int(np.argmax(e))
            pulse = t[st:st + plen]
        else:
            pulse = t
        pulse = pulse - pulse.mean()
        self.tmpl = pulse / (np.linalg.norm(pulse) + 1e-9)
        self.L = len(pulse)
        # 펄스 스펙트럼(주파수 일치도용, 단위벡터)
        spec = np.abs(np.fft.rfft(pulse * np.hanning(self.L)))
        self.tmpl_spec = spec / (np.linalg.norm(spec) + 1e-9)
        self.freqs = np.fft.rfftfreq(self.L, 1.0 / self.sr)
        self.target_freq = float(self.freqs[int(np.argmax(spec))])
        # 상태
        self.bg = None
        self.buflen = max(self.L, int(0.5 * self.sr))
        self.buf = np.zeros(self.buflen, np.float32)
        self.armed = False
        self.onset_t = None

    def arm(self):
        self.armed = True
        self.onset_t = None

    def reset(self):
        self.armed = False
        self.onset_t = None

    def _band_energy(self, x):
        spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
        freqs = np.fft.rfftfreq(len(x), 1.0 / self.sr)
        m = (freqs >= self.target_freq - self.band) & (freqs <= self.target_freq + self.band)
        return float(spec[m].sum())

    def _wave_sim(self):
        """파형 일치도: 최근 버퍼 vs 기대음 정규화 상호상관 최대값 [0,1]."""
        if _sig is None:
            return 0.0
        seg = self.buf[-min(self.buflen, int(0.5 * self.sr)):]
        if len(seg) < self.L:
            return 0.0
        num = _sig.correlate(seg, self.tmpl, mode="valid")
        ss = np.maximum(_sig.correlate(seg * seg, np.ones(self.L, np.float32), mode="valid"), 0.0)
        eng = np.sqrt(ss)
        mask = eng >= 0.1 * eng.max()            # 무음 창 폭주 배제
        if not mask.any():
            return 0.0
        return float(np.clip(np.max(np.abs(num[mask] / (eng[mask] + 1e-9))), 0.0, 1.0))

    def _freq_sim(self):
        """주파수 일치도: 최근 창 스펙트럼 vs 기대음 스펙트럼 코사인 유사도 [0,1]."""
        seg = self.buf[-self.L:]
        spec = np.abs(np.fft.rfft(seg * np.hanning(self.L)))
        sn = spec / (np.linalg.norm(spec) + 1e-9)
        return float(np.clip(np.dot(sn, self.tmpl_spec), 0.0, 1.0))

    def feed(self, block, now=None):
        """오디오 블록(mono float) → 결과 dict. now=perf_counter(검출시각용)."""
        x = np.asarray(block, np.float32).ravel()
        n = len(x)
        if n >= self.buflen:
            self.buf = x[-self.buflen:].copy()
        else:
            self.buf = np.roll(self.buf, -n)
            self.buf[-n:] = x

        e = self._band_energy(x)
        self.bg = e if self.bg is None else 0.98 * self.bg + 0.02 * e
        ratio = e / max(self.bg, 1e-6)
        has_sound = ratio >= self.energy_factor          # 실제 소리 있음(트리거)

        wave_sim = self._wave_sim()
        freq_sim = self._freq_sim()
        combined = freq_sim + wave_sim
        is_pass = (freq_sim >= self.freq_thr) and (wave_sim >= self.wave_thr)   # AND 조건

        match = False
        if self.armed and self.onset_t is None and has_sound and is_pass:
            self.onset_t = now                           # 기대 beep 확정 시각
            match = True

        return {
            "target_freq": self.target_freq,
            "energy_ratio": ratio,
            "has_sound": has_sound,
            "freq_sim": freq_sim,        # 주파수 일치도
            "wave_sim": wave_sim,        # 파형 일치도
            "combined": combined,        # 합산(참고용)
            "freq_thr": self.freq_thr,   # 주파수 임계
            "wave_thr": self.wave_thr,   # 파형 임계
            "is_pass": is_pass,
            "match": match,
            "onset_t": self.onset_t,
        }
