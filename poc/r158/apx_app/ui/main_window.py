"""메인 윈도우 — Gear / Cluster 탭, 웹캠 루프, 지표 패널, 컨트롤.
기어/클러스터 버튼은 문구·위치·단축키를 통일했다."""
import os
import sys
import time
import subprocess
import cv2
from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QShortcut, QKeySequence
from PySide6.QtWidgets import (
    QMainWindow, QWidget, QLabel, QPushButton, QHBoxLayout, QVBoxLayout,
    QTabWidget, QGridLayout, QMessageBox, QSizePolicy,
)

from .. import config
from ..engine.gear import GearDetector
from ..engine.cluster import ClusterDetector
from . import overlay
from .settings_tab import SettingsTab
from .image_slot import ImageSlot
from .capture import RoiDialog
from .audio_tab import AudioTab
from PySide6.QtWidgets import QGroupBox

DISPLAY = 512   # 영상 표시 크기(px). 엔진 캔버스는 640 정사각 → 클릭좌표 환산에 사용
PLAYER_STYLE = "background:#0a7a5a; color:white; font-weight:bold; padding:6px;"


def launch_player(script_path):
    """테스트 플레이어(cv2 창)를 별도 프로세스로 실행 — Qt 이벤트루프와 분리."""
    try:
        subprocess.Popen([sys.executable, script_path], cwd=config.PROJECT_ROOT)
        return True, ""
    except Exception as e:
        return False, str(e)


def save_evidence(dd, evd):
    """판단 전후 ±3프레임 스냅샷 저장 + 시간값 텍스트 블록 반환 (판단시점 기준 상대ms)."""
    if not evd:
        return "\n[증거] 판단 전후 프레임 : (판단 미발생 — 없음)\n"
    dt = evd["decide"][1]
    items = [("evidence_pre_-3f", evd["pre"]), ("evidence_decide", evd["decide"]),
             ("evidence_post_+3f", evd["post"])]
    lines = ["\n[증거] 판단 전후 프레임 스냅샷 (판단시점=0 기준 상대시간)"]
    for name, item in items:
        if item is None:
            lines.append(f"  {name:18s}: (미수집)"); continue
        img, t = item
        cv2.imwrite(os.path.join(dd, name + ".png"), img)
        lines.append(f"  {name:18s}: {(t - dt) * 1000.0:+.0f} ms   ({name}.png)")
    return "\n".join(lines) + "\n"


class ClickableLabel(QLabel):
    """클릭 좌표를 엔진 캔버스(640) 기준으로 환산해 emit.
    창 높이에 맞춰 세로로 늘어나는 정사각 영상 영역(최소 DISPLAY)."""
    clicked = Signal(int, int)

    def __init__(self, canon_size=640):
        super().__init__()
        self.canon_size = canon_size
        self.setMinimumSize(DISPLAY, DISPLAY)
        self.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Expanding)  # 세로로 늘고 폭=높이(정사각)
        self.setStyleSheet("background:#111;")
        self.setAlignment(Qt.AlignCenter)
        self._frame_pm = None       # 최신 프레임 픽스맵(리사이즈 시 재스케일)

    def set_frame(self, pm):
        self._frame_pm = pm
        self._rescale()

    def _rescale(self):
        side = self.height()
        if self.width() != side:            # 폭을 높이에 맞춰 정사각 유지(레터박스 없음)
            self.setFixedWidth(side)
        if self._frame_pm is not None:
            self.setPixmap(self._frame_pm.scaled(side, side, Qt.KeepAspectRatio,
                                                 Qt.SmoothTransformation))

    def resizeEvent(self, ev):
        self._rescale()
        super().resizeEvent(ev)

    def mousePressEvent(self, ev):
        s = min(self.width(), self.height())
        if s:
            cx = int(ev.position().x() * self.canon_size / s)
            cy = int(ev.position().y() * self.canon_size / s)
            self.clicked.emit(cx, cy)


