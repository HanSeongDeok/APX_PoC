# R158 검증 도구 - Python → Java(RCP) 이식 보고서

파이썬 PoC(`apx_app`, PySide6)를 Java/Eclipse 레거시 RCP(`R158/apx`)로 이식한 결과 정리.
목적: 제품 편입(SureSoft 툴 체인) + 성능. 판정 로직은 **CAN 없이 결정적(재현 가능)** 유지.

---

## 1. 라이브러리 스택 (Python ↔ Java)

| 기능 | Python (`apx_app`) | Java (`R158/apx`) | 비고 |
|---|---|---|---|
| 컴퓨터비전 (ORB / warpPerspective / matchTemplate / HSV / Otsu / 연결성분) | OpenCV `cv2` (opencv-python) | **OpenCV Java** `org.openpnp:opencv 4.9.0` | **동일 OpenCV 네이티브** → 알고리즘 / 수치 동일. openpnp가 네이티브 번들+자동로더(`nu.pattern.OpenCV.loadLocally()`) 제공, 오프라인 OK |
| SSIM | scikit-image `structural_similarity` | **자체 구현** `Ssim.java` (OpenCV GaussianBlur 기반 Wang et al.) | Java엔 전용 SSIM 라이브러리 없음. 단 판정 게이트는 **NCC**, SSIM은 참고지표 |
| FFT | NumPy `np.fft` | **JTransforms 3.1** (+ JLargeArrays, commons-math3) | 자작 radix-2와 머신정밀도 일치 검증(오차 ~1e-14) |
| 매칭필터 상호상관 | `scipy.signal.correlate` | 자체 (FFT 합성곱정리, JTransforms 기반) | `SignalMath.crossCorrValid` |
| 웹캠 캡처 | OpenCV `VideoCapture` | **webcam-capture** (Sarxos 0.3.12 + bridj) | 순수 자바. 프레임 = `BufferedImage` |
| 오디오 캡처 | `sounddevice` | **javax.sound.sampled** (JDK) | 무의존 |
| 실시간 파형 / 스펙트럼 차트 | PySide6 커스텀 위젯 | **XChart 3.8.8** (+ SWT_AWT 브리지) | Java8+Mars에서 되는 유일한 라이브러리 차트(SWTChart / Nebula는 Java11+/p2 문제). 폴백 `ScopeCanvas`(SWT GC) 보존 |
| GUI | PySide6 (Qt) | **Eclipse RCP** (SWT, 레거시 3.x: IPerspectiveFactory + plugin.xml) | 4 View 동시 표시 |
| WAV I/O | scipy/soundfile | javax.sound.sampled | |

> **핵심 원칙**: 무거운 로직은 전부 라이브러리(OpenCV / webcam-capture / XChart / JTransforms). 남은 자체 구현(SSIM / 증거수집 / 보고서)은 라이브러리화가 오히려 손해라 유지.

### 의존 JAR (수동 배치 - PDE `Bundle-ClassPath`)
- `core/lib/`: opencv-4.9.0-0(104MB) / webcam-capture-0.3.12 / bridj-0.7.0 / slf4j-api-1.7.2 / JTransforms-3.1 / JLargeArrays-1.6 / commons-math3-3.6.1
- `ui/lib/`: xchart-3.8.8

---

## 2. 판정 알고리즘 (기어 / 클러스터 = 공용 로직)

**기어 R단 / 클러스터 팝업 모두 동일한 "이미지 유사도" 파이프라인**(`RoiMatchDetector`).
(기어는 원래 채도 앵커 방식이었으나 - R 강조가 글자가 아닌 화살표/딴 위치인 기어봉이 있어 차종 의존적 → **폐기하고 유사도 방식으로 통일**.)

```
① ORB 정렬 (한 번 락)
   기준영상 / 현재프레임 ORB 특징점 → BFMatcher(HAMMING) knn + ratio(0.75)
   → findHomography(RANSAC) M(frame→ref) → warpPerspective로 canon(640²) 정렬
   inliers ≥ 25 이면 M 고정(이후 재계산 안 함)

② 고정 ROI 유사도 판정 (매 프레임)
   사용자가 기준영상에서 드래그로 지정한 영역만 비교:
   canon[ROI] vs 기준[ROI 크롭]  →  NCC(TM_CCOEFF_NORMED)
   hit = NCC ≥ 임계(기본 0.70)
 / 기어  : 기준=R 체결 화면, ROI=R 표시 영역   → hit=R 체결(PASS)
 / 클러스터: 기준=팝업 뜬 화면, ROI=경고창 영역 → hit=팝업 등장(PASS)

③ 전환 지연 + 증거
   최초 hit 순간: frame_gap + 분석시간 = 전환지연 기록
   판정 전후 ±3프레임 스냅샷(EvidenceCapture 링버퍼) → 보고서
```

**판정 지표는 NCC 단독**(SSIM은 참고). 실측: R 영역 유사도 R=0.993 vs P/N/D=0.036/0.015/-0.004 - NCC가 압도적 분리. (SSIM은 공통 배경 탓에 비-대상도 0.6~0.72로 높아 max(ncc,ssim)면 오검출 → NCC로 게이트.)

