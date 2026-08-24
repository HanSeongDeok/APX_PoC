# R158 검증 도구 (Java) - 사용 라이브러리 & 알고리즘 전체 목록

`R158/apx` (Eclipse 레거시 RCP, Java 8) 에서 검증에 사용한 외부 라이브러리와 알고리즘 전부.

---

## A. 라이브러리 (외부 의존)

### A-1. 컴퓨터비전
| 라이브러리 | 좌표 / 버전 | 용도 |
|---|---|---|
| **OpenCV (Java)** | `org.openpnp:opencv:4.9.0-0` | ORB / 정합 / 워프 / 색변환 / 이진화 / 연결성분 등 비전 **계산** 전부. 공식 `org.opencv.*` API + 네이티브(opencv_java490.dll) 번들 + 자동로더 `nu.pattern.OpenCV.loadLocally()` |
| **SWT Canvas + GC** | `org.eclipse.swt` (RCP 내장) | 비전 결과 **표시**: 프레임 blit(`drawImage`) + **ROI 초록 박스**(`drawRectangle`) / 정합 결과 오버레이 + 마우스로 ROI 설정. 계산=OpenCV / 그리기=SWT (OpenCV `HighGui.imshow`는 AWT라 RCP 미사용) |
| *(자체)* Mat↔ImageData | 자체 구현 (~20줄) | OpenCV `Mat`(BGR) ↔ SWT `ImageData` 변환 브리지 (라이브러리 아님) |

### A-2. 웹캠 캡처
| 라이브러리 | 좌표 / 버전 | 용도 |
|---|---|---|
| **webcam-capture** (Sarxos) | `com.github.sarxos:webcam-capture:0.3.12` | 웹캠 열거 / 캡처 → `java.awt.image.BufferedImage` |
| bridj | `com.nativelibs4java:bridj:0.7.0` | webcam-capture 기본 드라이버(네이티브 그래버) 의존 |
| slf4j-api | `org.slf4j:slf4j-api:1.7.2` | webcam-capture 로깅 API 의존 |

### A-3. FFT / 신호처리
| 라이브러리 | 좌표 / 버전 | 용도 |
|---|---|---|
| **JTransforms** | `com.github.wendykierp:JTransforms:3.1` | FFT (실수 rfft / 복소 FFT/IFFT). `Fft.java` 백엔드 |
| JLargeArrays | `pl.edu.icm:JLargeArrays:1.6` | JTransforms 의존 |
| commons-math3 | `org.apache.commons:commons-math3:3.6.1` | JTransforms FastMath 의존 |

### A-4. 그래프(파형 / 스펙트럼) 출력  ← **그래프 라이브러리**
| 라이브러리 | 좌표 / 버전 | 용도 |
|---|---|---|
| **XChart** | `org.knowm.xchart:xchart:3.8.8` | 실시간 파형 / 스펙트럼 라인차트 (AWT/Swing) |
| **SWT_AWT 브리지** | `org.eclipse.swt.awt.SWT_AWT` (SWT 내장) | AWT XChartPanel을 SWT.EMBEDDED Composite에 임베드 |
| *(폴백)* ScopeCanvas | 자체 SWT GC | XChart/SWT_AWT 이슈 시 되돌릴 무의존 렌더 |

> Java 8 + Mars(SWT 3.104) 제약상 최신 SWTChart / Nebula(Java11+/p2)는 불가 → **AWT 차트(XChart)+SWT_AWT**가 유일한 라이브러리 해법.

### A-5. GUI / 프레임워크 (플랫폼 제공)
| 번들 | 용도 |
|---|---|
| **Eclipse RCP** - SWT / JFace / `org.eclipse.ui` | 레거시 3.x 스타일 UI (IPerspectiveFactory + plugin.xml, 4 View) |
| `org.eclipse.core.runtime`, `org.eclipse.equinox.app` | RCP 런타임 / 애플리케이션 |

### A-6. JDK 내장 (외부 라이브러리 없이 사용)
| 표준 API | 용도 |
|---|---|
| `javax.sound.sampled` | 마이크 캡처, WAV 로드, 톤 재생 |
| `java.awt.image.BufferedImage` | 웹캠 ↔ OpenCV 경계 타입 |

### A-7. 자체 구현 (라이브러리 아님 - 참고)
| 클래스 | 내용 |
|---|---|
| `Ssim` | 구조적 유사도 SSIM (OpenCV GaussianBlur 조합, Wang et al.) - Java 전용 SSIM 라이브러리가 없어 직접 |
| `SignalMath` | 정규화 상호상관(정합필터) 등 - FFT는 JTransforms 사용 |
| `EvidenceCapture` | 판정 전후 ±3프레임 스냅샷 링버퍼 |

---

## B. 알고리즘 (용도만)

각 알고리즘이 "무엇에 쓰이는지"만 요약 (수식은 표준 정의라 생략).

### B-1. 영상 정렬 (기어 / 클러스터 공용)
- **ORB** - 특징점 검출 / 기술 (정렬 기준점)
- **BFMatcher(Hamming) + ratio test(0.75)** - 특징점 매칭
- **findHomography + RANSAC** - 프레임→기준영상 원근변환 추정 (inlier≥25에서 락)
- **warpPerspective** - 프레임을 canon 640²로 정렬(de-skew)

