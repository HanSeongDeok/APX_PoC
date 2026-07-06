"""① 설정 화면 — 웹캠 선택+미리보기, 마이크 선택+레벨. (기준 이미지는 각 탭에서 설정)"""
from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtWidgets import (
    QWidget, QLabel, QPushButton, QComboBox, QHBoxLayout, QVBoxLayout,
    QGroupBox, QProgressBar, QFormLayout,
)

from .. import devices
from . import overlay

PREVIEW = 460


class SettingsTab(QWidget):
    cameraChanged = Signal(int)

    def __init__(self):
        super().__init__()
        self.meter = devices.MicMeter()

        # ---- 웹캠 ----
        self.cam_combo = QComboBox()
        self.cam_refresh = QPushButton("새로고침"); self.cam_refresh.clicked.connect(self.refresh_cameras)
        self.cam_combo.currentIndexChanged.connect(self._on_cam_changed)
        cam_row = QHBoxLayout(); cam_row.addWidget(self.cam_combo, 1); cam_row.addWidget(self.cam_refresh)

        self.preview = QLabel("카메라 미리보기")
        self.preview.setFixedSize(PREVIEW, PREVIEW)
        self.preview.setStyleSheet("background:#111; color:#888;")
        self.preview.setAlignment(Qt.AlignCenter)

        cam_box = QGroupBox("웹캠")
        cv_ = QVBoxLayout(cam_box); cv_.addLayout(cam_row); cv_.addWidget(self.preview)

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
        mic_box = QGroupBox("마이크"); mic_box.setLayout(mic_form)

        self.status = QLabel("웹캠·마이크를 선택하고 미리보기로 확인하세요. (기준 이미지는 ②·③ 탭에서 설정)")
        self.status.setStyleSheet("color:#666;")

        top = QHBoxLayout(); top.addWidget(cam_box, 1); top.addWidget(mic_box, 1)
        lay = QVBoxLayout(self); lay.addLayout(top); lay.addWidget(self.status); lay.addStretch(1)

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
        pm = overlay.bgr_to_pixmap(frame).scaled(PREVIEW, PREVIEW, Qt.KeepAspectRatio)
        self.preview.setPixmap(pm)

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

    def _poll_level(self):
        self.mic_level.setValue(min(100, int(self.meter.level * 400)) if self.mic_test.isChecked() else 0)

    def cleanup(self):
        self.meter.stop()