특징: **OCR / CAN / ML 없음.** 정렬로 위치를 맞추고 고정 영역만 비교 → 결정적 / 재현 가능(법규 증거).

## 2-2. 음향 판정 (`BeepMatcher`)

```
기대 beep(.wav) → 에너지 최대 구간에서 '삐 한 번' 단일 펄스 추출(주기 무관)
매 오디오 블록:
 / 주파수 일치도 = 라이브 스펙트럼 vs 펄스 스펙트럼 코사인 유사도 [0,1]  (FFT)
 / 파형 일치도   = 정규화 상호상관(정합필터) 최대값 [0,1]              (FFT 상관)
  PASS = (주파수 ≥ 임계) AND (파형 ≥ 임계)
콜드스타트(소리 먼저 재생 후 측정) 대응: 에너지 급증(hasSound) 게이트 제외, isPass로만 확정.
```

---

## 3. 성능 비교 (실측)

**환경**: 동일 PC, 클러스터 팝업 기준영상(1254×1254) 자기매칭, `process()` 300회 평균.
Python `ClusterDetector.process(numpy)` vs Java `RoiMatchDetector.process(BufferedImage)`.
(Java는 BufferedImage→Mat 변환 포함. 내부 판정은 둘 다 640 canon으로 정렬 후 수행.)

| 구간 | Python (cv2) | Java (OpenCV) | 해석 |
|---|---|---|---|
| 첫 프레임 (ORB 락, **1회성**) | 47.6 ms | 81.3 ms | Java는 JVM/JIT/JNI 워밍업 포함. 락은 1회뿐이라 실시간 무관 |
| **정상상태 (실시간 핫패스)** | 5.54 ms (~181 fps) | **4.70 ms (~213 fps)** | **Java ~15% 빠름** |

### 해석
- 두 구현 모두 **같은 OpenCV 네이티브**를 호출 → 근본 성능은 동일. 차이는 주변부에서 발생.
- **정상상태 Java 우세** 이유: ① JIT 최적화된 루프, ② Java의 SSIM(OpenCV GaussianBlur)이 Python skimage SSIM보다 빠름, ③ BufferedImage→Mat 변환을 포함하고도 상쇄.
- **첫 프레임 Python 우세**: Java의 최초 ORB/JNI 경로 워밍업 비용. 정렬 락 후엔 나타나지 않음.
- **결론**: 이식으로 실시간 성능 손실 없음(오히려 소폭 개선). 30fps(33ms) 기준 양쪽 다 6~7배 여유. **네이티브 OpenCV 채택 결정이 성능적으로 정당.**

> 주의: 자기매칭 / 고정이미지 상대 비교. 실제 웹캠(≈1280×720)은 640 canon 정렬 기준이라 유사 수준. 절대치보다 **상대 비교**로 볼 것.

---

## 4. 아키텍처 (레이어 분리)

```
com.suresofttech.apx.core   (엔진 / SWT 무의존)
  ├ vision : Cv(로더 / 한글imread / BufferedImage↔Mat) / OrbAligner / RoiMatchDetector
  │         RoiMatchResult / Ssim / EvidenceCapture / CameraService(webcam-capture)
  ├ audio  : AudioCapture / BeepMatcher / MatchResult / WavIo / Tone / MicMeter
  └ dsp    : Fft(JTransforms) / SignalMath

com.suresofttech.apx.ui     (RCP / OpenCV 무의존)
  ├ view   : SettingsView / GearView / ClusterView / AudioView
  └ widget : CameraCanvas / ScopeCanvas / AudioScope / TestPlayerDialog
```

- **경계 타입 = `BufferedImage`**: core는 OpenCV(Mat)만, ui는 SWT만 안다. `RoiMatchDetector.process(BufferedImage)`가 내부에서 Mat 변환 / 해제, `canonImage`(BufferedImage)로 반환. → 각 번들이 자기 라이브러리만 의존.
- **Mat 수명관리**: 반환 canon은 다음 프레임에 release(뷰가 동기 변환 가정), 증거는 clone+축출 시 release (네이티브 메모리 누수 방지).
- **공유 웹캠**: `CameraService` 싱글턴 1대 → 4 View가 최신 프레임 폴링(파이썬 단일 cap 대응).
- **단축키**: 4 View 동시 표시라 `display.addFilter` + 포커스 스코프(`isDescendant`)로 활성 View만 반응.

---

## 5. 제약 / 환경 메모

- **런타임 = Java 8 + Eclipse Mars(SWT 3.104)**. 이 제약이 차트 라이브러리 선택을 좌우(최신 SWTChart / Nebula는 Java 11+/p2 배포 → 불가). AWT 차트(XChart)를 SWT_AWT로 임베드가 유일해.
- OpenCV JAR(104MB)는 GitHub 100MB 제한으로 저장소 제외 → clone 후 Maven에서 받아 `lib/` 배치(`VISION_SETUP.md`).
- 미검증 항목: SWT_AWT 임베드 런타임 거동(Windows 리페인트/포커스) - 실기 확인 필요. 문제 시 `ScopeCanvas`(GC) 폴백.