class RoiSlot(QWidget):
    """드래그로 지정한 팝업 판정 영역 — ImageSlot과 같은 룩(썸네일 + 제목 + 안내 + 버튼)."""
    def __init__(self, title, on_pick):
        super().__init__()
        self.thumb = QLabel("미지정"); self.thumb.setFixedSize(140, 100)
        self.thumb.setStyleSheet("background:#111; color:#888; border:1px solid #333;")
        self.thumb.setAlignment(Qt.AlignCenter)
        self.title = QLabel(title); self.title.setStyleSheet("font-weight:bold;")
        self.title.setWordWrap(True)
        self.info = QLabel("기준 화면에서 팝업 영역을 드래그하세요")
        self.info.setStyleSheet("color:#888; font-size:11px;"); self.info.setWordWrap(True)
        b = QPushButton("영역 지정 (드래그)"); b.clicked.connect(on_pick)
        rt = QVBoxLayout(); rt.addWidget(self.title); rt.addWidget(self.info, 1)
        brow = QHBoxLayout(); brow.addWidget(b); rt.addLayout(brow)
        lay = QHBoxLayout(self); lay.addWidget(self.thumb); lay.addLayout(rt, 1)

    def set_crop(self, bgr, roi):
        if bgr is None or bgr.size == 0:
            return
        self.thumb.setPixmap(overlay.bgr_to_pixmap(cv2.resize(bgr, (140, 100))))
        self.info.setText(f"팝업 영역 {roi}")


