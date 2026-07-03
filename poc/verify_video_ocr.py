"""
영상 일치 검증 PoC (Tesseract OCR 보강판)
- 입력: 클러스터 영상(MP4)
- 처리: 프레임별로 경고 팝업 영역을 OCR -> 기대 키워드(WARNING 등) 등장 프레임 = 표출 시점
- 강점: 픽셀 템플릿과 달리 차종/UI 디자인이 달라도 '문구'로 일반화
기존 verify_video.py(템플릿 매칭)와 보완 관계.
"""
import os
import cv2
import pytesseract

# Windows에서 PATH에 없으면 UB-Mannheim 기본 설치 경로 시도
_DEFAULT_TESS = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
if os.path.exists(_DEFAULT_TESS):
    pytesseract.pytesseract.tesseract_cmd = _DEFAULT_TESS

EXPECTED_KEYWORDS = ["WARNING", "REAR", "OBJECT"]  # 기대 경고 문구
ROI = (230, 360, 150, 500)  # (y1,y2,x1,x2) 팝업이 뜨는 하단 영역


def _ocr_text(frame):
    y1, y2, x1, x2 = ROI
    roi = frame[y1:y2, x1:x2]
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    # 밝은 글자/어두운 배경 대비 강화
    _, th = cv2.threshold(gray, 120, 255, cv2.THRESH_BINARY)
    txt = pytesseract.image_to_string(th, config="--psm 6")
    return txt.upper()


def detect_popup_ocr(video_path, keywords=EXPECTED_KEYWORDS, stride=1):
    cap = cv2.VideoCapture(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS)
    idx = 0
    onset_frame = None
    hit_text = ""
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % stride == 0:
            txt = _ocr_text(frame)
            if any(k in txt for k in keywords):
                onset_frame = idx
                hit_text = " ".join(txt.split())
                break
        idx += 1
    cap.release()
    return {
        "fps": fps,
        "method": "Tesseract OCR (keyword)",
        "onset_frame": onset_frame,
        "onset_time_s": (onset_frame / fps) if onset_frame is not None else None,
        "matched_text": hit_text,
    }


if __name__ == "__main__":
    base = os.path.join(os.path.dirname(__file__), "samples")
    r = detect_popup_ocr(os.path.join(base, "cluster.mp4"))
    print("[영상 일치 검출 - OCR]")
    print(f"  fps={r['fps']:.1f}, method={r['method']}")
    if r["onset_frame"] is not None:
        print(f"  팝업 등장 프레임: {r['onset_frame']}")
        print(f"  팝업 등장 시각  : {r['onset_time_s']:.4f} s")
        print(f"  인식 문구       : '{r['matched_text']}'")
    else:
        print("  기대 문구 미검출")
