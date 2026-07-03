"""
통합 검증 PoC (영상 + 음향 일치 / 시간 정합)
- 영상에서 팝업 등장 시각, 음향에서 경고음 시작 시각을 각각 자동 검출
- 두 시각 차이를 ±30ms 기준(R158 동기화 목표 오차)으로 판정
- ground_truth 와 비교하여 '검출 오차'까지 리포트
"""
import json
import os
from verify_video import detect_popup
from verify_audio import detect_beep

GAP_TOL_MS = 30.0  # 영상-음향 신호간 목표 오차범위(문서 ±30ms)


def main():
    base = os.path.join(os.path.dirname(__file__), "samples")
    gt = json.load(open(os.path.join(base, "ground_truth.json"), encoding="utf-8"))

    v = detect_popup(os.path.join(base, "cluster.mp4"),
                     os.path.join(base, "popup_template.png"))
    a = detect_beep(os.path.join(base, "warning.wav"))

    vt, at = v["onset_time_s"], a["onset_time_s"]
    gap_ms = (at - vt) * 1000.0

    print("=" * 60)
    print(" 통합 검증 결과 (영상/음향 일치 + 시간 정합)")
    print("=" * 60)
    print(f"[영상] 팝업 등장 : {vt*1000:8.1f} ms  (검출유사도 {v['max_score']:.3f})")
    print(f"[음향] 경고음    : {at*1000:8.1f} ms")
    print("-" * 60)

    # 1) 영상-음향 시간차 판정
    verdict = "PASS" if abs(gap_ms) <= GAP_TOL_MS else "FAIL"
    print(f"영상-음향 시간차 : {gap_ms:+.1f} ms  (허용 ±{GAP_TOL_MS:.0f}ms) -> {verdict}")

    # 2) 검출 정확도 (정답 대비 오차)
    v_err = (vt - gt["popup_time_s"]) * 1000.0
    a_err = (at - gt["beep_time_s"]) * 1000.0
    print("-" * 60)
    print("검출 정확도 (정답 대비):")
    print(f"  정답 시간차      : {gt['video_audio_gap_ms']:+.1f} ms")
    print(f"  영상 검출 오차   : {v_err:+.1f} ms  (프레임 분해능 {gt['frame_resolution_ms']}ms)")
    print(f"  음향 검출 오차   : {a_err:+.1f} ms")
    print("=" * 60)


if __name__ == "__main__":
    main()
