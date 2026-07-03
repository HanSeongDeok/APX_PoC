"""
클러스터 재생 창 (테스트용) — 일반화면 ↔ 후방팝업 화면 전환
웹캠으로 이 창을 촬영해 realtime_r158.py 로 팝업 검출 테스트.

사용:
  python cluster_player.py                 # 자동 순환(일반 <-> 팝업)
키: [1]일반(팝업없음) [2]팝업 / [space]자동순환 토글 / [q]종료
"""
import os
import time
import argparse
import cv2
import numpy as np


def imread_kr(path):
    data = np.fromfile(path, np.uint8)
    return cv2.imdecode(data, cv2.IMREAD_COLOR) if data.size else None


DEFAULT_DIR = r"c:\DEV\apx"
FILES = {"normal": "hyundai_cluster.png", "popup": "hyundai_cluster_popup.png"}
ORDER = ["normal", "popup"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=DEFAULT_DIR, help="클러스터 이미지 폴더")
    ap.add_argument("--interval", type=float, default=3.0, help="자동순환 간격(초)")
    ap.add_argument("--size", type=int, default=800, help="표시 크기(px)")
    args = ap.parse_args()

    imgs = {}
    for k, fn in FILES.items():
        p = os.path.join(args.dir, fn)
        im = imread_kr(p)
        if im is None:
            print("이미지 없음:", p); return
        imgs[k] = im
    print("로드 완료:", list(imgs.keys()))

    win = "CLUSTER PLAYER (point webcam here)"
    cv2.namedWindow(win, cv2.WINDOW_NORMAL)
    cv2.resizeWindow(win, args.size, args.size)
    idx = 0
    auto = True
    last = time.perf_counter()

    while True:
        cur = ORDER[idx]
        mode = "AUTO" if auto else "MANUAL"
        cv2.setWindowTitle(win, f"CLUSTER - {cur} [{mode}]  (1 normal / 2 popup / space / q)")
        cv2.imshow(win, imgs[cur])

        if auto and (time.perf_counter() - last) >= args.interval:
            idx = (idx + 1) % len(ORDER)
            last = time.perf_counter()

        k = cv2.waitKey(30) & 0xFF
        if k == ord('q'):
            break
        elif k == ord('1'):
            idx = ORDER.index("normal"); auto = False
        elif k == ord('2'):
            idx = ORDER.index("popup"); auto = False
        elif k == ord(' '):
            auto = not auto; last = time.perf_counter()

    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
