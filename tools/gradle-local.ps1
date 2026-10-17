# Runs Gradle using Android Studio's bundled JDK. Usage: .\tools\gradle-local.ps1 test
# Writes the full output to build-log.txt so it can be read back.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
$wrapper = Join-Path $root 'gradlew.bat'
if (Test-Path $wrapper) {
    $gradle = $wrapper
} else {
    $gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin" -Recurse -Filter gradle.bat | Select-Object -First 1 -ExpandProperty FullName
}
& $gradle @args 2>&1 | Tee-Object -FilePath (Join-Path $root 'build-log.txt')
"EXIT: $LASTEXITCODE" | Add-Content (Join-Path $root 'build-log.txt')
