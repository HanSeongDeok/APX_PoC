# APX Client (이솝 RCP)

설정 View와 측정 Kickoff / 모니터 View를 조립하는 샘플 클라이언트.

## View

| View | ID | 역할 |
|------|-----|------|
| Kickoff | `...view.kickoff` | 시작/중단, PASS 상태, 후방 자동 PASS, 증거 저장 |
| 음향 모니터 | `...view.audioMonitor` | `AudioScope` - Session 틱 |
| 비전 모니터 | `...view.visionMonitor` | `CameraCanvas` + read-only `RoiNcc` |
| 후방 모니터 | `...view.rearMonitor` | `RearGridCanvas` interactive off + 스냅샷 Select/범례 |
| 결과 | `...view.result` | 최근 1회 측정 시각 + 스냅샷(음향=PASS 밴드 종료, 비전=최초 PASS, 후방=overallPass) |
| 설정 | `...view.settings` / `settings2` | 최소 단위로 `ApxSettings` 편집 |

## 설정 vs 측정

- **설정**: Kickoff **설정** 버튼 → `SettingsDialog`(`SettingsForm`). 확인 시 모니터 View에 `ApxSettings` 반영.
- **측정**: Kickoff `start()` 시 스냅샷으로 고정. 모니터는 표시 / 증거용이며, 전체 PASS(음향∧비전) 규칙은 Kickoff가 `markRearPass()`로 적용한다.
- **음향 모니터**: 설정과 동일하게 파형만 (`AudioScope` trend/pitch off).
- **후방 모니터**: 로컬 범례 체크(`RearLegendBar` bindToSettings=false). 설정 범례와 무관.

## 증거 저장 규약

클라가 폴더만 넣으면(`setEvidenceDir` / 후방 / 음향의 `setSnapshotDir`와 동일 역할) 그 아래에 규약 파일이 생성된다.

```java
KickoffView kickoff = ...;
kickoff.setEvidenceDir(new File("C:/evidence/run1"));  // 미설정 시 ~/apx-evidence/<yyyyMMdd_HHmmss>/
```

중단(stop) 시 예시:

```
C:/evidence/run1/
  # 음향 (노션 MeasureReport / Evidence)
  full.wav                      # saveFull
  clip.wav                      # PASS 초록 밴드 시작~끝 (AudioScope passSpan)
  wave_center.png               # PASS 초록 밴드 종료 시점 스코프 스냅샷
  pass_times.txt

  # 비전 (EvidenceCapture ±3프레임, 파이썬 동일 이름)
  evidence_pre_-3f.png
  evidence_decide.png
  evidence_post_+3f.png

  # 후방 (RearGridCanvas 스냅샷 규약)
  P_c1_r2_PASS_4x6.png          # saveVerdictSnapshot (데모 tcId = P_c{col}_r{row})
  P_c3_r4_FAIL_4x6.png
  combined_P_c1_r2_P_c3_r4.png  # getCombinedSnapshot (Select 2개 이상)
```

| 채널 | 폴더 | 파일명 |
|------|------|--------|
| 음향 | 클라 `setEvidenceDir` | `full.wav`, `clip.wav`(초록 PASS 시작~끝), `wave_center.png`(밴드 종료 스냅) |
| 비전 | 동일 | `evidence_pre_-3f.png`, `evidence_decide.png`, `evidence_post_+3f.png` |
| 후방 | `RearGridCanvas.setSnapshotDir` | PASS/FAIL만 `<tcId>_c_r_VERDICT_WxH.png`, `combined_…png` (MEASURING 제외) |

이솝 연동 시 후방 데모 `P_c*_r*` 대신 실제 TC ID를 `saveVerdictSnapshot(r, tcId)`에 넘기면 됨.
