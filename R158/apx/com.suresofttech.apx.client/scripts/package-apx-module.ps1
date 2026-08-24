# (선택) 외부 전달용 JAR 아카이브. 로컬 RCP 실행에는 필요 없음.
$ErrorActionPreference = 'Stop'
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not (Test-Path (Join-Path $Root 'com.suresofttech.apx.core'))) {
    throw "R158/apx 경로를 찾지 못함: $Root"
}
$Core = Join-Path $Root 'com.suresofttech.apx.core'
$Ui   = Join-Path $Root 'com.suresofttech.apx.ui'
$Dist = Join-Path $Root 'dist\apx-module'

function Require-Dir($p, $hint) {
    if (-not (Test-Path $p)) { throw "없음: $p - $hint" }
}
Require-Dir (Join-Path $Core 'bin') 'core 빌드'
Require-Dir (Join-Path $Ui 'bin') 'ui 빌드'

New-Item -ItemType Directory -Force -Path $Dist | Out-Null
$coreJar = Join-Path $Dist 'apx-core.jar'
Push-Location (Join-Path $Core 'bin'); jar cf $coreJar .; Pop-Location

$uiStage = Join-Path $env:TEMP 'apx-ui-stage'
Remove-Item -Recurse -Force $uiStage -ErrorAction SilentlyContinue
$pkg = Join-Path $uiStage 'com\suresofttech\apx\ui\widget'
New-Item -ItemType Directory -Force -Path $pkg | Out-Null
$w = Join-Path $Ui 'bin\com\suresofttech\apx\ui\widget'
Get-ChildItem $w -Filter 'SettingsPanel*.class' | Copy-Item -Destination $pkg -Force
Get-ChildItem $w -Filter 'CameraCanvas*.class' | Copy-Item -Destination $pkg -Force
$uiJar = Join-Path $Dist 'apx-ui-settings.jar'
Push-Location $uiStage; jar cf $uiJar .; Pop-Location

$libOut = Join-Path $Dist 'lib'
New-Item -ItemType Directory -Force -Path $libOut | Out-Null
Copy-Item (Join-Path $Core 'lib\*.jar') $libOut -Force
Write-Host "OK -> $Dist"
