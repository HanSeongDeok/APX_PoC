# apx_app Java 이식 (R158 검증, 레거시 RCP)

파이썬 `poc/r158/apx_app` (PySide6) → Java/Eclipse RCP 이식. 제품 경로는 `R158/apx`.

## 기준 (고정)

- **Java 8** (JavaSE-1.8) - `Bundle-RequiredExecutionEnvironment` 및 컴파일 타겟
- **레거시 RCP** (Eclipse 3.x 계열: `IPerspectiveFactory`, `org.eclipse.ui.views` 확장점, 클래식 plugin.xml)
- **번들 네이밍** `com.suresofttech.apx.*` (기존 제품 편입 대비)
- **원칙**: 유지보수를 위해 **외부 라이브러리 우선**(직접 구현 최소화), native 의존 회피(RCP p2 배포 단순화)

## 라이브러리 스택 (확정)

| 영역 | 선택 | 비고 |
|---|---|---|
| 비전/ROI/ORB 정렬 | **BoofCV** | 순수 Java(native 없음), 서브픽셀 정밀 |
| 웹캠 캡처 | **webcam-capture(sarxos)** | native(bridj) 실장 검증 필요 |
| FFT | **JTransforms** (자작 radix-2는 검증용 유지) | 임의 길이 rfft |
| 행렬/수치 | **EJML** | BoofCV가 이미 의존 |
| 실시간 차트 | **자체 SWT GC (ScopeCanvas)** | Mars SWT 3.104가 최신 Nebula(SWT 3.115+) 미충족 → 무의존 GC 채택 |
| 설정 | **Gson** | |

> 전부 Maven JAR → OSGi 번들 wrap 필요(bnd/p2-maven-plugin 정석, 또는 lib/+Bundle-ClassPath 간이).

## 번들 구조

```
R158/apx/
  com.suresofttech.apx.core/     엔진 (SWT 무의존, 순수 Java)
    src/com/suresofttech/apx/core/
      dsp/    Fft, SignalMath
      audio/  BeepMatcher, MatchResult, Tone, WavIo, …
      vision/ CameraService, RoiMatchDetector, …
      rear/   RearGrid, Verdict, VerdictResult
      sync/   SyncBus, Calibration
  com.suresofttech.apx.ui/       RCP UI (SWT/JFace)
    plugin.xml                   Perspective + Views
    src/com/suresofttech/apx/ui/
      ApxPerspective, view/*, widget/*
```

기대 자산(차량 후방 이미지 / 기대음 WAV)은 PoC 경로 `poc/r158/expected/` 를 그대로 사용한다.

## 이식 상태

| 모듈 | 파이썬 원본 | 상태 |
|---|---|---|
| 음향 일치 엔진 | engine/audio.py | ✅ **이식 / 검증 완료** (파이썬과 동일 결과) |
| 근접 경고음 생성 | engine/tone.py | ✅ 이식 완료 |
| wav 로드 | (scipy.io.wavfile) | ✅ WavIo (JDK 내장) |
| FFT/상관 | numpy/scipy | ✅ 무의존 구현 (radix-2 FFT + NCC) |
| 음향 View | ui/audio_tab.py | 🟡 배선 골격 (마이크→엔진→SWT) |
| 설정/기어/클러스터 View | ui/*.py | 🟡 스켈레톤 |
| 실시간 파형 / 스펙트럼 | ui/scope.py | ✅ ScopeCanvas (SWT GC, 무의존) |
| 검출 지연(콜드스타트) | (신규) | ✅ AudioView 표시 + LatencyCheck |
| 기어 / 클러스터 엔진 | engine/{gear,cluster}.py | 🔴 미착수 (BoofCV 필요) |

## 빌드 / 검증

### 엔진(core) - Eclipse 없이 순수 검증 가능
```
cd com.suresofttech.apx.core
javac -encoding UTF-8 -source 8 -target 8 -d bin $(find src -name "*.java")
java -cp bin com.suresofttech.apx.core.audio.SelfCheck      # → ALL PASS
```
SelfCheck 는 파이썬에서 돌린 것과 동일 시나리오(일치 PASS, 900Hz 불일치 FAIL, AND 게이트, Tone 5.28s)를 검증.

### UI 번들 - Eclipse PDE 필요

**Import 경로 (이동 후):** `R158/apx/com.suresofttech.apx.core`, `R158/apx/com.suresofttech.apx.ui`
(예전 `poc/r158/apx_app_java/...` 프로젝트가 워크스페이스에 있으면 먼저 제거)

1. **File → Import → Existing Projects into Workspace**
   - root: `R158/apx` (또는 두 플러그인 폴더를 각각 선택)
   - `com.suresofttech.apx.core`, `com.suresofttech.apx.ui` 둘 다 체크
2. **Target Platform** (중요): Window → Preferences → Plug-in Development → Target Platform
   - Eclipse SDK / RCP가 포함된 active target 필요
   - `org.eclipse.ui` 등이 빨간불이면 Target이 비어 있는 상태
3. **JRE**: JavaSE-1.8 실행 환경이 잡혀 있어야 함 (Project → Properties → Java Build Path)
4. core `lib/opencv-4.9.0-0.jar` 로컬 배치 확인 (`VISION_SETUP.md`)
5. 두 프로젝트에서 **Project → Clean**, 필요 시 MANIFEST.MF 우클릭 → PDE Tools → Update Classpath
6. Run As → Eclipse Application, Perspective = "R158 검증"

## 이식 시 주의 (엔진)

- **FFT**: radix-2 로 다음 2의 거듭제곱 zero-padding. 라이브 / 템플릿 동일 길이라 일치도(정규화)엔 영향 없음. NumPy 완전 수치 일치가 필요하면 JTransforms(임의 길이 rfft)로 교체.
- **상호상관**: FFT 기반(합성곱 정리) O(S log S) + 국소에너지 prefix-sum O(S). 직접 O(S / L)(`nccMaxDirect`)과 수치 동일, 검증 완료. sr=44100 기준 블록당 45ms→4ms(11×), 실시간 배속 0.96→0.09.
- **판정**: `(freqSim ≥ freqThr) AND (waveSim ≥ waveThr)` - 파이썬 최신본과 동일.
