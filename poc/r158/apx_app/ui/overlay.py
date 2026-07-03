"""UI 레이어 — 엔진 결과(dict)를 화면에 그리기. (엔진은 순수 유지, 그리기는 여기서)"""
import cv2
from PySide6.QtGui import QImage, QPixmap


def bgr_to_pixmap(bgr):
    """OpenCV BGR 프레임 → QPixmap."""
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    h, w = rgb.shape[:2]
    img = QImage(rgb.data, w, h, 3 * w, QImage.Format_RGB888)
    return QPixmap.fromImage(img.copy())


def draw_gear(res):
    """기어 결과 dict → 오버레이 그린 BGR 이미지 반환."""
    disp = res["canon"].copy()
    if res["state"] == "aligning":
        cv2.putText(disp, "aligning... show gear panel", (15, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 165, 255), 2)
        return disp
    if res["state"] == "need_calib":
        cv2.putText(disp, "click R to calibrate", (15, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 165, 255), 2)
        return disp
    cells, r_idx, is_R = res["cells"], res["r_idx"], res["is_R"]
    for i, (x, y) in enumerate(cells):
        if i == r_idx:
            col = (0, 220, 0) if is_R else (0, 0, 255)
            tag = "R"
        else:
            col = (180, 180, 180); tag = ""
        cv2.rectangle(disp, (x - 13, y - 13), (x + 13, y + 13), col, 2)
        if tag:
            cv2.putText(disp, tag, (x + 16, y + 5),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, col, 2)
    return disp


def draw_cluster(res):
    """클러스터 결과 dict → 오버레이 그린 BGR 이미지 반환."""
    disp = res["canon"].copy()
    if res["state"] == "aligning":
        cv2.putText(disp, "aligning... show cluster", (15, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 165, 255), 2)
        return disp
    y1, y2, x1, x2 = res["roi"]
    col = (0, 220, 0) if res["popup_hit"] else (255, 200, 0)
    cv2.rectangle(disp, (x1, y1), (x2, y2), col, 2)
    return disp
