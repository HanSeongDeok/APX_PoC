"""웹캠에서 마우스 드래그로 기준 이미지를 직접 캡처하는 다이얼로그 (안전장치).
사전에 준비된 이미지 파일이 없어도, 라이브 웹캠 화면에서 필요한 영역을
드래그로 잘라 기준/검출 이미지로 저장한다."""
import os
import time
import cv2
from PySide6.QtCore import Qt, QTimer, QRect, QPoint
from PySide6.QtGui import QPainter, QPen, QColor
from PySide6.QtWidgets import (
    QDialog, QLabel, QPushButton, QVBoxLayout, QHBoxLayout, QMessageBox,
)

from . import overlay

VIEW = 640   # 캡처 뷰 최대 폭(px)


class RubberLabel(QLabel):
    """프레임을 표시하고 마우스 드래그로 사각형 선택. 선택은 위젯 좌표."""

    def __init__(self):
        super().__init__()
        self.setStyleSheet("background:#000;")
        self.setAlignment(Qt.AlignCenter)
        self._p0 = None
        self._p1 = None

    def clear_sel(self):
        self._p0 = self._p1 = None
        self.update()

    def selection(self):
        if self._p0 is None or self._p1 is None:
            return None
        r = QRect(self._p0, self._p1).normalized()
        return r if r.width() > 4 and r.height() > 4 else None

    def mousePressEvent(self, ev):
        self._p0 = ev.position().toPoint(); self._p1 = self._p0; self.update()

    def mouseMoveEvent(self, ev):
        if self._p0 is not None:
            self._p1 = ev.position().toPoint(); self.update()

    def mouseReleaseEvent(self, ev):
        if self._p0 is not None:
            self._p1 = ev.position().toPoint(); self.update()

    def paintEvent(self, ev):
        super().paintEvent(ev)
        r = self.selection()
        if r is not None:
            p = QPainter(self)
            p.setPen(QPen(QColor(0, 255, 0), 2, Qt.DashLine))
            p.drawRect(r)


class CaptureDialog(QDialog):
    """frame_grabber(): 최신 BGR 프레임 반환. 저장 시 result_path 세팅."""

    def __init__(self, frame_grabber, slot_name="capture", parent=None):
        super().__init__(parent)
        self.setWindowTitle("웹캠에서 기준 이미지 캡처")
        self.grab = frame_grabber
        self.slot = slot_name
        self.result_path = None
        self._frozen = None          # 고정된 프레임(BGR)
        self._shown_wh = (VIEW, VIEW)  # 현재 표시 픽스맵 크기

        self.view = RubberLabel()
        self.hint = QLabel("드래그로 필요한 영역을 선택하세요. (선택 없으면 전체 프레임 사용)")
        self.hint.setStyleSheet("color:#666;")

        self.b_freeze = QPushButton("정지 (선택 쉬움)"); self.b_freeze.setCheckable(True)
        self.b_freeze.toggled.connect(self._on_freeze)
        self.b_clear = QPushButton("선택 지우기"); self.b_clear.clicked.connect(self.view.clear_sel)
        self.b_save = QPushButton("이 영역 저장 & 사용"); self.b_save.clicked.connect(self._save)
        self.b_cancel = QPushButton("취소"); self.b_cancel.clicked.connect(self.reject)

        row = QHBoxLayout()
        for b in [self.b_freeze, self.b_clear, self.b_save, self.b_cancel]:
            row.addWidget(b)
        lay = QVBoxLayout(self)
        lay.addWidget(self.view); lay.addWidget(self.hint); lay.addLayout(row)

        self.timer = QTimer(self); self.timer.timeout.connect(self._refresh); self.timer.start(30)

    def _on_freeze(self, on):
        if on:
            fr = self.grab()
            self._frozen = None if fr is None else fr.copy()
            self.b_freeze.setText("정지 해제")
        else:
            self._frozen = None
            self.b_freeze.setText("정지 (선택 쉬움)")

    def _current(self):
        return self._frozen if self._frozen is not None else self.grab()

    def _refresh(self):
        fr = self._current()
        if fr is None:
            self.view.setText("웹캠 프레임 없음 — 설정에서 카메라 선택 확인")
            return
        h, w = fr.shape[:2]
        scale = VIEW / max(w, h)
        dw, dh = int(w * scale), int(h * scale)
        self._shown_wh = (dw, dh)
        disp = cv2.resize(fr, (dw, dh))
        self.view.setFixedSize(dw, dh)         # 위젯=픽스맵 크기 → 좌표 1:1
        self.view.setPixmap(overlay.bgr_to_pixmap(disp))

    def _save(self):
        fr = self._current()
        if fr is None:
            QMessageBox.information(self, "캡처", "웹캠 프레임이 없습니다."); return
        h, w = fr.shape[:2]
        dw, dh = self._shown_wh
        sel = self.view.selection()
        if sel is not None:
            sx, sy = w / dw, h / dh
            x1 = max(0, int(sel.left() * sx)); y1 = max(0, int(sel.top() * sy))
            x2 = min(w, int(sel.right() * sx)); y2 = min(h, int(sel.bottom() * sy))
            crop = fr[y1:y2, x1:x2]
        else:
            crop = fr
        if crop.size == 0:
            QMessageBox.information(self, "캡처", "선택 영역이 너무 작습니다."); return
        cap_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))),
                               "results", "captures")
        os.makedirs(cap_dir, exist_ok=True)
        path = os.path.join(cap_dir, f"{self.slot}_{time.strftime('%Y%m%d_%H%M%S')}.png")
        cv2.imwrite(path, crop)
        self.result_path = path
        self.accept()

    def closeEvent(self, ev):
        self.timer.stop()
        super().closeEvent(ev)


