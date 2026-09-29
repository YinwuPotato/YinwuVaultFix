@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =====================================================================
rem  YinwuVaultFix builder  (Canvas 26.3 / Folia fork, Java 25)
rem
rem  Compiles against the Canvas API jar that ships with the server:
rem     ..\Van\libraries\io\canvasmc\canvas\canvas-api\26.3.build.947-alpha\
rem         canvas-api-26.3.build.947-alpha.jar
rem  No NMS, no Maven, no internet needed.
rem =====================================================================

set "OUT_JAR=yinwu-vaultfix-1.0.0.jar"

rem ---------- 1. API jar (newest canvas-api-*.jar found) ----------
set "API_JAR="
for /f "delims=" %%F in ('dir /b /s /o-d "..\Van\libraries\io\canvasmc\canvas\canvas-api\canvas-api-*.jar" 2^>nul') do (
  if not defined API_JAR set "API_JAR=%%~fF"
)
if not defined API_JAR (
  for /f "delims=" %%F in ('dir /b /s /o-d "..\Van\libraries\canvas-api-*.jar" 2^>nul') do (
    if not defined API_JAR set "API_JAR=%%~fF"
  )
)
if not defined API_JAR (
  echo [ERR] canvas-api-*.jar not found under ..\Van\libraries\
  echo       Look for: Van\libraries\io\canvasmc\canvas\canvas-api\
  pause
  exit /b 1
)

rem ---------- 2. javac (JDK 25) ----------
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC (
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-25*") do (
    if not defined JAVAC if exist "%%~fD\bin\javac.exe" set "JAVAC=%%~fD\bin\javac.exe"
  )
)
if not defined JAVAC if exist "C:\Program Files\Java\jdk-25\bin\javac.exe" set "JAVAC=C:\Program Files\Java\jdk-25\bin\javac.exe"
if not defined JAVAC (
  where javac.exe >nul 2>nul && set "JAVAC=javac.exe"
)
if not defined JAVAC (
  echo [ERR] javac.exe not found. Install JDK 25, or set JAVA_HOME.
  pause
  exit /b 1
)

set "JAR=jar.exe"
for %%D in ("%JAVAC%") do if not "%%~dpD"=="" set "JAR=%%~dpDjar.exe"

echo API jar : %API_JAR%
echo javac   : %JAVAC%
"%JAVAC%" -version
echo.

rem ---------- 3. classpath = API jar + 服务器 libraries\ 里全部 jar ----------
rem  canvas-api 的类引用了 adventure / guava / annotations / bungee-chat 等，
rem  只给 API jar 会报"程序包 org.bukkit 不存在"，必须把 libraries 一起带上。
set "CP=%API_JAR%"
for /r "..\Van\libraries" %%F in (*.jar) do set "CP=!CP!;%%F"

rem ---------- 4. compile ----------
if exist out rmdir /s /q out
mkdir out
echo [1/2] compiling ...
"%JAVAC%" -encoding UTF-8 --release 21 -proc:none -cp "!CP!" -d out src\main\java\io\yinwu\vaultfix\VaultFixPlugin.java
if errorlevel 1 goto fail

rem ---------- 5. package ----------
echo [2/2] packaging ...
"%JAR%" --create --file "%OUT_JAR%" -C out . -C src\main\resources .
if errorlevel 1 goto fail

echo.
echo [OK] %CD%\%OUT_JAR%
echo      Copy it to ..\Van\plugins\ , then restart the Van server.
echo      (Van's start.bat already loops, so typing  stop  in its console
echo       is enough: it comes back in a few seconds with the plugin loaded.)
pause
exit /b 0

:fail
echo.
echo [ERR] build failed.
echo       If javac complains about class file version 69 (Java 25),
echo       your JDK is too old - use JDK 25 and pass --release 25 instead of 21.
pause
exit /b 1
