"""
기어봉 재생 창 (테스트용) — P/R/N/D 이미지를 바꿔가며 표시
웹캠으로 이 창을 촬영해 gear_probe.py 로 R단 검출 테스트.

사용:
  python gear_player.py                      # 자동 순환 (P->R->N->D)
  python gear_player.py --dir c:/DEV/apx     # 이미지 폴더 지정
키: [1]P [2]R [3]N [4]D 수동전환 / [space]자동순환 토글 / [q]종료
"""
import os
import time
import argparse
import cv2
import numpy as np


def imread_kr(path):
    """한글 경로 대응 imread (cv2.imread는 Windows 비ASCII 경로 실패)."""
    data = np.fromfile(path, np.uint8)
    return cv2.imdecode(data, cv2.IMREAD_COLOR) if data.size else None


DEFAULT_DIR = r"c:\DEV\apx"
FILES = {"P": "hyundai_P.png", "R": "hyundai_R.png",
         "N": "hyundai_N.png", "D": "hyundai_D.png"}
ORDER = ["P", "R", "N", "D"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=DEFAULT_DIR, help="기어봉 이미지 폴더")
    ap.add_argument("--interval", type=float, default=2.0, help="자동순환 간격(초)")
    ap.add_argument("--size", type=int, default=700, help="표시 크기(px)")
    args = ap.parse_args()

    imgs = {}
    for g, fn in FILES.items():
        p = os.path.join(args.dir, fn)
        im = imread_kr(p)
        if im is None:
            print("이미지 없음:", p); return
        imgs[g] = im                                  # 원본 유지(리사이즈 X)
    print("로드 완료:", list(imgs.keys()))

    win = "GEAR PLAYER (point webcam here)"
    cv2.namedWindow(win, cv2.WINDOW_NORMAL)           # 창 크기 자유 조절 가능
    cv2.resizeWindow(win, args.size, args.size)       # 초기 크기
    idx = 0
    auto = True
    last = time.perf_counter()

    while True:
        cur = ORDER[idx]
        mode = "AUTO" if auto else "MANUAL"
        # 글자를 이미지에 그리지 않음(웹캠이 찍어 검출 오염) → 창 제목에만 표시
        cv2.setWindowTitle(win, f"GEAR PLAYER - {cur} [{mode}]  (1P 2R 3N 4D space q)")
        cv2.imshow(win, imgs[cur])   # 순수 기어 이미지만 표시

        if auto and (time.perf_counter() - last) >= args.interval:
            idx = (idx + 1) % len(ORDER)
            last = time.perf_counter()

        k = cv2.waitKey(30) & 0xFF
        if k == ord('q'):
            break
        elif k == ord('1'):
            idx = ORDER.index("P"); auto = False
        elif k == ord('2'):
            idx = ORDER.index("R"); auto = False
        elif k == ord('3'):
            idx = ORDER.index("N"); auto = False
        elif k == ord('4'):
            idx = ORDER.index("D"); auto = False
        elif k == ord(' '):
            auto = not auto; last = time.perf_counter()

    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
