@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."

set "JAR_PATH=%ROOT_DIR%\agent\target\agent-1.0-SNAPSHOT-executable.jar"

if not exist "%JAR_PATH%" (
    echo [run-agent] Executable jar not found at %JAR_PATH%.
    echo [run-agent] Packaging agent module using Maven wrapper...
    call "%ROOT_DIR%\mvnw.cmd" -f "%ROOT_DIR%\pom.xml" -pl agent -am package -DskipTests
    if errorlevel 1 (
        echo [run-agent] Build failed.
        exit /b 1
    )
)

echo [run-agent] Launching LAN Backup Agent...
java %JAVA_OPTS% -jar "%JAR_PATH%" %*
