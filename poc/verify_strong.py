"""
통합 검증 PoC (강력 조합판: OCR + librosa)
- 영상: Tesseract OCR 로 경고 문구 등장 시각 검출
- 음향: librosa onset + 2kHz 대역 필터로 경고음 시각 검출
- 두 시각차를 ±30ms(R158 동기 목표)로 판정 + 정답 대비 검출오차 리포트
- 참고: 기존 기본형(템플릿 매칭 / FFT)도 함께 돌려 비교 표시
"""
import json
import os

from verify_video_ocr import detect_popup_ocr        # 강력: OCR
from verify_audio_librosa import detect_beep as beep_librosa  # 강력: librosa
from verify_video import detect_popup                # 기본: 템플릿 매칭
from verify_audio import detect_beep as beep_fft      # 기본: FFT

GAP_TOL_MS = 30.0
BASE = os.path.join(os.path.dirname(__file__), "samples")


def _ms(x):
    return None if x is None else x * 1000.0


def main():
    gt = json.load(open(os.path.join(BASE, "ground_truth.json"), encoding="utf-8"))
    vid = os.path.join(BASE, "cluster.mp4")
    wav = os.path.join(BASE, "warning.wav")

    # --- 강력 조합 ---
    v = detect_popup_ocr(vid)
    a = beep_librosa(wav)
    vt, at = v["onset_time_s"], a["onset_time_s"]
    gap = (at - vt) * 1000.0
    verdict = "PASS" if abs(gap) <= GAP_TOL_MS else "FAIL"

    print("=" * 64)
    print(" 통합 검증 (강력 조합: OCR + librosa)")
    print("=" * 64)
    print(f"[영상/OCR ]  {_ms(vt):8.1f} ms   인식문구='{v['matched_text']}'")
    print(f"[음향/lib ]  {_ms(at):8.1f} ms   ({a['method']})")
    print("-" * 64)
    print(f"영상-음향 시간차 : {gap:+.1f} ms  (허용 ±{GAP_TOL_MS:.0f}ms) -> {verdict}")
    print("-" * 64)
    print("검출 정확도 (정답 대비):")
    print(f"  정답 시간차    : {gt['video_audio_gap_ms']:+.1f} ms")
    print(f"  영상 검출오차  : {(_ms(vt)-gt['popup_time_s']*1000):+.1f} ms  (프레임 분해능 {gt['frame_resolution_ms']}ms)")
    print(f"  음향 검출오차  : {(_ms(at)-gt['beep_time_s']*1000):+.1f} ms")

    # --- 기본형 비교 ---
    v0 = detect_popup(vid, os.path.join(BASE, "popup_template.png"))
    a0 = beep_fft(wav)
    print("=" * 64)
    print(" 방식 비교 (기본형 vs 강력 조합)")
    print("=" * 64)
    print(f"{'':10}{'영상(ms)':>12}{'음향(ms)':>12}")
    print(f"{'기본형':10}{_ms(v0['onset_time_s']):>12.1f}{_ms(a0['onset_time_s']):>12.1f}   (템플릿매칭 / FFT)")
    print(f"{'강력조합':10}{_ms(vt):>12.1f}{_ms(at):>12.1f}   (OCR / librosa)")
    print("=" * 64)


if __name__ == "__main__":
    main()
