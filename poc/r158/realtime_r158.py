"""
UN R158 (PDW) 실시간 검증 앱 — 핸드폰 가상 웹캠(B 방식)
================================================================
흐름:
  1) (세션 시작) 화면 4모서리 클릭 → 원근 보정행렬 1회 계산 (각도 변동 흡수)
  2) 매 프레임: 보정 → 기어표시 OCR로 'R' 검출 시점 = T0
  3) T0 이후: 팝업(영상 템플릿) / 경고음(마이크 2kHz onset) 시점 검출
  4) 응답시간(≤0.6s)·동기오차(≤30ms) 실시간 판정 + 화면 오버레이

사용법:
  python list_devices.py                      # 폰 카메라/마이크 인덱스 확인
  python realtime_r158.py --camera 1 --audio 2 --calib
  # 테스트(카메라 없이): 우리 합성 영상으로 파이프라인 점검
  python realtime_r158.py --video samples/PASS/cluster.mp4 --no-audio

키: [c] 보정 다시 / [r] 판정 리셋 / [q] 종료
"""
import os
import sys
import time
import json
import argparse
import threading
import collections
import cv2
import numpy as np

try:
    import pytesseract
    _T = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
    if os.path.exists(_T):
        pytesseract.pytesseract.tesseract_cmd = _T
except ImportError:
    pytesseract = None

try:
    from skimage.metrics import structural_similarity as _ssim
except ImportError:
    _ssim = None

HERE = os.path.dirname(__file__)
CW, CH = 640, 640                       # 정면 캔버스(정사각 — 클러스터 이미지가 정사각)
GEAR_ROI = (185, 225, 205, 255)         # (y1,y2,x1,x2) 기어 D/R 표시 영역(640x640, --gear시)
POPUP_ROI = (207, 368, 226, 406)        # 후방카메라 팝업 영역(hyundai_cluster 기준, 640x640)
RESP_LIMIT_S, SYNC_TOL_MS, POPUP_SIM = 0.30, 30.0, 0.70   # 응답시간 내부목표 0.3초(R158 법규는 0.6초)
RESULTS = os.path.join(HERE, "results")   # 실시간 검증 결과 저장 폴더
BEEP_FREQ, BEEP_BAND = 2000, 200

# ---------------- 오디오 검출 (FFT 1차 트리거 → 정합필터 2차 확정) ----------------
class BeepDetector:
    """공통 경고음 검출.
    1차: 2kHz 대역 에너지 급증(빠른 트리거)
    2차: 기대 경고음과 정규화 상호상관(정합필터) — 노이즈 강건, '기대음 일치' 확인
    template 이 없으면 1차만(에너지) 사용.
    """
    def __init__(self, sr=48000, block=2048, factor=6.0, template=None, corr_thr=0.35):
        self.sr, self.block, self.factor = sr, block, factor
        self.corr_thr = corr_thr
        self.tmpl = None
        if template is not None and len(template) > 0:
            t = template.astype(np.float32)
            t = t - t.mean()
            self.tmpl = t / (np.linalg.norm(t) + 1e-9)   # 단위벡터화
        self.bg = None
        self.last_e = 0.0
        self.last_ratio = 0.0
        self.last_corr = 0.0        # 정합필터 최대 상관(0~1)
        self.onset_t = None
        self.armed = False
        self.buflen = int(0.6 * sr)                      # 최근 0.6초 링버퍼
        self.buf = np.zeros(self.buflen, np.float32)

    def _matched(self):
        """최근 버퍼 vs 기대음 정규화 상호상관(NCC) 최대값."""
        from scipy import signal
        L = len(self.tmpl)
        seg = self.buf[-int(0.5 * self.sr):]
        if len(seg) < L:
            return 0.0
        num = signal.correlate(seg, self.tmpl, mode="valid")
        eng = np.sqrt(signal.correlate(seg * seg, np.ones(L, np.float32), mode="valid")) + 1e-9
        return float(np.max(np.abs(num / eng)))

    def feed(self, x):
        x = np.asarray(x, np.float32)
        n = len(x)
        if n < self.buflen:                              # 링버퍼 갱신
            self.buf = np.roll(self.buf, -n); self.buf[-n:] = x
        else:
            self.buf = x[-self.buflen:].copy()
        spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
        freqs = np.fft.rfftfreq(len(x), 1 / self.sr)
        m = (freqs >= BEEP_FREQ - BEEP_BAND) & (freqs <= BEEP_FREQ + BEEP_BAND)
        e = float(spec[m].sum())
        self.bg = e if self.bg is None else 0.98 * self.bg + 0.02 * e
        self.last_e = e
        self.last_ratio = e / max(self.bg, 1e-6)
        if self.armed and self.onset_t is None and e > max(self.bg, 1e-6) * self.factor:
            if self.tmpl is not None:                    # 2차: 정합필터 확정
                self.last_corr = self._matched()
                if self.last_corr >= self.corr_thr:
                    self.onset_t = time.perf_counter()
            else:
                self.onset_t = time.perf_counter()

    def arm(self):
        self.armed, self.onset_t = True, None

    def reset(self):
        self.armed, self.onset_t = False, None


