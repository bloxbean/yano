@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "COMPOSE_FILE=%SCRIPT_DIR%compose\yano.yml"
set "DEVNET_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-devnet.yml"
set "ENV_FILE=%SCRIPT_DIR%compose\.env"
set "ACTION=%~1"

if "%ACTION%"=="" goto usage

where docker >nul 2>nul
if errorlevel 1 (
  echo Docker is not installed or not found in PATH.
  exit /b 1
)

docker compose version >nul 2>nul
if errorlevel 1 (
  echo docker compose is not available. Install Docker with the Compose plugin.
  exit /b 1
)

if not exist "%ENV_FILE%" (
  echo Compose env file not found: %ENV_FILE%
  exit /b 1
)

if "%ACTION%"=="start" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="start:devnet" (
  docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="stop" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" down
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="restart" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" down
  if errorlevel 1 exit /b !ERRORLEVEL!
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="restart:devnet" (
  docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
  if errorlevel 1 exit /b !ERRORLEVEL!
  docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="logs" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" logs -f yano
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="logs:yano" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" logs -f yano
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="status" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" ps
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="config" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" config
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="config:devnet" (
  docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" config
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="pull" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" pull
  exit /b !ERRORLEVEL!
)

echo Invalid action: %ACTION%

:usage
echo Usage: %~nx0 [start^|start:devnet^|stop^|restart^|restart:devnet^|logs^|logs:yano^|status^|config^|config:devnet^|pull]
exit /b 1
