"""기준 이미지 한 칸 위젯 — 썸네일 + 경로 + [파일…] + [웹캠 캡처]. (탭에서 재사용)"""
import os
import cv2
from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QWidget, QLabel, QPushButton, QHBoxLayout, QVBoxLayout, QFileDialog, QMessageBox,
)

from ..engine.gear import imread_kr
from . import overlay
from .capture import CaptureDialog

THUMB = (140, 100)


class ImageSlot(QWidget):
    changed = Signal(str)   # 새 경로

    def __init__(self, title, init_path, frame_grabber, slot_key):
        super().__init__()
        self.path = init_path
        self.frame_grabber = frame_grabber
        self.slot_key = slot_key

        self.thumb = QLabel(); self.thumb.setFixedSize(*THUMB)
        self.thumb.setStyleSheet("background:#111; border:1px solid #333;")
        self.thumb.setAlignment(Qt.AlignCenter)
        self.title = QLabel(title); self.title.setStyleSheet("font-weight:bold;")
        self.title.setWordWrap(True)
        self.path_lbl = QLabel(); self.path_lbl.setStyleSheet("color:#888; font-size:11px;")
        self.path_lbl.setWordWrap(True)
        b_file = QPushButton("파일…"); b_file.clicked.connect(self.pick_file)
        b_cap = QPushButton("웹캠 캡처"); b_cap.clicked.connect(self.capture)

        rt = QVBoxLayout(); rt.addWidget(self.title); rt.addWidget(self.path_lbl, 1)
        brow = QHBoxLayout(); brow.addWidget(b_file); brow.addWidget(b_cap); rt.addLayout(brow)
        lay = QHBoxLayout(self); lay.addWidget(self.thumb); lay.addLayout(rt, 1)
        self._refresh_thumb()

    def _refresh_thumb(self):
        self.path_lbl.setText(self.path or "(없음)")
        im = imread_kr(self.path) if self.path and os.path.exists(self.path) else None
        if im is None:
            self.thumb.setPixmap(QPixmap()); self.thumb.setText("없음")
            return
        self.thumb.setPixmap(overlay.bgr_to_pixmap(cv2.resize(im, THUMB)))

    def set_path(self, path):
        if path:
            self.path = path
            self._refresh_thumb()
            self.changed.emit(path)

    def pick_file(self):
        p, _ = QFileDialog.getOpenFileName(self, "기준 이미지 선택", "",
                                           "이미지 (*.png *.jpg *.jpeg *.bmp)")
        self.set_path(p)

    def capture(self):
        if self.frame_grabber is None or self.frame_grabber() is None:
            QMessageBox.information(self, "캡처", "웹캠 프레임 없음 — ① 설정에서 카메라를 먼저 선택하세요.")
            return
        dlg = CaptureDialog(self.frame_grabber, self.slot_key, self)
        if dlg.exec() and dlg.result_path:
            self.set_path(dlg.result_path)
