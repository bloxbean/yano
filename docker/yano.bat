@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_NAME=%~nx0"
set "COMPOSE_FILE=%SCRIPT_DIR%compose\yano.yml"
set "DEVNET_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-devnet.yml"
set "MAINNET_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-mainnet.yml"
set "PREVIEW_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-preview.yml"
set "SANCHONET_COMPOSE_FILE=%SCRIPT_DIR%compose\yano-sanchonet.yml"
set "ENV_FILE=%SCRIPT_DIR%compose\.env"
set "COMPOSE_DIR=%SCRIPT_DIR%compose"
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
  call :ensure_container_ownership replace
  if errorlevel 1 exit /b !ERRORLEVEL!
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
call :prepare_chainstate preprod
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership start
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_mainnet
call :prepare_chainstate mainnet
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership start
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%MAINNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_preview
call :prepare_chainstate preview
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership start
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%PREVIEW_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_sanchonet
call :prepare_chainstate sanchonet
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership start
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%SANCHONET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_devnet
call :prepare_chainstate devnet
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership start
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:start_custom
call :prepare_custom_profile
if errorlevel 1 exit /b !ERRORLEVEL!
call :prepare_chainstate "%CUSTOM_PROFILE%"
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership start
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_preprod
call :prepare_chainstate preprod
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership replace
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_mainnet
call :prepare_chainstate mainnet
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership replace
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%MAINNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%MAINNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_preview
call :prepare_chainstate preview
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership replace
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%PREVIEW_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%PREVIEW_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_sanchonet
call :prepare_chainstate sanchonet
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership replace
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%SANCHONET_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%SANCHONET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_devnet
call :prepare_chainstate devnet
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership replace
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" down
if errorlevel 1 exit /b !ERRORLEVEL!
docker compose -f "%COMPOSE_FILE%" -f "%DEVNET_COMPOSE_FILE%" --env-file "%ENV_FILE%" up -d
exit /b !ERRORLEVEL!

:restart_custom
call :prepare_custom_profile
if errorlevel 1 exit /b !ERRORLEVEL!
call :prepare_chainstate "%CUSTOM_PROFILE%"
if errorlevel 1 exit /b !ERRORLEVEL!
call :ensure_container_ownership replace
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

:ensure_container_ownership
rem This launcher manages only the container created from this directory's Compose file.
rem "start" refuses any other container with the configured name. "replace" (stop and
rem restart) also removes this directory's container when it belongs to an older project
rem name, such as "compose" from bundles that did not name their Compose project.
set "EXPECTED_PROJECT="
set "OWNED_CONTAINER="
for /f "usebackq tokens=1,*" %%A in (`docker compose -f "%COMPOSE_FILE%" --env-file "%ENV_FILE%" config 2^>nul`) do (
  if "%%A"=="name:" if not defined EXPECTED_PROJECT set "EXPECTED_PROJECT=%%B"
  if "%%A"=="container_name:" if not defined OWNED_CONTAINER set "OWNED_CONTAINER=%%B"
)
if not defined EXPECTED_PROJECT exit /b 0
if not defined OWNED_CONTAINER exit /b 0
rem Compose acts on every container in the project, so a project shared with another
rem directory (the same COMPOSE_PROJECT_NAME) would let it stop or replace that container.
for /f "delims=" %%C in ('docker ps -aq --filter "label=com.docker.compose.project=!EXPECTED_PROJECT!" 2^>nul') do (
  call :check_project_member %%C
  if errorlevel 1 exit /b 1
)
set "EXISTING_PROJECT="
set "EXISTING_FILES="
for /f "delims=" %%P in ('docker container inspect --format "{{index .Config.Labels `com.docker.compose.project`}}" "!OWNED_CONTAINER!" 2^>nul') do set "EXISTING_PROJECT=%%P"
if not defined EXISTING_PROJECT exit /b 0
for /f "delims=" %%F in ('docker container inspect --format "{{index .Config.Labels `com.docker.compose.project.config_files`}}" "!OWNED_CONTAINER!" 2^>nul') do set "EXISTING_FILES=%%F"
set "OTHER_FILES=!EXISTING_FILES:%COMPOSE_FILE%=!"
if "!OTHER_FILES!"=="!EXISTING_FILES!" (
  echo Container !OWNED_CONTAINER! is not managed by this directory.
  echo Compose project: !EXISTING_PROJECT!; files: !EXISTING_FILES!
  echo Set a distinct INSTANCE_NAME in %ENV_FILE%, or stop that instance from its own directory.
  exit /b 1
)
if /i "!EXISTING_PROJECT!"=="!EXPECTED_PROJECT!" exit /b 0
if not "%~1"=="replace" (
  echo Container !OWNED_CONTAINER! was started from this directory as Compose project '!EXISTING_PROJECT!'.
  echo Run '%SCRIPT_NAME% stop' or '%SCRIPT_NAME% restart' to replace it with project '!EXPECTED_PROJECT!'.
  exit /b 1
)
echo Removing container !OWNED_CONTAINER! from Compose project '!EXISTING_PROJECT!'.
docker stop "!OWNED_CONTAINER!" >nul
if errorlevel 1 exit /b !ERRORLEVEL!
docker rm "!OWNED_CONTAINER!" >nul
exit /b !ERRORLEVEL!

