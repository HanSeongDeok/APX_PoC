@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ---------------------------------------------------------------
REM Eclipse workspace repair for the APX plug-ins.
REM
REM Symptom this fixes:
REM   "XML format error in '.classpath' ... Bad format"
REM   "'src/' is not a source folder."  "'bin/' is not an output folder."
REM   -> no build output -> ClassNotFoundException ClientApplication
REM
REM Cause: the .classpath files on disk are valid, but Eclipse still
REM holds the invalid verdict it cached when those files were briefly
REM corrupted. F5 does not clear it (mtime already known) and Clean only
REM wipes bin. Deleting the cached build state forces a full re-read.
REM
REM Nothing under the project source tree is touched. Only regenerable
REM Eclipse caches are removed, and a backup is taken first.
REM ---------------------------------------------------------------

set "ECLIPSE_HOME=C:\DEV\apx-java"
set "WS=%ECLIPSE_HOME%\workspace"
set "MD=%WS%\.metadata\.plugins"
set "RES=%MD%\org.eclipse.core.resources\.projects"

if not exist "%MD%" (
  echo [ERROR] Workspace not found: %WS%
  pause
  exit /b 1
)

tasklist /fi "imagename eq eclipse.exe" 2>nul | find /i "eclipse.exe" >nul
if not errorlevel 1 (
  echo [ERROR] Eclipse is still running. Close it completely, then run this again.
  echo         Deleting cache while Eclipse runs would be overwritten on exit.
  pause
  exit /b 1
)

for /f "tokens=1-6 delims=/: " %%a in ("%date% %time%") do set "STAMP=%%a%%b%%c-%%d%%e"
set "STAMP=!STAMP: =0!"
set "BAK=%ECLIPSE_HOME%\workspace-backup-!STAMP!"

echo.
echo Backing up Eclipse cache to:
echo   !BAK!
mkdir "!BAK!" 2>nul
xcopy "%MD%\org.eclipse.jdt.core"     "!BAK!\org.eclipse.jdt.core\"     /E /I /Q /Y >nul 2>&1
xcopy "%MD%\org.eclipse.pde.core"     "!BAK!\org.eclipse.pde.core\"     /E /I /Q /Y >nul 2>&1
xcopy "%RES%"                         "!BAK!\.projects\"                /E /I /Q /Y >nul 2>&1
echo   done.

echo.
echo [1/3] Removing stale problem markers ...
for %%P in (com.suresofttech.apx.core com.suresofttech.apx.ui com.suresofttech.apx.client) do (
  if exist "%RES%\%%P\.markers"      del /q "%RES%\%%P\.markers"      && echo    - %%P\.markers
  if exist "%RES%\%%P\.markers.snap" del /q "%RES%\%%P\.markers.snap" && echo    - %%P\.markers.snap
)
if exist "%RES%\External Plug-in Libraries\.markers"      del /q "%RES%\External Plug-in Libraries\.markers"
if exist "%RES%\External Plug-in Libraries\.markers.snap" del /q "%RES%\External Plug-in Libraries\.markers.snap"

echo [2/3] Removing cached JDT build state and classpath containers ...
REM variablesAndContainers.dat is where the resolved (and here, poisoned)
REM classpath lives. Eclipse rebuilds it from .classpath on next start.
if exist "%MD%\org.eclipse.jdt.core" rmdir /s /q "%MD%\org.eclipse.jdt.core"
echo    - org.eclipse.jdt.core

echo [3/3] Removing PDE model cache ...
if exist "%MD%\org.eclipse.pde.core\.cache" rmdir /s /q "%MD%\org.eclipse.pde.core\.cache"
echo    - org.eclipse.pde.core\.cache

echo.
echo Cache cleared. Starting Eclipse with -clean -refresh ...
echo   -clean   flushes the OSGi bundle / extension registry cache
echo   -refresh re-reads every project from disk on startup
echo.
start "" "%ECLIPSE_HOME%\eclipse.exe" -clean -refresh -data "%WS%"

echo.
echo After Eclipse opens:
echo   1. Wait for the background build to finish (progress bar, bottom right).
echo   2. Problems view: the .classpath errors should be gone.
echo   3. Project -^> Clean... -^> Clean all -^> Build.
echo   4. Run the apx_client.product launch configuration.
echo.
echo If anything went wrong, restore from:
echo   !BAK!
echo.
pause
endlocal
