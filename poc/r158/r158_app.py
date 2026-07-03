"""
UN R158 (PDW) 검증 테스트 앱
========================================
차량 PDW가 거리 판단 → 본 앱은 'R단 변속 후 경고가 기대대로/제때 떴는가'를 검증.

입력 (시나리오 폴더):
  - cluster.mp4   : 클러스터 영상 (경고 팝업)
  - warning.wav   : 마이크 음향 (경고음)
  - can_log.json  : 차량 CAN (R단 변속 시점 T0, 경고 CAN 시점)
  - expected/     : 기대 팝업 템플릿, 기대 경고음 주파수

판정 기준 (킥오프 문서 p6):
  ① 팝업 일치   : 영상 템플릿 유사도 >= POPUP_SIM
  ② 경고음 일치 : 기대 주파수 대역 onset 검출
  ③ 응답시간    : {팝업, 경고음, 경고CAN} 모두 T0 +0.6초 이내
  ④ 동기 오차   : {팝업, 경고음, 경고CAN} 상호 최대-최소 <= 30ms
  → ①~④ 모두 만족 = PASS

출력: 콘솔 판정표 + 시나리오별 evidence.csv (인증 근거)
"""
import os
import csv
import json
import cv2
import numpy as np
import librosa

HERE = os.path.dirname(__file__)

# ---- 법규 기준값 ----
RESP_LIMIT_S = 0.60     # 응답시간 한계 0.6초
SYNC_TOL_MS = 30.0      # 동기 오차 허용 ±30ms
POPUP_SIM = 0.70        # 팝업 일치 임계 유사도


# ---------- 검출기 (추출) ----------
def detect_popup(video_path, template, roi, sim=POPUP_SIM):
    y1, y2, x1, x2 = roi
    cap = cv2.VideoCapture(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS)
    idx, onset, best = 0, None, 0.0
    while True:
        ok, fr = cap.read()
        if not ok:
            break
        res = cv2.matchTemplate(fr[y1:y2, x1:x2], template, cv2.TM_CCOEFF_NORMED)
        s = float(res.max())
        best = max(best, s)
        if onset is None and s >= sim:
            onset = idx
        idx += 1
    cap.release()
    return (onset / fps if onset is not None else None), best


