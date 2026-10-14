@echo off
setlocal
set "ROOT=%~dp0"
set "GRADLEHOME=%USERPROFILE%\.gradle\portable\gradle-9.4.1"
if not exist "%GRADLEHOME%\bin\gradle.bat" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $base=Join-Path $env:USERPROFILE '.gradle\portable'; New-Item -ItemType Directory -Force -Path $base | Out-Null; $zip=Join-Path $base 'gradle-9.4.1-bin.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-9.4.1-bin.zip' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath $base -Force"
  if errorlevel 1 exit /b 1
)
call "%GRADLEHOME%\bin\gradle.bat" -p "%ROOT%" %*
exit /b %ERRORLEVEL%