class GearTab(QWidget):
    def __init__(self, frame_grabber=None):
        super().__init__()
        self.det = None
        self.gear_ref = config.GEAR_REF     # 런타임 교체 가능
        self.armed = False
        self.last_res = None

        self.video = ClickableLabel(640)
        self.video.clicked.connect(self.on_click)
        self.metrics = QLabel("엔진 준비 안됨"); self.metrics.setTextFormat(Qt.RichText)
        self.metrics.setStyleSheet("font-family:Consolas; font-size:13px;")
        self.metrics.setAlignment(Qt.AlignTop)

        # ---- 공통 버튼 (클러스터와 문구·위치·단축키 통일) ----
        b_align = QPushButton("정렬 리셋 (C)"); b_align.clicked.connect(self.reset_align)
        b_reset = QPushButton("판정 리셋 (R)"); b_reset.clicked.connect(self.reset_judge)
        b_tminus = QPushButton("임계 − (-)"); b_tminus.clicked.connect(lambda: self.adj_thr(-0.01))
        b_tplus = QPushButton("임계 + (+)"); b_tplus.clicked.connect(lambda: self.adj_thr(+0.01))
        b_save = QPushButton("보고서 저장 (D)"); b_save.clicked.connect(self.save_report)
        # ---- 기어 전용 버튼 ----
        b_auto = QPushButton("R 자동재검출 (A)"); b_auto.clicked.connect(self.auto_recalib)
        b_click = QPushButton("R 위치 재지정 (M)"); b_click.clicked.connect(self.toggle_arm)
        self.b_click = b_click

        btns = QGridLayout()
        btns.addWidget(b_align, 0, 0); btns.addWidget(b_reset, 0, 1)
        btns.addWidget(b_tminus, 1, 0); btns.addWidget(b_tplus, 1, 1)
        btns.addWidget(b_save, 2, 0); btns.addWidget(b_auto, 2, 1)
        btns.addWidget(b_click, 3, 0)

        # ---- 테스트 플레이어 (다른 색 버튼) ----
        b_player = QPushButton("▶ 테스트 화면 열기 (기어봉 P·R·N·D)")
        b_player.setStyleSheet(PLAYER_STYLE); b_player.clicked.connect(self.open_player)

        # ---- 기준 이미지 (이 탭에서 설정) ----
        self.gear_slot = ImageSlot("기어 판정/정렬 기준 (R 이동된 기어봉 정면)",
                                   self.gear_ref, frame_grabber, "gear_ref")
        self.gear_slot.changed.connect(self.set_ref)
        ref_box = QGroupBox("기준 이미지"); rb = QVBoxLayout(ref_box); rb.addWidget(self.gear_slot)

        # 웹캠·오른쪽 컬럼 모두 창 높이에 맞춰 채움 → 하단선이 창 하단까지 일치.
        right_w = QWidget()
        right = QVBoxLayout(right_w); right.setContentsMargins(0, 0, 0, 0)
        right.addWidget(self.metrics, 1)
        right.addWidget(b_player); right.addLayout(btns); right.addWidget(ref_box)
        lay = QHBoxLayout(self)
        lay.addWidget(self.video)
        lay.addWidget(right_w, 1)

        # ---- 단축키 (통일) ----
        for key, cb in [("C", self.reset_align), ("R", self.reset_judge),
                        ("-", lambda: self.adj_thr(-0.01)), ("=", lambda: self.adj_thr(+0.01)),
                        ("+", lambda: self.adj_thr(+0.01)), ("D", self.save_report),
                        ("A", self.auto_recalib), ("M", self.toggle_arm)]:
            QShortcut(QKeySequence(key), self, cb)

    def open_player(self):
        ok, err = launch_player(config.GEAR_PLAYER)
        if not ok:
            QMessageBox.warning(self, "테스트 화면", f"실행 실패:\n{err}")

    def set_ref(self, path):
        self.gear_ref = path
        self.det = None     # 다음 프레임에 새 기준영상으로 재생성

    def reset_align(self):
        if self.det:
            self.det.reset_alignment()

    def reset_judge(self):
        if self.det:
            self.det.reset_judgment()

    def ensure_engine(self):
        if self.det is None:
            self.det = GearDetector(self.gear_ref)
        return self.det

    def toggle_arm(self):
        self.armed = not self.armed
        self.b_click.setText("클릭 대기 중… 영상에서 R 클릭" if self.armed else "R 위치 재지정 (M)")

    def on_click(self, cx, cy):
        if self.armed and self.det is not None:
            ok = self.det.recalibrate_click((cx, cy))
            self.armed = False
            self.b_click.setText("R 위치 재지정 (M)")
            if not ok:
                QMessageBox.information(self, "재클릭", "칸 검출 실패 — R 글자 위를 더 정확히 클릭")

    def auto_recalib(self):
        if self.det:
            ok = self.det.recalibrate_auto()
            if not ok:
                QMessageBox.information(self, "자동검출", "실패 — R 위치 재지정으로 수동 지정하세요")

    def adj_thr(self, dv):
        if self.det:
            self.det.margin = max(0.0, min(0.5, self.det.margin + dv))

    def update_frame(self, frame):
        det = self.ensure_engine()
        res = det.process(frame)
        self.last_res = res
        disp = overlay.draw_gear(res)
        self.video.set_frame(overlay.bgr_to_pixmap(disp))
        self.metrics.setText(self._metrics_html(res))

    def _metrics_html(self, res):
        if res["state"] != "ok":
            return f"<b>상태:</b> {res['state']}"
        lat = ("%.0f ms (frame %.0f + analysis %.1f)" %
               (res["pass_ms"], res["frame_gap_ms"], res["analysis_ms"])) if res["pass_ms"] else "-"
        ang = res["lock_ang"]
        c = "#0c0" if res["is_R"] else "#c00"
        return (
            f"<b style='color:{c}'>GEAR = {'R' if res['is_R'] else 'not R'}</b><br><br>"
            f"[1] R 검출 지연: {lat}<br>"
            f"[2] ORB 정렬: LOCKED  inliers {res['lock_inliers']}"
            + (f"  roll {ang['roll']:.1f}° scale {ang['scale']:.2f}" if ang else "") + "<br>"
            f"[3] 일치도: R {res['r_ratio']:.3f} / 2nd {res['second_ratio']:.3f}  "
            f"R−2nd {res['r_gap']:+.3f}  [≥ {res['margin']:.2f}]"
        )

    def save_report(self):
        r = self.last_res
        if not r or r["state"] != "ok":
            QMessageBox.information(self, "저장", "R 위치 미확정 — 저장 불가"); return
        ts = time.strftime("%Y%m%d_%H%M%S")
        dd = os.path.join(config.RESULTS_DIR, ts); os.makedirs(dd, exist_ok=True)
        cv2.imwrite(os.path.join(dd, "gear_R.png"), r["canon"])
        ang = r["lock_ang"]
        lat = ("%.0f" % r["pass_ms"]) if r["pass_ms"] else "N/A"
        rep = (
            "========================================\n"
            " UN R158 기어 R 검출 (시간 검증)\n"
            "========================================\n"
            f"시각             : {ts}\n"
            f"판정             : {'PASS (R 검출)' if r['is_R'] else 'FAIL'}\n\n"
            "[1] R 검출 지연 (프레임 도착 + 분석)\n"
            f"  전체 지연       : {lat} ms\n"
            f"   - 프레임 도착   : {r['frame_gap_ms']:.0f} ms\n"
            f"   - 분석 시간     : {(r['analysis_ms'] or 0):.1f} ms\n\n"
            "[2] ORB 정렬 (각도)\n"
            f"  inliers         : {r['lock_inliers']}\n"
            f"  roll/scale      : {ang['roll']:.1f} deg / {ang['scale']:.2f}\n\n" if ang else ""
        )
        rep += (
            "[3] 일치도 (R칸 밝기)\n"
            f"  R칸 / 2위 / 차이 : {r['r_ratio']:.3f} / {r['second_ratio']:.3f} / {r['r_gap']:+.3f}\n"
            f"  기대(margin)    : >= {r['margin']:.2f}\n"
            f"  결과            : {'PASS' if r['is_R'] else 'FAIL'}\n"
        )
        rep += save_evidence(dd, self.det.get_evidence())
        rep += "========================================\n"
        with open(os.path.join(dd, "gear_result.txt"), "w", encoding="utf-8") as fp:
            fp.write(rep)
        with open(os.path.join(config.RESULTS_DIR, "gear_realtime_log.txt"), "a", encoding="utf-8") as fp:
            fp.write(rep + "\n")
        QMessageBox.information(self, "저장", f"저장됨:\n{dd}")


