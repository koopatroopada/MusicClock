@echo off
rem Rebuild the APK after editing ../index.html
setlocal
cd /d "%~dp0"

echo [1/3] Syncing index.html into app assets...
copy /Y "..\index.html" "app\src\main\assets\www\index.html" >nul

if not defined JAVA_HOME (
  if exist "D:\2Enviroment\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=D:\2Enviroment\Android Studio\jbr"
)

echo [2/3] Building debug APK...
call gradlew.bat assembleDebug
if errorlevel 1 goto fail

echo [3/3] Copying APK to project root...
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "..\番茄专注-1.1.6.apk" >nul

echo.
echo Done: 番茄专注-1.1.6.apk
goto :eof

:fail
echo.
echo Build failed. See the messages above.
exit /b 1