class RoiDialog(QDialog):
    """정렬 캔버스(640) 이미지 위에서 드래그로 팝업 ROI를 직접 지정.
    result_roi = (y1,y2,x1,x2) 캔버스 좌표. (차종별 팝업 위치 수동 지정용)"""

    def __init__(self, canon_img, parent=None):
        super().__init__(parent)
        self.setWindowTitle("팝업 영역 직접 지정 (드래그)")
        self.canon = canon_img
        self.result_roi = None
        h, w = canon_img.shape[:2]
        scale = VIEW / max(w, h)
        self._dw, self._dh = int(w * scale), int(h * scale)

        self.view = RubberLabel()
        self.view.setFixedSize(self._dw, self._dh)
        self.view.setPixmap(overlay.bgr_to_pixmap(cv2.resize(canon_img, (self._dw, self._dh))))
        self.hint = QLabel("팝업(경고문구+카메라) 영역을 드래그로 감싸세요.")
        self.hint.setStyleSheet("color:#666;")

        b_ok = QPushButton("이 영역으로 지정"); b_ok.clicked.connect(self._ok)
        b_cancel = QPushButton("취소"); b_cancel.clicked.connect(self.reject)
        row = QHBoxLayout(); row.addWidget(b_ok); row.addWidget(b_cancel)
        lay = QVBoxLayout(self); lay.addWidget(self.view); lay.addWidget(self.hint); lay.addLayout(row)

    def _ok(self):
        sel = self.view.selection()
        if sel is None:
            QMessageBox.information(self, "지정", "먼저 영역을 드래그하세요."); return
        H, W = self.canon.shape[:2]
        sx, sy = W / self._dw, H / self._dh
        x1 = max(0, int(sel.left() * sx)); x2 = min(W, int(sel.right() * sx))
        y1 = max(0, int(sel.top() * sy)); y2 = min(H, int(sel.bottom() * sy))
        if x2 - x1 < 8 or y2 - y1 < 8:
            QMessageBox.information(self, "지정", "영역이 너무 작습니다."); return
        self.result_roi = (y1, y2, x1, x2)
        self.accept()
