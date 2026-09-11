#!/bin/bash
# Shared helpers for the sparse-backfill regression runner.
#
# Isolation rules enforced here:
#   * only PIDs started by this run are ever signalled (PID_FILE);
#   * ports are probed, never defaults a developer node might be using;
#   * every path handed to Yano is absolute and inside the per-case directory.

# shellcheck disable=SC2034
SPARSE_COMMON_LOADED=1

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '[%s] WARN: %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# --- process tracking -------------------------------------------------------
# PID_FILE holds "<pid> <label>" for processes THIS run started. Nothing else is
# ever signalled: no pkill, no port-based killing, no touching developer nodes.
track_pid() {
  echo "$1 $2" >> "$PID_FILE"
}

stop_tracked_pid() { # <pid> <label> [grace-seconds]
  local pid="$1" label="$2" grace="${3:-60}" waited=0
  kill -0 "$pid" 2>/dev/null || return 0
  log "stopping $label (pid $pid, SIGTERM, up to ${grace}s)"
  kill -TERM "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$grace" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    warn "$label (pid $pid) did not exit within ${grace}s; sending SIGKILL"
    kill -KILL "$pid" 2>/dev/null || true
    return 2
  fi
  log "$label stopped after ${waited}s"
  return 0
}

wait_pid_exit() { # <pid> <timeout-seconds> -> exit code of the process, or 124 on timeout
  local pid="$1" timeout="$2" waited=0
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$timeout" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    return 124
  fi
  wait "$pid" 2>/dev/null
  return $?
}

cleanup_tracked() {
  [ -f "$PID_FILE" ] || return 0
  local pid label
  while read -r pid label; do
    [ -n "${pid:-}" ] || continue
    stop_tracked_pid "$pid" "$label" 30 || true
  done < <(tail -r "$PID_FILE" 2>/dev/null || tac "$PID_FILE")
  : > "$PID_FILE"
}

# --- JVM crash detection ----------------------------------------------------
# A node must fail and shut down cleanly. A SIGSEGV in native code (RocksDB JNI)
# exits within the SIGTERM grace period and would otherwise be recorded as a
# normal stop, so every shutdown is checked for a crash signature.
jvm_crash_detail() { # <work-dir> <log-file>... -> prints detail, returns 0 when crashed
  local dir="$1"; shift
  local log hs
  for log in "$@"; do
    [ -f "$log" ] || continue
    if grep -q "A fatal error has been detected by the Java Runtime Environment" "$log"; then
      grep -m1 -A1 "Problematic frame" "$log" | tail -1 | head -c 180
      return 0
    fi
  done
  hs=$(ls "$dir"/hs_err_pid*.log 2>/dev/null | head -1)
  if [ -n "$hs" ]; then
    printf 'JVM crash report present: %s' "$hs"
    return 0
  fi
  return 1
}

# --- ports ------------------------------------------------------------------
# Scan upward from a base for a port nothing is listening on. Never returns the
# well-known dev ports (7070 Yano HTTP, 13337 n2n, 3002 Haskell, 32000 preprod).
RESERVED_PORTS=" 7070 13337 3002 32000 12788 12798 "
pick_port() { # <start>
  local candidate="$1"
  while [ "$candidate" -lt 65000 ]; do
    case "$RESERVED_PORTS" in *" $candidate "*) candidate=$((candidate + 1)); continue ;; esac
    if ! port_in_use "$candidate"; then
      echo "$candidate"
      return 0
    fi
    candidate=$((candidate + 1))
  done
  die "no free port found from $1"
}

port_in_use() { # <port>
  python3 - "$1" <<'PY'
import socket, sys
port = int(sys.argv[1])
with socket.socket() as probe:
    probe.settimeout(0.4)
    sys.exit(0 if probe.connect_ex(("127.0.0.1", port)) == 0 else 1)
PY
}

# --- http -------------------------------------------------------------------
http_json() { # <url> [method] [body]
  local url="$1" method="${2:-GET}" body="${3:-}"
  if [ "$method" = "POST" ]; then
    curl -sS -X POST -H 'Content-Type: application/json' ${body:+-d "$body"} "$url"
  else
    curl -sS "$url"
  fi
}

http_status_and_body() { # <url> <method> <body> <out-file> -> echoes status
  local url="$1" method="$2" body="$3" out="$4"
  curl -sS -o "$out" -w '%{http_code}' -X "$method" \
    -H 'Content-Type: application/json' ${body:+-d "$body"} "$url"
}

json_get() { # <file> <dotted.path>
  python3 - "$1" "$2" <<'PY'
import json, sys
value = json.load(open(sys.argv[1]))
for key in sys.argv[2].split("."):
    value = value[key] if isinstance(value, dict) else value[int(key)]
print(value)
PY
}

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

wait_for_http() { # <url> <timeout-seconds> <label>
  local url="$1" timeout="$2" label="$3" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if curl -sf -o /dev/null "$url"; then
      log "$label ready after ${waited}s"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

wait_for_log() { # <file> <regex> <timeout-seconds> <label>
  local file="$1" pattern="$2" timeout="$3" label="$4" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if [ -f "$file" ] && grep -Eq "$pattern" "$file"; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  warn "timeout waiting for '$pattern' in $label"
  return 1
}
