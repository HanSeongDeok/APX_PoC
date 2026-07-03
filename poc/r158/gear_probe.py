"""
기어봉 R단 검출 — 기준영상 R자동검출 + 상대밝기 판정 (CAN 없이, 클릭 불필요)
================================================================
웹캠으로 모니터/클러스터를 찍으면 파란 하이라이트가 회백색으로 워시아웃된다.
하지만 ORB '기준영상(REF, hyundai_R.png)'은 깨끗한 정면이라 파란 R이 살아있다.
→ 기준영상에서 R 위치를 자동으로 찾고, 라이브는 그 좌표계로 warp되므로 위치가 일치.

동작:
  0) [기동시-자동] 기준영상에서 '가장 크고 진한 파란 덩어리 = R' 로 R 위치를 자동검출,
     그 앵커로 국소 Otsu를 걸어 P/R/N/D 4칸 좌표를 자동 확정. (클릭 불필요)
  1) ORB로 정면 기준(REF)에 정렬 → 고정(lock).
  2) 매 프레임 각 칸의 '밝은픽셀 비율(V>VTH)'을 재고,
     R칸이 4칸 중 최대이고 2위보다 MARGIN 이상 크면  →  GEAR = R (초록).
  * 색(파랑)은 라이브에서 워시아웃되므로 '판정'은 밝기 상대비교로 한다(색은 위치찾기용).
  * 자동검출이 틀리면 R 글자를 '마우스 클릭'해 수동 보정 가능.

HUD 지표(cluster와 동일 형식):
  [1] R 검출 지연(ms): 웹캠 캡처→분석→R 판정PASS   [2] ORB 정렬(inliers/roll/scale)
  [3] 일치도(R칸 밝기비율 / R-2nd / margin)
키: [d]보고서/디버그저장  [r]재클릭(누르고 R클릭)  [x]자동재검출  [+/-]마진  [c]정렬리셋  [q]종료
"""
import os
import time
import argparse
import cv2
import numpy as np

HERE = os.path.dirname(__file__)
REF = r"c:\DEV\apx\hyundai_R.png"     # ORB 정면 기준
GC = 640
MIN_MATCH = 12
VTH = 190          # 하이라이트로 볼 밝기(Value) 하한
STRIP = 22         # 클릭 x 주변 반폭(px)
YWIN = 95          # 클릭 y 주변 반높이(px) — P..D 4칸 커버, 국소 Otsu용
CELL = 13          # 칸 반쪽 크기(px)
GEAR_ORDER = ["P", "R", "N", "D"]


def imread_kr(path):
    d = np.fromfile(path, np.uint8)
    return cv2.imdecode(d, cv2.IMREAD_COLOR) if d.size else None


