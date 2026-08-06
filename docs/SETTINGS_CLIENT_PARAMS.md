# settings 모듈 — client(이솝/ESOP) 주입 파라미터 계약

> **범위**: `c:\DEV\apx\R158\apx` 의 settings 모듈이 client 로부터 **받아야 할 파라미터**만 확정.
> 판정/측정 제어·파일 I/O·영속화는 **client 책임**, 본 모듈은 **시각화 + 엔진 + 설정 API** 를 제공한다.
> **작성**: apx-claude(오케스트레이터) ↔ apx-cursor(Cursor Agent) tmux 합의. **대화 턴 수: 4턴** (10턴 한도 내).

---

## 계약 진입점 (합의된 구조)

- 주입 계약의 주 진입점은 **불변 파라미터 객체 `ApxSettingsSpec`** + `apply(spec)` / `applyQuiet(spec)`.
  - 기존 `replaceQuiet(인자 8~10개)` 는 깨지기 쉬워 **Spec 으로 대체**.
  - 도메인 블록 분리: **`VisionSpec` / `AudioSpec`** (+ optional `EvidenceSpec` / `preferredWidth`).
  - 부분 갱신: `apply(spec.visionOnly(...))` / `apply(spec.audioOnly(...))` / `apply(full)`.
  - UI 단위 위젯(바)용 **개별 setter 는 그대로 유지** (client 계약의 주 경로는 Spec apply).
- 영속화(저장/로드)는 **client 책임**. 모듈은 런타임 싱글턴 메모리만 소유하고 **`toSpec()` / `apply(spec)` 직렬화 왕복**까지만 제공. 로컬 persist API 는 두지 않음(경로·스키마·멀티프로파일·권한 충돌 및 이솝 저장소와 이중 진실 방지).

---

## 파라미터 표 (도메인별)

### ① 비전 (VisionSpec)

| 파라미터명 | 타입 | 범위 / 기본값 | 의미 | 현재 반영 | apx-cursor 의견 / 합의 |
|---|---|---|---|---|---|
| `useReferenceImage` | boolean | 기본 `false` | 기준 이미지 사용 ON/OFF. OFF 여도 ROI 지정이 디폴트 | ✅ 반영 | 유지 |
| `visionRefPath` | String | 기본 `null` | 비전 기준 이미지 경로 | ✅ 반영 | 유지 |
| `roiNorm` | double[4] `{ny1,ny2,nx1,nx2}` | 각 성분 ∈ `[0,1]`, 의미상 `ny1<ny2`,`nx1<nx2` (픽셀 변환 시 `y2≥y1+1`,`x2≥x1+1` 보정) / 기본 `{0.40625, 0.59375, 0.40625, 0.59375}` (중앙 ~18.75% 박스, 구 640 기준 120×120) | 정규화 ROI(해상도 무관) | ✅ 반영 | 유지 (해상도 무관 정규화 좌표가 정답) |
| `simThr` | double | `[0.05, 0.99]` clamp01 / 기본 `0.70` (`RoiMatchDetector.DEFAULT_SIM`) | 비전 NCC 유사도 임계 | ✅ 반영 | 유지 |

### ② 음향 (AudioSpec)

| 파라미터명 | 타입 | 범위 / 기본값 | 의미 | 현재 반영 | apx-cursor 의견 / 합의 |
|---|---|---|---|---|---|
| `micName` | String | 기본 `null` | 선택 마이크 표시명(**name 을 계약 키로**) | ✅ 반영 | name 통일 확정 |
| `expectedWavPath` | String | 기본 `null` | 기대 경고음 WAV 경로 | ✅ 반영 | 유지 |
| `audioFreqThr` | double | `[0.05, 0.99]` / 기본 `0.90` | 음향 주파수 일치 임계 | ✅ 반영 | 유지 |
| `audioWaveThr` | double | `[0.05, 0.99]` / 기본 `0.90` | 음향 파형 일치 임계 | ✅ 반영 | 유지 |

### ③ 장치 식별

| 파라미터명 | 타입 | 범위 / 기본값 | 의미 | 현재 반영 | apx-cursor 의견 / 합의 |
|---|---|---|---|---|---|
| `cameraName` | String | 기본 `null` | 카메라 **name 을 계약 키로 통일**. index 는 열거/오픈 시 내부 해석용만 | ❌ 미반영 (현재 카메라=`CameraService` **index** 기반) | **name 통일 확정.** index 는 USB 재꽂기·허브·부팅 순서로 깨져 재연결 안정성 최악 |
| `cameraIndexHint` | int (optional) | — | 동종 2대 name 충돌 시 disambiguate 힌트 | ❌ 미반영 | **비권장·보류** (충돌 케이스 확인 전까지 계약 미포함) |

> 마이크(name)·카메라(name)로 **식별 키 정책 통일**. OS급 uid(경로·인스턴스 ID)는 노출·매핑 비용이 커서 현 스택(sarxos / JavaSound)에서는 계약에 넣지 않음.

### ④ 캡처·타이밍