class ClusterTab(QWidget):
    def __init__(self, frame_grabber=None):
        super().__init__()
        self.det = None
        # ORB 정렬 기준 = 팝업(경고창) 뜬 화면. 팝업 판정 템플릿은 여기서 드래그로 크롭.
        self.cluster_ref = config.CLUSTER_POPUP_REF    # 런타임 교체 가능
        self.cluster_roi = None                        # 드래그로 지정한 팝업 영역(선택)
        self.last_res = None

        self.video = ClickableLabel(640)
        self.metrics = QLabel("엔진 준비 안됨"); self.metrics.setTextFormat(Qt.RichText)
        self.metrics.setStyleSheet("font-family:Consolas; font-size:13px;")
        self.metrics.setAlignment(Qt.AlignTop)

        # ---- 공통 버튼 (기어와 문구·위치·단축키 통일) ----
        b_align = QPushButton("정렬 리셋 (C)"); b_align.clicked.connect(self.reset_align)
        b_reset = QPushButton("판정 리셋 (R)"); b_reset.clicked.connect(self.reset_judge)
        b_tminus = QPushButton("임계 − (-)"); b_tminus.clicked.connect(lambda: self.adj_thr(-0.02))
        b_tplus = QPushButton("임계 + (+)"); b_tplus.clicked.connect(lambda: self.adj_thr(+0.02))
        b_save = QPushButton("보고서 저장 (D)"); b_save.clicked.connect(self.save_report)

        btns = QGridLayout()
        btns.addWidget(b_align, 0, 0); btns.addWidget(b_reset, 0, 1)
        btns.addWidget(b_tminus, 1, 0); btns.addWidget(b_tplus, 1, 1)
        btns.addWidget(b_save, 2, 0)

        # ---- 테스트 플레이어 (다른 색 버튼) ----
        b_player = QPushButton("▶ 테스트 화면 열기 (클러스터 일반·팝업)")
        b_player.setStyleSheet(PLAYER_STYLE); b_player.clicked.connect(self.open_player)

        # ---- 기준 이미지 (이 탭에서 설정) ----
        #  ORB 정렬 기준 = 팝업(경고창)이 뜬 전체 화면. [팝업 영역 지정 (드래그)]로 이 화면에서
        #  경고창 영역을 드래그 → 그 고정 영역만 라이브와 비교해 PASS 판정.
        self.ref_slot = ImageSlot("ORB 정렬 기준 (팝업 뜬 화면)",
                                  self.cluster_ref, frame_grabber, "cluster_ref")
        self.ref_slot.changed.connect(self._on_img_changed)
        #  팝업 판정 영역(드래그 크롭) — ORB 기준 바로 아래, 같은 룩의 슬롯
        self.roi_slot = RoiSlot("팝업 판정 영역 (드래그 크롭)", self.pick_popup_roi)
        self._roi_shown = False
        ref_box = QGroupBox("기준 이미지"); rb = QVBoxLayout(ref_box)
        rb.addWidget(self.ref_slot); rb.addWidget(self.roi_slot)

        # 웹캠·오른쪽 컬럼 모두 창 높이에 맞춰 채움 → 하단선이 창 하단까지 일치.
        right_w = QWidget()
        right = QVBoxLayout(right_w); right.setContentsMargins(0, 0, 0, 0)
        right.addWidget(self.metrics, 1)
        right.addWidget(b_player); right.addLayout(btns); right.addWidget(ref_box)
        lay = QHBoxLayout(self)
        lay.addWidget(self.video)
        lay.addWidget(right_w, 1)

        for key, cb in [("C", self.reset_align), ("R", self.reset_judge),
                        ("-", lambda: self.adj_thr(-0.02)), ("=", lambda: self.adj_thr(+0.02)),
                        ("+", lambda: self.adj_thr(+0.02)), ("D", self.save_report)]:
            QShortcut(QKeySequence(key), self, cb)

    def open_player(self):
        ok, err = launch_player(config.CLUSTER_PLAYER)
        if not ok:
            QMessageBox.warning(self, "테스트 화면", f"실행 실패:\n{err}")


    def _on_img_changed(self, _=None):
        self.set_ref(self.ref_slot.path)

    def set_ref(self, ref):
        self.cluster_ref = ref
        self.det = None     # 다음 프레임에 새 기준영상으로 재생성
        self._roi_shown = False

    def pick_popup_roi(self):
        """기준 화면(팝업 뜬 스냅샷)에서 경고창을 드래그 → 그 고정 영역을 판정 영역으로."""
        det = self.ensure_engine()
        dlg = RoiDialog(det.ref_canon, self)
        if dlg.exec() and dlg.result_roi:
            det.set_roi(dlg.result_roi)
            self.cluster_roi = dlg.result_roi     # 기준 재생성 시 재적용
            self.roi_slot.set_crop(det.tmpl, det.roi)

    def reset_align(self):
        if self.det:
            self.det.reset_alignment()

    def reset_judge(self):
        if self.det:
            self.det.reset()

    def ensure_engine(self):
        if self.det is None:
            self.det = ClusterDetector(self.cluster_ref)
            if self.cluster_roi is not None:      # 지정해둔 팝업 영역 재적용
                self.det.set_roi(self.cluster_roi)
        return self.det

    def adj_thr(self, dv):
        if self.det:
            self.det.popup_sim = max(0.0, min(1.0, self.det.popup_sim + dv))

    def update_frame(self, frame):
        det = self.ensure_engine()
        if not self._roi_shown:                     # 판정 영역 썸네일 최초 채움(기본/재적용 크롭)
            self.roi_slot.set_crop(det.tmpl, det.roi)
            self._roi_shown = True
        res = det.process(frame)
        self.last_res = res
        disp = overlay.draw_cluster(res)
        self.video.set_frame(overlay.bgr_to_pixmap(disp))
        self.metrics.setText(self._metrics_html(res))

    def _metrics_html(self, res):
        if res["state"] != "ok":
            return f"<b>상태:</b> {res['state']}"
        ang = res["lock_ang"]
        c = "#0c0" if res["popup_hit"] else "#888"
        # 판정: 사용자가 지정한 팝업(경고창) 이미지와 일치하면 PASS
        t = ("%.0f ms" % res["pass_ms"]) if res["pass_ms"] else "-"
        return (
            f"<b style='color:{c}'>{'POPUP = PASS' if res['popup_hit'] else 'no popup (대기)'}</b><br><br>"
            f"[1] 팝업 등장→PASS 시간: {t}<br>"
            f"[2] 일치도: ncc {res['ncc']:.3f}  ssim {res['ssim']:.3f}  [≥ {res['popup_sim']:.2f}]<br>"
            f"[3] ORB 정렬: LOCKED  inliers {res['lock_inliers']}"
            + (f"  roll {ang['roll']:.1f}° scale {ang['scale']:.2f}" if ang else "")
            + f"<br><span style='color:#888'>팝업 영역(드래그): {res['roi']}</span>"
        )

    def save_report(self):
        r = self.last_res
        if not r or r["state"] != "ok":
            QMessageBox.information(self, "저장", "정렬 전 — 저장 불가"); return
        ts = time.strftime("%Y%m%d_%H%M%S")
        dd = os.path.join(config.RESULTS_DIR, ts); os.makedirs(dd, exist_ok=True)
        cv2.imwrite(os.path.join(dd, "cluster.png"), r["canon"])
        ang = r["lock_ang"]
        lat = ("%.0f" % r["pass_ms"]) if r["pass_ms"] else "N/A"
        ok = "PASS" if r["popup_hit"] else "FAIL"
        rep = (
            "========================================\n"
            " UN R158 클러스터 팝업 일치 (기대값 검증)\n"
            "========================================\n"
            f"시각             : {ts}\n"
            f"판정(팝업일치)   : {ok}\n\n"
            "[1] 팝업 경고창 일치 (사용자 지정 팝업 이미지)\n"
            f"  팝업 등장→PASS  : {lat} ms\n"
            f"  일치도 NCC/SSIM : {r['ncc']:.3f} / {r['ssim']:.3f}\n"
            f"  기대(임계)      : >= {r['popup_sim']:.2f}\n"
            f"  결과            : {ok}\n\n"
            "[2] ORB 정렬 (각도)\n"
            f"  inliers         : {r['lock_inliers']}\n"
            + (f"  roll/scale      : {ang['roll']:.1f} deg / {ang['scale']:.2f}\n" if ang else "")
        )
        rep += save_evidence(dd, self.det.get_evidence())
        rep += "========================================\n"
        with open(os.path.join(dd, "cluster_result.txt"), "w", encoding="utf-8") as fp:
            fp.write(rep)
        with open(os.path.join(config.RESULTS_DIR, "cluster_realtime_log.txt"), "a", encoding="utf-8") as fp:
            fp.write(rep + "\n")
        QMessageBox.information(self, "저장", f"저장됨:\n{dd}")