class AudioBeep(BeepDetector):
    """마이크 입력(sounddevice)."""
    def __init__(self, device=None, **kw):
        super().__init__(**kw)
        self.device = device
        self._stream = None

    def _cb(self, indata, frames, t, status):
        self.feed(indata[:, 0])

    def start(self):
        import sounddevice as sd
        self._stream = sd.InputStream(device=self.device, channels=1,
                                      samplerate=self.sr, blocksize=self.block,
                                      callback=self._cb)
        self._stream.start()

    def stop(self):
        if self._stream:
            self._stream.stop(); self._stream.close()


class LoopbackBeep(BeepDetector):
    """PC 재생음 루프백 캡처(soundcard) — 스피커/마이크 불필요."""
    def __init__(self, **kw):
        super().__init__(**kw)
        self._run = False
        self._th = None
        self.err = None

    def _loop(self):
        try:
            import soundcard as sc
            mic = sc.get_microphone(sc.default_speaker().name, include_loopback=True)
            with mic.recorder(samplerate=self.sr, blocksize=self.block) as rec:
                while self._run:
                    data = rec.record(numframes=self.block)
                    x = data[:, 0] if getattr(data, "ndim", 1) > 1 else data
                    self.feed(x)
        except Exception as e:
            self.err = e

    def start(self):
        self._run = True
        self._th = threading.Thread(target=self._loop, daemon=True)
        self._th.start()

    def stop(self):
        self._run = False


