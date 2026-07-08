"""주차센서식 근접 경고음 생성·재생 (테스트용).

실제 경고등/주차센서처럼 '거리'가 가까워질수록 삐- 주기가 짧아지고,
최종적으로는 끊기지 않는 단일 연속음(삐————)이 되는 파형을 만든다.

  단계별 간격(gap)을 gap_start → gap_end 로 선형 축소하며 짧은 비프를 반복,
  마지막에 solid_ms 길이의 연속음을 붙인다.
재생은 sounddevice 출력(비차단). scipy/sounddevice 없으면 조용히 무시.
"""
import numpy as np

try:
    import sounddevice as sd
except Exception:
    sd = None


def _beep(sr, freq, ms, fade_ms=5.0):
    """짧은 사인 비프 + 클릭 방지용 페이드 인/아웃."""
    n = max(1, int(sr * ms / 1000.0))
    t = np.arange(n) / sr
    wave = np.sin(2 * np.pi * freq * t).astype(np.float32)
    f = max(1, int(sr * fade_ms / 1000.0))
    if 2 * f < n:
        env = np.ones(n, np.float32)
        env[:f] = np.linspace(0, 1, f)
        env[-f:] = np.linspace(1, 0, f)
        wave *= env
    return wave


def proximity_warning(sr=16000, freq=2000.0, beep_ms=90.0,
                      gap_start_ms=600.0, gap_end_ms=60.0,
                      steps=9, solid_ms=1500.0, amp=0.5):
    """근접 경고음 파형(float32, [-amp, amp]) 생성.

    steps 회에 걸쳐 비프 간격을 gap_start_ms → gap_end_ms 로 좁힌 뒤,
    solid_ms 길이의 단일 연속음으로 마무리한다.
    """
    beep = _beep(sr, freq, beep_ms)
    parts = []
    for i in range(steps):
        frac = i / max(1, steps - 1)                 # 0(멀다) → 1(가깝다)
        gap_ms = gap_start_ms + (gap_end_ms - gap_start_ms) * frac
        parts.append(beep)
        parts.append(np.zeros(int(sr * gap_ms / 1000.0), np.float32))
    parts.append(_beep(sr, freq, solid_ms))          # 최종 연속음 삐————
    out = np.concatenate(parts) * amp
    return out.astype(np.float32)


class TonePlayer:
    """근접 경고음 비차단 재생. sounddevice 없으면 available=False."""

    def __init__(self, sr=16000):
        self.sr = int(sr)

    @property
    def available(self):
        return sd is not None

    def play(self, device=None, **kw):
        """경고음 재생 시작(비차단). 성공 여부 반환."""
        if sd is None:
            return False
        try:
            wave = proximity_warning(sr=self.sr, **kw)
            sd.play(wave, self.sr, device=device)
            return True
        except Exception:
            return False

    def stop(self):
        if sd is not None:
            try:
                sd.stop()
            except Exception:
                pass
