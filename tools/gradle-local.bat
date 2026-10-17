@echo off
rem Runs the Gradle wrapper with Android Studio's bundled JDK. Usage: tools\gradle-local.bat :core:test -PwithAndroid=false
rem Output goes to %TEMP%\swath-build.txt as well as the screen.
set "ROOT=%~dp0.."
pushd "%ROOT%"
if exist "C:\Program Files\Android\Android Studio\jbr" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
rem If antivirus HTTPS scanning (e.g. AVG Web Shield) breaks Java downloads, build %USERPROFILE%\.gradle\avg-truststore.jks
rem (a copy of the JDK cacerts plus the AVG certificate) and Gradle will trust it. See README, "Building on this PC".
if exist "%USERPROFILE%\.gradle\avg-truststore.jks" set "JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=C:\Users\ROBERT~1\.gradle\avg-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
call "%ROOT%\gradlew.bat" %* > "%TEMP%\swath-build.txt" 2>&1
echo EXIT: %ERRORLEVEL%>> "%TEMP%\swath-build.txt"
type "%TEMP%\swath-build.txt"
