"""① 설정 화면 — 웹캠 선택+미리보기, 마이크 선택+레벨. (기준 이미지는 각 탭에서 설정)"""
from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtWidgets import (
    QWidget, QLabel, QPushButton, QComboBox, QHBoxLayout, QVBoxLayout,
    QGroupBox, QProgressBar, QFormLayout, QSizePolicy,
)

from .. import devices
from ..engine.tone import TonePlayer
from . import overlay

PREVIEW = 460


class SquarePreview(QLabel):
    """창 높이에 맞춰 커지는 정사각 카메라 미리보기 (폭=높이, 레터박스 없음)."""
    def __init__(self, minsize=PREVIEW):
        super().__init__("카메라 미리보기")
        self.setMinimumSize(minsize, minsize)
        self.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Expanding)
        self.setStyleSheet("background:#111; color:#888;")
        self.setAlignment(Qt.AlignCenter)
        self._pm = None

    def set_frame(self, pm):
        self._pm = pm
        self._rescale()

    def _rescale(self):
        side = self.height()
        if self.width() != side:
            self.setFixedWidth(side)
        if self._pm is not None:
            self.setPixmap(self._pm.scaled(side, side, Qt.KeepAspectRatio, Qt.SmoothTransformation))

    def resizeEvent(self, ev):
        self._rescale()
        super().resizeEvent(ev)


class SettingsTab(QWidget):
    cameraChanged = Signal(int)

    def __init__(self):
        super().__init__()
        self.meter = devices.MicMeter()
        self.tone = TonePlayer()

        # ---- 웹캠 ----
        self.cam_combo = QComboBox()
        self.cam_refresh = QPushButton("새로고침"); self.cam_refresh.clicked.connect(self.refresh_cameras)
        self.cam_combo.currentIndexChanged.connect(self._on_cam_changed)
        cam_row = QHBoxLayout(); cam_row.addWidget(self.cam_combo, 1); cam_row.addWidget(self.cam_refresh)

        self.preview = SquarePreview()

        cam_box = QGroupBox("웹캠")
        cv_ = QVBoxLayout(cam_box); cv_.addLayout(cam_row); cv_.addWidget(self.preview, 1)

        # ---- 마이크 ----
        self.mic_combo = QComboBox()
        self.mic_refresh = QPushButton("새로고침"); self.mic_refresh.clicked.connect(self.refresh_mics)
        self.mic_test = QPushButton("마이크 테스트 시작"); self.mic_test.setCheckable(True)
        self.mic_test.toggled.connect(self._on_mic_test)
        self.mic_level = QProgressBar(); self.mic_level.setRange(0, 100); self.mic_level.setTextVisible(False)
        mic_form = QFormLayout()
        mrow = QHBoxLayout(); mrow.addWidget(self.mic_combo, 1); mrow.addWidget(self.mic_refresh)
        mic_form.addRow("장치", self._wrap(mrow))
        mic_form.addRow("입력 레벨", self.mic_level)
        mic_form.addRow("", self.mic_test)

        self.tone_test = QPushButton("경고음 테스트")
        self.tone_test.clicked.connect(self._on_tone_test)
        if not self.tone.available:
            self.tone_test.setEnabled(False); self.tone_test.setText("경고음 테스트 (sounddevice 미설치)")
        mic_form.addRow("", self.tone_test)
        mic_box = QGroupBox("마이크"); mic_box.setLayout(mic_form)

        self.status = QLabel("웹캠·마이크를 선택하고 미리보기로 확인하세요. (기준 이미지는 ②·③ 탭에서 설정)")
        self.status.setStyleSheet("color:#666;")

        top = QHBoxLayout()
        top.addWidget(cam_box, 1)
        top.addWidget(mic_box, 1, Qt.AlignTop)        # 마이크 폼은 위 정렬, 카메라만 세로로 채움
        lay = QVBoxLayout(self)
        lay.addLayout(top, 1)                          # 창 하단까지 채움
        lay.addWidget(self.status)

        self._lvl_timer = QTimer(self); self._lvl_timer.timeout.connect(self._poll_level); self._lvl_timer.start(60)
        self.refresh_cameras(); self.refresh_mics()

    def _wrap(self, layout):
        w = QWidget(); w.setLayout(layout); return w

    # ---- 카메라 ----
    def refresh_cameras(self):
        self.status.setText("카메라 검색 중…")
        self.cam_combo.blockSignals(True); self.cam_combo.clear()
        cams = devices.list_cameras()
        for idx, label in cams:
            self.cam_combo.addItem(label, idx)
        self.cam_combo.blockSignals(False)
        if cams:
            self.status.setText(f"카메라 {len(cams)}개 발견"); self._on_cam_changed(0)
        else:
            self.status.setText("연결된 카메라 없음")

    def _on_cam_changed(self, _):
        idx = self.cam_combo.currentData()
        if idx is not None:
            self.cameraChanged.emit(int(idx))

    def update_frame(self, frame):
        self.preview.set_frame(overlay.bgr_to_pixmap(frame))

    # ---- 마이크 ----
    def refresh_mics(self):
        self.mic_combo.clear()
        mics = devices.list_microphones()
        for idx, name in mics:
            self.mic_combo.addItem(name, idx)
        if not mics:
            self.mic_combo.addItem("마이크 없음 / sounddevice 미설치", -1)

    def _on_mic_test(self, on):
        if on:
            dev = self.mic_combo.currentData()
            if dev is None or dev == -1 or not self.meter.start(int(dev)):
                self.mic_test.setChecked(False); self.status.setText("마이크 열기 실패"); return
            self.mic_test.setText("마이크 테스트 정지")
        else:
            self.meter.stop(); self.mic_test.setText("마이크 테스트 시작")

    def _on_tone_test(self):
        if not self.tone.play():
            self.status.setText("경고음 재생 실패")

    def _poll_level(self):
        self.mic_level.setValue(min(100, int(self.meter.level * 400)) if self.mic_test.isChecked() else 0)

    def cleanup(self):
        self.meter.stop()
        self.tone.stop()
