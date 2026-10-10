@echo off
setlocal
cd /d "%~dp0"

set SDK=C:\Users\yorai\Documents\android_sdk
set BT=%SDK%\build-tools\34.0.0
set BT8=%SDK%\build-tools\33.0.2
set PLATFORM=%SDK%\platforms\android-34\android.jar
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.12
set PATH=%JAVA_HOME%\bin;%PATH%

if exist build rmdir /s /q build
mkdir build\res-compiled
mkdir build\gen
mkdir build\classes
mkdir build\dex

echo === Compiling resources (aapt2 compile) ===
"%BT%\aapt2.exe" compile --dir res -o build\res-compiled\res.zip
if errorlevel 1 goto :error

echo === Linking resources (aapt2 link) ===
"%BT%\aapt2.exe" link -o build\app-unsigned.apk ^
  -I "%PLATFORM%" ^
  --manifest AndroidManifest.xml ^
  --java build\gen ^
  build\res-compiled\res.zip
if errorlevel 1 goto :error

echo === Compiling Java sources ===
dir /s /b src\*.java > build\sources.txt
dir /s /b build\gen\*.java >> build\sources.txt
javac -encoding UTF-8 --release 11 -g:none -classpath "%PLATFORM%" -d build\classes @build\sources.txt
if errorlevel 1 goto :error

echo === Dexing ===
dir /s /b build\classes\*.class > build\classfiles.txt
java -Xmx1024M -cp "%BT8%\lib\d8.jar" com.android.tools.r8.D8 --output build\dex --lib "%PLATFORM%" @build\classfiles.txt
if errorlevel 1 goto :error

echo === Assembling APK ===
copy build\app-unsigned.apk build\app-with-dex.apk >nul
jar -uf build\app-with-dex.apk -C build\dex classes.dex
if errorlevel 1 goto :error

echo === Zipaligning ===
"%BT%\zipalign.exe" -f -p 4 build\app-with-dex.apk build\app-aligned.apk
if errorlevel 1 goto :error

echo === Signing ===
call "%BT%\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out fizikal-auto.apk build\app-aligned.apk
if errorlevel 1 goto :error

echo.
echo BUILD OK: fizikal-auto.apk
goto :eof

:error
echo.
echo BUILD FAILED
exit /b 1
