@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."

set "JAR_PATH=%ROOT_DIR%\server\target\server-1.0-SNAPSHOT-executable.jar"

if not exist "%JAR_PATH%" (
    echo [run-server] Executable jar not found at %JAR_PATH%.
    echo [run-server] Packaging server module using Maven wrapper...
    call "%ROOT_DIR%\mvnw.cmd" -f "%ROOT_DIR%\pom.xml" -pl server -am package -DskipTests
    if errorlevel 1 (
        echo [run-server] Build failed.
        exit /b 1
    )
)

echo [run-server] Launching LAN Backup Server...
java %JAVA_OPTS% -jar "%JAR_PATH%" %*