| 파라미터명 | 타입 | 범위 / 기본값 | 의미 | 현재 반영 | apx-cursor 의견 / 합의 |
|---|---|---|---|---|---|
| `targetFps` | — | — | 목표 캡처 fps | ❌ **계약 제외** | client 주입 금지. 실확보 fps 는 장치·드라이버·USB 대역 종속 → "요청≠실측". **자동감지(capability pick) 유지** |
| `captureResolution` | — | — | 캡처 해상도 | ❌ **계약 제외** | 동상. 엔진이 장치 열거 후 지원 모드 중 최선 선택 |
| `preferredWidth` | int (optional) | 생략 시 엔진 pick ≈ 640 | 캡처 폭 **clamp 힌트만** (hard 지정 아님) | ❌ 미반영 | 필요 시 optional 로만. hard `targetFps`/`captureResolution` 은 계약에 올리지 않음 |

> `POLL_MS=4` 는 캡처 fps 가 아니라 UI/엔진 폴링 상수 → **설정 API 필드 아님**. 실측 fps/해상도는 필요 시 **read-only 상태 보고**로 충분.

### ⑤ 스냅샷·증거 (EvidenceSpec, optional)

| 파라미터명 | 타입 | 범위 / 기본값 | 의미 | 현재 반영 | apx-cursor 의견 / 합의 |
|---|---|---|---|---|---|
| `evidenceBeforeFrames` | int (optional) | `≥0` / 기본 `3` | 판정 직전 보관 프레임 수 | ❌ 미반영 (`EvidenceCapture` 링버퍼 존재, Spec 계약엔 없음) | **꼭 넣는다면 이 필드만.** VisionSpec 하위 또는 별도 EvidenceSpec, `null=기본 3/3` |
| `evidenceAfterFrames` | int (optional) | `≥0` / 기본 `3` | 판정 이후 post 확정까지 프레임 수 | ❌ 미반영 | 동상 |
| `snapshotDir` | — | — | 스냅샷 저장 경로 | ❌ **계약 제외** | 파일 저장은 영속화/보고서 → **client 책임** |
| `intervalMs` | — | — | 스냅샷 캡처 간격(ms) | ❌ **계약 제외** | 현 `EvidenceCapture` 는 프레임 개수 링버퍼이지 **ms 간격 샘플러 아님** → 넣으면 구현과 계약 어긋남 |

> 모듈 역할: 판정 시점에 `Evidence(pre/decide/post + 시각)` 를 **`getEvidence()` 또는 이벤트로 제공** → client 가 경로·파일명·PNG 저장. `setSnapshotDir`/`saveVerdictSnapshot` 류는 위젯 편의 API(후방 격자 등)로는 남겨도 되나 **Spec 주입 필드는 아님**.

### ⑥ 주입·영속화 (구조 계약)

| 항목 | 결정 | 비고 |
|---|---|---|
| 주입 방식 | `ApxSettingsSpec`(불변) + `apply(spec)` / `applyQuiet(spec)` | `replaceQuiet(8~10 인자)` 대체 |
| 부분 갱신 | `VisionSpec` / `AudioSpec` 블록 + `visionOnly()`/`audioOnly()`/`full()` | UI 개별 setter 는 병행 유지 |
| 영속화 | **client 책임** (prefs/DB/프로젝트 파일) | 모듈은 런타임 싱글턴 메모리만 |
| 직렬화 왕복 | 모듈은 `toSpec()` / `apply(spec)` 까지만 | 로컬 persist API 없음 |

---

## 합의점 / 이견 요약

- **합의(4/4 쟁점 + 필드 확정)**: ①장치=**name 통일**(camera/mic), ②캡처 물리 파라미터(fps/해상도)=**엔진 자동감지·계약 제외**, ③주입=**불변 `ApxSettingsSpec` + apply/applyQuiet**, ④영속화=**client 책임·모듈은 toSpec 까지**, ⑤스냅샷 I/O=**계약 제외·모듈은 메모리 증거만 제공**.
- **계약 원칙 한 줄**: "설정 API = **판정 의미 파라미터**(장치 name, ROI, 임계, 경로)만 client 주입 / **캡처 물리·파일 I/O 는 엔진 자동 또는 client**."
- **의도적 제외**: `snapshotDir`·`intervalMs`·`targetFps`·`captureResolution`(I/O·물리), `cameraIndexHint`(보류).
- **남은 이견/후속 확인**: (1) `cameraName` 계약화는 **현재 CameraService index 기반 → name 기반 오픈 리팩터링** 필요(구현 갭). (2) `cameraIndexHint`·`preferredWidth`·`evidence*Frames` 는 **optional 보류** 상태로, 동종 2대 충돌·60fps 장비 실투입 시 재논의. (3) 실측 fps/해상도 **read-only 보고 API** 필요 여부는 추후 결정.

---

*대화 턴 수: **4턴** (한도 10턴). apx-cursor 는 각 턴마다 실제 코드(`ApxSettings.java`, `CameraService.java`, `EvidenceCapture.java`, `RoiMatchDetector`)를 확인 후 응답, 값·기본은 코드 대조 완료.*