:check_project_member
set "MEMBER_FILES="
for /f "delims=" %%F in ('docker container inspect --format "{{index .Config.Labels `com.docker.compose.project.config_files`}}" "%~1" 2^>nul') do set "MEMBER_FILES=%%F"
set "OTHER_FILES=!MEMBER_FILES:%COMPOSE_FILE%=!"
if not "!OTHER_FILES!"=="!MEMBER_FILES!" exit /b 0
set "MEMBER_NAME="
for /f "delims=" %%N in ('docker container inspect --format "{{.Name}}" "%~1" 2^>nul') do set "MEMBER_NAME=%%N"
echo Compose project '!EXPECTED_PROJECT!' already has container !MEMBER_NAME:~1! from another directory.
echo Files: !MEMBER_FILES!
set "SHARED_PROJECT_NAME=%COMPOSE_PROJECT_NAME%"
if not defined SHARED_PROJECT_NAME call :read_env_value COMPOSE_PROJECT_NAME SHARED_PROJECT_NAME
if defined SHARED_PROJECT_NAME (
  echo Give each directory its own project: set a distinct COMPOSE_PROJECT_NAME in %ENV_FILE%,
  echo or remove COMPOSE_PROJECT_NAME so the project follows INSTANCE_NAME.
) else (
  echo Give each directory its own project: set a distinct INSTANCE_NAME in %ENV_FILE%.
)
exit /b 1
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
if "%YANO_RUNTIME_DATA_PATH%"=="" call :read_env_value YANO_RUNTIME_DATA_PATH YANO_RUNTIME_DATA_PATH
if "%YANO_RUNTIME_DATA_PATH%"=="" set "YANO_RUNTIME_DATA_PATH=../runtime-data-%CUSTOM_PROFILE%"
exit /b 0

:prepare_chainstate
set "CHAINSTATE_PROFILE=%~1"
if "%YANO_RUNTIME_DATA_PATH%"=="" call :read_env_value YANO_RUNTIME_DATA_PATH YANO_RUNTIME_DATA_PATH
if "%YANO_RUNTIME_DATA_PATH%"=="" set "YANO_RUNTIME_DATA_PATH=../runtime-data-%CHAINSTATE_PROFILE%"
rem Create the writable parent before Docker can create it as root on the host.
set "SAVED_CHAINSTATE_PATH=%YANO_CHAINSTATE_PATH%"
set "YANO_CHAINSTATE_PATH=%YANO_RUNTIME_DATA_PATH%"
call :host_chainstate_path
call :ensure_chainstate_dir
set "YANO_CHAINSTATE_PATH=%SAVED_CHAINSTATE_PATH%"
if errorlevel 1 exit /b !ERRORLEVEL!
if "%YANO_CHAINSTATE_PATH%"=="" call :read_env_value YANO_CHAINSTATE_PATH YANO_CHAINSTATE_PATH
if "%YANO_CHAINSTATE_PATH%"=="" set "YANO_CHAINSTATE_PATH=../chainstate-%CHAINSTATE_PROFILE%"
call :host_chainstate_path
call :ensure_chainstate_dir
exit /b !ERRORLEVEL!

:host_chainstate_path
set "HOST_CHAINSTATE_PATH=%YANO_CHAINSTATE_PATH%"
if "%HOST_CHAINSTATE_PATH:~1,2%"==":\" exit /b 0
if "%HOST_CHAINSTATE_PATH:~1,2%"==":/" exit /b 0
if "%HOST_CHAINSTATE_PATH:~0,2%"=="\\" exit /b 0
if "%HOST_CHAINSTATE_PATH:~0,1%"=="\" exit /b 0
if "%HOST_CHAINSTATE_PATH:~0,1%"=="/" exit /b 0
set "HOST_CHAINSTATE_PATH=%COMPOSE_DIR%\%HOST_CHAINSTATE_PATH%"
exit /b 0

:ensure_chainstate_dir
if exist "%HOST_CHAINSTATE_PATH%\" exit /b 0
if exist "%HOST_CHAINSTATE_PATH%" (
  echo Chainstate path exists but is not a directory: %HOST_CHAINSTATE_PATH%
  exit /b 1
)
mkdir "%HOST_CHAINSTATE_PATH%"
exit /b !ERRORLEVEL!

:read_env_value
for /f "usebackq tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
  if "%%A"=="%~1" if not "%%B"=="" set "%~2=%%B"
)
exit /b 0

:usage
echo Usage: %~nx0 [start^|start:^<profile^>^|stop^|restart^|restart:^<profile^>^|logs^|logs:yano^|status^|config^|config:^<profile^>^|pull]
exit /b 1
