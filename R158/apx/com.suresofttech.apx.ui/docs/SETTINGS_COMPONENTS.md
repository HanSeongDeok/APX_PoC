# 설정 UI 단위 컴포넌트

이솝이 원하는 조각만 골라 배치한다. 공유 값은 `ApxSettings.get()` 싱글턴.
마이크 테스트 ↔ 측정 충돌은 `MicExclusive.bind`로 연결.

## 패키지 구조

```
com.suresofttech.apx.ui.widget
├── SettingsPanel                 ← 통짜: Vision + Audio 조립
├── CameraCanvas, AudioScope …    ← 공용 위젯
└── settings/
    ├── StatusLabel, StatusSink, SettingsUi
    ├── vision/
    │   ├── *Bar / WebcamRoiPane  ← 최소 단위
    │   └── VisionSettingsPanel   ← 비전 조립
    ├── audio/
    │   ├── *Bar / *Pane          ← 최소 단위
    │   └── AudioSettingsPanel    ← 음향 조립
    └── rear/
        └── RearSettingsPanel     ← 후방 조립 (예약)
```

## 계층

| 계층 | 클래스 | 용도 |
|------|--------|------|
| 통짜 | `SettingsPanel` | 비전+음향 2열 |
| 도메인 조립 | `VisionSettingsPanel` / `AudioSettingsPanel` / `RearSettingsPanel` | 영역 단위로 붙일 때 |
| 최소 단위 | `CameraSelectBar`, `MicSelectBar`, … | 이솝 커스텀 레이아웃 |

### vision 최소 단위

| 클래스 | 내용 |
|--------|------|
| `CameraSelectBar` | 웹캠 콤보 + 새로고침 |
| `WebcamRoiPane` | 프리뷰 + ROI 드래그 + NCC |
| `ReferenceImageBar` | 기준 이미지 사용/경로 |
| `VisionThresholdBar` | NCC 임계 ± + 매칭도 |

### audio 최소 단위

| 클래스 | 내용 |
|--------|------|
| `MicSelectBar` | 마이크 + 레벨 + 테스트 |
| `ExpectedWavBar` | 기대 wav 경로 |
| `ExpectedAudioMeasurePane` | AudioScope + 측정/초기화 |
| `ExpectedTonePlayBar` | 기대음 재생/정지 |
| `AudioThresholdBar` | 주파수·파형 임계 ± |
| `MicExclusive` | 테스트↔측정 장치 배타 |

## 사용 예

```java
// 1) 통짜
new SettingsPanel(parent);

// 2) 도메인만
new VisionSettingsPanel(parent);
new AudioSettingsPanel(parent);

// 3) 최소 단위 직접 조립
CameraSelectBar cam = new CameraSelectBar(g);
WebcamRoiPane roi = new WebcamRoiPane(g);
cam.setRoiPane(roi);
// …
MicSelectBar mic = new MicSelectBar(g);
ExpectedAudioMeasurePane measure = new ExpectedAudioMeasurePane(g);
new MicExclusive().bind(mic, measure);
```