def detect_beep(wav_path, freq, band=200, hop=256, factor=5.0):
    """기대 경고음 주파수 대역(freq±band) 에너지가 배경 대비 급증하는
    첫 프레임 = 경고음 onset. librosa(STFT) 사용, 대역 임계 교차로 직접 검출(견고)."""
    y, sr = librosa.load(wav_path, sr=None, mono=True)
    S = np.abs(librosa.stft(y, hop_length=hop))
    freqs = librosa.fft_frequencies(sr=sr)
    mask = (freqs >= freq - band) & (freqs <= freq + band)
    be = S[mask, :].sum(axis=0)                       # 대역 에너지 포락선
    bg = np.median(be[: max(1, len(be) // 10)]) + 1e-9
    over = np.where(be > bg * factor)[0]              # 임계 초과 프레임
    if len(over) == 0:
        return None
    return float(librosa.frames_to_time(over[0], sr=sr, hop_length=hop))


# ---------- 판정 (검증) ----------
def judge(t0, popup_t, beep_t, warn_can_t, popup_sim):
    rows = []
    crit_pass = []

    # ① 팝업 일치
    c1 = popup_sim >= POPUP_SIM and popup_t is not None
    rows.append(("① 팝업 일치(영상)", f"유사도 {popup_sim:.2f} (기준 ≥{POPUP_SIM})", c1))
    crit_pass.append(c1)

    # ② 경고음 일치
    c2 = beep_t is not None
    rows.append(("② 경고음 일치(음향)", "기대 주파수 대역 onset " + ("검출" if c2 else "미검출"), c2))
    crit_pass.append(c2)

    # ③ 응답시간 0.6초 이내 (각 신호별)
    lat = {}
    for label, t in [("팝업", popup_t), ("경고음", beep_t), ("경고CAN", warn_can_t)]:
        lat[label] = None if t is None else (t - t0) * 1000.0
    c3 = all(v is not None and 0 <= v <= RESP_LIMIT_S * 1000 for v in lat.values())
    latstr = ", ".join(f"{k} {('-' if v is None else f'{v:.0f}ms')}" for k, v in lat.items())
    rows.append(("③ 응답시간 ≤0.6s", f"{latstr} (기준 ≤{int(RESP_LIMIT_S*1000)}ms)", c3))
    crit_pass.append(c3)

    # ④ 동기 오차 30ms 이내
    ts = [t for t in [popup_t, beep_t, warn_can_t] if t is not None]
    sync_ms = (max(ts) - min(ts)) * 1000.0 if len(ts) >= 2 else None
    c4 = sync_ms is not None and sync_ms <= SYNC_TOL_MS
    rows.append(("④ 동기 오차 ≤30ms", f"{('-' if sync_ms is None else f'{sync_ms:.0f}ms')} (기준 ≤{int(SYNC_TOL_MS)}ms)", c4))
    crit_pass.append(c4)

    return rows, all(crit_pass), lat, sync_ms


def run_scenario(name):
    d = os.path.join(HERE, "samples", name)
    exp = os.path.join(HERE, "expected")
    expj = json.load(open(os.path.join(exp, "expected.json")))
    tmpl = cv2.imread(os.path.join(exp, "popup_template.png"))
    can = json.load(open(os.path.join(d, "can_log.json"), encoding="utf-8"))

    # CAN 사실
    t0 = next(e["t"] for e in can["events"] if e["signal"] == "GEAR")
    warn_can = next(e["t"] for e in can["events"] if e["signal"] == "PDW_WARN_CAN")

    # 검출 (영상/음향)
    popup_t, popup_sim = detect_popup(os.path.join(d, "cluster.mp4"), tmpl, tuple(expj["popup_roi"]))
    beep_t = detect_beep(os.path.join(d, "warning.wav"), expj["beep_freq"])

    rows, verdict, lat, sync_ms = judge(t0, popup_t, beep_t, warn_can, popup_sim)

    # 콘솔 출력
    print(f"\n{'='*70}\n  시나리오: {name}   [R단 변속 T0={t0:.3f}s]\n{'='*70}")
    print(f"  검출: 팝업={popup_t}s(유사도 {popup_sim:.2f}) / 경고음={beep_t}s / 경고CAN={warn_can}s")
    print("  " + "-" * 66)
    for label, detail, ok in rows:
        print(f"  [{'PASS' if ok else 'FAIL'}] {label:20} {detail}")
    print("  " + "-" * 66)
    print(f"  >>> 최종 판정: {'✅ PASS' if verdict else '❌ FAIL'}")

    # 증거 CSV (항목 / 값 / 설명 3열)
    def r1(x, nd=1):
        return "" if x is None else round(x, nd)
    out = os.path.join(d, "evidence.csv")
    with open(out, "w", newline="", encoding="utf-8-sig") as fp:
        w = csv.writer(fp)
        w.writerow(["UN R158 (PDW) 검증 결과", "", ""])
        w.writerow(["항목", "값", "설명"])
        w.writerow(["scenario", name, "시나리오 이름"])
        w.writerow(["T0_Rshift_s", t0, "R단 변속 시점(초) = 응답시간 측정 기준점 (CAN)"])
        w.writerow(["popup_time_s", popup_t, "경고 팝업이 검출된 시각(초) — 영상 템플릿 매칭"])
        w.writerow(["popup_similarity", round(popup_sim, 3), f"팝업 유사도 (기준 ≥{POPUP_SIM})"])
        w.writerow(["beep_time_s", r1(beep_t, 3), "경고음이 검출된 시각(초) — 음향 2kHz onset"])
        w.writerow(["warn_can_time_s", warn_can, "경고 CAN 신호 시각(초) (CAN)"])
        w.writerow(["popup_latency_ms", r1(lat["팝업"]),
                    f"팝업 응답시간 = popup - T0 (기준 ≤{int(RESP_LIMIT_S*1000)}ms)"])
        w.writerow(["beep_latency_ms", r1(lat["경고음"]),
                    f"경고음 응답시간 = beep - T0 (기준 ≤{int(RESP_LIMIT_S*1000)}ms)"])
        w.writerow(["sync_error_ms", r1(sync_ms),
                    f"팝업↔경고음↔CAN 최대 시간차 (기준 ≤{int(SYNC_TOL_MS)}ms)"])
        w.writerow(["", "", ""])
        w.writerow(["판정 기준", "상세", "결과"])
        for label, detail, ok in rows:
            w.writerow([label, detail, "PASS" if ok else "FAIL"])
        w.writerow(["최종 판정(VERDICT)", "①②③④ 모두 PASS 여야 PASS", "PASS" if verdict else "FAIL"])
    return name, verdict


def main():
    sdir = os.path.join(HERE, "samples")
    if not os.path.isdir(sdir):
        print("샘플 없음. 먼저 gen_r158_samples.py 실행."); return
    results = [run_scenario(n) for n in sorted(os.listdir(sdir))
               if os.path.isdir(os.path.join(sdir, n))]
    print(f"\n{'='*70}\n  종합\n{'='*70}")
    for n, v in results:
        print(f"  {n:14} -> {'PASS' if v else 'FAIL'}")
    print("  (시나리오별 evidence.csv 생성됨)")


if __name__ == "__main__":
    main()
