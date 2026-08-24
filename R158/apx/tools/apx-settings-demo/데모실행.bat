@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM APX Settings component demo launcher (ASCII-only for cmd.exe)

set "ROOT=%~dp0..\.."
for %%I in ("%ROOT%") do set "ROOT=%%~fI"
set "UI=%ROOT%\com.suresofttech.apx.ui"
set "CORE=%ROOT%\com.suresofttech.apx.core"
set "CLIENT=%ROOT%\com.suresofttech.apx.client"

REM SWT is win32 x86_64 - 32-bit JRE/JDK (Program Files (x86), JAVA_HOME) will not run.
set "JAVA_EXE="
set "JAVAC_EXE="
if defined APX_JAVA_HOME call :acceptJdk "%APX_JAVA_HOME%"
if not defined JAVA_EXE for /d %%D in ("C:\Program Files\Java\jdk1.8*") do call :acceptJdk "%%~fD"
if not defined JAVA_EXE for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-8*") do call :acceptJdk "%%~fD"
if not defined JAVA_EXE for /d %%D in ("C:\Program Files\Microsoft\jdk-1.8*") do call :acceptJdk "%%~fD"
if not defined JAVA_EXE for /d %%D in ("C:\Program Files\Amazon Corretto\jdk1.8*") do call :acceptJdk "%%~fD"
if not defined JAVA_EXE for /d %%D in ("C:\Program Files\Zulu\zulu-8*") do call :acceptJdk "%%~fD"
if not defined JAVA_EXE if defined JAVA_HOME call :acceptJdk "%JAVA_HOME%"
if not defined JAVA_EXE for /f "delims=" %%P in ('where javac 2^>nul') do (
  for %%I in ("%%~dpP..") do call :acceptJdk "%%~fI"
)
if not defined JAVA_EXE (
  echo [ERROR] 64-bit JDK 8 not found. Install a 64-bit JDK 1.8 ^(not Program Files x86^)
  echo or set APX_JAVA_HOME to that JDK folder.
  pause
  exit /b 1
)
echo Using JDK: !JAVA_EXE!

if not exist "%UI%\src\com\suresofttech\apx\ui\widget\settings" (
  echo [ERROR] Missing UI settings sources:
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings
  pause
  exit /b 1
)
if not exist "%CORE%\bin\com\suresofttech\apx\core" (
  echo [ERROR] Missing Core bin. Build com.suresofttech.apx.core in Eclipse first.
  pause
  exit /b 1
)
if not exist "lib\org.eclipse.swt.win32.win32.x86_64.jar" (
  echo [ERROR] Missing lib\org.eclipse.swt.win32.win32.x86_64.jar
  pause
  exit /b 1
)

set "CP=%UI%\bin"
set "CP=!CP!;%CORE%\bin"
set "CP=!CP!;%CLIENT%\bin"
set "CP=!CP!;lib\org.eclipse.swt.win32.win32.x86_64.jar"
set "CP=!CP!;%UI%\lib\ChartDirector_s.jar"
set "CP=!CP!;%CORE%\lib\webcam-capture-0.3.12.jar"
set "CP=!CP!;%CORE%\lib\bridj-0.7.0.jar"
set "CP=!CP!;%CORE%\lib\slf4j-api-1.7.2.jar"
set "CP=!CP!;%CORE%\lib\opencv-4.9.0-0.jar"
set "CP=!CP!;%CORE%\lib\JTransforms-3.1.jar"
set "CP=!CP!;%CORE%\lib\JLargeArrays-1.6.jar"
set "CP=!CP!;%CORE%\lib\commons-math3-3.6.1.jar"

if not exist "%UI%\bin" mkdir "%UI%\bin"
if not exist "%CLIENT%\bin" mkdir "%CLIENT%\bin"
if not exist bin mkdir bin