class MainWindow(QMainWindow):
    def __init__(self, camera=config.DEFAULT_CAMERA):
        super().__init__()
        self.setWindowTitle("APX PoC — R158 검증")
        self.cap = None
        self.cam_index = None

        self._last_frame = None
        grab = lambda: self._last_frame     # 각 탭의 이미지 캡처가 최신 프레임 얻는 콜백

        self.tabs = QTabWidget()
        self.settings = SettingsTab()
        self.settings.cameraChanged.connect(self.set_camera)
        self.gear = GearTab(grab); self.cluster = ClusterTab(grab)
        self.audio = AudioTab()
        self.tabs.addTab(self.settings, "① 설정")
        self.tabs.addTab(self.gear, "② 기어 R 검출")
        self.tabs.addTab(self.cluster, "③ 클러스터 팝업")
        self.tabs.addTab(self.audio, "④ 음향 검증")
        self.setCentralWidget(self.tabs)

        self.timer = QTimer(self); self.timer.timeout.connect(self.tick); self.timer.start(15)

    def set_camera(self, index):
        """설정에서 카메라 선택 시 재오픈."""
        if index == self.cam_index and self.cap is not None:
            return
        if self.cap is not None:
            self.cap.release()
        self.cap = cv2.VideoCapture(index, cv2.CAP_DSHOW)
        self.cam_index = index
        self.statusBar().showMessage(
            f"카메라 {index} " + ("연결됨" if self.cap.isOpened() else "열기 실패"))

    def tick(self):
        if self.cap is None or not self.cap.isOpened():
            return
        ok, frame = self.cap.read()
        if not ok:
            return
        self._last_frame = frame
        w = self.tabs.currentWidget()
        if not hasattr(w, "update_frame"):
            return
        try:
            w.update_frame(frame)
        except FileNotFoundError as e:
            self.statusBar().showMessage(f"기준영상 없음: {e}")

    def closeEvent(self, ev):
        self.timer.stop()
        self.settings.cleanup()
        self.audio.cleanup()
        if self.cap is not None and self.cap.isOpened():
            self.cap.release()
        super().closeEvent(ev)
