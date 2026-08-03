"""④ 음향 검증 탭 — 기대 beep(.wav) vs 마이크 입력: 주파수·파형 일치도 + 합산 PASS/FAIL."""
import os
import time
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QShortcut, QKeySequence
from PySide6.QtWidgets import (
    QWidget, QLabel, QPushButton, QComboBox, QHBoxLayout, QVBoxLayout,
    QGroupBox, QProgressBar, QFileDialog, QMessageBox, QDoubleSpinBox, QGridLayout,
)

from .. import devices, config
from ..engine.audio import load_beep, BeepMatcher
from .scope import ScopeWidget

try:
    import sounddevice as sd
except Exception:
    sd = None


class AudioTab(QWidget):
    def __init__(self):
        super().__init__()
        self.setAcceptDrops(True)        # wav 드래그앤드롭
        self.beep = None
        self.sr = 0
        self.beep_path = None
        self.matcher = None
        self.freq_thr = 0.5              # 주파수 일치도 PASS 임계, UI로 조절
        self.wave_thr = 0.5              # 파형 일치도 PASS 임계, UI로 조절
        self.stream = None
        self._latest = None
        self._passed = None
        self._arm_t = None               # 측정 시작(arm) 시각 (perf_counter)
        self._detect_ms = None           # 시작→일치 확정까지 판단 소요(ms)

        # ================= 결과 영역 (위로) =================
        self.head = QLabel("파형 및 주파수 일치도 검증 [측정 시작]")
        self.head.setTextFormat(Qt.RichText)
        self.head.setStyleSheet("font-family:Consolas; font-size:12px; font-weight:bold;")
        self.freq_bar = QProgressBar(); self.freq_bar.setRange(0, 100); self.freq_bar.setFormat("주파수 일치도 %p%")
        self.wave_bar = QProgressBar(); self.wave_bar.setRange(0, 100); self.wave_bar.setFormat("파형 일치도 %p%")
        self.detail = QLabel("")         # 합산·임계·목표주파수·신호세기 한 줄
        self.detail.setStyleSheet("font-family:Consolas; font-size:12px; color:#888;")

        self.scope = ScopeWidget()       # 실시간 파형·스펙트럼

        res_box = QGroupBox("음향 검증 결과")
        rl = QVBoxLayout(res_box)
        rl.addWidget(self.head); rl.addWidget(self.freq_bar); rl.addWidget(self.wave_bar); rl.addWidget(self.detail)
        rl.addWidget(self.scope, 1)

        # ================= 기대 beep (드래그앤드롭) =================
        self.beep_lbl = QLabel("여기로 .wav 를 끌어놓거나 [파일…]")
        self.beep_lbl.setStyleSheet("color:#888; border:1px dashed #555; padding:8px;")
        self.beep_lbl.setAlignment(Qt.AlignCenter)
        b_file = QPushButton("파일…"); b_file.clicked.connect(self.pick_beep)
        beep_box = QGroupBox("기대 경고음 (.wav 드래그앤드롭 / 파일)")
        bl = QVBoxLayout(beep_box); bl.addWidget(self.beep_lbl); bl.addWidget(b_file)

        # ================= 판정 임계 (주파수·파형 각각, AND 조건) =================
        self.freq_spin = self._mk_thr_spin(self.freq_thr, self._on_freq_thr)
        fq_minus = QPushButton("− (-)"); fq_minus.clicked.connect(lambda: self.adj_freq_thr(-0.05))
        fq_plus = QPushButton("+ (+)"); fq_plus.clicked.connect(lambda: self.adj_freq_thr(+0.05))
        self.wave_spin = self._mk_thr_spin(self.wave_thr, self._on_wave_thr)
        wv_minus = QPushButton("− ([)"); wv_minus.clicked.connect(lambda: self.adj_wave_thr(-0.05))
        wv_plus = QPushButton("+ (])"); wv_plus.clicked.connect(lambda: self.adj_wave_thr(+0.05))

        thr_box = QGroupBox("PASS 임계 — 주파수 AND 파형 (각 0~1, 둘 다 넘어야 PASS)")
        tg = QGridLayout(thr_box)
        tg.addWidget(QLabel("주파수 일치도 ≥"), 0, 0); tg.addWidget(self.freq_spin, 0, 1)
        tg.addWidget(fq_minus, 0, 2); tg.addWidget(fq_plus, 0, 3)
        tg.addWidget(QLabel("파형 일치도 ≥"), 1, 0); tg.addWidget(self.wave_spin, 1, 1)
        tg.addWidget(wv_minus, 1, 2); tg.addWidget(wv_plus, 1, 3)

        # ================= 마이크 =================
        self.mic_combo = QComboBox()
        self.mic_refresh = QPushButton("새로고침"); self.mic_refresh.clicked.connect(self.refresh_mics)
        mrow = QHBoxLayout(); mrow.addWidget(self.mic_combo, 1); mrow.addWidget(self.mic_refresh)
        mic_box = QGroupBox("마이크"); mic_box.setLayout(mrow)

        # ================= 버튼 =================
        self.b_meas = QPushButton("측정 시작 (S)"); self.b_meas.setCheckable(True)
        self.b_meas.toggled.connect(self.toggle_measure)
        self.b_reset = QPushButton("리셋 (R)"); self.b_reset.clicked.connect(self.reset_meas)
        self.b_save = QPushButton("보고서 저장 (D)"); self.b_save.clicked.connect(self.save_report)
        brow = QHBoxLayout(); brow.addWidget(self.b_meas); brow.addWidget(self.b_reset); brow.addWidget(self.b_save)

        lay = QVBoxLayout(self)
        lay.addWidget(res_box, 1)              # 결과(파형·스펙트럼)가 맨 위 + 남는 세로 차지
        row = QHBoxLayout(); row.addWidget(beep_box, 1); row.addWidget(mic_box, 1)
        lay.addLayout(row)
        lay.addWidget(thr_box)
        lay.addLayout(brow)

        self.timer = QTimer(self); self.timer.timeout.connect(self._poll); self.timer.start(60)
        self.refresh_mics()

        # ---- 단축키 (다른 탭과 통일) ----
        for key, cb in [("S", self.b_meas.toggle), ("R", self.reset_meas), ("D", self.save_report),
                        ("-", lambda: self.adj_freq_thr(-0.05)), ("=", lambda: self.adj_freq_thr(+0.05)),
                        ("+", lambda: self.adj_freq_thr(+0.05)),
                        ("[", lambda: self.adj_wave_thr(-0.05)), ("]", lambda: self.adj_wave_thr(+0.05))]:
            QShortcut(QKeySequence(key), self, cb)

    # ---- 드래그앤드롭 ----
    def dragEnterEvent(self, ev):
        for u in ev.mimeData().urls():
            if u.toLocalFile().lower().endswith(".wav"):
                ev.acceptProposedAction(); return
        ev.ignore()

    def dropEvent(self, ev):
        for u in ev.mimeData().urls():
            p = u.toLocalFile()
            if p.lower().endswith(".wav"):
                self._load_beep(p); ev.acceptProposedAction(); return

    # ---- 기대 beep ----
    def pick_beep(self):
        p, _ = QFileDialog.getOpenFileName(self, "기대 경고음 wav 선택", "", "오디오 (*.wav)")
        if p:
            self._load_beep(p)

    def _load_beep(self, p):
        beep, sr = load_beep(p)
        if beep is None or sr == 0:
            QMessageBox.warning(self, "beep", "wav 로드 실패 (scipy 필요)"); return
        self.beep, self.sr, self.beep_path = beep, sr, p
        self.matcher = BeepMatcher(beep, sr, freq_thr=self.freq_thr, wave_thr=self.wave_thr)
        self.beep_lbl.setStyleSheet("color:#0a0; border:1px solid #383; padding:8px;")
        self.beep_lbl.setText(f"{os.path.basename(p)}\nsr={sr} · {len(beep)/sr:.2f}s · "
                              f"주도 {self.matcher.target_freq:.0f}Hz")

    def _mk_thr_spin(self, val, cb):
        """0~1 임계용 스핀박스 생성 (0.05 단위)."""
        sp = QDoubleSpinBox()
        sp.setRange(0.0, 1.0); sp.setSingleStep(0.05); sp.setDecimals(2); sp.setValue(val)
        sp.valueChanged.connect(cb)
        return sp

    def _on_freq_thr(self, val):
        """주파수 임계 변경 → 현재 matcher에 즉시 반영."""
        self.freq_thr = float(val)
        if self.matcher is not None:
            self.matcher.freq_thr = self.freq_thr

    def _on_wave_thr(self, val):
        """파형 임계 변경 → 현재 matcher에 즉시 반영."""
        self.wave_thr = float(val)
        if self.matcher is not None:
            self.matcher.wave_thr = self.wave_thr

    def adj_freq_thr(self, delta):
        """버튼·단축키로 주파수 임계 증감 (스핀박스 → _on_freq_thr가 반영)."""
        self.freq_spin.setValue(round(self.freq_spin.value() + delta, 2))

    def adj_wave_thr(self, delta):
        """버튼·단축키로 파형 임계 증감 (스핀박스 → _on_wave_thr가 반영)."""
        self.wave_spin.setValue(round(self.wave_spin.value() + delta, 2))

    def refresh_mics(self):
        self.mic_combo.clear()
        for idx, name in devices.list_microphones():
            self.mic_combo.addItem(name, idx)
        if self.mic_combo.count() == 0:
            self.mic_combo.addItem("마이크 없음 / sounddevice 미설치", -1)

    # ---- 측정 ----
    def toggle_measure(self, on):
        if on:
            if self.matcher is None:
                self.b_meas.setChecked(False)
                QMessageBox.information(self, "측정", "먼저 기대 beep .wav를 지정하세요."); return
            dev = self.mic_combo.currentData()
            if sd is None or dev is None or dev == -1:
                self.b_meas.setChecked(False)
                QMessageBox.information(self, "측정", "마이크를 선택하세요."); return
            self._passed = None
            self._detect_ms = None
            self.matcher.arm()
            self._arm_t = time.perf_counter()        # 판단 속도 측정 기준시각
            try:
                self.stream = sd.InputStream(device=int(dev), channels=1, samplerate=self.sr,
                                             blocksize=2048, callback=self._cb)
                self.stream.start()
            except Exception as e:
                self.b_meas.setChecked(False)
                QMessageBox.warning(self, "측정", f"마이크 열기 실패:\n{e}"); return
            self.b_meas.setText("측정 정지 (S)")
        else:
            self._stop_stream()
            self.b_meas.setText("측정 시작 (S)")

    def _stop_stream(self):
        if self.stream is not None:
            try:
                self.stream.stop(); self.stream.close()
            except Exception:
                pass
            self.stream = None

    def reset_meas(self):
        if self.matcher:
            self.matcher.arm()
        self._passed = None
        self._detect_ms = None
        self._arm_t = time.perf_counter()            # 재무장 → 판단 속도 기준 재설정

    def _cb(self, indata, frames, t, status):
        if self.matcher is not None:
            res = self.matcher.feed(indata[:, 0], now=time.perf_counter())
            self._latest = res
            if res["match"] and self._passed is None:
                self._passed = res
                if self._arm_t is not None and res["onset_t"] is not None:
                    self._detect_ms = (res["onset_t"] - self._arm_t) * 1000.0

    def _poll(self):
        if self.matcher is not None:
            self.scope.set_data(self.matcher.buf, self.matcher.sr, self.matcher.target_freq)
        r = self._passed or self._latest
        if r is None:
            return
        self.freq_bar.setValue(int(r["freq_sim"] * 100))
        self.wave_bar.setValue(int(r["wave_sim"] * 100))
        if self._passed is not None:
            spd = f" · 판단 {self._detect_ms:.0f} ms" if self._detect_ms is not None else ""
            head, col = f"BEEP = PASS (확정){spd}", "#0c0"
        elif not r["has_sound"]:
            head, col = "대기 (소리 없음/약함)", "#888"
        elif r["is_pass"]:
            head, col = "일치 감지 → PASS", "#0a0"
        else:
            head, col = "불일치 → FAIL", "#c00"
        self.head.setStyleSheet(f"font-family:Consolas; font-size:18px; font-weight:bold; color:{col};")
        self.head.setText(head)
        fq_ok = "✓" if r["freq_sim"] >= r["freq_thr"] else "✗"
        wv_ok = "✓" if r["wave_sim"] >= r["wave_thr"] else "✗"
        self.detail.setText(
            f"주파수 {r['freq_sim']:.2f}[≥{r['freq_thr']:.2f}]{fq_ok} AND "
            f"파형 {r['wave_sim']:.2f}[≥{r['wave_thr']:.2f}]{wv_ok}  ·  "
            f"목표 {r['target_freq']:.0f}Hz  ·  신호세기 {r['energy_ratio']:.1f} "
            f"{'✓소리있음' if r['has_sound'] else '·조용'}"
        )

    def save_report(self):
        r = self._passed or self._latest
        if r is None or self.matcher is None:
            QMessageBox.information(self, "저장", "측정 데이터 없음"); return
        ts = time.strftime("%Y%m%d_%H%M%S")
        dd = os.path.join(config.RESULTS_DIR, ts); os.makedirs(dd, exist_ok=True)
        ok = "PASS" if r["is_pass"] else "FAIL"
        rep = (
            "========================================\n"
            " UN R158 음향(경고음) 일치 검증\n"
            "========================================\n"
            f"시각             : {ts}\n"
            f"기대 beep        : {os.path.basename(self.beep_path or '-')}\n"
            f"판정             : {ok}\n\n"
            "[1] 주파수 일치도  : %.3f  [>= %.2f]\n" % (r["freq_sim"], r["freq_thr"]) +
            "[2] 파형 일치도    : %.3f  [>= %.2f]\n" % (r["wave_sim"], r["wave_thr"]) +
            "[3] 판정 조건      : 주파수 AND 파형 (둘 다 임계 이상)\n"
            f"[4] 목표 주파수    : {r['target_freq']:.0f} Hz\n"
            "[5] 판단 소요 시간 : %s\n" % (f"{self._detect_ms:.0f} ms (측정 시작→일치 확정)"
                                          if self._detect_ms is not None else "- (미검출)") +
            f"  결과            : {ok}\n"
            "========================================\n"
        )
        with open(os.path.join(dd, "audio_result.txt"), "w", encoding="utf-8") as fp:
            fp.write(rep)
        with open(os.path.join(config.RESULTS_DIR, "audio_realtime_log.txt"), "a", encoding="utf-8") as fp:
            fp.write(rep + "\n")
        QMessageBox.information(self, "저장", f"저장됨:\n{dd}")

    def cleanup(self):
        self.timer.stop()
        self._stop_stream()
