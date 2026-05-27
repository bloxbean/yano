@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "COMPOSE_FILE=%SCRIPT_DIR%compose\yano.yml"
set "DEVNET_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-devnet.yml"
set "MAINNET_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-mainnet.yml"
set "PREVIEW_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-preview.yml"
set "SANCHONET_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-sanchonet.yml"
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

if "%ACTION%"=="start" goto start_preprod
if "%ACTION%"=="start:preprod" goto start_preprod
if "%ACTION%"=="start:mainnet" goto start_mainnet
if "%ACTION%"=="start:preview" goto start_preview
if "%ACTION%"=="start:sanchonet" goto start_sanchonet
if "%ACTION%"=="start:devnet" goto start_devnet
if "%ACTION:~0,6%"=="start:" (
  set "CUSTOM_PROFILE=%ACTION:~6%"
  goto start_custom
)

if "%ACTION%"=="restart" goto restart_preprod
if "%ACTION%"=="restart:preprod" goto restart_preprod
if "%ACTION%"=="restart:mainnet" goto restart_mainnet
if "%ACTION%"=="restart:preview" goto restart_preview
if "%ACTION%"=="restart:sanchonet" goto restart_sanchonet
if "%ACTION%"=="restart:devnet" goto restart_devnet
if "%ACTION:~0,8%"=="restart:" (
  set "CUSTOM_PROFILE=%ACTION:~8%"
  goto restart_custom
)

if "%ACTION%"=="config" goto config_preprod
if "%ACTION%"=="config:preprod" goto config_preprod
if "%ACTION%"=="config:mainnet" goto config_mainnet
if "%ACTION%"=="config:preview" goto config_preview
if "%ACTION%"=="config:sanchonet" goto config_sanchonet
if "%ACTION%"=="config:devnet" goto config_devnet
if "%ACTION:~0,7%"=="config:" (
  set "CUSTOM_PROFILE=%ACTION:~7%"
  goto config_custom
)

if "%ACTION%"=="stop" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" down
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="logs" goto logs
if "%ACTION%"=="logs:yano" goto logs

if "%ACTION%"=="status" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" ps
  exit /b !ERRORLEVEL!
)

if "%ACTION%"=="pull" (
  docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" pull
  exit /b !ERRORLEVEL!
)

echo Invalid action: %ACTION%
goto usage

:start_preprod
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_mainnet
docker compose -f "%COMPOSE_FILE%" -f "%MAINNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_preview
docker compose -f "%COMPOSE_FILE%" -f "%PREVIEW_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_sanchonet
docker compose -f "%COMPOSE_FILE%" -f "%SANCHONET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_devnet
docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_custom
call :prepare_custom_profile
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_preprod
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_mainnet
docker compose -f "%COMPOSE_FILE%" -f "%MAINNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%MAINNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_preview
docker compose -f "%COMPOSE_FILE%" -f "%PREVIEW_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%PREVIEW_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_sanchonet
docker compose -f "%COMPOSE_FILE%" -f "%SANCHONET_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%SANCHONET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_devnet
docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_custom
call :prepare_custom_profile
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:logs
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" logs -f yano
exit /b !ERRORLEVEL!

:config_preprod
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" config
exit /b !ERRORLEVEL!

:config_mainnet
docker compose -f "%COMPOSE_FILE%" -f "%MAINNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" config
exit /b !ERRORLEVEL!

:config_preview
docker compose -f "%COMPOSE_FILE%" -f "%PREVIEW_COMPOSE_FILE%" --env-file "%ENV_FILE%" config
exit /b !ERRORLEVEL!

:config_sanchonet
docker compose -f "%COMPOSE_FILE%" -f "%SANCHONET_COMPOSE_FILE%" --env-file "%ENV_FILE%" config
exit /b !ERRORLEVEL!

:config_devnet
docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" config
exit /b !ERRORLEVEL!

:config_custom
call :prepare_custom_profile
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" config
exit /b !ERRORLEVEL!

:prepare_custom_profile
if "%CUSTOM_PROFILE%"=="" (
  echo Invalid profile name: %CUSTOM_PROFILE%
  exit /b 1
)
echo %CUSTOM_PROFILE%| findstr /R "[\\/]" >nul
if not errorlevel 1 (
  echo Invalid profile name: %CUSTOM_PROFILE%
  echo Use letters, numbers, dot, underscore, and dash only.
  exit /b 1
)
echo %CUSTOM_PROFILE%| findstr /R "\\.\\." >nul
if not errorlevel 1 (
  echo Invalid profile name: %CUSTOM_PROFILE%
  echo Use letters, numbers, dot, underscore, and dash only.
  exit /b 1
)
echo %CUSTOM_PROFILE%| findstr /R "[^A-Za-z0-9_.-]" >nul
if not errorlevel 1 (
  echo Invalid profile name: %CUSTOM_PROFILE%
  echo Use letters, numbers, dot, underscore, and dash only.
  exit /b 1
)

if "%YANO_CHAINSTATE_PATH%"=="" call :read_env_value YANO_CHAINSTATE_PATH YANO_CHAINSTATE_PATH
if "%YANO_CHAINSTATE_PATH%"=="" set "YANO_CHAINSTATE_PATH=../chainstate-%CUSTOM_PROFILE%"
set "YANO_PROFILE=%CUSTOM_PROFILE%"
set "YANO_NETWORK=%CUSTOM_PROFILE%"
exit /b 0

:read_env_value
for /f "usebackq tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
  if "%%A"=="%~1" if not "%%B"=="" set "%~2=%%B"
)
exit /b 0

:usage
echo Usage: %~nx0 [start^|start:^<profile^>^|stop^|restart^|restart:^<profile^>^|logs^|logs:yano^|status^|config^|config:^<profile^>^|pull]
exit /b 1
