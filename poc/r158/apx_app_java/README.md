# apx_app Java 이식 (R158 검증, 레거시 RCP)

파이썬 `poc/r158/apx_app` (PySide6) → Java/Eclipse RCP 이식. 성능·제품 편입 목적.

## 기준 (고정)

- **Java 8** (JavaSE-1.8) — `Bundle-RequiredExecutionEnvironment` 및 컴파일 타겟
- **레거시 RCP** (Eclipse 3.x 계열: `IPerspectiveFactory`, `org.eclipse.ui.views` 확장점, 클래식 plugin.xml)
- **번들 네이밍** `com.suresofttech.apx.*` (기존 제품 편입 대비)
- **OpenCV** = JavaCV(bytedeco) 예정 (영상 엔진용, 오디오엔 불필요)

## 번들 구조

```
apx_app_java/
  com.suresofttech.apx.core/     엔진 (SWT 무의존, 순수 Java) — 컴파일·검증 완료
    src/com/suresofttech/apx/core/
      dsp/    Fft, SignalMath                (numpy.fft / scipy 대체)
      audio/  BeepMatcher, MatchResult, Tone, WavIo, SelfCheck
      vision/ VisionPortTodo                 (gear·cluster 이식 예정 표식)
  com.suresofttech.apx.ui/       RCP UI (SWT/JFace) — 스켈레톤
    plugin.xml                   Perspective + 4 View (설정·기어·클러스터·음향)
    src/com/suresofttech/apx/ui/
      ApxPerspective, view/{Settings,Gear,Cluster,Audio}View
```

## 이식 상태

| 모듈 | 파이썬 원본 | 상태 |
|---|---|---|
| 음향 일치 엔진 | engine/audio.py | ✅ **이식·검증 완료** (파이썬과 동일 결과) |
| 근접 경고음 생성 | engine/tone.py | ✅ 이식 완료 |
| wav 로드 | (scipy.io.wavfile) | ✅ WavIo (JDK 내장) |
| FFT/상관 | numpy/scipy | ✅ 무의존 구현 (radix-2 FFT + NCC) |
| 음향 View | ui/audio_tab.py | 🟡 배선 골격 (마이크→엔진→SWT) |
| 설정/기어/클러스터 View | ui/*.py | 🟡 스켈레톤 |
| 기어·클러스터 엔진 | engine/{gear,cluster}.py | 🔴 미착수 (OpenCV/JavaCV 필요) |

## 빌드·검증

### 엔진(core) — Eclipse 없이 순수 검증 가능
```
cd com.suresofttech.apx.core
javac -encoding UTF-8 -source 8 -target 8 -d bin $(find src -name "*.java")
java -cp bin com.suresofttech.apx.core.audio.SelfCheck      # → ALL PASS
```
SelfCheck 는 파이썬에서 돌린 것과 동일 시나리오(일치 PASS, 900Hz 불일치 FAIL, AND 게이트, Tone 5.28s)를 검증.

### UI 번들 — Eclipse PDE 필요
1. Eclipse(레거시, 3.x 타겟)에서 두 프로젝트 Import (기존 `.project`/`.classpath` 포함)
2. UI 번들은 `org.eclipse.ui`, `org.eclipse.swt` 타겟 플랫폼 필요 (javac 단독 컴파일 불가)
3. Run As → Eclipse Application, Perspective = "R158 검증"

## 이식 시 주의 (엔진)

- **FFT**: radix-2 로 다음 2의 거듭제곱 zero-padding. 라이브·템플릿 동일 길이라 일치도(정규화)엔 영향 없음. NumPy 완전 수치 일치가 필요하면 JTransforms(임의 길이 rfft)로 교체.
- **상호상관**: FFT 기반(합성곱 정리) O(S log S) + 국소에너지 prefix-sum O(S). 직접 O(S·L)(`nccMaxDirect`)과 수치 동일, 검증 완료. sr=44100 기준 블록당 45ms→4ms(11×), 실시간 배속 0.96→0.09.
- **판정**: `(freqSim ≥ freqThr) AND (waveSim ≥ waveThr)` — 파이썬 최신본과 동일.
