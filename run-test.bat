@echo off
setlocal EnableExtensions
rem HBNS loading-screen quick test launcher (dev client, no Gradle overhead)
rem   run-test.bat                 fast launch
rem   run-test.bat install         copy newest hbns jars into run\mods, then launch
rem   run-test.bat build           gradlew build first, then launch
rem   run-test.bat build install   both
rem Requires one previous "gradlew runClient" to generate build\moddev\clientRun*.txt
pushd "%~dp0"
set "PROJ=%CD%"
popd
set "RUNDIR=%PROJ%\run"
set "MODDEV=%PROJ%\build\moddev"
set "DEVLAUNCH=C:\Users\HiBer2007\.gradle\caches\modules-2\files-2.1\net.neoforged\DevLaunch\1.0.2\2e0e9a67793d5bfdedee4f9ea00934dbf3f2e22d\DevLaunch-1.0.2.jar"
set "JAVA=C:\Program Files\Microsoft\jdk-21.0.5.11-hotspot\bin\java.exe"
if exist "%JAVA%" goto havejava
set "JAVA=java"
:havejava
set "DO_BUILD="
set "DO_INSTALL="
:parse
if "%~1"=="" goto checkfiles
if /i "%~1"=="build" set "DO_BUILD=1"
if /i "%~1"=="install" set "DO_INSTALL=1"
shift
goto parse
:checkfiles
if not exist "%MODDEV%\clientRunVmArgs.txt" goto needinit
if not exist "%MODDEV%\clientRunProgramArgs.txt" goto needinit
if not exist "%DEVLAUNCH%" goto needlaunch
if not defined DO_BUILD goto afterbuild
echo [1/2] gradlew build ...
pushd "%PROJ%"
call gradlew.bat build -x test --offline --no-daemon --console=plain
if errorlevel 1 goto buildfail
popd
:afterbuild
if not defined DO_INSTALL goto afterinstall
echo [2/2] copy fakeplayerlib jar to run\mods ...
del /q "%RUNDIR%\mods\fakeplayerlib-*.jar" "%RUNDIR%\mods\hibercarpet-*.jar" 2>nul
if exist "%PROJ%\build\libs\fakeplayerlib-*.jar" copy /y "%PROJ%\build\libs\fakeplayerlib-*.jar" "%RUNDIR%\mods\" >nul
dir /b "%RUNDIR%\mods\fakeplayerlib*.jar"
:afterinstall
echo Launching dev client from %RUNDIR% ...
cd /d "%RUNDIR%"
set "MODFOLDERS=fakeplayerlib%%%%%PROJ%\build\classes\java\main;fakeplayerlib%%%%%PROJ%\build\resources\main"
"%JAVA%" -Dfml.modFolders=%MODFOLDERS% "@%MODDEV%\clientRunVmArgs.txt" -cp "%DEVLAUNCH%;%PROJ%\build\moddev\artifacts\neoforge-21.1.266-merged.jar;%PROJ%\build\moddev\artifacts\neoforge-21.1.266-client-extra-aka-minecraft-resources.jar" net.neoforged.devlaunch.Main "@%MODDEV%\clientRunProgramArgs.txt"
set "RC=%ERRORLEVEL%"
echo.
echo Client exited, code %RC%
pause
exit /b %RC%
:buildfail
popd
echo [ERROR] build failed
pause
exit /b 1
:needinit
echo [ERROR] missing launch arg files:
echo   %MODDEV%\clientRunVmArgs.txt
echo   %MODDEV%\clientRunProgramArgs.txt
echo Run once first:  gradlew.bat runClient
pause
exit /b 1
:needlaunch
echo [ERROR] DevLaunch jar not found:
echo   %DEVLAUNCH%
pause
exit /b 1
