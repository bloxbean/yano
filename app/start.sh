#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ "$1" = "--native" ]; then
  shift
  BINARY="build/yano"
  [ ! -f "$BINARY" ] && echo "Native binary not found. Build: ../gradlew :app:build -Dquarkus.native.enabled=true" && exit 1
  exec "$BINARY" "$@"
else
  if [ -f "build/yano.jar" ]; then
    JAR="build/yano.jar"
  elif [ -f "build/quarkus-app/quarkus-run.jar" ]; then
    JAR="build/quarkus-app/quarkus-run.jar"
  else
    echo "Jar not found. Build: ../gradlew :app:quarkusBuild" && exit 1
  fi
  # Separate JVM options (-D*, -X*, -ea, etc.) from application arguments
  JVM_OPTS=()
  APP_ARGS=()
  for arg in "$@"; do
    case "$arg" in
      -D*|-X*|-ea|-da) JVM_OPTS+=("$arg") ;;
      *) APP_ARGS+=("$arg") ;;
    esac
  done
  exec java "${JVM_OPTS[@]}" -jar "$JAR" "${APP_ARGS[@]}"
fi
