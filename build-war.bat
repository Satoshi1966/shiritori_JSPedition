@echo off
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
javac -encoding UTF-8 --release 17 -cp "provided\servlet-api.jar;src\main\webapp\WEB-INF\lib\gson-2.13.2.jar" -d build\classes @sources.txt
if errorlevel 1 goto end
if exist build\war rmdir /s /q build\war
mkdir build\war
xcopy /e /i /q /y src\main\webapp build\war >nul
mkdir build\war\WEB-INF\classes
xcopy /e /i /q /y build\classes build\war\WEB-INF\classes >nul
jar --create --file GenreShiritoriWeb.war -C build\war .
:end
pause
