"""실시간 파형·스펙트럼 스코프 (외부 라이브러리 없이 QPainter 직접 렌더).

set_data(wave, sr, target_freq)를 주기적으로 호출하면
  위: 파형(시간축)  /  아래: 스펙트럼(주파수축, FFT 크기, 목표주파수 표시)
를 다시 그린다. numpy 로 데시메이션·FFT만 수행.
"""
import numpy as np
from PySide6.QtCore import QPointF
from PySide6.QtGui import QPainter, QPen, QColor, QPolygonF, QFont
from PySide6.QtWidgets import QWidget


class ScopeWidget(QWidget):
    def __init__(self, fmax=5000.0):
        super().__init__()
        self.setMinimumHeight(220)   # 파형·스펙트럼 y축 기본 높이(창 커지면 stretch로 더 늘어남)
        self._wave = None
        self._spec = None
        self._freqs = None
        self._target = 0.0
        self._fmax = float(fmax)     # 스펙트럼 표시 상한(Hz)

    def set_data(self, wave, sr, target_freq=0.0):
        w = np.asarray(wave, np.float32).ravel()
        self._wave = w
        if len(w) >= 16:
            win = w * np.hanning(len(w))
            self._spec = np.abs(np.fft.rfft(win))
            self._freqs = np.fft.rfftfreq(len(w), 1.0 / max(sr, 1))
        else:
            self._spec = self._freqs = None
        self._target = float(target_freq)
        self.update()

    # ---- 그리기 ----
    def paintEvent(self, ev):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        W, H = self.width(), self.height()
        p.fillRect(0, 0, W, H, QColor("#0b0b0b"))
        gap = 10
        h = (H - gap) // 2
        self._draw_wave(p, 0, W, h)
        self._draw_spec(p, h + gap, W, H - (h + gap))

    def _label(self, p, x, y, text, col="#888"):
        p.setPen(QColor(col)); f = QFont("Consolas", 8); p.setFont(f)
        p.drawText(x, y, text)

    def _draw_wave(self, p, top, W, h):
        p.fillRect(0, top, W, h, QColor("#0e0e12"))
        mid = top + h / 2
        p.setPen(QPen(QColor("#333"), 1)); p.drawLine(0, int(mid), W, int(mid))
        self._label(p, 6, top + 14, "파형 (시간축)")
        w = self._wave
        if w is None or len(w) < 2:
            return
        # 폭에 맞게 데시메이션(구간별 최대 절대값 유지)
        n = min(len(w), max(W, 2))
        idx = np.linspace(0, len(w) - 1, n).astype(int)
        ys = w[idx]
        amp = float(np.max(np.abs(ys))) or 1.0
        scale = (h / 2) * 0.9 / amp
        xs = np.linspace(0, W, n)
        pts = [QPointF(float(xs[i]), float(mid - ys[i] * scale)) for i in range(n)]
        p.setPen(QPen(QColor("#2ee66e"), 1))
        p.drawPolyline(QPolygonF(pts))

    def _draw_spec(self, p, top, W, h):
        p.fillRect(0, top, W, h, QColor("#0e0e12"))
        base = top + h - 14
        p.setPen(QPen(QColor("#333"), 1)); p.drawLine(0, int(base), W, int(base))
        self._label(p, 6, top + 14, f"스펙트럼 (0~{int(self._fmax)}Hz)")
        spec, freqs = self._spec, self._freqs
        if spec is None or freqs is None or len(spec) < 2:
            return
        m = freqs <= self._fmax
        spec = spec[m]; freqs = freqs[m]
        if len(spec) < 2:
            return
        peak = float(spec.max()) or 1.0
        scale = (h - 24) / peak
        xs = freqs / self._fmax * W
        # 채워진 스펙트럼(바닥부터)
        poly = [QPointF(0, base)]
        for i in range(len(spec)):
            poly.append(QPointF(float(xs[i]), float(base - spec[i] * scale)))
        poly.append(QPointF(float(xs[-1]), base))
        p.setPen(QPen(QColor("#4aa3ff"), 1))
        p.setBrush(QColor(74, 163, 255, 70))
        p.drawPolygon(QPolygonF(poly))
        # 목표 주파수 표시선
        if 0 < self._target <= self._fmax:
            tx = self._target / self._fmax * W
            p.setPen(QPen(QColor("#ff5252"), 1))
            p.drawLine(int(tx), top, int(tx), int(base))
            self._label(p, int(tx) + 4, top + 26, f"{self._target:.0f}Hz", "#ff8a8a")
