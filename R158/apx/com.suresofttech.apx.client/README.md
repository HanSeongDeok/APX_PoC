# APX 이솝 RCP 클라이언트 (`com.suresofttech.apx.client`)

Eclipse에 **Import** 한 뒤 **Run As → Eclipse Application** 으로 설정 모듈을 띄웁니다.

## Import · 실행

1. **File → Import → Existing Projects into Workspace**
2. Root: `R158/apx` (또는 아래 세 폴더)
   - `com.suresofttech.apx.core`
   - `com.suresofttech.apx.ui`
   - `com.suresofttech.apx.client`
3. **세 프로젝트 모두** 워크스페이스에 열어 둘 것
4. `com.suresofttech.apx.client` 선택  
   → **Run As → Eclipse Application**  
   (또는 `apx.product` 열어 Launch)
5. Perspective **이솝 (설정)** → View **설정 (APX 모듈)**

Target Platform에 Eclipse SDK/RCP가 있어야 `org.eclipse.ui`가 해석됩니다.

## 의존 (개발 시)

```
Require-Bundle:
  org.eclipse.ui
  org.eclipse.core.runtime
  org.eclipse.equinox.app
  com.suresofttech.apx.core   ← workspace 플러그인
  com.suresofttech.apx.ui     ← settings.vision / settings.audio 단위 바
```

설정 UI는 `SettingsClientView`가 vision/audio **최소 단위를 직접 조립**한다  
(상세·다이어그램: `com.suresofttech.apx.ui/docs/SETTINGS_COMPONENTS.md`).

## 외부에 JAR만 넘길 때 (선택)

제품 팀에 플러그인 전체가 아니라 아카이브만 줄 경우:

```powershell
.\com.suresofttech.apx.client\scripts\package-apx-module.ps1
```

→ `dist/apx-module/` 에 `apx-core.jar`, `apx-ui-settings.jar`, `lib/*` 생성.  
이솝 RCP에 넣을 때는 그 JAR를 받는 쪽 플러그인 `lib/` + `Bundle-ClassPath`로 붙이면 됩니다.
