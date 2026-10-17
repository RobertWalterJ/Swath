@echo off
title Swath demo
rem Builds the desktop tool, generates a synthetic sticky-note wall (shaky, drifting, with glare),
rem stitches it, finds the notes, and opens the result folder. No phone needed.
cd /d "%~dp0"
if exist "C:\Program Files\Android\Android Studio\jbr" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
rem If antivirus HTTPS scanning blocks downloads, tools\gradle-local.bat explains the fix (README).
if exist "%USERPROFILE%\.gradle\avg-truststore.jks" set "JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=C:\Users\ROBERT~1\.gradle\avg-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"

echo Building the demo tool (the first time takes a few minutes)...
call "%~dp0gradlew.bat" :replay-cli:installDist -PwithAndroid=false --no-daemon -q
if errorlevel 1 ( echo. & echo Build failed. See the messages above. & pause & exit /b 1 )

set "TOOL=replay-cli\build\install\swath-replay\bin\swath-replay.bat"
if exist "sessions\demo" rmdir /s /q "sessions\demo"
echo.
echo Making a synthetic 6 m wall...
call "%TOOL%" generate sessions\demo --width-mm 6000
echo.
echo Stitching it and finding the notes...
call "%TOOL%" replay sessions\demo --px-per-mm 1
echo.
echo Done. Opening sessions\demo:
echo   mosaic\full.png  the stitched wall
echo   notes.csv        one row per sticky note
echo   metrics.json     what the pipeline measured
start "" "sessions\demo\mosaic"
pause