### B-2. 판정 - 기어 R / 클러스터 팝업 (이미지 유사도)
- **NCC** (`matchTemplate TM_CCOEFF_NORMED`) - 고정 ROI vs 기준 크롭 유사도 → **판정 게이트**(≥임계면 hit). 실측 R=0.99 vs P/N/D≈0
- **SSIM** - 구조적 유사도, **참고 지표**(게이트 미사용)
- **cvtColor / resize** - 색변환 / 크기 보조

### B-3. (구) 기어 채도 방식 - 폐기 (참고)
HSV 채도 마스크 + connectedComponents + Otsu + 밝기 상대비교 → 차종 의존이라 B-2로 통일.

### B-4. 판정 - 음향 (`BeepMatcher`)
- **FFT(rfft)** - 스펙트럼 계산
- **코사인 유사도** - 주파수 일치도 (라이브 vs 기대펄스 스펙트럼)
- **정합필터(정규화 상호상관)** - 파형 일치도 (FFT 합성곱)
- **Hanning 창 / 단일펄스 추출 / 대역에너지 EMA** - 누설저감 / 주기무관 매칭 / 트리거
- 판정 = 주파수 AND 파형 (각 임계 이상)

---

## C. 판단 속도 & 지연 계산식

- **판단(처리) 속도** = 한 프레임/블록을 판정하는 **분석 시간 `D_ana`** - 영상 ≈ **4.7ms**(실측), 음향 ≈ 수 ms
- **전환 지연(응답 등록까지)** = **프레임/블록 간격 + 분석** → `passMs = D_gap + D_ana` (아래 분해)
- 30fps(33ms) 기준 분석 4.7ms는 여유 → 실시간 병목 아님

### C-1. 영상 (기어 / 클러스터)
```
[화면 변화 발생]
   │  ① 캡처 / 전송 지연 D_cap  (센서 노출→인코딩→USB→드라이버 버퍼→cap 반환)
   │       ≈ 카메라 의존(수십~150ms), 거의 일정
   ▼  cap.read() 반환 시각 = t_arrive  ← 지연 측정 기준점
   │  ② 프레임 간격 D_gap = 1/fps (30fps → 33.3ms)  ← 이벤트가 프레임 사이 발생 → 최대 1프레임 양자화
   │  ③ 분석 D_ana = process()  (BufferedImage→Mat + warp + NCC + SSIM)  ≈ 4.7ms (실측)
   ▼
[PASS 판정]
```
| 항목 | 기호 | 값(예) | total 반영 |
|---|---|---|---|
| 캡처 / 전송 | D_cap | ~수십~150ms (일정) | **차분 시 상쇄** (아래) |
| 프레임 간격 | D_gap | 33.3ms @30fps | ✅ 포함(`frameGapMs`) |
| 분석 | D_ana | ~4.7ms | ✅ 포함(`analysisMs`) |
| **측정 전환지연** | **passMs** | **= D_gap + D_ana ≈ 38ms** | 도구 오버헤드 |

> **핵심**: `passMs = frameGapMs + analysisMs`. **D_cap(캡처지연)은 상수라 "이벤트→이벤트" 차분**(예: 기어 R 검출 → 팝업 검출)에선 **상쇄**되어 실제 차량 응답만 남는다. 절대 D_cap이 필요하면 → 검출속도 캘리브레이션 도구(예정).

### C-2. 음향
```
[소리 발생]
   │  ① 마이크 캡처 지연 D_mic  (마이크→ADC→드라이버/OS 버퍼)  ≈ 182ms (실측, 장치 의존)
   │  ② 블록 지연 D_blk = blocksize / sr  (예: 2048/44100 ≈ 46ms, 블록 단위 처리 양자화)
   │  ③ 분석 D_ana = feed()  (FFT + 정합필터)  ≈ 수 ms
   ▼
[PASS 판정]
onset 시각 = capturedSamples / sr   (측정 시작 후 경과 = 버튼 이후 샘플수/샘플레이트)
```
| 항목 | 기호 | 값(예) | 비고 |
|---|---|---|---|
| 마이크 캡처 | D_mic | ~182ms (실측) | 장치 / 드라이버 의존, **가장 큼** |
| 블록 | D_blk | blocksize/sr (~46ms) | 콜백 단위 양자화 |
| 분석 | D_ana | ~수 ms | FFT+상관 |

> onset을 **벽시계가 아니라 누적 샘플수(capturedSamples/sr)** 로 재는 이유: 측정 시작(버튼) 이후 경과를 정확히 - 콜드스타트(소리 먼저 재생) 대응. **D_mic 절대 보정은 캘리브레이션 도구로**(마이크 캡처지연 실측 / 차감, 예정).

### C-3. 증거 (공통)
- **±3프레임 링버퍼** - 판정 순간의 전/중/후 스냅샷 + 상대시각 기록(보고서).

---

## C. 한 줄 요약
- **비전** = OpenCV(ORB / homography / warp / matchTemplate) / **정렬 후 고정 ROI NCC**로 결정적 판정 (OCR / CAN / ML 없음)
- **음향** = JTransforms FFT 기반 **주파수(코사인)+파형(정합필터) AND** 판정
- **그래프** = XChart(AWT) + SWT_AWT 브리지
- **웹캠** = webcam-capture, **FFT** = JTransforms, **UI** = Eclipse RCP(SWT)