# ---------------- ORB 자동 정렬 (마커/클릭 불필요) ----------------
class OrbAligner:
    """기준 클러스터 이미지에 대해 매 프레임 자동 정면 정렬."""
    MIN_MATCH = 12

    def __init__(self, ref):
        self.ref = ref
        self.orb = cv2.ORB_create(3000)
        self.kp_ref, self.des_ref = self.orb.detectAndCompute(ref, None)
        self.bf = cv2.BFMatcher(cv2.NORM_HAMMING)

    def homography(self, frame):
        """frame -> 기준(정면) 변환행렬과 인라이어 수 계산."""
        kp, des = self.orb.detectAndCompute(frame, None)
        if des is None or len(kp) < self.MIN_MATCH:
            return None, 0
        knn = self.bf.knnMatch(self.des_ref, des, k=2)
        good = [m for m, n in knn if len([m, n]) == 2 and m.distance < 0.75 * n.distance]
        if len(good) < self.MIN_MATCH:
            return None, len(good)
        src = np.float32([self.kp_ref[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
        dst = np.float32([kp[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
        Mh, mask = cv2.findHomography(dst, src, cv2.RANSAC, 3.0)   # frame -> ref(정면)
        if Mh is None:
            return None, len(good)
        return Mh, (int(mask.sum()) if mask is not None else 0)

    def align(self, frame):
        Mh, inliers = self.homography(frame)
        if Mh is None:
            return None, inliers
        return cv2.warpPerspective(frame, Mh, (CW, CH)), inliers


def homography_angles(M):
    """호모그래피에서 사용자용 지표 추출: roll(좌우기울기°), perspective(비스듬함), scale(줌)."""
    if M is None:
        return None
    a, b = M[0, 0], M[0, 1]
    d, e = M[1, 0], M[1, 1]
    roll = float(np.degrees(np.arctan2(d, a)))          # 좌우 기울기(회전)
    sx = float(np.hypot(a, d))                          # x 스케일
    sy = float(np.hypot(b, e))                          # y 스케일
    scale = (sx + sy) / 2.0
    persp = float(np.hypot(M[2, 0], M[2, 1]) * 1000.0)  # 원근 정도(비스듬함, ×1000 가독)
    return {"roll": roll, "scale": scale, "persp": persp}


def sane_homography(M):
    """정렬 변환이 '완전히 망가진' 경우만 거부 (뒤집힘·붕괴). 강한 원근은 허용."""
    if M is None or not np.all(np.isfinite(M)):
        return False
    det = M[0, 0] * M[1, 1] - M[0, 1] * M[1, 0]   # 선형부 행렬식
    if det <= 0:                                   # 뒤집힘(거울상)
        return False
    if not (0.01 < det < 100.0):                   # 완전 붕괴/폭주만 거부
        return False
    return True


# ---------------- 보정(세션 1회, 수동 폴백) ----------------
class Calibrator:
    def __init__(self):
        self.pts = []
        self.M = None

    def on_mouse(self, ev, x, y, flags, param):
        if ev == cv2.EVENT_LBUTTONDOWN and len(self.pts) < 4:
            self.pts.append([x, y])
            if len(self.pts) == 4:
                src = np.float32(self.pts)
                dst = np.float32([[0, 0], [CW, 0], [CW, CH], [0, CH]])
                self.M = cv2.getPerspectiveTransform(src, dst)

    def apply(self, frame):
        if self.M is None:
            return cv2.resize(frame, (CW, CH))   # 미보정 시 단순 리사이즈
        return cv2.warpPerspective(frame, self.M, (CW, CH))

    def reset(self):
        self.pts, self.M = [], None


# ---------------- 검출 ----------------
def gear_binary(canon):
    """기어 ROI 전처리 = 확대(4x) + Otsu 자동임계 + 반전(검은글자/흰배경).
    작은 글자·조명변화·실촬영에 강건. OCR과 디버그 저장이 같은 이미지를 사용."""
    y1, y2, x1, x2 = GEAR_ROI
    roi = canon[y1:y2, x1:x2]
    roi = cv2.resize(roi, None, fx=4, fy=4, interpolation=cv2.INTER_CUBIC)  # 작은 글자 확대
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (3, 3), 0)
    # Otsu: 밝기 자동 판단 → 실촬영/모니터 조명 변화에 강건. INV = Tesseract 선호(검은글자/흰배경)
    _, th = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    return th


def gear_text(canon):
    """기어표시 ROI를 OCR하여 인식 문자열 반환."""
    if pytesseract is None:
        return ""
    th = gear_binary(canon)
    return pytesseract.image_to_string(
        th, config="--psm 10 -c tessedit_char_whitelist=RDPNrdpn").strip().upper()


def gear_score(canon, gtmpl):
    """기어 'R 들어온 상태' 그림 유사도 (ncc, ssim). 팝업과 동일 방식 —
    차종마다 기어표시(불/하이라이트/심볼)가 달라도 타겟 이미지로 일반화."""
    y1, y2, x1, x2 = GEAR_ROI
    roi = canon[y1:y2, x1:x2]
    if roi.shape[0] < gtmpl.shape[0] or roi.shape[1] < gtmpl.shape[1]:
        return 0.0, 0.0
    ncc = float(cv2.matchTemplate(roi, gtmpl, cv2.TM_CCOEFF_NORMED).max())
    sval = 0.0
    if _ssim is not None:
        t = cv2.resize(gtmpl, (roi.shape[1], roi.shape[0]))
        g1 = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        g2 = cv2.cvtColor(t, cv2.COLOR_BGR2GRAY)
        try:
            sval = float(_ssim(g1, g2))
        except Exception:
            sval = 0.0
    return ncc, sval


POPUP_KEYWORDS = ["WARNING", "REAR", "OBJECT"]   # 팝업 안 기대 문구


def popup_text(canon):
    """팝업 영역 OCR (색/블러에 강건 — 문구 유무로 판정)."""
    if pytesseract is None:
        return ""
    y1, y2, x1, x2 = POPUP_ROI
    g = cv2.cvtColor(canon[y1:y2, x1:x2], cv2.COLOR_BGR2GRAY)
    _, th = cv2.threshold(g, 120, 255, cv2.THRESH_BINARY)
    return " ".join(pytesseract.image_to_string(th, config="--psm 6").upper().split())


def popup_scores(canon, tmpl):
    """팝업 그림 유사도 (ncc, ssim) 분리 반환 — 보고용 수치화."""
    y1, y2, x1, x2 = POPUP_ROI
    roi = canon[y1:y2, x1:x2]
    if roi.shape[0] < tmpl.shape[0] or roi.shape[1] < tmpl.shape[1]:
        return 0.0, 0.0
    ncc = float(cv2.matchTemplate(roi, tmpl, cv2.TM_CCOEFF_NORMED).max())
    sval = 0.0
    if _ssim is not None:
        t = cv2.resize(tmpl, (roi.shape[1], roi.shape[0]))
        g1 = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        g2 = cv2.cvtColor(t, cv2.COLOR_BGR2GRAY)
        try:
            sval = float(_ssim(g1, g2))
        except Exception:
            sval = 0.0
    return ncc, sval


def popup_score(canon, tmpl):
    ncc, sval = popup_scores(canon, tmpl)
    return max(ncc, sval)


# ---------------- 메인 ----------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--camera", type=int, default=None)
    ap.add_argument("--audio", type=int, default=None)
    ap.add_argument("--video", type=str, default=None, help="테스트용 영상 파일")
    ap.add_argument("--calib", action="store_true", help="4모서리 클릭 보정(수동 폴백)")
    ap.add_argument("--auto", action="store_true", help="ORB 자동 정렬(클릭 불필요)")
    ap.add_argument("--gear", action="store_true",
                    help="기어 R 검출을 T0로 사용 (기본 off: 클러스터 팝업만 인식)")
    ap.add_argument("--ref", type=str, default=r"c:\DEV\apx\hyundai_cluster.png",
                    help="ORB 정렬 기준 클러스터 이미지")
    ap.add_argument("--no-audio", action="store_true")
    ap.add_argument("--sim-beep", action="store_true",
                    help="테스트용: 팝업 검출 시 경고음이 동시 발생했다고 간주(실제 음향검증 아님)")
    ap.add_argument("--loopback", action="store_true",
                    help="PC 재생음을 직접 캡처(WASAPI 루프백) — 스피커/마이크 불필요, 실제 음향검증")
    ap.add_argument("--popup-ocr", action="store_true",
                    help="팝업을 문구 OCR로 검출(기본은 그림 유사도: 템플릿+SSIM)")
    ap.add_argument("--gear-ocr", action="store_true",
                    help="기어 R을 문자 OCR로 검출(기본은 gear 타겟 있으면 그림 유사도)")
    args = ap.parse_args()   # 기본: 그림 유사도(차종 문구 무관), OCR은 opt-in

    # 팝업 템플릿 = 기대 클러스터(팝업 켜진 상태)에서 POPUP_ROI 부분을 잘라 사용
    popup_ref = cv2.imread(r"c:\DEV\apx\hyundai_cluster_popup.png")
    if popup_ref is None:
        print("팝업 기준영상 없음: c:\\DEV\\apx\\hyundai_cluster_popup.png"); return
    _py1, _py2, _px1, _px2 = POPUP_ROI
    tmpl = cv2.resize(popup_ref, (CW, CH))[_py1:_py2, _px1:_px2].copy()
    # 기어 R 타겟(그림 유사도, --gear 일 때만 사용)
    gtmpl = cv2.imread(os.path.join(HERE, "expected", "gear_R_template.png"))
    GEAR_SIM = 0.60
    use_gear_tmpl = (gtmpl is not None) and (not args.gear_ocr)
    if args.gear:
        print("기어 검출 방식:", "그림 유사도(타겟 등록됨)" if use_gear_tmpl else "OCR 'R'")
    else:
        print("기어 검출: OFF — 클러스터 팝업만 인식")

    if args.video:
        cap = cv2.VideoCapture(args.video)
    else:
        cap = cv2.VideoCapture(args.camera if args.camera is not None else 0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        print("카메라/영상 열기 실패"); return

    # 기대 경고음(정합필터 템플릿) 로드
    beep_tmpl = None
    bt_path = os.path.join(HERE, "expected", "beep_template.wav")
    if os.path.exists(bt_path):
        from scipy.io import wavfile
        _, bw = wavfile.read(bt_path)
        beep_tmpl = (bw.astype(np.float32) / 32768.0) if bw.dtype == np.int16 else bw.astype(np.float32)

    audio = None
    if not args.no_audio and not args.video and not args.sim_beep:
        try:
            audio = (LoopbackBeep(template=beep_tmpl) if args.loopback
                     else AudioBeep(device=args.audio, template=beep_tmpl))
            audio.start()
            mf = "정합필터 ON" if beep_tmpl is not None else "에너지만"
            print(f"오디오 캡처 시작 ({'루프백(PC재생음)' if args.loopback else '마이크'}, "
                  f"sr={audio.sr}, {mf})")
        except Exception as e:
            print("오디오 시작 실패(영상만 진행):", e)
            audio = None

    calib = Calibrator()
    aligner = None
    if args.auto:
        ref = cv2.imread(args.ref)
        if ref is None:
            print("기준 이미지 없음:", args.ref, "→ gen_r158_samples.py 먼저 실행"); return
        ref = cv2.resize(ref, (CW, CH))
        aligner = OrbAligner(ref)
        print("ORB 자동 정렬 모드 (클릭 불필요)")
    win = "R158 Realtime"
    cv2.namedWindow(win, cv2.WINDOW_NORMAL)     # 창 크기 자유 조절
    cv2.resizeWindow(win, CW, CH)
    if args.calib and not args.auto:
        cv2.setMouseCallback(win, calib.on_mouse)
        print("화면 4모서리를 좌상→우상→우하→좌하 순서로 클릭하세요.")

    # 상태
    t0 = popup_t = beep_t = None
    verdict = None
    popup_sim = POPUP_SIM          # 런타임 조정 가능(+/- 키)
    psc_max = 0.0                  # 팝업 최고 유사도(튜닝: 실제 몇 점까지 나오나)
    locked_M = None                # ORB 정렬 고정(세션 내 카메라 고정 → 1회만 계산)
    lock_inliers = None            # lock 성공 시점의 inliers (개발용)
    lock_ang = None                # lock 시점 기울기/원근/스케일 (사용자용)
    t0_img = popup_img = None       # 증거 스냅샷 (기어 R 순간 / 팝업 순간)
    saved = False                   # 이번 사이클 저장 완료 여부
    # ---- 보고용 3대 지표 ----
    gear_ms = 0.0                   # [1] not-R->R 판단 시간(ms)
    gear_seen_notR = False          # [1] not-R 상태를 먼저 봤는가(전환 판정용)
    popup_ms = 0.0                  # [2] 팝업 일치 판단 시간(ms) — 로직 계산만
    t_prev_arrive = None            # [2] 직전 프레임 도착 시각
    frame_gap_ms = 0.0              # [2] 실측 프레임 간격(프레임 도착 지연 ~1/fps)
    analysis_ms = 0.0               # [2] 프레임 도착→매칭 판정 분석시간
    pass_ms = None                  # [2] 전체 지연 = 프레임 도착 + 분석
    ncc = ssim = 0.0               # [2] 일치도 (NCC/SSIM)
    ncc_max = ssim_max = 0.0        # [2] 최고값
    popup_ncc = popup_ssim = None   # [2] 팝업 검출 순간 값

    def now():
        return time.perf_counter()

    while True:
        ok, frame = cap.read()
        if not ok:
            if args.video:
                print("영상 종료"); break
            continue
        t_arrive = time.perf_counter()   # 프레임 도착 시각
        frame_gap_ms = (t_arrive - t_prev_arrive) * 1000.0 if t_prev_arrive else 0.0
        t_prev_arrive = t_arrive

        # 정렬: ORB 자동(1회 고정) / 수동 보정 / 단순 리사이즈
        inliers = None
        if aligner is not None:
            if locked_M is None:                   # 아직 고정 전 → 정렬 시도
                M, inliers = aligner.homography(frame)
                if M is not None and inliers >= 25 and sane_homography(M):  # 견고+정상만 lock
                    locked_M = M
                    lock_inliers = inliers      # lock 시점 inliers (개발용)
                    lock_ang = homography_angles(M)   # 기울기/원근/스케일 (사용자용)
                    print(f"정렬 고정됨 (inliers={inliers}, roll={lock_ang['roll']:.1f}deg). c로 재정렬.")
            if locked_M is None:                   # 아직 못 잡음 → 탐색 표시
                disp = cv2.resize(frame, (CW, CH))
                cv2.putText(disp, f"ALIGN: searching... (inliers={inliers})", (20, 30),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 165, 255), 2)
                cv2.imshow(win, disp)
                if cv2.waitKey(1) & 0xFF == ord('q'):
                    break
                continue
            canon = cv2.warpPerspective(frame, locked_M, (CW, CH))  # 고정 변환 재사용(출렁임 X)
        else:
            canon = calib.apply(frame)

        # 보정 진행 중이면 클릭 안내만
        if args.calib and not args.auto and calib.M is None:
            disp = frame.copy()
            for p in calib.pts:
                cv2.circle(disp, tuple(p), 5, (0, 255, 0), -1)
            cv2.putText(disp, f"Click 4 corners ({len(calib.pts)}/4)", (20, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
            cv2.imshow(win, disp)
            if cv2.waitKey(1) & 0xFF == ord('q'):
                break
            continue

        # ① T0: 기어 R 검출 (그림 유사도 or OCR) + 처리시간 측정  (--gear 일 때만)
        gtxt = ""
        gear_hit = False
        if args.gear and t0 is None:
            _t = time.perf_counter()
            if use_gear_tmpl:                       # 타겟 있으면 그림 유사도
                g_ncc, g_ssim = gear_score(canon, gtmpl)
                gear_sim = max(g_ncc, g_ssim)
                gtxt = f"sim {gear_sim:.2f}"
                gear_hit = gear_sim >= GEAR_SIM
            else:                                   # 타겟 없으면 OCR 'R'
                gtxt = gear_text(canon)
                gear_hit = "R" in gtxt
            gear_ms = (time.perf_counter() - _t) * 1000.0
            if not gear_hit:
                gear_seen_notR = True    # not-R 상태를 먼저 봐야 전환으로 인정
            elif gear_seen_notR:         # not-R -> R 전환 시점 = T0
                t0 = now()
                t0_img = canon.copy()    # 기어 R 순간 스냅샷
                if audio:
                    audio.arm()

        # ② 팝업 검출/표시 (NCC/SSIM 분리)
        ptxt = ""
        psc = 0.0
        popup_hit = False
        gate = (t0 is not None) or (not args.gear)   # 기어 off면 팝업 단독 검출
        if args.popup_ocr:
            if gate and popup_t is None:
                ptxt = popup_text(canon)
                popup_hit = any(k in ptxt for k in POPUP_KEYWORDS)
        else:
            _pt = time.perf_counter()
            ncc, ssim = popup_scores(canon, tmpl)     # 분리 계산
            popup_ms = (time.perf_counter() - _pt) * 1000.0   # 팝업 일치 판단 처리시간
            psc = max(ncc, ssim)
            psc_max = max(psc_max, psc)
            ncc_max = max(ncc_max, ncc)
            ssim_max = max(ssim_max, ssim)
            popup_hit = psc >= popup_sim
        if gate and popup_t is None and popup_hit:
            popup_t = now()
            analysis_ms = (popup_t - t_arrive) * 1000.0   # 프레임 도착→PASS 분석시간
            pass_ms = frame_gap_ms + analysis_ms          # 전체 = 프레임 도착 + 분석
            if t0 is None:               # 기어 off: 팝업 뜬 시점을 기준(T0)으로
                t0 = popup_t
            popup_img = canon.copy()     # 팝업 순간 스냅샷
            popup_ncc, popup_ssim = ncc, ssim   # 검출 순간 값 기록
            if args.sim_beep:            # 테스트용: 경고음 동시 발생 간주
                beep_t = popup_t
        if audio and audio.onset_t is not None and beep_t is None:
            beep_t = audio.onset_t

        # 판정 (둘 다 잡혔거나 윈도우 만료 시)
        if t0 is not None and verdict is None:
            have_all = popup_t is not None and (beep_t is not None or args.no_audio or audio is None)
            expired = (now() - t0) > 2.0
            if have_all or expired:
                ev = {"팝업": popup_t, "경고음": beep_t}
                lat = {k: (None if v is None else (v - t0) * 1000) for k, v in ev.items()}
                ts = [v for v in [popup_t, beep_t] if v is not None]
                sync = (max(ts) - min(ts)) * 1000 if len(ts) >= 2 else None
                c_popup = popup_t is not None
                c_beep = beep_t is not None or (audio is None or args.no_audio)
                c_resp = all(v is not None and 0 <= v <= RESP_LIMIT_S * 1000
                             for k, v in lat.items() if (k != "경고음" or c_beep and beep_t is not None))
                c_sync = sync is None or sync <= SYNC_TOL_MS
                # 판정 = 팝업 일치 기준(응답/동기는 참고, 음향/CAN 미연동)
                verdict = dict(pass_=c_popup,
                               lat=lat, sync=sync,
                               crit=(c_popup, c_beep, c_resp, c_sync))
                # 증거 저장 (스냅샷 + CSV 로그) — 이 사이클 1회
                if not saved:
                    try:
                        os.makedirs(RESULTS, exist_ok=True)
                        ts = time.strftime("%Y%m%d_%H%M%S")
                        dd = os.path.join(RESULTS, ts)
                        os.makedirs(dd, exist_ok=True)
                        if t0_img is not None:
                            cv2.imwrite(os.path.join(dd, "gear_R.png"), t0_img)
                        if popup_img is not None:
                            cv2.imwrite(os.path.join(dd, "popup.png"), popup_img)
                        def v(x, nd=1):
                            return "N/A" if x is None else round(x, nd)
                        sim_ok = "PASS" if psc_max >= popup_sim else "FAIL"
                        gear_report = f"""========================================
 UN R158 기어 R 검출 (시간 검증)
========================================
시각             : {ts}

[1] 기어 R 검출 (Not R --> R)
  판단 시간       : {v(gear_ms)} ms
  결과            : {"PASS (R 검출)" if t0 is not None else "N/A"}

스냅샷           : {dd}
========================================
"""
                        cluster_report = f"""========================================
 UN R158 클러스터 팝업 일치 (기대값 검증)
========================================
시각             : {ts}
판정(팝업일치)   : {sim_ok}

[2] 팝업 일치 판단
  전체 지연       : {v(pass_ms)} ms  (프레임 도착 + 분석)
   - 프레임 도착   : {v(frame_gap_ms)} ms  (실측 프레임 간격 ~1/fps)
   - 분석 시간     : {v(analysis_ms)} ms
  일치도 NCC      : {v(popup_ncc, 3)}
  일치도 SSIM     : {v(popup_ssim, 3)}
  기대(임계)      : >= {popup_sim:.2f}
  결과            : {sim_ok}

[3] ORB 정렬 (각도)
  상태            : {"LOCKED" if locked_M is not None else "N/A"}
  roll(좌우기울기): {v(lock_ang["roll"]) if lock_ang else "N/A"} deg
  perspective     : {v(lock_ang["persp"], 2) if lock_ang else "N/A"} (비스듬함)
  scale(줌)       : {v(lock_ang["scale"], 2) if lock_ang else "N/A"}
  [dev] inliers   : {lock_inliers if lock_inliers is not None else "N/A"}

스냅샷           : {dd}
========================================
"""
                        # 개별 결과 txt (클러스터는 항상, 기어는 --gear 일 때만)
                        with open(os.path.join(dd, "cluster_result.txt"), "w", encoding="utf-8") as fp:
                            fp.write(cluster_report)
                        with open(os.path.join(RESULTS, "cluster_realtime_log.txt"), "a",
                                  encoding="utf-8") as fp:
                            fp.write(cluster_report + "\n")
                        print(cluster_report)
                        if args.gear:
                            with open(os.path.join(dd, "gear_result.txt"), "w", encoding="utf-8") as fp:
                                fp.write(gear_report)
                            with open(os.path.join(RESULTS, "gear_realtime_log.txt"), "a",
                                      encoding="utf-8") as fp:
                                fp.write(gear_report + "\n")
                            print(gear_report)
                    except Exception as e:
                        print("증거 저장 실패:", e)
                    saved = True

        # 오버레이 + 튜닝 HUD
        disp = cv2.resize(canon, (CW, CH)).copy()
        # ROI 박스(정렬 확인용): 기어=노랑, 팝업=하늘
        gy1, gy2, gx1, gx2 = GEAR_ROI
        py1, py2, px1, px2 = POPUP_ROI
        cv2.rectangle(disp, (gx1, gy1), (gx2, gy2), (0, 255, 255), 1)
        cv2.rectangle(disp, (px1, py1), (px2, py2), (255, 200, 0), 1)

        def put(y, txt, col=(255, 255, 255)):
            cv2.putText(disp, txt, (10, y), cv2.FONT_HERSHEY_SIMPLEX, 0.55, col, 2)
        # [1] 기어: --gear 일 때만 (기본은 팝업만 인식 → OFF 표시)
        if args.gear:
            put(25, f"[1] gear Not R->R: {gear_ms:.0f} ms  result {'PASS' if t0 else '-'}",
                (0, 255, 0) if t0 else ((0, 255, 255) if "R" in gtxt else (180, 180, 180)))
        else:
            put(25, "[1] gear check: OFF (popup only)", (140, 140, 140))
        _pcol = (0, 255, 0) if psc >= popup_sim else (180, 180, 180)
        # [2] 팝업 일치 판단 (전체/로직 시간 + 결과)
        if args.popup_ocr:
            hit = any(k in ptxt for k in POPUP_KEYWORDS)
            put(48, f"[2] popup match: OCR '{ptxt[:16]}'  [expect keyword]",
                (0, 255, 0) if hit else (180, 180, 180))
        else:
            _tot = (f"{pass_ms:.0f}ms (frame {frame_gap_ms:.0f} + analysis {analysis_ms:.1f})"
                    if pass_ms is not None else "-")
            put(48, f"[2] popup match: total {_tot}"
                    f"  result {'PASS' if psc >= popup_sim else '-'}", _pcol)
        # [3] 일치도 (NCC / SSIM 분리)
        put(71, f"[3] similarity: ncc {ncc:.3f}   ssim {ssim:.3f}   [>= {popup_sim:.2f}]", _pcol)
        # [4] ORB 정렬 매칭도 (각도 강건성)
        if aligner is not None:
            if locked_M is not None and lock_ang is not None:
                put(94, "[4] ORB align: LOCKED", (0, 255, 0))
            else:
                put(94, f"[4] ORB align: searching (inliers {inliers if inliers is not None else '-'})",
                    (0, 165, 255))
        else:
            put(94, "[4] ORB align: manual mode", (180, 180, 180))
        # VERDICT (동적) — 팝업 유사도 실시간 기준: >=임계 PASS, 미만 FAIL
        if t0 is None:
            put(124, "VERDICT: waiting popup...", (150, 150, 150))
        elif psc >= popup_sim:
            put(124, f"VERDICT: PASS  (sim {psc:.2f} >= {popup_sim:.2f})", (0, 220, 0))
        else:
            put(124, f"VERDICT: FAIL  (sim {psc:.2f} < {popup_sim:.2f})", (0, 0, 255))
        put(CH - 12, "[+/-]th  [r]reset  [c]align  [p/g]target  [q]quit",
            (200, 200, 200))
        cv2.imshow(win, disp)

        k = cv2.waitKey(1) & 0xFF
        if k == ord('q'):
            break
        elif k == ord('r'):     # 판정 리셋 (다음 테스트)
            t0 = popup_t = beep_t = verdict = None
            t0_img = popup_img = None
            saved = False
            gear_seen_notR = False   # 다음 테스트도 not-R->R 전환으로 새로 판정
            pass_ms = None           # 전체 지연도 새로 측정
            psc_max = ncc_max = ssim_max = 0.0
            popup_ncc = popup_ssim = None
            if audio: audio.reset()
            print("리셋")
        elif k == ord('c'):     # 정렬 재설정 (카메라 움직였을 때)
            calib.reset()
            locked_M = None
            lock_inliers = None
            lock_ang = None
            print("정렬 리셋 — 자동 재정렬(또는 4모서리 재클릭)")
        elif k in (ord('+'), ord('=')):
            popup_sim = min(0.99, popup_sim + 0.02); print("popup th =", round(popup_sim, 2))
        elif k == ord('-'):
            popup_sim = max(0.10, popup_sim - 0.02); print("popup th =", round(popup_sim, 2))
        elif k == ord(']') and audio:
            audio.factor += 0.5; print("audio th =", audio.factor)
        elif k == ord('[') and audio:
            audio.factor = max(1.5, audio.factor - 0.5); print("audio th =", audio.factor)
        elif k == ord('p'):     # 현재 팝업 영역을 실촬영 템플릿으로 등록
            y1, y2, x1, x2 = POPUP_ROI
            tmpl = canon[y1:y2, x1:x2].copy()
            cv2.imwrite(os.path.join(HERE, "expected", "popup_template.png"), tmpl)
            print("팝업 템플릿 등록됨 → 현재 팝업 영역 저장 (이후 이 그림과 비교)")
        elif k == ord('g'):     # 현재 기어 영역을 'R 들어온 상태' 타겟으로 등록
            y1, y2, x1, x2 = GEAR_ROI
            gtmpl = canon[y1:y2, x1:x2].copy()
            cv2.imwrite(os.path.join(HERE, "expected", "gear_R_template.png"), gtmpl)
            use_gear_tmpl = not args.gear_ocr
            print("기어 R 타겟 등록됨 → 이제 그림 유사도로 기어변속 검출 (r로 리셋 후 테스트)")
        elif k == ord('d'):     # 디버그: 현재 정렬화면/기어ROI/이진화 저장 (기어검출 진단)
            dbg = os.path.join(RESULTS, "debug")
            os.makedirs(dbg, exist_ok=True)
            gy1, gy2, gx1, gx2 = GEAR_ROI
            groi = canon[gy1:gy2, gx1:gx2]
            cv2.imwrite(os.path.join(dbg, "canon.png"), canon)          # 정렬된 전체 화면
            cv2.imwrite(os.path.join(dbg, "gear_roi.png"), groi)        # 기어 영역 크롭
            cv2.imwrite(os.path.join(dbg, "gear_thresh.png"), gear_binary(canon))  # OCR이 실제 보는 이미지
            print(f"디버그 저장: {dbg}  (OCR 결과='{gear_text(canon)}')")

    cap.release()
    if audio: audio.stop()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
