# 영상 레이어 셋업 (순수 자바: webcam-capture + BoofCV)

네이티브 OpenCV 대신 **순수 자바 스택**을 씁니다.
- **webcam-capture** (Sarxos) — 웹캠 열거/캡처 → `java.awt.image.BufferedImage`
- **BoofCV** — ORB 정렬·warp·matchTemplate 등 (기어/클러스터 엔진 단계에서 사용)

PDE(Eclipse 플러그인) 프로젝트라 Maven이 없으므로, **jar를 각 번들의 `lib/` 폴더에 넣고
`Bundle-ClassPath`에 등록**하는 방식으로 붙입니다. (target platform이 있으면 OSGi 번들로 넣어도 됨)

---

## 1단계: 웹캠 캡처/미리보기 (현재 적용됨)

`com.suresofttech.apx.core/lib/` 폴더를 만들고 아래 3개 jar를 넣으세요:

| jar | Maven 좌표 | 용도 |
|---|---|---|
| `webcam-capture-0.3.12.jar` | `com.github.sarxos:webcam-capture:0.3.12` | 웹캠 열거·캡처 |
| `bridj-0.7.0.jar` | `com.nativelibs4java:bridj:0.7.0` | webcam-capture 기본 드라이버(네이티브 그래버) |
| `slf4j-api-1.7.2.jar` | `org.slf4j:slf4j-api:1.7.2` | webcam-capture 로깅 API |

> 이미 `MANIFEST.MF`(Bundle-ClassPath)·`build.properties`(bin.includes: lib/)에 등록해 두었습니다.
> jar만 넣으면 `com.suresofttech.apx.core`가 컴파일됩니다. (jar가 없으면 CameraService에서 컴파일 에러)

받는 법(택1):
- Maven Central에서 위 좌표로 직접 다운로드
- `mvn dependency:copy-dependencies` 로 3개를 한 번에 받아 `lib/`로 복사

적용 후:
- ① 설정 탭에서 웹캠 선택 → 미리보기 표시
- 카메라는 공유 `CameraService`로 한 대만 열리고, 이후 기어/클러스터 View가 같은 프레임을 폴링

---

## 2단계(예정): BoofCV — 기어/클러스터 엔진

기어(gear.py)·클러스터(cluster.py) 이식 시 BoofCV로 ORB 정렬 + warpPerspective +
matchTemplate(NCC) + HSV/그레이 변환 + Otsu + 연결성분을 구현합니다.
BoofCV는 전이 의존성(georegression, ddogleg, ejml)이 있어, 그 단계에서 정확한 jar 목록을
확정해 여기에 추가하겠습니다. (SSIM은 직접 구현)

---

## 참고 — PDE에 jar 붙이는 규칙
1. `lib/*.jar` 에 jar 배치
2. `META-INF/MANIFEST.MF` 의 `Bundle-ClassPath:` 에 `lib/xxx.jar` 나열 (첫 항목은 `.`)
3. `build.properties` 의 `bin.includes` 에 `lib/` 포함 (export 시 jar 포함되게)
4. 다른 번들에서 쓰는 API는 `Export-Package` 로 노출 (여기선 `com.suresofttech.apx.core.vision`)
