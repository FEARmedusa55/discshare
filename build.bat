@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set LOG=%~dp0build-log.txt
echo DiscShare build started %date% %time% > "%LOG%"

rem ---- find a JDK 25 ----
for /d %%J in ("%USERPROFILE%\.jdks\*25*") do if exist "%%J\bin\java.exe" set "JAVA_HOME=%%J"
for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-25*") do if exist "%%J\bin\java.exe" set "JAVA_HOME=%%J"
for /d %%J in ("C:\Program Files\Java\jdk-25*") do if exist "%%J\bin\java.exe" set "JAVA_HOME=%%J"
for /d %%J in ("C:\Program Files\Microsoft\jdk-25*") do if exist "%%J\bin\java.exe" set "JAVA_HOME=%%J"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JAVA_HOME=%JAVA_HOME% >> "%LOG%"
java -version >> "%LOG%" 2>&1

rem ---- find Gradle 9.5.1 (downloaded earlier by your IDE) or any gradle on PATH ----
set "GRADLE="
for /d %%D in ("%USERPROFILE%\.gradle\wrapper\dists\gradle-9.5.1-bin\*") do if exist "%%D\gradle-9.5.1\bin\gradle.bat" set "GRADLE=%%D\gradle-9.5.1\bin\gradle.bat"
if not defined GRADLE for /d %%D in ("%USERPROFILE%\.gradle\wrapper\dists\gradle-9*-bin\*") do for /d %%G in ("%%D\gradle-*") do if exist "%%G\bin\gradle.bat" set "GRADLE=%%G\bin\gradle.bat"
if not defined GRADLE (where gradle >nul 2>&1 && set "GRADLE=gradle")
echo GRADLE=%GRADLE% >> "%LOG%"
echo --- JDKs in .jdks: >> "%LOG%"
dir /b "%USERPROFILE%\.jdks" >> "%LOG%" 2>&1
echo --- Gradle dists: >> "%LOG%"
dir /b "%USERPROFILE%\.gradle\wrapper\dists" >> "%LOG%" 2>&1

if not defined GRADLE (
  echo No Gradle found. >> "%LOG%"
  echo No Gradle found - tell Claude.
  pause
  exit /b 1
)

echo Building... this can take a few minutes the first time.
call "%GRADLE%" wrapper --gradle-version 9.5.1 --no-daemon >> "%LOG%" 2>&1
call "%GRADLE%" build --no-daemon --stacktrace >> "%LOG%" 2>&1
set MODERR=%errorlevel%
call "%GRADLE%" dumpClasspath --no-daemon >> "%LOG%" 2>&1
rem Paper plugin build is off for now. To turn it back on, remove "rem " from the next two lines.
rem call "%GRADLE%" -p plugin build --no-daemon --stacktrace >> "%LOG%" 2>&1
set PLUGERR=0
if not "%MODERR%%PLUGERR%"=="00" (
  echo BUILD FAILED >> "%LOG%"
  echo Build failed. Tell Claude "done" and it will read build-log.txt.
) else (
  echo BUILD OK >> "%LOG%"
  echo Build worked! The mod is in build\libs
)
pause