class Aligner:
    def __init__(self, ref):
        self.orb = cv2.ORB_create(3000)
        self.kp, self.des = self.orb.detectAndCompute(ref, None)
        self.bf = cv2.BFMatcher(cv2.NORM_HAMMING)

    def homography(self, frame):
        kp, des = self.orb.detectAndCompute(frame, None)
        if des is None or len(kp) < MIN_MATCH:
            return None, 0
        knn = self.bf.knnMatch(self.des, des, k=2)
        good = [m for m, n in knn if m.distance < 0.75 * n.distance]
        if len(good) < MIN_MATCH:
            return None, len(good)
        src = np.float32([self.kp[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
        dst = np.float32([kp[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
        M, mask = cv2.findHomography(dst, src, cv2.RANSAC, 3.0)
        if M is None:
            return None, len(good)
        return M, (int(mask.sum()) if mask is not None else 0)


def homography_angles(M):
    """호모그래피에서 사용자용 정렬 지표: roll(좌우기울기°), perspective(비스듬함), scale(줌)."""
    if M is None:
        return None
    a, b = M[0, 0], M[0, 1]
    d, e = M[1, 0], M[1, 1]
    roll = float(np.degrees(np.arctan2(d, a)))
    scale = (float(np.hypot(a, d)) + float(np.hypot(b, e))) / 2.0
    persp = float(np.hypot(M[2, 0], M[2, 1]) * 1000.0)
    return {"roll": roll, "scale": scale, "persp": persp}


def brightpix(bgr):
    """칸 안에서 하이라이트로 볼만큼 밝은 픽셀 비율(0~1)."""
    if bgr.shape[0] < 3 or bgr.shape[1] < 3:
        return 0.0
    v = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)[:, :, 2]
    return float((v > VTH).mean())


def calibrate_from_click(canon, click):
    """클릭한 x위치의 세로 스트립에 국소 Otsu → 글자칸 후보를 찾고,
    클릭 지점 근처 4칸을 골라 [(x,y)..] + R 인덱스(클릭에 가장 가까운 칸) 반환."""
    cx, cy = click
    H, W = canon.shape[:2]
    x0 = max(0, cx - STRIP); x1 = min(W, cx + STRIP)
    y0 = max(0, cy - YWIN); y1 = min(H, cy + YWIN)
    strip = canon[y0:y1, x0:x1]
    g = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    th = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]
    n, _, stats, cent = cv2.connectedComponentsWithStats(th)
    cells = []
    for i in range(1, n):
        a = stats[i, cv2.CC_STAT_AREA]
        bw, bh = stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]
        if 15 <= a <= 400 and bw <= 40 and bh <= 40:
            cells.append((int(cent[i][0]) + x0, int(cent[i][1]) + y0))
    if not cells:
        return None, None
    cells.sort(key=lambda c: c[1])                 # y 오름차순
    # 클릭에 가장 가까운 칸을 기준으로 위아래 포함 4칸 선택
    ci = min(range(len(cells)), key=lambda i: abs(cells[i][1] - cy))
    start = max(0, min(ci - 1, len(cells) - 4)) if len(cells) >= 4 else 0
    picked = cells[start:start + 4] if len(cells) >= 4 else cells
    r_idx = min(range(len(picked)), key=lambda i: (picked[i][0] - cx) ** 2 + (picked[i][1] - cy) ** 2)
    return picked, r_idx


def find_R_on_ref(ref_canon):
    """깨끗한 기준영상(GCxGC)에서 '가장 큰 채도 높은(=하이라이트된) 글자 덩어리'를 R로 본다.
    색조(hue)에 의존하지 않음 → 파랑/초록/주황/빨강 등 차종별 하이라이트 색이 달라도 동작.
    (활성 기어글자는 P/N/D 회색과 달리 채도가 튀고, 컬러 덩어리 중 가장 크다)"""
    hsv = cv2.cvtColor(ref_canon, cv2.COLOR_BGR2HSV)
    S, V = hsv[:, :, 1], hsv[:, :, 2]
    colorful = ((S > 60) & (V > 80)).astype(np.uint8) * 255
    n, _, stats, cent = cv2.connectedComponentsWithStats(colorful)
    best, best_area = None, 0
    for i in range(1, n):
        a = stats[i, cv2.CC_STAT_AREA]
        w, h = stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]
        if 40 <= a <= 1200 and w <= 60 and h <= 60 and a > best_area:
            best_area, best = a, (int(cent[i][0]), int(cent[i][1]))
    return best


def calibrate_from_ref(ref_canon):
    """기준영상에서 R 위치를 자동검출하고, 그 앵커로 P/R/N/D 4칸을 확정."""
    rc = find_R_on_ref(ref_canon)
    if rc is None:
        return None, None
    return calibrate_from_click(ref_canon, rc)


def ratios(canon, cells):
    out = []
    for (x, y) in cells:
        roi = canon[max(0, y - CELL):y + CELL, max(0, x - CELL):x + CELL]
        out.append(brightpix(roi))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--ref", default=REF)
    ap.add_argument("--margin", type=float, default=0.02)
    ap.add_argument("--exposure", type=float, default=None)
    args = ap.parse_args()

    ref = imread_kr(args.ref)
    if ref is None:
        print("기준 이미지 없음:", args.ref); return
    ref_canon = cv2.resize(ref, (GC, GC))
    aligner = Aligner(ref_canon)

    # 기동시 기준영상에서 R + 4칸 자동검출 (클릭 불필요)
    auto_cells, auto_ridx = calibrate_from_ref(ref_canon)
    if auto_cells:
        print(f"기준영상 R 자동검출: cells={auto_cells} R_idx={auto_ridx} ({auto_cells[auto_ridx]})")
    else:
        print("기준영상에서 R 자동검출 실패 — 정렬 후 R 글자를 클릭하세요")

    cap = cv2.VideoCapture(args.camera, cv2.CAP_DSHOW)
    if not cap.isOpened():
        print("카메라 열기 실패:", args.camera); return
    exposure = args.exposure
    if exposure is not None:
        cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 0.25); cap.set(cv2.CAP_PROP_EXPOSURE, exposure)

    state = {"click": None, "armed": False}

    def on_mouse(ev, x, y, flags, param):
        # 'r'로 대기모드(armed)를 켠 뒤에만 클릭 인식 → 실수 클릭 방지
        if ev == cv2.EVENT_LBUTTONDOWN and state["armed"]:
            state["click"] = (x, y)
            state["armed"] = False

    win = "Gear R-probe (click R once)"
    cv2.namedWindow(win, cv2.WINDOW_NORMAL)
    cv2.resizeWindow(win, GC, GC)
    cv2.setMouseCallback(win, on_mouse)

    locked_M = None
    lock_inliers = None     # [3] ORB 정렬 매칭도 (inliers)
    lock_ang = None         # [3] ORB 정렬 각도(roll/perspective/scale)
    cells = auto_cells      # 기준영상 자동검출 4칸 (없으면 클릭으로 대체)
    r_idx = auto_ridx       # 그중 R 인덱스
    margin = args.margin
    # ---- 보고용 지표 ----
    # [1] 검출 지연 = 프레임 도착(실측 프레임 간격 ≈1/fps) + 분석 시간
    t_prev_arrive = None    # 직전 프레임 도착 시각
    frame_gap_ms = 0.0      # 실측 프레임 간격(프레임 도착 지연)
    analysis_ms = 0.0       # 프레임 도착 → R 판정까지 분석시간
    pass_ms = None          # 전체 지연(전환 프레임): frame_gap + analysis
    prev_is_R = False       # 직전 프레임 R 여부(전환 감지)
    r_ratio = 0.0           # [3] 일치도: R칸 밝기비율
    r_gap = 0.0             # [3] 일치도: 2위칸 대비(R-2nd)
    is_R = False            # 현재 R 판정

    while True:
        ok, frame = cap.read()
        if not ok:
            continue
        t_arrive = time.perf_counter()   # 프레임 도착 시각
        frame_gap_ms = (t_arrive - t_prev_arrive) * 1000.0 if t_prev_arrive else 0.0
        t_prev_arrive = t_arrive
        if locked_M is None:
            M, inl = aligner.homography(frame)
            if M is not None and inl >= 25:
                locked_M = M
                lock_inliers = inl          # [3] 정렬 매칭도
                lock_ang = homography_angles(M)   # [3] 정렬 각도
                print(f"정렬 고정 (inliers={inl}) → R 자동검출 사용중"
                      + ("" if cells else " (자동실패, R 글자 클릭)"))
        if locked_M is None:
            disp = cv2.resize(frame, (GC, GC))
            cv2.putText(disp, "aligning... show gear panel", (15, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 165, 255), 2)
            cv2.imshow(win, disp)
            if cv2.waitKey(1) & 0xFF == ord('q'):
                break
            continue

        canon = cv2.warpPerspective(frame, locked_M, (GC, GC))

        # 클릭 처리 → 캘리브레이션
        if state["click"] is not None:
            c = state["click"]; state["click"] = None
            cells, r_idx = calibrate_from_click(canon, c)
            if cells:
                print(f"캘리브 완료: cells={cells}  R_index={r_idx} ({cells[r_idx]})")
            else:
                print("칸 검출 실패 — R 글자 위를 더 정확히 클릭하세요")

        disp = canon.copy()

        def put(yy, t, col=(255, 255, 255)):
            cv2.putText(disp, t, (15, yy), cv2.FONT_HERSHEY_SIMPLEX, 0.6, col, 2)

        if cells is None:
            put(30, "auto R failed - press [r] then click the 'R' letter", (0, 165, 255))
        else:
            rt = ratios(canon, cells)                       # 각 칸 밝기 계산
            order = sorted(range(len(rt)), key=lambda i: rt[i], reverse=True)
            top, second = order[0], (order[1] if len(order) > 1 else order[0])
            r_ratio = rt[r_idx]
            r_gap = rt[r_idx] - rt[second]
            is_R = (top == r_idx) and (r_gap >= margin)
            # --- [1] 검출 지연: not-R -> R 전환 프레임의 (프레임 도착 + 분석) ---
            if is_R and not prev_is_R:                         # 이번 프레임에 R로 전환됨
                analysis_ms = (time.perf_counter() - t_arrive) * 1000.0
                pass_ms = frame_gap_ms + analysis_ms
            prev_is_R = is_R
            for i, (x, y) in enumerate(cells):
                if i == r_idx:
                    col = (0, 220, 0) if is_R else (0, 0, 255)
                    tag = "R"
                else:
                    col = (180, 180, 180); tag = ""
                cv2.rectangle(disp, (x - CELL, y - CELL), (x + CELL, y + CELL), col, 2)
                if tag:
                    cv2.putText(disp, tag, (x + CELL + 3, y + 5),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.6, col, 2)
            gcol = (0, 220, 0) if is_R else (0, 0, 255)
            # cluster와 동일한 번호형 지표
            _lat = (f"{pass_ms:.0f}ms (frame {frame_gap_ms:.0f} + analysis {analysis_ms:.1f})"
                    if pass_ms is not None else "-")
            put(26, f"[1] R detect: {_lat}  result {'PASS' if is_R else '-'}", gcol)
            put(52, f"[2] ORB align: {'LOCKED' if locked_M is not None else 'N/A'}"
                    f"  inliers {lock_inliers if lock_inliers is not None else '-'}"
                    + (f"  roll {lock_ang['roll']:.1f}d scale {lock_ang['scale']:.2f}"
                       if lock_ang else ""),
                (0, 220, 0) if locked_M is not None else (0, 165, 255))
            put(78, f"[3] match: R {r_ratio:.2f} / 2nd {rt[second]:.2f}"
                    f"  R-2nd {r_gap:+.3f} [>= {margin:.2f}]", gcol)

        if state["armed"]:
            put(GC - 45, ">> CLICK the R letter now (r to cancel) <<", (0, 255, 255))
        put(GC - 15, "[d]debug  [r]re-click  [x]auto  [+/-]margin  [c]align  [q]quit",
            (200, 200, 200))
        cv2.imshow(win, disp)

        k = cv2.waitKey(1) & 0xFF
        if k == ord('q'):
            break
        elif k == ord('d'):     # 디버그 저장 = 보고서 txt + 스냅샷/디버그 이미지
            if cells is None:
                print("저장 불가 — R 위치 미확정")
            else:
                ts = time.strftime("%Y%m%d_%H%M%S")
                dd = os.path.join(HERE, "results", ts)
                os.makedirs(dd, exist_ok=True)
                cv2.imwrite(os.path.join(dd, "gear_R.png"), canon)
                cv2.imwrite(os.path.join(dd, "probe_view.png"), disp)
                report = f"""========================================
 UN R158 기어 R 검출 (시간 검증)
========================================
시각             : {ts}
판정             : {"PASS (R 검출)" if is_R else "FAIL"}

[1] R 검출 지연 (프레임 도착 + 분석)
  전체 지연       : {("%.0f ms" % pass_ms) if pass_ms is not None else "N/A"}
   - 프레임 도착   : {("%.0f ms" % frame_gap_ms) if pass_ms is not None else "N/A"}  (실측 프레임 간격 ~1/fps)
   - 분석 시간     : {("%.1f ms" % analysis_ms) if pass_ms is not None else "N/A"}
  결과            : {"PASS (R 검출)" if is_R else "FAIL"}

[2] ORB 정렬 (각도)
  상태            : {"LOCKED" if locked_M is not None else "N/A"}
  roll(좌우기울기): {("%.1f deg" % lock_ang["roll"]) if lock_ang else "N/A"}
  perspective     : {("%.2f" % lock_ang["persp"]) if lock_ang else "N/A"} (비스듬함)
  scale(줌)       : {("%.2f" % lock_ang["scale"]) if lock_ang else "N/A"}
  [dev] inliers   : {lock_inliers if lock_inliers is not None else "N/A"}

[3] 일치도 (R칸 밝기)
  R칸 밝기비율    : {r_ratio:.3f}
  2위칸 밝기비율   : {(r_ratio - r_gap):.3f}
  R-2nd 차이      : {r_gap:+.3f}
  기대(margin)    : >= {margin:.2f}
  결과            : {"PASS" if is_R else "FAIL"}

스냅샷           : {dd}
========================================
"""
                with open(os.path.join(dd, "gear_result.txt"), "w", encoding="utf-8") as fp:
                    fp.write(report)
                with open(os.path.join(HERE, "results", "gear_realtime_log.txt"), "a",
                          encoding="utf-8") as fp:
                    fp.write(report + "\n")
                print(report)
                print("보고서/디버그 저장:", dd)
        elif k == ord('r'):
            state["armed"] = not state["armed"]
            print("re-click 대기 ON — R 글자를 클릭" if state["armed"] else "re-click 취소")
        elif k == ord('x'):
            cells, r_idx = calibrate_from_ref(ref_canon)
            print("기준영상 R 자동재검출:", cells, r_idx if cells else "(실패→클릭)")
        elif k == ord('c'):
            locked_M = None; print("정렬 리셋 (칸 좌표는 유지)")
        elif k in (ord('+'), ord('=')):
            margin = min(0.5, margin + 0.01); print("margin =", round(margin, 3))
        elif k == ord('-'):
            margin = max(0.0, margin - 0.01); print("margin =", round(margin, 3))
        elif k == ord('e'):
            exposure = (exposure or -6) - 1
            cap.set(cv2.CAP_PROP_EXPOSURE, exposure); print("exposure =", exposure)
        elif k == ord('w'):
            exposure = (exposure or -6) + 1
            cap.set(cv2.CAP_PROP_EXPOSURE, exposure); print("exposure =", exposure)

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