echo [1/4] Compiling UI settings widgets into UI\bin ...
del /q "%UI%\bin\com\suresofttech\apx\ui\widget\settings\audio\MicLevelBar*.class" 2>nul
del /q "%UI%\bin\com\suresofttech\apx\ui\widget\settings\audio\MicExclusive*.class" 2>nul
del /q "%UI%\bin\com\suresofttech\apx\ui\widget\settings\audio\MicDevices*.class" 2>nul
del /q "%UI%\bin\com\suresofttech\apx\ui\widget\settings\audio\MicDeviceProvider*.class" 2>nul
del /q "%UI%\bin\com\suresofttech\apx\ui\widget\AudioScope*.class" 2>nul
del /q "%UI%\bin\com\suresofttech\apx\ui\widget\RearGridCanvas*.class" 2>nul
del /q "%UI%\bin\com\suresofttech\apx\ui\widget\settings\rear\RearSettingsPanel*.class" 2>nul

"%JAVAC_EXE%" -encoding UTF-8 -source 1.8 -target 1.8 -cp "!CP!" -d "%CORE%\bin" ^
  "%CORE%\src\com\suresofttech\apx\core\audio\AudioCapture.java" ^
  "%CORE%\src\com\suresofttech\apx\core\config\ApxSettings.java" ^
  "%CORE%\src\com\suresofttech\apx\core\rear\RearGrid.java" ^
  "%CORE%\src\com\suresofttech\apx\core\vision\VisionChannel.java" ^
  "%CORE%\src\com\suresofttech\apx\core\vision\CameraService.java" ^
  "%CORE%\src\com\suresofttech\apx\core\vision\VisionJudges.java"
if errorlevel 1 (
  echo [ERROR] Core compile failed: AudioCapture ApxSettings RearGrid VisionChannel CameraService VisionJudges
  pause
  exit /b 1
)

REM Delayed expansion so a folder name like "apx_demo (1)" does not close this block.
set "SRCLIST=%TEMP%\apx-settings-demo-ui.sources"
> "%SRCLIST%" (
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\vision\CameraCanvas.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\audio\AudioScope.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\vision\CameraSelectBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\vision\RoiNcc.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\vision\ReferenceImageBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\vision\VisionThresholdBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\vision\VisionJudgeBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\audio\MicSelectBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\audio\MicTestBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\audio\ExpectedWavBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\audio\AudioMeasureBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\audio\ExpectedTonePlayBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\audio\AudioThresholdBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\rear\RearGridCanvas.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\rear\RearGridSizeBar.java
  echo !UI!\src\com\suresofttech\apx\ui\widget\settings\rear\RearLegendBar.java
)

"%JAVAC_EXE%" -encoding UTF-8 -source 1.8 -target 1.8 -cp "!CP!" -d "%UI%\bin" @"%SRCLIST%"
if errorlevel 1 (
  echo [ERROR] UI settings compile failed
  pause
  exit /b 1
)

echo [2/4] Compiling client SettingsForm into CLIENT\bin ...
"%JAVAC_EXE%" -encoding UTF-8 -source 1.8 -target 1.8 -cp "!CP!" -d "%CLIENT%\bin" ^
  "%CLIENT%\src\com\suresofttech\apx\client\view\SettingsForm.java"
if errorlevel 1 (
  echo [ERROR] Client SettingsForm compile failed
  pause
  exit /b 1
)

echo [3/4] Compiling demo ...
set "CP=bin;!CP!"
"%JAVAC_EXE%" -encoding UTF-8 -source 1.8 -target 1.8 -cp "!CP!" -d bin src\ApxSettingsComponentDemo.java
if errorlevel 1 (
  echo [ERROR] Demo compile failed
  pause
  exit /b 1
)

echo [4/4] Starting APX Settings Component Demo...
"%JAVA_EXE%" -cp "!CP!" ApxSettingsComponentDemo
set ERR=!ERRORLEVEL!
if not "!ERR!"=="0" (
  echo.
  echo [ERROR] exit code !ERR!
  pause
)
endlocal
goto :eof

:acceptJdk
if defined JAVA_EXE goto :eof
if not exist "%~1\bin\javac.exe" goto :eof
if not exist "%~1\bin\java.exe" goto :eof
echo "%~1" | find /i "Program Files (x86)" >nul && goto :eof
"%~1\bin\java.exe" -version 2>&1 | find /i "64-Bit" >nul || goto :eof
set "JAVA_EXE=%~1\bin\java.exe"
set "JAVAC_EXE=%~1\bin\javac.exe"
goto :eof
