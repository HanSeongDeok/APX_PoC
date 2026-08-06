# 오케스트레이션 지시서 — settings 모듈 client 파라미터 도출

너(**apx-claude**)는 **오케스트레이터**다. 같은 tmux 서버에 있는 **`apx-cursor`** 세션(Cursor Agent)과 **최대 10턴 이내**로 대화하여, 결과를 도출·합의한 뒤 문서로 저장하라.

## 목표
`c:\DEV\apx\R158\apx` 의 **settings 모듈**이 **client(이솝, ESOP)** 로부터 **받아야 할 파라미터 목록**을 확정한다.
(판정/측정 제어는 client 책임. 우리 모듈은 시각화 + 엔진 + 설정 API 제공.)

## apx-cursor 와 대화하는 방법 (tmux)
너의 Bash 도구로 아래를 사용한다.
- 질문 보내기: `tmux send-keys -t apx-cursor -- "<질문 한 줄>" ; sleep 1 ; tmux send-keys -t apx-cursor Enter`
- 응답 읽기: 몇 초 간격으로 `tmux capture-pane -pt apx-cursor | tail -n 40` 를 폴링하여 출력이 안정되면(더 이상 변하지 않으면) 그 응답을 한 턴으로 간주.
- 한 번에 질문 하나씩. 왕복 1회 = 1턴. **총 10턴 초과 금지.**
- 응답이 30초 넘게 안 오면 한 번 더 capture 후 다음 진행.

## 근거 사실 (현재 코드)
`ApxSettings`(싱글턴) 현재 필드:
- 비전: `useReferenceImage`(기본 false), `visionRefPath`, `roiNorm`(정규화 {ny1,ny2,nx1,nx2}, 해상도 무관), `simThr`(NCC 임계)
- 음향: `micName`, `expectedWavPath`, `audioFreqThr`, `audioWaveThr`
- 일괄 주입: `replaceQuiet(...)` (인자 8~10개)
조립 구조: `SettingsClientView`가 통짜 패널이 아니라 **단위 컴포넌트를 직접 조합**(CameraSelectBar, CameraCanvas, RoiNcc, VisionThresholdBar / MicSelectBar, AudioMeasureBar, AudioScope, AudioThresholdBar 등). 값은 `ApxSettings.get()`에 저장.

## apx-cursor와 반드시 합의할 쟁점
1. **장치 식별** — 카메라/마이크를 name vs index vs uid 중 무엇으로 받을지 (재연결 안정성). 현재 마이크=name, 카메라=index.
2. **fps/해상도 파라미터화** — 60fps 장비로 동적 교체 대비. `targetFps`/`captureResolution`을 client가 주입할지 자동감지할지. (현재 POLL_MS=4 상수, pickSize 640 타겟)
3. **주입 방식** — 개별 setter vs `replaceQuiet`. 인자가 많으니 **파라미터 객체(ApxSettingsSpec)** 로 묶을지.
4. **영속화 책임** — 저장/로드를 client가 하는지 모듈이 하는지 (현재 싱글턴 메모리).
5. **스냅샷/증거 파라미터** — snapshotDir, PASS 전후 스냅샷 장수·간격을 파라미터로 받을지.

## 산출물
1. 합의 결과를 `c:\DEV\apx\docs\SETTINGS_CLIENT_PARAMS.md` 로 저장.
   - 표 형식: 파라미터명 | 타입 | 범위/기본 | 의미 | 현재 반영 여부 | (apx-cursor 의견/합의 결과)
   - 도메인별 그룹: 비전 / 음향 / 캡처·타이밍 / 스냅샷·증거 / 주입·영속화
2. 마지막에 **합의점 / 이견 남은 것** 을 3~5줄로 요약.
3. 대화 턴 수(몇 턴 썼는지)도 기록.

지금 시작하라.
