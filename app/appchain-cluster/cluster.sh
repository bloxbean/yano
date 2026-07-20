#!/usr/bin/env bash
# =============================================================================
# Yano single-host app-chain cluster launcher — spin up and manage an N-node
# app-chain cluster. Defaults to a self-contained devnet (node 0 produces L1
# blocks, the rest follow); can also run every node as a public-network relay.
#
# Runs with whatever Yano build is available — the uber-jar (app/build/yano.jar)
# or the native binary (app/build/yano) — auto-detected.
#
# Quick start:
#   ./cluster.sh start 3            # 3-node devnet, chains from application-appchain.yml
#   ./cluster.sh status
#   ./cluster.sh submit orders-chain demo "hello"
#   ./cluster.sh stop               # keep data     |  clean = stop + wipe
#
# See ./README.md for the full guide.
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"          # app/ (dev layout): holds build/ + config/
REPO_DIR="$(cd "$APP_DIR/.." && pwd)"

# Yano "home" = the tree that holds config/ (application-appchain.yml + the
# network genesis referenced by relative paths). Nodes launch with cwd = HOME so
# the app resolves ./config/*. Defaults to the repo's app/ for local-dev use;
# override to run a RELEASED tree on a machine with no build:
#   YANO_HOME=/opt/yano ./cluster.sh start 3          # /opt/yano/config/... + a binary
# The binary is auto-detected under HOME (build/yano.jar, build/quarkus-app, or a
# release-root yano.jar / yano), or pointed at explicitly — anywhere on disk:
#   YANO_JAR=/downloads/yano.jar    YANO_NATIVE=/downloads/yano
YANO_HOME="${YANO_HOME:-$APP_DIR}"
CONFIG_FILE="$YANO_HOME/config/application-appchain.yml"

# --- Tunables (override via env) ---------------------------------------------
CLUSTER_DIR="${YANO_CLUSTER_DIR:-/tmp/yano-appchain-cluster}"
HTTP_BASE="${YANO_CLUSTER_HTTP_BASE:-7070}"       # node i HTTP  = HTTP_BASE + i
SERVER_BASE="${YANO_CLUSTER_SERVER_BASE:-13337}"  # node i n2n   = SERVER_BASE + i
HTTP_BASE_EXPLICIT=0
SERVER_BASE_EXPLICIT=0
# Environment overrides are operator choices, just like the CLI flags: never
# silently move them. Literal defaults are preferences and may be relocated.
[ -n "${YANO_CLUSTER_HTTP_BASE:-}" ] && HTTP_BASE_EXPLICIT=1
[ -n "${YANO_CLUSTER_SERVER_BASE:-}" ] && SERVER_BASE_EXPLICIT=1
NETWORK="devnet"
NETWORK_EXPLICIT=0                                # set when --network is passed
RUNTIME="auto"                                    # auto | jar | native
THRESHOLD=""                                      # default: majority
TRANSPORT=""                                      # ""=node default (shared) | shared | dedicated
ENABLE_ANCHOR=0
ANCHOR_MODE="script"                              # metadata | script (--anchor-mode)
ANCHOR_KEY=""                                     # --anchor-key: funded wallet seed (hex, 32 bytes)
ANCHOR_EVERY=""                                   # --anchor-every: default 2 devnet / 30 public
CLUSTER_API_KEY="${YANO_CLUSTER_API_KEY:-}"
LOCAL_CLUSTER_API_KEY="yano-local-cluster-full-key"
NODE_CONFIG_DIR="${YANO_CLUSTER_NODE_CONFIG_DIR:-}"
NODE_CONFIG_DIR_CANON=""
NODE_CONFIG_URIS=()
NODE_CONFIG_LOCATION=""
MEMBER_KEY_DIR="${YANO_CLUSTER_MEMBER_KEY_DIR:-}"
MEMBER_KEY_DIR_CANON=""
MEMBER_SEEDS=()
MEMBER_PUBLIC_KEYS=()
ANCHOR_KEY_FILE="${YANO_CLUSTER_ANCHOR_KEY_FILE:-}"
ANCHOR_KEY_FILE_VALUE=""
PRIVATE_CONFIG_DIR_REQUESTED="${YANO_CLUSTER_PRIVATE_CONFIG_DIR:-}"
PRIVATE_CONFIG_DIR=""
PRIVATE_CONFIG_FILES=()
PRIVATE_CONFIG_URIS=()
PRIVATE_CONFIG_DIGESTS=()
DEVNET_GENESIS_FILE="${YANO_CLUSTER_DEVNET_GENESIS_FILE:-}"
DEVNET_GENESIS_FILE_CANON=""
DEVNET_GENESIS_FILE_IDENTITY=""
DEVNET_GENESIS_FILE_DIGEST=""
DEVNET_GENESIS_TIMESTAMP_MILLIS=""
APPCHAIN_IDENTITY_MARKER="${YANO_CLUSTER_APPCHAIN_IDENTITY_MARKER:-}"

# Deterministic demo member identities: node i uses seed = byte(i+1) x32.
# Precomputed Ed25519 public keys (standard Ed25519 == Yano app-chain keys).
# Covers up to 16 nodes without any crypto tooling; beyond that we derive live.
PUBKEYS=(
  8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c
  8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394
  ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1
  ca93ac1705187071d67b83c7ff0efe8108e8ec4530575d7726879333dbdabe7c
  6e7a1cdd29b0b78fd13af4c5598feff4ef2a97166e3ca6f2e4fbfccd80505bf1
  8a875fff1eb38451577acd5afee405456568dd7c89e090863a0557bc7af49f17
  ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c
  1398f62c6d1a457c51ba6a4b5f3dbd2f69fca93216218dc8997e416bd17d93ca
  fd1724385aa0c75b64fb78cd602fa1d991fdebf76b13c58ed702eac835e9f618
  43a72e714401762df66b68c26dfbdf2682aaec9f2474eca4613e424a0fbafd3c
  66be7e332c7a453332bd9d0a7f7db055f5c5ef1a06ada66d98b39fb6810c473a
  0b513ad9b4924015ca0902ed079044d3ac5dbec2306f06948c10da8eb6e39f2d
  91a28a0b74381593a4d9469579208926afc8ad82c8839b7644359b9eba9a4b3a
  0beef5a9e679e6a3e134fe27837bff32c7cb5f5d44ea09bcb0e542bad6a4c0cc
  d9bf2148748a85c89da5aad8ee0b0fc2d105fd39d41a4c796536354f0ae2900c
  5c9c6df261c9cb840475776aaefcd944b405328fab28f9b3a95ef40490d3de84
)

c_red()   { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw()   { printf '\033[33m%s\033[0m\n' "$*"; }
die()     { c_red "error: $*" >&2; exit 1; }

# --- Optional per-node configuration overlays -------------------------------
# Overlay contents may include connector credentials. Keep them in files: the
# launcher passes only one config-location URI and never expands or logs keys.
canonical_path() {
  python3 - "$1" <<'PY' 2>/dev/null
import os
import sys

print(os.path.realpath(sys.argv[1]))
PY
}

file_uri() {
  python3 - "$1" <<'PY' 2>/dev/null
import pathlib
import sys

print(pathlib.Path(sys.argv[1]).as_uri())
PY
}

posix_mode() {
  local path="$1" mode
  if mode="$(stat -c '%a' "$path" 2>/dev/null)" && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s' "$mode"
    return 0
  fi
  if mode="$(stat -f '%Lp' "$path" 2>/dev/null)" && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s' "$mode"
    return 0
  fi
  return 1
}

posix_size() {
  local path="$1" size
  if size="$(stat -c '%s' "$path" 2>/dev/null)" && [[ "$size" =~ ^[0-9]+$ ]]; then
    printf '%s' "$size"
    return 0
  fi
  if size="$(stat -f '%z' "$path" 2>/dev/null)" && [[ "$size" =~ ^[0-9]+$ ]]; then
    printf '%s' "$size"
    return 0
  fi
  return 1
}

owned_by_launcher() {
  python3 - "$1" <<'PY' >/dev/null 2>&1
import os
import stat
import sys

try:
    item = os.lstat(sys.argv[1])
except OSError:
    sys.exit(1)
sys.exit(0 if stat.S_ISREG(item.st_mode) and item.st_uid == os.geteuid() else 1)
PY
}

file_identity() {
  python3 - "$1" <<'PY' 2>/dev/null
import os
import stat
import sys

try:
    item = os.lstat(sys.argv[1])
except OSError:
    sys.exit(1)
if not stat.S_ISREG(item.st_mode):
    sys.exit(1)
print(f"{item.st_dev}:{item.st_ino}")
PY
}

validate_node_config_directory_security() {
  local path="$1" label="${2:-per-node config}" result
  python3 - "$path" <<'PY' >/dev/null 2>&1
import os
import stat
import sys

path = sys.argv[1]
euid = os.geteuid()
trusted_owners = {0, euid}
try:
    directory = os.lstat(path)
except OSError:
    sys.exit(1)
if not stat.S_ISDIR(directory.st_mode):
    sys.exit(1)
if directory.st_uid != euid:
    sys.exit(2)

# The canonical path contains no intentional symlink components. Root and the
# launcher account are the trust boundary. A sticky writable ancestor (most
# notably /tmp) is safe only when its immediate child is trusted too.
child = path
while True:
    parent = os.path.dirname(child)
    if parent == child:
        break
    try:
        parent_stat = os.lstat(parent)
        child_stat = os.lstat(child)
    except OSError:
        sys.exit(3)
    if not stat.S_ISDIR(parent_stat.st_mode):
        sys.exit(3)
    if parent_stat.st_uid not in trusted_owners:
        sys.exit(3)
    parent_mode = stat.S_IMODE(parent_stat.st_mode)
    if parent_mode & 0o022:
        if not (parent_mode & stat.S_ISVTX):
            sys.exit(3)
        if child_stat.st_uid not in trusted_owners:
            sys.exit(3)
    child = parent
sys.exit(0)
PY
  result=$?
  case "$result" in
    0) return 0;;
    2) die "$label directory must be owned by the launcher user";;
    *) die "$label directory has an unsafe or untrusted canonical ancestor";;
  esac
}

validate_node_config_contents() {
  local path="$1" result
  python3 - "$path" <<'PY' >/dev/null 2>&1
import re
import sys

path = sys.argv[1]
try:
    data = open(path, "rb").read(1_048_577)
    if len(data) > 1_048_576 or b"\x00" in data:
        sys.exit(1)
    text = data.decode("utf-8")
except Exception:
    sys.exit(1)

key_pattern = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.\-\[\]]{0,511}")
ordinal_count = 0
for raw in text.splitlines():
    stripped = raw.lstrip(" \t\f")
    if not stripped or stripped.startswith(("#", "!")):
        continue
    # Keep the overlay grammar deliberately simpler than java.util.Properties:
    # one ASCII key, one '=', one physical line. This makes escaped or
    # continued spellings of reserved source-control keys impossible.
    if len(raw) > 16_384 or raw.endswith("\\") or "=" not in raw:
        sys.exit(1)
    key, _ = raw.split("=", 1)
    key = key.strip()
    if not key_pattern.fullmatch(key):
        sys.exit(1)
    normalized = key.lower()
    if normalized == "config_ordinal" or normalized.endswith(".config_ordinal"):
        # The exact fixed source ordinal is part of the overlay contract. It
        # sits above Quarkus' packaged/filesystem application config and below
        # direct environment values and launcher-owned system properties.
        if raw != "config_ordinal=275":
            sys.exit(2)
        ordinal_count += 1
        continue
    if "config.locations" in normalized:
        sys.exit(2)
if ordinal_count != 1:
    sys.exit(3)
sys.exit(0)
PY
  result=$?
  case "$result" in
    0) return 0;;
    2) die "node config contains a reserved configuration-control key";;
    3) die "node config must contain exactly one literal config_ordinal=275 line";;
    *) die "node config must use bounded UTF-8 key=value lines without key escapes or continuations";;
  esac
}

validate_node_config_file() {
  local i="$1" candidate canonical final_canonical mode uri identity final_identity
  NODE_CONFIG_LOCATION=""
  candidate="$NODE_CONFIG_DIR_CANON/node$i.properties"
  [ ! -L "$candidate" ] || die "node $i config must not be a symbolic link"
  [ -f "$candidate" ] || die "node $i config is missing or not a regular file"
  [ -r "$candidate" ] || die "node $i config is not readable"

  canonical="$(canonical_path "$candidate")" || die "cannot resolve node $i config"
  case "$canonical" in
    "$NODE_CONFIG_DIR_CANON"/node"$i".properties) ;;
    *) die "node $i config resolves outside the configured directory";;
  esac
  identity="$(file_identity "$candidate")" || die "node $i config changed during validation"
  owned_by_launcher "$candidate" || die "node $i config must be owned by the launcher user"

  mode="$(posix_mode "$candidate")" || die "cannot inspect node $i config permissions"
  (( (8#$mode & 077) == 0 )) \
    || die "node $i config must not grant group or world permissions (use chmod 600)"
  validate_node_config_contents "$candidate"

  # Repeat identity/ownership/mode after reading. This catches path replacement
  # during validation; launch_node repeats this whole function immediately
  # before the child process resolves the file URI.
  [ ! -L "$candidate" ] && [ -f "$candidate" ] \
    || die "node $i config changed during validation"
  final_canonical="$(canonical_path "$candidate")" || die "cannot resolve node $i config"
  [ "$final_canonical" = "$canonical" ] || die "node $i config changed during validation"
  final_identity="$(file_identity "$candidate")" || die "node $i config changed during validation"
  [ "$final_identity" = "$identity" ] || die "node $i config changed during validation"
  owned_by_launcher "$candidate" || die "node $i config must be owned by the launcher user"
  mode="$(posix_mode "$candidate")" || die "cannot inspect node $i config permissions"
  (( (8#$mode & 077) == 0 )) \
    || die "node $i config must not grant group or world permissions (use chmod 600)"

  uri="$(file_uri "$final_canonical")" || die "cannot encode node $i config location"
  [ -n "$uri" ] || die "cannot encode node $i config location"
  NODE_CONFIG_LOCATION="$uri"
}

validate_node_config_overlays() {
  local n="$1" i mode entry base index digits
  NODE_CONFIG_DIR_CANON=""
  NODE_CONFIG_URIS=()
  [ -n "$NODE_CONFIG_DIR" ] || return 0

  [ -d "$NODE_CONFIG_DIR" ] || die "per-node config directory does not exist or is not a directory"
  NODE_CONFIG_DIR_CANON="$(canonical_path "$NODE_CONFIG_DIR")" \
    || die "cannot resolve per-node config directory"
  [ -n "$NODE_CONFIG_DIR_CANON" ] && [ -d "$NODE_CONFIG_DIR_CANON" ] \
    || die "cannot resolve per-node config directory"
  mode="$(posix_mode "$NODE_CONFIG_DIR_CANON")" \
    || die "cannot inspect per-node config directory permissions"
  (( (8#$mode & 022) == 0 )) \
    || die "per-node config directory must not be group or world writable"
  validate_node_config_directory_security "$NODE_CONFIG_DIR_CANON"

  for ((i=0;i<n;i++)); do
    validate_node_config_file "$i"
    NODE_CONFIG_URIS[$i]="$NODE_CONFIG_LOCATION"
  done

  # Fail closed on stale/mistyped node overlay names while permitting unrelated
  # support files (for example trust stores) in the same private directory.
  for entry in "$NODE_CONFIG_DIR_CANON"/node*.properties; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    base="${entry##*/}"
    if [[ "$base" =~ ^node([0-9]+)\.properties$ ]]; then
      digits="${BASH_REMATCH[1]}"
      [ "${#digits}" -le 9 ] \
        || die "unexpected per-node config filename (expected node<N>.properties)"
      index=$((10#$digits))
      [ "$base" = "node$index.properties" ] \
        || die "unexpected per-node config filename (expected node<N>.properties)"
      [ "$index" -lt "$n" ] || die "unexpected per-node config for node $index"
    else
      die "unexpected per-node config filename (expected node<N>.properties)"
    fi
  done
}

revalidate_node_config_for_launch() {
  local i="$1" mode
  NODE_CONFIG_LOCATION=""
  [ -n "$NODE_CONFIG_DIR_CANON" ] || return 0
  [ -d "$NODE_CONFIG_DIR_CANON" ] || die "per-node config directory changed before launch"
  mode="$(posix_mode "$NODE_CONFIG_DIR_CANON")" \
    || die "cannot inspect per-node config directory permissions"
  (( (8#$mode & 022) == 0 )) \
    || die "per-node config directory must not be group or world writable"
  validate_node_config_directory_security "$NODE_CONFIG_DIR_CANON"
  validate_node_config_file "$i"
}

node_config_location() {
  local i="$1"
  [ -n "$NODE_CONFIG_DIR_CANON" ] || return 0
  printf '%s' "${NODE_CONFIG_URIS[$i]}"
}

# --- Optional private member and anchor keys ---------------------------------
# Public-network demos must not use the checked-in deterministic identities.
# These inputs are read once, before cluster state is created, and retained in
# memory. A later path replacement therefore cannot change the launched keys.
PRIVATE_KEY_DIR_CANON=""
VALIDATED_HEX_VALUE=""

validate_key_directory_security() {
  local path="$1" label="$2" result
  python3 - "$path" <<'PY' >/dev/null 2>&1
import os
import stat
import sys

path = sys.argv[1]
euid = os.geteuid()
trusted_owners = {0, euid}
try:
    directory = os.lstat(path)
except OSError:
    sys.exit(1)
if not stat.S_ISDIR(directory.st_mode):
    sys.exit(1)
if directory.st_uid != euid:
    sys.exit(2)

child = path
while True:
    parent = os.path.dirname(child)
    if parent == child:
        break
    try:
        parent_stat = os.lstat(parent)
        child_stat = os.lstat(child)
    except OSError:
        sys.exit(3)
    if not stat.S_ISDIR(parent_stat.st_mode):
        sys.exit(3)
    if parent_stat.st_uid not in trusted_owners:
        sys.exit(3)
    parent_mode = stat.S_IMODE(parent_stat.st_mode)
    if parent_mode & 0o022:
        if not (parent_mode & stat.S_ISVTX):
            sys.exit(3)
        if child_stat.st_uid not in trusted_owners:
            sys.exit(3)
    child = parent
sys.exit(0)
PY
  result=$?
  case "$result" in
    0) return 0;;
    2) die "$label directory must be owned by the launcher user";;
    *) die "$label directory has an unsafe or untrusted canonical ancestor";;
  esac
}

validate_private_key_directory() {
  local configured="$1" label="$2" path canonical mode
  PRIVATE_KEY_DIR_CANON=""
  path="$configured"
  while [ "$path" != "/" ] && [ "${path%/}" != "$path" ]; do path="${path%/}"; done
  [ ! -L "$path" ] || die "$label directory must not be a symbolic link"
  [ -d "$path" ] || die "$label directory does not exist or is not a directory"
  canonical="$(canonical_path "$path")" || die "cannot resolve $label directory"
  [ -n "$canonical" ] && [ -d "$canonical" ] || die "cannot resolve $label directory"
  mode="$(posix_mode "$canonical")" || die "cannot inspect $label directory permissions"
  (( (8#$mode & 077) == 0 && (8#$mode & 0500) == 0500 )) \
    || die "$label directory must be owner-only (use chmod 700)"
  [ -r "$canonical" ] && [ -x "$canonical" ] \
    || die "$label directory must be readable and searchable by its owner"
  validate_key_directory_security "$canonical" "$label"
  PRIVATE_KEY_DIR_CANON="$canonical"
}

validate_private_hex_file() {
  local candidate="$1" label="$2" expected_canonical="${3:-}"
  local canonical identity final_canonical final_identity mode size value="" extra=""
  VALIDATED_HEX_VALUE=""
  [ ! -L "$candidate" ] || die "$label must not be a symbolic link"
  [ -f "$candidate" ] || die "$label is missing or not a regular file"
  [ -r "$candidate" ] || die "$label is not readable"
  canonical="$(canonical_path "$candidate")" || die "cannot resolve $label"
  [ -z "$expected_canonical" ] || [ "$canonical" = "$expected_canonical" ] \
    || die "$label resolves outside its configured directory"
  identity="$(file_identity "$candidate")" || die "$label changed during validation"
  owned_by_launcher "$candidate" || die "$label must be owned by the launcher user"
  mode="$(posix_mode "$candidate")" || die "cannot inspect $label permissions"
  (( (8#$mode & 077) == 0 && (8#$mode & 0400) != 0 && (8#$mode & 0111) == 0 )) \
    || die "$label must be owner-only and non-executable (use chmod 600)"
  size="$(posix_size "$candidate")" || die "cannot inspect $label size"
  [ "$size" -eq 64 ] || [ "$size" -eq 65 ] \
    || die "$label must contain exactly one 64-hex-character value"

  exec 3< "$candidate" || die "cannot read $label"
  IFS= read -r value <&3 || [ -n "$value" ] \
    || { exec 3<&-; die "$label is empty"; }
  if IFS= read -r extra <&3; then
    exec 3<&-
    die "$label must contain exactly one line"
  fi
  exec 3<&-
  [[ "$value" =~ ^[0-9a-fA-F]{64}$ ]] \
    || die "$label must contain exactly one 64-hex-character value"

  [ ! -L "$candidate" ] && [ -f "$candidate" ] \
    || die "$label changed during validation"
  final_canonical="$(canonical_path "$candidate")" || die "cannot resolve $label"
  [ "$final_canonical" = "$canonical" ] || die "$label changed during validation"
  final_identity="$(file_identity "$candidate")" || die "$label changed during validation"
  [ "$final_identity" = "$identity" ] || die "$label changed during validation"
  owned_by_launcher "$candidate" || die "$label must be owned by the launcher user"
  mode="$(posix_mode "$candidate")" || die "cannot inspect $label permissions"
  (( (8#$mode & 077) == 0 && (8#$mode & 0400) != 0 && (8#$mode & 0111) == 0 )) \
    || die "$label must be owner-only and non-executable (use chmod 600)"
  size="$(posix_size "$candidate")" || die "cannot inspect $label size"
  [ "$size" -eq 64 ] || [ "$size" -eq 65 ] || die "$label changed during validation"
  value="${value//A/a}"; value="${value//B/b}"; value="${value//C/c}"
  value="${value//D/d}"; value="${value//E/e}"; value="${value//F/f}"
  VALIDATED_HEX_VALUE="$value"
}

validate_member_key_pair() {
  local seed_file="$1" public_file="$2" i="$3" result
  # Paths, never key bytes, are passed to the helper. The pure-stdlib check is
  # bounded to the exact 32-byte seed/public profile validated above.
  python3 - "$seed_file" "$public_file" <<'PY' >/dev/null 2>&1
import hashlib
import hmac
import re
import sys

HEX = re.compile(rb"[0-9a-fA-F]{64}\n?")
FIELD = 2**255 - 19
CURVE_D = (-121665 * pow(121666, FIELD - 2, FIELD)) % FIELD
BASE = (
    15112221349535400772501151409588531511454012693041857206046113283949847762202,
    46316835694926478169428394003475163141307993866256225615783033603165251855960,
)

def read_hex(path):
    with open(path, "rb") as stream:
        value = stream.read(66)
    if not HEX.fullmatch(value):
        raise ValueError("invalid key profile")
    return bytes.fromhex(value.strip().decode("ascii"))

def add(left, right):
    x1, y1 = left
    x2, y2 = right
    product = CURVE_D * x1 * x2 * y1 * y2 % FIELD
    x3 = (x1 * y2 + y1 * x2) * pow(1 + product, FIELD - 2, FIELD) % FIELD
    y3 = (y1 * y2 + x1 * x2) * pow(1 - product, FIELD - 2, FIELD) % FIELD
    return x3, y3

def multiply(scalar, point):
    result = (0, 1)
    while scalar:
        if scalar & 1:
            result = add(result, point)
        point = add(point, point)
        scalar >>= 1
    return result

try:
    seed = read_hex(sys.argv[1])
    expected = read_hex(sys.argv[2])
    digest = bytearray(hashlib.sha512(seed).digest())
    digest[0] &= 248
    digest[31] &= 63
    digest[31] |= 64
    x, y = multiply(int.from_bytes(digest[:32], "little"), BASE)
    actual = (y | ((x & 1) << 255)).to_bytes(32, "little")
except Exception:
    sys.exit(1)
sys.exit(0 if hmac.compare_digest(actual, expected) else 2)
PY
  result=$?
  case "$result" in
    0) return 0;;
    2) die "node $i member public key does not match its seed";;
    *) die "cannot verify node $i member seed/public-key pair";;
  esac
}

validate_member_key_inputs() {
  local n="$1" i j candidate entry base digits index extension
  MEMBER_KEY_DIR_CANON=""
  MEMBER_SEEDS=()
  MEMBER_PUBLIC_KEYS=()
  [ -n "$MEMBER_KEY_DIR" ] || return 0

  validate_private_key_directory "$MEMBER_KEY_DIR" "member key"
  MEMBER_KEY_DIR_CANON="$PRIVATE_KEY_DIR_CANON"
  for ((i=0;i<n;i++)); do
    candidate="$MEMBER_KEY_DIR_CANON/node$i.seed"
    validate_private_hex_file "$candidate" "node $i member seed" "$candidate"
    MEMBER_SEEDS[$i]="$VALIDATED_HEX_VALUE"
    candidate="$MEMBER_KEY_DIR_CANON/node$i.public"
    validate_private_hex_file "$candidate" "node $i member public key" "$candidate"
    MEMBER_PUBLIC_KEYS[$i]="$VALIDATED_HEX_VALUE"
    validate_member_key_pair "$MEMBER_KEY_DIR_CANON/node$i.seed" "$candidate" "$i"
    for ((j=0;j<i;j++)); do
      [ "${MEMBER_PUBLIC_KEYS[$j]}" != "${MEMBER_PUBLIC_KEYS[$i]}" ] \
        || die "node $i member public key duplicates node $j"
    done
  done

  # Reject stale, out-of-range, and ambiguous node-key spellings. Other private
  # support files may coexist in the directory, but nothing beginning `node`
  # can silently be ignored as a likely identity-configuration mistake.
  for entry in "$MEMBER_KEY_DIR_CANON"/node*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    base="${entry##*/}"
    if [[ "$base" =~ ^node([0-9]+)\.(seed|public)$ ]]; then
      digits="${BASH_REMATCH[1]}"
      extension="${BASH_REMATCH[2]}"
      [ "${#digits}" -le 9 ] \
        || die "unexpected member key filename (expected node<N>.seed or node<N>.public)"
      index=$((10#$digits))
      [ "$base" = "node$index.$extension" ] \
        || die "unexpected member key filename (expected node<N>.seed or node<N>.public)"
      [ "$index" -lt "$n" ] || die "unexpected member key for node $index"
    else
      die "unexpected member key filename (expected node<N>.seed or node<N>.public)"
    fi
  done
}

validate_anchor_key_input() {
  local parent
  ANCHOR_KEY_FILE_VALUE=""
  if [ -n "$ANCHOR_KEY_FILE" ] && [ -n "$ANCHOR_KEY" ]; then
    die "YANO_CLUSTER_ANCHOR_KEY_FILE conflicts with --anchor-key; configure only one"
  fi
  if [ -n "$ANCHOR_KEY" ]; then
    [[ "$ANCHOR_KEY" =~ ^[0-9a-fA-F]{64}$ ]] \
      || die "--anchor-key must be a 32-byte Ed25519 seed as 64 hex chars"
  fi
  [ -n "$ANCHOR_KEY_FILE" ] || return 0

  parent="$(dirname "$ANCHOR_KEY_FILE")"
  validate_private_key_directory "$parent" "anchor key parent"
  validate_private_hex_file "$ANCHOR_KEY_FILE" "anchor key file"
  ANCHOR_KEY_FILE_VALUE="$VALIDATED_HEX_VALUE"
}

validate_cluster_key_inputs() {
  validate_member_key_inputs "$1"
  validate_anchor_key_input
}

file_digest() {
  python3 - "$1" <<'PY' 2>/dev/null
import hashlib
import sys

digest = hashlib.sha256()
with open(sys.argv[1], "rb") as stream:
    while True:
        chunk = stream.read(65536)
        if not chunk:
            break
        digest.update(chunk)
print(digest.hexdigest())
PY
}

# An orchestrator may prepare one immutable devnet genesis shared by the
# app-chain nodes and any companion process. Treat that file as public network
# identity, not as a secret: it may be owner-readable or world-readable, but it
# must remain launcher-owned and non-writable by other users. Cache its exact
# identity and bytes before any cluster state directory is created.
validate_devnet_genesis_json() {
  python3 - "$1" <<'PY' >/dev/null 2>&1
import json
import sys

MAX_BYTES = 1_048_576

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result

def reject_constant(_value):
    raise ValueError("non-finite JSON number")

try:
    with open(sys.argv[1], "rb") as stream:
        data = stream.read(MAX_BYTES + 1)
    if not data or len(data) > MAX_BYTES or b"\x00" in data:
        raise ValueError("invalid size or NUL")
    document = json.loads(
        data.decode("utf-8"),
        object_pairs_hook=unique_object,
        parse_constant=reject_constant,
    )
    if not isinstance(document, dict):
        raise ValueError("genesis must be a JSON object")
except Exception:
    sys.exit(1)
PY
}

devnet_genesis_timestamp_millis() {
  python3 - "$1" <<'PY' 2>/dev/null
import datetime
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream).get("systemStart")
if not isinstance(value, str) or not value.endswith("Z"):
    raise SystemExit(1)
parsed = datetime.datetime.fromisoformat(value[:-1] + "+00:00")
if parsed.utcoffset() != datetime.timedelta(0):
    raise SystemExit(1)
millis = int(parsed.timestamp() * 1000)
if millis <= 0:
    raise SystemExit(1)
print(millis)
PY
}

validate_devnet_genesis_input() {
  local candidate canonical parent parent_mode identity final_canonical
  local final_identity mode size digest
  DEVNET_GENESIS_FILE_CANON=""
  DEVNET_GENESIS_FILE_IDENTITY=""
  DEVNET_GENESIS_FILE_DIGEST=""
  DEVNET_GENESIS_TIMESTAMP_MILLIS=""
  [ -n "$DEVNET_GENESIS_FILE" ] || return 0
  [ "$NETWORK" = "devnet" ] \
    || die "YANO_CLUSTER_DEVNET_GENESIS_FILE is supported only with --network devnet"

  candidate="$DEVNET_GENESIS_FILE"
  [ ! -L "$candidate" ] || die "devnet genesis input must not be a symbolic link"
  [ -f "$candidate" ] && [ -r "$candidate" ] \
    || die "devnet genesis input must be a readable regular file"
  canonical="$(canonical_path "$candidate")" || die "cannot resolve devnet genesis input"
  [ -n "$canonical" ] && [ ! -L "$canonical" ] && [ -f "$canonical" ] \
    || die "cannot resolve devnet genesis input"

  parent="$(dirname "$canonical")"
  parent_mode="$(posix_mode "$parent")" \
    || die "cannot inspect devnet genesis parent permissions"
  (( (8#$parent_mode & 022) == 0 )) \
    || die "devnet genesis parent directory must not be group or world writable"
  validate_node_config_directory_security "$parent" "devnet genesis parent"

  identity="$(file_identity "$candidate")" || die "devnet genesis input changed during validation"
  owned_by_launcher "$candidate" \
    || die "devnet genesis input must be owned by the launcher user"
  mode="$(posix_mode "$candidate")" || die "cannot inspect devnet genesis input permissions"
  (( (8#$mode & 022) == 0 && (8#$mode & 0111) == 0 )) \
    || die "devnet genesis input must not be group/world writable or executable"
  size="$(posix_size "$candidate")" || die "cannot inspect devnet genesis input size"
  [ "$size" -ge 1 ] && [ "$size" -le 1048576 ] \
    || die "devnet genesis input must be between 1 byte and 1 MiB"
  validate_devnet_genesis_json "$candidate" \
    || die "devnet genesis input must be a bounded UTF-8 JSON object with unique keys"
  DEVNET_GENESIS_TIMESTAMP_MILLIS="$(devnet_genesis_timestamp_millis "$candidate")" \
    || die "devnet genesis input must contain a valid UTC systemStart"
  digest="$(file_digest "$candidate")" || die "cannot hash devnet genesis input"

  # Recheck path identity and permissions after parsing and hashing so a path
  # replacement cannot silently select another devnet identity.
  [ ! -L "$candidate" ] && [ -f "$candidate" ] && [ -r "$candidate" ] \
    || die "devnet genesis input changed during validation"
  final_canonical="$(canonical_path "$candidate")" \
    || die "devnet genesis input changed during validation"
  [ "$final_canonical" = "$canonical" ] \
    || die "devnet genesis input changed during validation"
  final_identity="$(file_identity "$candidate")" \
    || die "devnet genesis input changed during validation"
  [ "$final_identity" = "$identity" ] \
    || die "devnet genesis input changed during validation"
  owned_by_launcher "$candidate" \
    || die "devnet genesis input must be owned by the launcher user"
  mode="$(posix_mode "$candidate")" || die "cannot inspect devnet genesis input permissions"
  (( (8#$mode & 022) == 0 && (8#$mode & 0111) == 0 )) \
    || die "devnet genesis input must not be group/world writable or executable"
  size="$(posix_size "$candidate")" || die "cannot inspect devnet genesis input size"
  [ "$size" -ge 1 ] && [ "$size" -le 1048576 ] \
    || die "devnet genesis input changed during validation"

  DEVNET_GENESIS_FILE_CANON="$canonical"
  DEVNET_GENESIS_FILE_IDENTITY="$identity"
  DEVNET_GENESIS_FILE_DIGEST="$digest"
}

revalidate_devnet_genesis_input() {
  local source canonical identity mode size digest parent parent_mode timestamp
  [ -n "$DEVNET_GENESIS_FILE_CANON" ] || return 0
  source="$DEVNET_GENESIS_FILE_CANON"
  [ ! -L "$source" ] && [ -f "$source" ] && [ -r "$source" ] \
    || die "devnet genesis input changed before installation"
  canonical="$(canonical_path "$source")" \
    || die "devnet genesis input changed before installation"
  [ "$canonical" = "$DEVNET_GENESIS_FILE_CANON" ] \
    || die "devnet genesis input changed before installation"
  identity="$(file_identity "$source")" \
    || die "devnet genesis input changed before installation"
  [ "$identity" = "$DEVNET_GENESIS_FILE_IDENTITY" ] \
    || die "devnet genesis input changed before installation"
  owned_by_launcher "$source" \
    || die "devnet genesis input changed before installation"
  parent="$(dirname "$source")"
  parent_mode="$(posix_mode "$parent")" \
    || die "cannot inspect devnet genesis parent permissions"
  (( (8#$parent_mode & 022) == 0 )) \
    || die "devnet genesis parent directory changed before installation"
  validate_node_config_directory_security "$parent" "devnet genesis parent"
  mode="$(posix_mode "$source")" \
    || die "cannot inspect devnet genesis input permissions"
  (( (8#$mode & 022) == 0 && (8#$mode & 0111) == 0 )) \
    || die "devnet genesis input changed before installation"
  size="$(posix_size "$source")" || die "cannot inspect devnet genesis input size"
  [ "$size" -ge 1 ] && [ "$size" -le 1048576 ] \
    || die "devnet genesis input changed before installation"
  digest="$(file_digest "$source")" || die "cannot hash devnet genesis input"
  [ "$digest" = "$DEVNET_GENESIS_FILE_DIGEST" ] \
    || die "devnet genesis input changed before installation"
  timestamp="$(devnet_genesis_timestamp_millis "$source")" \
    || die "devnet genesis input changed before installation"
  [ "$timestamp" = "$DEVNET_GENESIS_TIMESTAMP_MILLIS" ] \
    || die "devnet genesis input changed before installation"
}

private_config_needed() {
  local i="$1"
  [ -n "$MEMBER_KEY_DIR_CANON" ] \
    || { [ "$i" -eq 0 ] && [ "$ENABLE_ANCHOR" = "1" ] && [ -n "$ANCHOR_KEY_FILE_VALUE" ]; }
}

validate_generated_private_config() {
  local i="$1" file expected mode size digest
  file="${PRIVATE_CONFIG_FILES[$i]:-}"
  expected="$PRIVATE_CONFIG_DIR/node$i-private.properties"
  [ -n "$file" ] && [ "$file" = "$expected" ] || return 1
  [ ! -L "$file" ] && [ -f "$file" ] && [ -r "$file" ] || return 1
  owned_by_launcher "$file" || return 1
  mode="$(posix_mode "$file")" || return 1
  (( 8#$mode == 8#600 )) || return 1
  size="$(posix_size "$file")" || return 1
  [ "$size" -ge 1 ] && [ "$size" -le 1048576 ] || return 1
  digest="$(file_digest "$file")" || return 1
  [ "$digest" = "${PRIVATE_CONFIG_DIGESTS[$i]:-}" ] || return 1
  return 0
}

write_private_config() {
  local i="$1" file tmp idx uri digest
  file="$PRIVATE_CONFIG_DIR/node$i-private.properties"
  tmp="$PRIVATE_CONFIG_DIR/.node$i-private.properties.tmp.$$"
  [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || die "stale private config temporary file for node $i"
  if ! ( umask 077; set -C
    {
      printf 'config_ordinal=350\n'
      if [ -n "$MEMBER_KEY_DIR_CANON" ]; then
        for idx in $(chain_indices); do
          printf 'yano.app-chain.chains[%s].signing-key=%s\n' "$idx" "${MEMBER_SEEDS[$i]}"
        done
      fi
      if [ "$i" -eq 0 ] && [ "$ENABLE_ANCHOR" = "1" ] \
          && [ -n "$ANCHOR_KEY_FILE_VALUE" ]; then
        for idx in $(chain_indices); do
          printf 'yano.app-chain.chains[%s].anchor.signing-key=%s\n' \
            "$idx" "$ANCHOR_KEY_FILE_VALUE"
        done
      fi
    } > "$tmp"
  ); then
    rm -f "$tmp"
    die "cannot create private config for node $i"
  fi
  chmod 600 "$tmp" || { rm -f "$tmp"; die "cannot secure private config for node $i"; }
  mv -f "$tmp" "$file" || { rm -f "$tmp"; die "cannot install private config for node $i"; }
  uri="$(file_uri "$file")" || die "cannot encode private config location for node $i"
  digest="$(file_digest "$file")" || die "cannot hash private config for node $i"
  PRIVATE_CONFIG_FILES[$i]="$file"
  PRIVATE_CONFIG_URIS[$i]="$uri"
  PRIVATE_CONFIG_DIGESTS[$i]="$digest"
  validate_generated_private_config "$i" || die "generated private config for node $i is not secure"
}

prepare_private_config_directory() {
  local requested parent cluster_canon
  requested="${PRIVATE_CONFIG_DIR_REQUESTED:-$CLUSTER_DIR/private-config}"
  if [ -n "$PRIVATE_CONFIG_DIR_REQUESTED" ]; then
    cluster_canon="$(canonical_path "$CLUSTER_DIR")" || die "cannot resolve cluster data directory"
    case "$(canonical_path "$requested")" in
      "$cluster_canon"|"$cluster_canon"/*)
        die "YANO_CLUSTER_PRIVATE_CONFIG_DIR must be outside the cluster data directory";;
    esac
  fi
  PRIVATE_CONFIG_DIR="$requested"
  if [ ! -e "$PRIVATE_CONFIG_DIR" ]; then
    parent="$(dirname "$PRIVATE_CONFIG_DIR")"
    [ -d "$parent" ] && [ ! -L "$parent" ] \
      || die "private-config parent must already be a regular directory: $parent"
    mkdir -m 700 "$PRIVATE_CONFIG_DIR" || die "cannot create cluster private-config directory"
  fi
  validate_private_key_directory "$PRIVATE_CONFIG_DIR" "cluster private-config"
  PRIVATE_CONFIG_DIR="$PRIVATE_KEY_DIR_CANON"
}

prepare_private_configs() {
  local n="$1" i
  PRIVATE_CONFIG_DIR=""
  PRIVATE_CONFIG_FILES=()
  PRIVATE_CONFIG_URIS=()
  PRIVATE_CONFIG_DIGESTS=()
  [ -n "$MEMBER_KEY_DIR_CANON" ] \
    || { [ "$ENABLE_ANCHOR" = "1" ] && [ -n "$ANCHOR_KEY_FILE_VALUE" ]; } \
    || return 0
  prepare_private_config_directory
  for ((i=0;i<n;i++)); do
    private_config_needed "$i" || continue
    write_private_config "$i"
  done
}

prepare_private_config_for_node() {
  local i="$1"
  PRIVATE_CONFIG_DIR=""
  PRIVATE_CONFIG_FILES=()
  PRIVATE_CONFIG_URIS=()
  PRIVATE_CONFIG_DIGESTS=()
  private_config_needed "$i" || return 0
  prepare_private_config_directory
  write_private_config "$i"
}

combined_config_location() {
  local i="$1" operator="$NODE_CONFIG_LOCATION" private=""
  if private_config_needed "$i"; then
    validate_generated_private_config "$i" \
      || die "generated private config for node $i changed before launch"
    private="${PRIVATE_CONFIG_URIS[$i]}"
  fi
  if [ -n "$operator" ] && [ -n "$private" ]; then printf '%s,%s' "$operator" "$private"
  elif [ -n "$operator" ]; then printf '%s' "$operator"
  else printf '%s' "$private"
  fi
}

# --- Identities --------------------------------------------------------------
# Repeat a 2-hex-digit byte 32 times → a 32-byte hex seed.
repeat_byte() { local b="$1" out="" k; for ((k=0;k<32;k++)); do out+="$b"; done; printf '%s' "$out"; }
# 32-byte hex seed for node i (0-based): (i+1) as one byte, repeated 32 times.
node_seed() {
  local i="$1" b
  if [ "${#MEMBER_SEEDS[@]}" -gt "$i" ]; then printf '%s' "${MEMBER_SEEDS[$i]}"; return; fi
  printf -v b '%02x' "$(( i + 1 ))"
  repeat_byte "$b"
}
# 32-byte anchor-wallet seed for node i (distinct from the member seed).
anchor_seed() { local b; printf -v b '%02x' "$(( $1 + 48 ))"; repeat_byte "$b"; }

anchor_signing_seed() {
  if [ -n "$ANCHOR_KEY_FILE_VALUE" ]; then printf '%s' "$ANCHOR_KEY_FILE_VALUE"
  elif [ -n "$ANCHOR_KEY" ]; then printf '%s' "$ANCHOR_KEY"
  else anchor_seed "$1"
  fi
}

# Member public key (hex) for node i: precomputed table, else derived live.
node_pub() {
  local i="$1"
  if [ "${#MEMBER_PUBLIC_KEYS[@]}" -gt "$i" ]; then
    printf '%s' "${MEMBER_PUBLIC_KEYS[$i]}"; return
  fi
  if [ "$i" -lt "${#PUBKEYS[@]}" ]; then
    printf '%s' "${PUBKEYS[$i]}"; return
  fi
  local pk
  pk="$(python3 - "$(node_seed "$i")" <<'PY' 2>/dev/null
import sys
try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    seed = bytes.fromhex(sys.argv[1])
    print(Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes_raw().hex())
except Exception:
    pass
PY
)"
  [ -n "$pk" ] || die "cannot derive member key for node $i (>16 nodes needs python3 'cryptography')"
  printf '%s' "$pk"
}

# L1 storage and app-chain storage have different lifecycles. The shared L1
# marker binds only Cardano network/genesis identity. Standalone use also keeps
# an app-chain marker in the cluster directory; an orchestrator that separates
# app-chain storage may instead provide its already-validated instance marker.
cluster_identity_file() { printf '%s' "$CLUSTER_DIR/cluster-identity.json"; }
cluster_app_identity_file() { printf '%s' "$CLUSTER_DIR/cluster-appchain-identity.json"; }

IDENTITY_NETWORK=""
IDENTITY_MEMBER_COUNT=""
IDENTITY_THRESHOLD=""
IDENTITY_PROPOSER=""
IDENTITY_MEMBERS=""
IDENTITY_CHAINS=""

# Read the immutable standalone bootstrap identity without sourcing shell data.
# Governed epochs live in app-chain history and deliberately do not rewrite
# this marker; a joining node always starts from these genesis members and then
# derives later epochs through verified catch-up.
load_cluster_app_identity() {
  local marker output line key value seen=""
  marker="$(cluster_app_identity_file)"
  [ -e "$marker" ] && [ ! -L "$marker" ] \
    || die "standalone app-chain identity marker is missing; start a governed cluster first"
  output="$(python3 - "$marker" <<'PY'
import json
import os
import re
import stat
import sys

path = sys.argv[1]

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result

descriptor = -1
try:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    before = os.fstat(descriptor)
    if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
            or stat.S_IMODE(before.st_mode) not in (0o400, 0o600)
            or before.st_nlink != 1 or before.st_size < 1 or before.st_size > 65536):
        raise ValueError("unsafe marker")
    raw = b""
    while len(raw) < before.st_size:
        chunk = os.read(descriptor, before.st_size - len(raw))
        if not chunk:
            break
        raw += chunk
    after = os.fstat(descriptor)
    stable = ("st_dev", "st_ino", "st_uid", "st_mode", "st_nlink", "st_size")
    if len(raw) != before.st_size or any(
            getattr(before, field) != getattr(after, field) for field in stable):
        raise ValueError("marker changed")
    document = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                          parse_constant=lambda _value: (_ for _ in ()).throw(ValueError()))
    canonical = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
    members = document.get("members")
    chains = document.get("chainIds")
    anchor = document.get("anchor")
    count = document.get("memberCount")
    threshold = document.get("threshold")
    proposer = document.get("proposer")
    network = document.get("network")
    expected_fields = {
        "schemaVersion", "kind", "network", "memberCount", "members",
        "threshold", "proposer", "chainIds", "anchor",
    }
    if (raw != canonical or set(document) != expected_fields
            or document.get("schemaVersion") != 1
            or document.get("kind") != "yano.cluster.appchain-identity"
            or network not in {"devnet", "preprod", "preview", "mainnet", "sanchonet"}
            or type(count) is not int or not 1 <= count <= 32
            or not isinstance(members, list) or len(members) != count
            or len(set(members)) != count
            or any(not isinstance(item, str) or not re.fullmatch(r"[0-9a-f]{64}", item)
                   for item in members)
            or type(threshold) is not int or not 1 <= threshold <= count
            or proposer not in members
            or not isinstance(chains, list) or not chains or len(set(chains)) != len(chains)
            or any(not isinstance(item, str)
                   or not re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", item) for item in chains)
            or not isinstance(anchor, dict)
            or set(anchor) != {"enabled", "mode", "signerFingerprint"}
            or type(anchor.get("enabled")) is not bool):
        raise ValueError("invalid marker")
    if anchor["enabled"]:
        if (anchor.get("mode") not in {"metadata", "script"}
                or not isinstance(anchor.get("signerFingerprint"), str)
                or not re.fullmatch(r"[0-9a-f]{64}", anchor["signerFingerprint"])):
            raise ValueError("invalid anchor identity")
    elif anchor.get("mode") is not None or anchor.get("signerFingerprint") is not None:
        raise ValueError("invalid disabled anchor identity")
    print("NETWORK=" + network)
    print("MEMBER_COUNT=" + str(count))
    print("THRESHOLD=" + str(threshold))
    print("PROPOSER=" + proposer)
    print("MEMBERS=" + ",".join(members))
    print("CHAINS=" + ",".join(chains))
except Exception:
    raise SystemExit(1)
finally:
    if descriptor >= 0:
        os.close(descriptor)
PY
)" || die "standalone app-chain identity marker is unsafe or malformed"

  IDENTITY_NETWORK=""; IDENTITY_MEMBER_COUNT=""; IDENTITY_THRESHOLD=""
  IDENTITY_PROPOSER=""; IDENTITY_MEMBERS=""; IDENTITY_CHAINS=""
  while IFS= read -r line || [ -n "$line" ]; do
    key="${line%%=*}"; value="${line#*=}"
    case "$key" in
      NETWORK)      [ "${seen#*N}" = "$seen" ] || die "duplicate identity field"; seen+="N"; IDENTITY_NETWORK="$value";;
      MEMBER_COUNT) [ "${seen#*C}" = "$seen" ] || die "duplicate identity field"; seen+="C"; IDENTITY_MEMBER_COUNT="$value";;
      THRESHOLD)    [ "${seen#*T}" = "$seen" ] || die "duplicate identity field"; seen+="T"; IDENTITY_THRESHOLD="$value";;
      PROPOSER)     [ "${seen#*P}" = "$seen" ] || die "duplicate identity field"; seen+="P"; IDENTITY_PROPOSER="$value";;
      MEMBERS)      [ "${seen#*M}" = "$seen" ] || die "duplicate identity field"; seen+="M"; IDENTITY_MEMBERS="$value";;
      CHAINS)       [ "${seen#*H}" = "$seen" ] || die "duplicate identity field"; seen+="H"; IDENTITY_CHAINS="$value";;
      *) die "standalone app-chain identity marker contains an unknown field";;
    esac
  done <<< "$output"
  [ "${#seen}" -eq 6 ] || die "standalone app-chain identity marker is incomplete"
  [ "$IDENTITY_NETWORK" = "$NETWORK" ] \
    || die "saved cluster network differs from the app-chain identity marker"
  local configured_chains
  configured_chains="$(chain_ids | paste -sd, -)"
  [ "$configured_chains" = "$IDENTITY_CHAINS" ] \
    || die "configured chain IDs differ from the retained app-chain identity"
}

l1_state_present() {
  local path
  for path in "$CLUSTER_DIR"/node*/chainstate/CURRENT; do
    [ -e "$path" ] && return 0
  done
  return 1
}

appchain_state_present() {
  local path
  for path in "$CLUSTER_DIR"/node*/chainstate/app-chain/*/CURRENT; do
    [ -e "$path" ] && return 0
  done
  return 1
}

install_or_validate_identity() {
  local candidate="$1" marker="$2" state_check="$3" label="$4"
  local retained_state=0 result temp_prefix
  "$state_check" && retained_state=1
  temp_prefix="${candidate%.*}."

  # Publish with link(2), never replace(2), and make both the candidate bytes
  # and the parent-directory mutation durable.  A crash can therefore leave
  # only two recognizable states: an unpublished, single-link temporary or a
  # marker and temporary that are the same inode.  The next invocation removes
  # only those exact, content-matching states; every unknown or unsafe sibling
  # fails closed for operator inspection.
  python3 - "$candidate" "$marker" "$temp_prefix" "$retained_state" <<'PY'
import os
import re
import stat
import sys

candidate_path, marker_path, prefix_path, retained_text = sys.argv[1:]
candidate_parent = os.path.dirname(candidate_path) or "."
marker_parent = os.path.dirname(marker_path) or "."
prefix_parent = os.path.dirname(prefix_path) or "."
candidate_name = os.path.basename(candidate_path)
marker_name = os.path.basename(marker_path)
prefix = os.path.basename(prefix_path)
retained_state = retained_text == "1"

EXIT_MISMATCH = 10
EXIT_MARKER_UNSAFE = 11
EXIT_RETAINED_MISSING = 12
EXIT_TEMP_UNSAFE = 13
EXIT_DURABILITY = 14


def stop(code, message):
    print(message, file=sys.stderr)
    raise SystemExit(code)


if (candidate_parent != marker_parent or candidate_parent != prefix_parent
        or not prefix or not candidate_name.startswith(prefix)
        or not re.fullmatch(r"[0-9]+", candidate_name[len(prefix):])):
    stop(EXIT_TEMP_UNSAFE, "identity candidate has an invalid temporary name")

directory_flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
directory_flags |= getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
try:
    directory = os.open(candidate_parent, directory_flags)
except OSError as error:
    stop(EXIT_DURABILITY, f"cannot open identity directory: {error.strerror}")


def sync_directory():
    try:
        os.fsync(directory)
    except OSError as error:
        stop(EXIT_DURABILITY, f"cannot sync identity directory: {error.strerror}")


def stat_entry(name):
    try:
        return os.stat(name, dir_fd=directory, follow_symlinks=False)
    except FileNotFoundError:
        return None
    except OSError as error:
        stop(EXIT_TEMP_UNSAFE, f"cannot inspect identity entry {name}: {error.strerror}")


def read_regular(name, allowed_links, unsafe_exit):
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    descriptor = -1
    try:
        descriptor = os.open(name, flags, dir_fd=directory)
        before = os.fstat(descriptor)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
                or stat.S_IMODE(before.st_mode) not in (0o400, 0o600)
                or before.st_nlink not in allowed_links
                or before.st_size < 1 or before.st_size > 65536):
            stop(unsafe_exit, f"unsafe identity entry: {name}")
        chunks = []
        remaining = before.st_size
        while remaining:
            chunk = os.read(descriptor, remaining)
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        after = os.fstat(descriptor)
        stable_fields = ("st_dev", "st_ino", "st_uid", "st_mode", "st_nlink", "st_size")
        if (sum(map(len, chunks)) != before.st_size
                or any(getattr(before, field) != getattr(after, field)
                       for field in stable_fields)):
            stop(unsafe_exit, f"identity entry changed while reading: {name}")
        return before, b"".join(chunks)
    except OSError as error:
        stop(unsafe_exit, f"cannot safely read identity entry {name}: {error.strerror}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def unlink_exact(name, expected):
    current = stat_entry(name)
    if current is None:
        stop(EXIT_TEMP_UNSAFE, f"identity temporary disappeared: {name}")
    if (current.st_dev, current.st_ino) != (expected.st_dev, expected.st_ino):
        stop(EXIT_TEMP_UNSAFE, f"identity temporary changed before cleanup: {name}")
    try:
        os.unlink(name, dir_fd=directory)
    except OSError as error:
        stop(EXIT_DURABILITY, f"cannot remove identity temporary {name}: {error.strerror}")


def sync_exact(name, expected, unsafe_exit):
    descriptor = -1
    try:
        descriptor = os.open(
            name,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=directory,
        )
        current = os.fstat(descriptor)
        if (current.st_dev, current.st_ino) != (expected.st_dev, expected.st_ino):
            stop(unsafe_exit, f"identity entry changed before sync: {name}")
        os.fsync(descriptor)
    except OSError as error:
        stop(EXIT_DURABILITY, f"cannot sync identity entry {name}: {error.strerror}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)


try:
    directory_info = os.fstat(directory)
    if not stat.S_ISDIR(directory_info.st_mode) or directory_info.st_uid != os.geteuid():
        stop(EXIT_DURABILITY, "identity directory must be launcher-owned")

    candidate_info, candidate_bytes = read_regular(candidate_name, {1}, EXIT_TEMP_UNSAFE)
    if stat.S_IMODE(candidate_info.st_mode) != 0o600:
        stop(EXIT_TEMP_UNSAFE, "identity candidate must have mode 0600")
    sync_exact(candidate_name, candidate_info, EXIT_TEMP_UNSAFE)

    try:
        names = os.listdir(directory)
    except OSError as error:
        stop(EXIT_TEMP_UNSAFE, f"cannot inspect identity temporaries: {error.strerror}")

    temporary_names = []
    for name in names:
        if not name.startswith(prefix):
            continue
        if not re.fullmatch(r"[0-9]+", name[len(prefix):]):
            stop(EXIT_TEMP_UNSAFE, f"unknown identity temporary: {name}")
        temporary_names.append(name)
    if candidate_name not in temporary_names:
        stop(EXIT_TEMP_UNSAFE, "identity candidate disappeared during recovery")

    marker_info = stat_entry(marker_name)
    marker_bytes = None
    if marker_info is not None:
        marker_info, marker_bytes = read_regular(marker_name, {1, 2}, EXIT_MARKER_UNSAFE)
        sync_exact(marker_name, marker_info, EXIT_MARKER_UNSAFE)

    temporaries = {}
    for name in temporary_names:
        info, content = read_regular(name, {1, 2}, EXIT_TEMP_UNSAFE)
        linked_to_marker = (
            marker_info is not None
            and (info.st_dev, info.st_ino) == (marker_info.st_dev, marker_info.st_ino)
        )
        expected = marker_bytes if linked_to_marker else candidate_bytes
        if content != expected:
            stop(EXIT_TEMP_UNSAFE, f"identity temporary has unexpected content: {name}")
        temporaries[name] = (info, content)

    old_names = [name for name in temporary_names if name != candidate_name]
    if marker_info is not None:
        linked_names = [
            name for name, (info, _) in temporaries.items()
            if (info.st_dev, info.st_ino) == (marker_info.st_dev, marker_info.st_ino)
        ]
        if marker_info.st_nlink == 2:
            if len(linked_names) != 1:
                stop(EXIT_MARKER_UNSAFE, "identity marker has an unrecognized hard link")
            linked_name = linked_names[0]
            # A separate old temporary alongside a published marker cannot be
            # attributed to the marker's create-only publication.
            if any(name != linked_name and name != candidate_name for name in temporary_names):
                stop(EXIT_TEMP_UNSAFE, "unrecognized identity temporary beside marker")
            unlink_exact(linked_name, temporaries[linked_name][0])
            sync_directory()
            temporaries.pop(linked_name)
        elif linked_names:
            stop(EXIT_MARKER_UNSAFE, "identity marker link count changed during recovery")
        elif old_names:
            stop(EXIT_TEMP_UNSAFE, "unrecognized identity temporary beside marker")

        _, marker_bytes = read_regular(marker_name, {1}, EXIT_MARKER_UNSAFE)
        if marker_bytes != candidate_bytes:
            if candidate_name in temporaries:
                unlink_exact(candidate_name, temporaries[candidate_name][0])
                sync_directory()
            raise SystemExit(EXIT_MISMATCH)
        if candidate_name in temporaries:
            unlink_exact(candidate_name, temporaries[candidate_name][0])
            sync_directory()
        raise SystemExit(0)

    # Marker absent: exact single-link leftovers are pre-publication crash
    # states.  Remove them durably; hard-linked leftovers are not attributable
    # to this protocol and must be inspected by the operator.
    for name in old_names:
        info, _ = temporaries[name]
        if info.st_nlink != 1:
            stop(EXIT_TEMP_UNSAFE, f"unrecognized hard-linked identity temporary: {name}")
        unlink_exact(name, info)
        temporaries.pop(name)
    if old_names:
        sync_directory()

    if retained_state:
        unlink_exact(candidate_name, temporaries[candidate_name][0])
        sync_directory()
        raise SystemExit(EXIT_RETAINED_MISSING)

    try:
        os.link(
            candidate_name,
            marker_name,
            src_dir_fd=directory,
            dst_dir_fd=directory,
            follow_symlinks=False,
        )
    except OSError as error:
        stop(EXIT_DURABILITY, f"cannot create identity marker: {error.strerror}")
    # Publication is durable before the recovery link is removed.  If either
    # sync or unlink fails, the same-inode pair remains recognizable next time.
    sync_directory()
    unlink_exact(candidate_name, temporaries[candidate_name][0])
    sync_directory()
    read_regular(marker_name, {1}, EXIT_MARKER_UNSAFE)
finally:
    os.close(directory)
PY
  result=$?
  case "$result" in
    0) return 0;;
    10) die "$label differs from retained state; restore the original profile or use a new data directory";;
    11) die "$label marker must be a bounded launcher-owned 0400/0600 regular file with no unknown hard links";;
    12) die "retained $label state has no identity marker; restore or migrate it explicitly before restart";;
    13) die "unsafe or unrecognized $label temporary file; inspect the cluster directory before restart";;
    *) die "cannot durably install or recover $label marker";;
  esac
}

validate_external_appchain_identity() {
  local marker="$1" network="$2" members="$3" threshold="$4" proposer="$5"
  local anchor_enabled="$6" anchor_mode="$7" anchor_fingerprint="$8" result
  shift 8
  python3 - "$marker" "$network" "$members" "$threshold" "$proposer" \
      "$anchor_enabled" "$anchor_mode" "$anchor_fingerprint" "$@" <<'PY' >/dev/null 2>&1
import json
import os
import stat
import sys

(path, network, members, threshold, proposer, anchor_enabled,
 anchor_mode, anchor_fingerprint, *chains) = sys.argv[1:]
descriptor = -1
try:
    flags = (os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
             | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    descriptor = os.open(path, flags)
    info = os.fstat(descriptor)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) not in (0o400, 0o600) or info.st_nlink != 1
            or info.st_size < 1 or info.st_size > 65536):
        raise OSError("unsafe marker")
    chunks = []
    remaining = info.st_size
    while remaining:
        chunk = os.read(descriptor, remaining)
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    raw = b"".join(chunks)
    after = os.fstat(descriptor)
    if (len(raw) != info.st_size
            or (info.st_dev, info.st_ino, info.st_uid, info.st_mode, info.st_nlink, info.st_size)
            != (after.st_dev, after.st_ino, after.st_uid, after.st_mode, after.st_nlink, after.st_size)):
        raise OSError("marker changed while reading")
except OSError:
    raise SystemExit(2)
finally:
    if descriptor >= 0:
        os.close(descriptor)

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result

def reject_constant(_value):
    raise ValueError("non-finite number")

try:
    document = json.loads(
        raw.decode("utf-8"),
        object_pairs_hook=unique_object,
        parse_constant=reject_constant,
    )
except Exception:
    raise SystemExit(1)
canonical = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
if not isinstance(document, dict):
    raise SystemExit(1)
membership = document.get("membership", {})
anchor = document.get("anchor", {})
expected_enabled = anchor_enabled == "1"
if (raw != canonical or not isinstance(membership, dict) or not isinstance(anchor, dict)):
    raise SystemExit(1)
if (type(document.get("schemaVersion")) is not int
        or document.get("schemaVersion") != 1
        or document.get("kind") != "yano.demo.appchain-identity"
        or document.get("networkName") != network
        or document.get("chainIds") != chains
        or membership.get("members") != members.split(",")
        or type(membership.get("threshold")) is not int
        or membership.get("threshold") != int(threshold)
        or membership.get("proposer") != proposer
        or anchor.get("enabled") is not expected_enabled):
    raise SystemExit(1)
if expected_enabled:
    if anchor.get("mode") != anchor_mode or anchor.get("signerFingerprint") != anchor_fingerprint:
        raise SystemExit(1)
elif anchor.get("mode") != "none" or anchor.get("signerFingerprint") is not None:
    raise SystemExit(1)
PY
  result=$?
  case "$result" in
    0) return 0;;
    2) die "YANO_CLUSTER_APPCHAIN_IDENTITY_MARKER must be a bounded launcher-owned 0400/0600 regular file";;
    *) die "external app-chain identity marker does not match the selected cluster profile";;
  esac
}

ensure_cluster_identity() {
  local n="$1" l1_candidate app_candidate members threshold proposer
  local cluster_anchor_fingerprint="none" external_anchor_fingerprint="none"
  local genesis_identity="launcher-auto"
  local -a cids=()
  while IFS= read -r value; do [ -z "$value" ] || cids+=("$value"); done < <(chain_ids)
  [ "${#cids[@]}" -ge 1 ] || die "cannot bind cluster identity without a chain id"
  members="$(members_csv "$n")"
  threshold="${THRESHOLD:-$(default_threshold "$n")}" # Effective quorum is identity-bound below.
  [[ "$threshold" =~ ^[0-9]+$ ]] && [ "$threshold" -ge 1 ] && [ "$threshold" -le "$n" ] \
    || die "threshold must be between 1 and the member count"
  proposer="$(node_pub 0)"
  if [ "$ENABLE_ANCHOR" = "1" ]; then
    cluster_anchor_fingerprint="$(anchor_signing_seed 0 | python3 -c '
import hashlib,sys
value=sys.stdin.buffer.read()
print(hashlib.sha256(b"yano-cluster-anchor-signer-v1\0" + value).hexdigest())
')" || die "cannot fingerprint the selected anchor signer"
    external_anchor_fingerprint="$(anchor_signing_seed 0 | python3 -c '
import hashlib,sys
value=sys.stdin.buffer.read()
print(hashlib.sha256(b"yano-demo-anchor-signer-v1\0" + value).hexdigest())
')" || die "cannot fingerprint the selected external anchor signer"
  fi
  [ -z "$DEVNET_GENESIS_FILE_DIGEST" ] || genesis_identity="$DEVNET_GENESIS_FILE_DIGEST"

  l1_candidate="$CLUSTER_DIR/.cluster-l1-identity.tmp.$$"
  [ ! -e "$l1_candidate" ] && [ ! -L "$l1_candidate" ] \
    || die "stale L1 identity temporary file"
  if ! (umask 077; python3 - "$l1_candidate" "$NETWORK" "$genesis_identity" <<'PY'
import json
from pathlib import Path
import sys

output, network, genesis_identity = sys.argv[1:]
document = {
    "schemaVersion": 1,
    "kind": "yano.cluster.l1-identity",
    "network": network,
    "devnetGenesisSourceSha256": genesis_identity if network == "devnet" else None,
}
Path(output).write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
PY
  ); then
    rm -f "$l1_candidate"
    die "cannot create L1 identity candidate"
  fi
  chmod 600 "$l1_candidate" || { rm -f "$l1_candidate"; die "cannot secure L1 identity candidate"; }
  install_or_validate_identity "$l1_candidate" "$(cluster_identity_file)" \
    l1_state_present "L1 identity"

  if [ -n "$APPCHAIN_IDENTITY_MARKER" ]; then
    # An external marker is the orchestrator-owned identity for app-chain state
    # mounted separately from this retained L1 tree.  Never let it bypass a
    # standalone marker already installed for app-chain state owned here.
    if [ -e "$(cluster_app_identity_file)" ] || [ -L "$(cluster_app_identity_file)" ]; then
      die "external app-chain identity marker cannot replace a standalone cluster identity;" \
        "use the original profile or a new data directory"
    fi
    validate_external_appchain_identity "$APPCHAIN_IDENTITY_MARKER" "$NETWORK" \
      "$members" "$threshold" "$proposer" "$ENABLE_ANCHOR" "$ANCHOR_MODE" \
      "$external_anchor_fingerprint" "${cids[@]}"
    return 0
  fi

  app_candidate="$CLUSTER_DIR/.cluster-app-identity.tmp.$$"
  [ ! -e "$app_candidate" ] && [ ! -L "$app_candidate" ] \
    || die "stale app-chain identity temporary file"
  if ! (umask 077; python3 - "$app_candidate" "$NETWORK" "$n" "$members" \
      "$threshold" "$proposer" "$ENABLE_ANCHOR" "$ANCHOR_MODE" \
      "$cluster_anchor_fingerprint" "${cids[@]}" <<'PY'
import json
from pathlib import Path
import sys

(output, network, count, members, threshold, proposer, anchor_enabled,
 anchor_mode, anchor_fingerprint, *chains) = sys.argv[1:]
document = {
    "schemaVersion": 1,
    "kind": "yano.cluster.appchain-identity",
    "network": network,
    "memberCount": int(count),
    "members": members.split(","),
    "threshold": int(threshold),
    "proposer": proposer,
    "chainIds": chains,
    "anchor": {
        "enabled": anchor_enabled == "1",
        "mode": anchor_mode if anchor_enabled == "1" else None,
        "signerFingerprint": anchor_fingerprint if anchor_enabled == "1" else None,
    },
}
Path(output).write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
PY
  ); then
    rm -f "$app_candidate"
    die "cannot create app-chain identity candidate"
  fi
  chmod 600 "$app_candidate" \
    || { rm -f "$app_candidate"; die "cannot secure app-chain identity candidate"; }
  install_or_validate_identity "$app_candidate" "$(cluster_app_identity_file)" \
    appchain_state_present "app-chain identity"
}

# --- Runtime detection (jar vs native) ---------------------------------------
# Emits the launch prefix to stdout; nodes run with cwd = APP_DIR.
JAR=""; NATIVE=""
resolve_runtime() {
  # Explicit path overrides win (a released binary anywhere on disk); otherwise
  # auto-detect under YANO_HOME — dev layout (build/…) or a release-root binary.
  NATIVE="${YANO_NATIVE:-}"
  if [ -z "$NATIVE" ]; then
    if   [ -x "$YANO_HOME/build/yano" ]; then NATIVE="$YANO_HOME/build/yano"
    elif [ -x "$YANO_HOME/yano" ];       then NATIVE="$YANO_HOME/yano"
    fi
  fi
  JAR="${YANO_JAR:-}"
  if [ -z "$JAR" ]; then
    if   [ -f "$YANO_HOME/build/yano.jar" ];                    then JAR="$YANO_HOME/build/yano.jar"
    elif [ -f "$YANO_HOME/build/quarkus-app/quarkus-run.jar" ]; then JAR="$YANO_HOME/build/quarkus-app/quarkus-run.jar"
    elif [ -f "$YANO_HOME/yano.jar" ];                          then JAR="$YANO_HOME/yano.jar"
    fi
  fi
  case "$RUNTIME" in
    native) [ -n "$NATIVE" ] && [ -x "$NATIVE" ] || die "native binary not found — set YANO_NATIVE=/path/to/yano, or build: ./gradlew :app:build -Dquarkus.native.enabled=true";;
    jar)    [ -n "$JAR" ] && [ -f "$JAR" ]       || die "jar not found — set YANO_JAR=/path/to/yano.jar, or build: ./gradlew :app:quarkusBuild";;
    auto)
      if   [ -n "$JAR" ] && [ -f "$JAR" ];          then RUNTIME="jar"
      elif [ -n "$NATIVE" ] && [ -x "$NATIVE" ];    then RUNTIME="native"
      else die "no Yano binary found under $YANO_HOME. Build it (./gradlew :app:quarkusBuild), or point YANO_JAR=/path/to/yano.jar (or YANO_NATIVE) at a released binary and YANO_HOME at its config tree."; fi;;
    *) die "unknown runtime: $RUNTIME";;
  esac
}

# Chain indices defined in the config file (0,1,2,...), ignoring commented lines.
chain_indices() {
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE"
  grep -vE '^[[:space:]]*#' "$CONFIG_FILE" | grep -oE 'chains\[[0-9]+\]' | grep -oE '[0-9]+' | sort -un
}
# Chain ids in authored order, ignoring commented lines.
chain_ids() {
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE"
  grep -vE '^[[:space:]]*#' "$CONFIG_FILE" | grep -oE 'chain-id:[[:space:]]*"[^"]+"' | sed -E 's/.*"([^"]+)".*/\1/'
}

# --- CBOR helpers for the kv-registry state machine --------------------------
# A kv-registry command is CBOR [op(uint), key(bstr), value(bstr)]; op 0=PUT 1=DEL.
_hex_of()   { printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n'; }
_bstr_cbor() {                          # ascii -> CBOR byte-string hex
  local s="$1" n; n="$(printf '%s' "$s" | wc -c | tr -d ' ')"
  if [ "$n" -lt 24 ]; then printf '%02x%s' "$(( 0x40 + n ))" "$(_hex_of "$s")"
  else printf '58%02x%s' "$n" "$(_hex_of "$s")"; fi
}
kv_cbor() {                             # <op> <key> <value> -> CBOR array hex
  printf '83%02x%s%s' "$1" "$(_bstr_cbor "$2")" "$(_bstr_cbor "$3")"
}

http_port()   { echo $(( HTTP_BASE + $1 )); }
server_port() { echo $(( SERVER_BASE + $1 )); }
node_dir()    { echo "$CLUSTER_DIR/node$1"; }
pid_file()    { echo "$CLUSTER_DIR/node$1.pid"; }
pid_meta_file() { echo "$CLUSTER_DIR/node$1.pid.meta"; }
launch_fence_file() { echo "$CLUSTER_DIR/node$1.launch"; }
log_file()    { echo "$(node_dir "$1")/node.log"; }

validate_cluster_directory() {
  python3 - "$CLUSTER_DIR" <<'PY' >/dev/null 2>&1
import os
import stat
import sys

try:
    info = os.lstat(sys.argv[1])
except OSError:
    raise SystemExit(1)
if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid()
        or stat.S_IMODE(info.st_mode) & 0o022):
    raise SystemExit(1)
PY
}

VALIDATED_PID=""
VALIDATED_PID_START=""
VALIDATED_PID_HTTP=""
VALIDATED_PID_SERVER=""

process_start_token() {
  python3 - "$1" <<'PY' 2>/dev/null
import hashlib
import os
import pathlib
import subprocess
import sys

pid = int(sys.argv[1])
proc_stat = pathlib.Path(f"/proc/{pid}/stat")
try:
    if proc_stat.is_file():
        raw = proc_stat.read_text(encoding="ascii")
        end = raw.rfind(")")
        if end < 0:
            raise ValueError("invalid proc stat")
        fields = raw[end + 2:].split()
        raw_token = "linux:" + fields[19]
    else:
        result = subprocess.run(
            ["ps", "-o", "lstart=", "-p", str(pid)],
            check=True, capture_output=True, text=True,
            env={**os.environ, "LC_ALL": "C"},
        )
        started = result.stdout.strip()
        if not started:
            raise ValueError("missing process start")
        raw_token = "ps:" + started
except Exception:
    sys.exit(1)
print("p-" + hashlib.sha256(raw_token.encode("ascii", "strict")).hexdigest())
PY
}

# Validate one exact same-owner process instance by PID + OS start token. This
# deliberately does not inspect argv: it is used only to prove that an already
# validated process has exited, and to clean up a direct child if publication
# of its launcher PID record fails. Signal delivery happens in the same Python
# process as validation, closing the shell-level PID-reuse window.
validate_process_instance() {
  local pid="$1" start="$2" signal="${3:-0}"
  python3 - "$pid" "$(id -u)" "$start" "$signal" <<'PY' >/dev/null 2>&1
import hashlib
import os
import pathlib
import subprocess
import sys

pid = int(sys.argv[1])
expected_uid = int(sys.argv[2])
expected_start = sys.argv[3]
requested_signal = int(sys.argv[4])

try:
    os.kill(pid, 0)
    proc = pathlib.Path(f"/proc/{pid}")
    if (proc / "stat").is_file():
        if proc.stat().st_uid != expected_uid:
            raise ValueError("owner mismatch")
        raw = (proc / "stat").read_text(encoding="ascii")
        end = raw.rfind(")")
        if end < 0:
            raise ValueError("invalid proc stat")
        fields = raw[end + 2:].split()
        raw_start = "linux:" + fields[19]
    else:
        env = {**os.environ, "LC_ALL": "C"}
        uid = subprocess.run(
            ["ps", "-o", "uid=", "-p", str(pid)], check=True,
            capture_output=True, text=True, env=env,
        ).stdout.strip()
        started = subprocess.run(
            ["ps", "-o", "lstart=", "-p", str(pid)], check=True,
            capture_output=True, text=True, env=env,
        ).stdout.strip()
        if int(uid) != expected_uid or not started:
            raise ValueError("owner/start mismatch")
        raw_start = "ps:" + started
    actual_start = "p-" + hashlib.sha256(raw_start.encode("ascii", "strict")).hexdigest()
    if actual_start != expected_start:
        raise ValueError("start mismatch")
    if requested_signal:
        os.kill(pid, requested_signal)
except Exception:
    sys.exit(1)
PY
}

wait_process_instance_exit() {
  local pid="$1" start="$2" seconds="$3"
  local attempts=$((seconds * 10))
  while validate_process_instance "$pid" "$start" 0; do
    [ "$attempts" -gt 0 ] || return 1
    sleep 0.1
    attempts=$((attempts - 1))
  done
  return 0
}

# Stop a direct child for which the launcher captured an exact start token.
# This is the recovery path before a trustworthy PID record exists.
stop_launched_process_confirmed() {
  local pid="$1" start="$2"
  if ! validate_process_instance "$pid" "$start" 0; then return 0; fi
  if ! validate_process_instance "$pid" "$start" 15; then
    validate_process_instance "$pid" "$start" 0 && return 1
    return 0
  fi
  wait_process_instance_exit "$pid" "$start" 2 && return 0
  if ! validate_process_instance "$pid" "$start" 9; then
    validate_process_instance "$pid" "$start" 0 && return 1
    return 0
  fi
  wait_process_instance_exit "$pid" "$start" 5
}

# Validate ownership, launch start-token, and exact launcher-owned node
# properties. When signal != 0 Python signals only after all checks, avoiding a
# shell-level validation-to-kill PID-reuse window.
validate_managed_process() {
  local i="$1" pid="$2" start="$3" signal="${4:-0}"
  local expected_http="${5:-$(http_port "$i")}"
  local expected_server="${6:-$(server_port "$i")}"
  python3 - "$pid" "$(id -u)" "$start" \
    "-Dyano.storage.path=$(node_dir "$i")/chainstate" \
    "-Dquarkus.http.port=$expected_http" \
    "-Dyano.server.port=$expected_server" "$signal" <<'PY' >/dev/null 2>&1
import hashlib
import os
import pathlib
import subprocess
import sys

pid = int(sys.argv[1])
expected_uid = int(sys.argv[2])
expected_start = sys.argv[3]
expected_args = sys.argv[4:7]
requested_signal = int(sys.argv[7])

def ps_value(column):
    result = subprocess.run(
        ["ps", "-ww", "-o", column + "=", "-p", str(pid)],
        check=True, capture_output=True, text=True,
        env={**os.environ, "LC_ALL": "C"},
    )
    return result.stdout.strip()

try:
    os.kill(pid, 0)
    proc = pathlib.Path(f"/proc/{pid}")
    if (proc / "stat").is_file():
        if proc.stat().st_uid != expected_uid:
            raise ValueError("owner mismatch")
        raw = (proc / "stat").read_text(encoding="ascii")
        end = raw.rfind(")")
        fields = raw[end + 2:].split()
        raw_start = "linux:" + fields[19]
        argv = (proc / "cmdline").read_bytes().split(b"\0")
        argv = [item.decode("utf-8", "surrogateescape") for item in argv if item]
        if not all(item in argv for item in expected_args):
            raise ValueError("node signature mismatch")
        if not any(item.startswith("-Dquarkus.profile=") for item in argv):
            raise ValueError("profile signature missing")
    else:
        if int(ps_value("uid")) != expected_uid:
            raise ValueError("owner mismatch")
        raw_start = "ps:" + ps_value("lstart")
        command = ps_value("command")
        if not all(item in command for item in expected_args):
            raise ValueError("node signature mismatch")
        if "-Dquarkus.profile=" not in command:
            raise ValueError("profile signature missing")
    actual_start = "p-" + hashlib.sha256(raw_start.encode("ascii", "strict")).hexdigest()
    if actual_start != expected_start:
        raise ValueError("start mismatch")
    if requested_signal:
        os.kill(pid, requested_signal)
except Exception:
    sys.exit(1)
PY
}

read_pid_record() {
  local i="$1" file meta mode size pid="" extra="" version_line pid_line start_line
  local http_line server_line http_port_value server_port_value
  VALIDATED_PID=""
  VALIDATED_PID_START=""
  VALIDATED_PID_HTTP=""
  VALIDATED_PID_SERVER=""
  file="$(pid_file "$i")"; meta="$(pid_meta_file "$i")"
  [ ! -L "$file" ] && [ -f "$file" ] && [ -r "$file" ] || return 1
  owned_by_launcher "$file" || return 1
  mode="$(posix_mode "$file")" || return 1
  (( (8#$mode & 022) == 0 )) || return 1
  size="$(posix_size "$file")" || return 1
  [ "$size" -ge 2 ] && [ "$size" -le 32 ] || return 1
  exec 3< "$file" || return 1
  IFS= read -r pid <&3 || [ -n "$pid" ] || { exec 3<&-; return 1; }
  if IFS= read -r extra <&3; then exec 3<&-; return 1; fi
  exec 3<&-
  [[ "$pid" =~ ^[0-9]{1,10}$ ]] || return 1
  pid=$((10#$pid))
  [ "$pid" -gt 1 ] && [ "$pid" -le 2147483647 ] || return 1

  [ ! -L "$meta" ] && [ -f "$meta" ] && [ -r "$meta" ] || return 1
  owned_by_launcher "$meta" || return 1
  mode="$(posix_mode "$meta")" || return 1
  (( (8#$mode & 022) == 0 )) || return 1
  size="$(posix_size "$meta")" || return 1
  [ "$size" -ge 1 ] && [ "$size" -le 256 ] || return 1
  exec 3< "$meta" || return 1
  IFS= read -r version_line <&3 || { exec 3<&-; return 1; }
  IFS= read -r pid_line <&3 || { exec 3<&-; return 1; }
  IFS= read -r start_line <&3 || { exec 3<&-; return 1; }
  IFS= read -r http_line <&3 || { exec 3<&-; return 1; }
  IFS= read -r server_line <&3 || { exec 3<&-; return 1; }
  if IFS= read -r extra <&3; then exec 3<&-; return 1; fi
  exec 3<&-
  [ "$version_line" = "VERSION=1" ] || return 1
  [ "$pid_line" = "PID=$pid" ] || return 1
  [[ "$start_line" =~ ^START=(p-[0-9a-f]{64})$ ]] || return 1
  VALIDATED_PID="$pid"
  VALIDATED_PID_START="${BASH_REMATCH[1]}"
  [[ "$http_line" =~ ^HTTP_PORT=([0-9]{1,5})$ ]] || return 1
  http_port_value=$((10#${BASH_REMATCH[1]}))
  [[ "$server_line" =~ ^SERVER_PORT=([0-9]{1,5})$ ]] || return 1
  server_port_value=$((10#${BASH_REMATCH[1]}))
  [ "$http_port_value" -ge 1 ] && [ "$http_port_value" -le 65535 ] || return 1
  [ "$server_port_value" -ge 1 ] && [ "$server_port_value" -le 65535 ] || return 1
  VALIDATED_PID_HTTP="$http_port_value"
  VALIDATED_PID_SERVER="$server_port_value"
  return 0
}

managed_node_pid() {
  local i="$1"
  read_pid_record "$i" || return 1
  validate_managed_process "$i" "$VALIDATED_PID" "$VALIDATED_PID_START" 0 \
    "$VALIDATED_PID_HTTP" "$VALIDATED_PID_SERVER" || return 1
  return 0
}

node_record_artifacts_exist() {
  local i="$1" artifact
  for artifact in \
    "$(pid_file "$i")" "$(pid_meta_file "$i")" "$(launch_fence_file "$i")" \
    "$(pid_file "$i")".tmp.* "$(pid_meta_file "$i")".tmp.*; do
    [ -e "$artifact" ] || [ -L "$artifact" ] || continue
    return 0
  done
  return 1
}

create_launch_fence() {
  local i="$1" fence
  fence="$(launch_fence_file "$i")"
  ! node_record_artifacts_exist "$i" \
    || die "node $i has an incomplete or stale launcher record; inspect it before starting"
  if ! ( umask 077; set -C
    { printf 'VERSION=1\n'; printf 'NODE=%s\n' "$i"; printf 'LAUNCHER_PID=%s\n' "$$"; } > "$fence"
  ); then
    die "cannot create launch fence for node $i"
  fi
  chmod 600 "$fence" || { rm -f "$fence"; die "cannot secure launch fence for node $i"; }
}

remove_launch_fence() {
  rm -f "$(launch_fence_file "$1")"
}

# Publication starts only after a persistent launch fence exists. If any
# record write fails, stop the exact direct child before removing that fence;
# otherwise preserve all artifacts so a caller cannot mistake it for a clean
# shutdown and release shared state ownership.
abort_node_record_publication() {
  local i="$1" pid="$2" start="$3" pid_tmp="$4" meta_tmp="$5" reason="$6"
  if [ -z "$start" ]; then start="$(process_start_token "$pid")" || start=""; fi
  if { [ -z "$start" ] && ! kill -0 "$pid" 2>/dev/null; } \
      || { [ -n "$start" ] && stop_launched_process_confirmed "$pid" "$start"; }; then
    rm -f "$pid_tmp" "$meta_tmp" "$(pid_file "$i")" "$(pid_meta_file "$i")"
    if remove_launch_fence "$i"; then
      die "$reason"
    fi
  fi
  die "$reason; node $i process $pid was not confirmed stopped, so its launch fence was preserved"
}

write_pid_record() {
  local i="$1" pid="$2" start="" attempt=0 file meta pid_tmp meta_tmp http server
  http="$(http_port "$i")"; server="$(server_port "$i")"
  file="$(pid_file "$i")"; meta="$(pid_meta_file "$i")"
  pid_tmp="$file.tmp.$$"; meta_tmp="$meta.tmp.$$"
  while [ "$attempt" -lt 40 ]; do
    start="$(process_start_token "$pid")" || start=""
    if [ -n "$start" ] && validate_managed_process "$i" "$pid" "$start" 0 "$http" "$server"; then break; fi
    sleep 0.05
    attempt=$((attempt + 1))
  done
  if [ -z "$start" ] || ! validate_managed_process "$i" "$pid" "$start" 0 "$http" "$server"; then
    abort_node_record_publication "$i" "$pid" "$start" "$pid_tmp" "$meta_tmp" \
      "node $i did not expose the expected launcher process signature"
  fi
  [ ! -e "$pid_tmp" ] && [ ! -L "$pid_tmp" ] \
    && [ ! -e "$meta_tmp" ] && [ ! -L "$meta_tmp" ] \
    || abort_node_record_publication "$i" "$pid" "$start" "$pid_tmp" "$meta_tmp" \
      "stale PID record temporary file for node $i"
  if ! ( umask 077; set -C
    printf '%s\n' "$pid" > "$pid_tmp"
    { printf 'VERSION=1\n'; printf 'PID=%s\n' "$pid"; printf 'START=%s\n' "$start"
      printf 'HTTP_PORT=%s\n' "$http"; printf 'SERVER_PORT=%s\n' "$server"; } > "$meta_tmp"
  ); then
    abort_node_record_publication "$i" "$pid" "$start" "$pid_tmp" "$meta_tmp" \
      "cannot create PID record for node $i"
  fi
  chmod 600 "$pid_tmp" "$meta_tmp" \
    || abort_node_record_publication "$i" "$pid" "$start" "$pid_tmp" "$meta_tmp" \
      "cannot secure PID record for node $i"
  mv -f "$meta_tmp" "$meta" \
    || abort_node_record_publication "$i" "$pid" "$start" "$pid_tmp" "$meta_tmp" \
      "cannot install PID metadata for node $i"
  mv -f "$pid_tmp" "$file" \
    || abort_node_record_publication "$i" "$pid" "$start" "$pid_tmp" "$meta_tmp" \
      "cannot install PID record for node $i"
  remove_launch_fence "$i" \
    || abort_node_record_publication "$i" "$pid" "$start" "$pid_tmp" "$meta_tmp" \
      "cannot clear launch fence for node $i"
}

remove_pid_record() {
  rm -f "$(pid_file "$1")" "$(pid_meta_file "$1")"
}

# The process must first match the complete launcher signature. After TERM, the
# PID + start token proves whether that exact instance exited; KILL is sent only
# after the complete signature is revalidated.
stop_managed_process_snapshot() {
  local i="$1" pid="$2" start="$3" http="$4" server="$5"
  if ! validate_managed_process "$i" "$pid" "$start" 15 "$http" "$server"; then
    validate_process_instance "$pid" "$start" 0 && return 1
    return 0
  fi
  wait_process_instance_exit "$pid" "$start" 2 && return 0
  if ! validate_managed_process "$i" "$pid" "$start" 9 "$http" "$server"; then
    validate_process_instance "$pid" "$start" 0 && return 1
    return 0
  fi
  wait_process_instance_exit "$pid" "$start" 5
}

pid_record_matches_snapshot() {
  local i="$1" pid="$2" start="$3" http="$4" server="$5"
  read_pid_record "$i" || return 1
  [ "$VALIDATED_PID" = "$pid" ] \
    && [ "$VALIDATED_PID_START" = "$start" ] \
    && [ "$VALIDATED_PID_HTTP" = "$http" ] \
    && [ "$VALIDATED_PID_SERVER" = "$server" ]
}

# Stop one launcher-owned node and remove its records only after the exact
# process instance is gone and the records still match the original snapshot.
stop_managed_node_confirmed() {
  local i="$1" pid start http server
  read_pid_record "$i" || return 1
  pid="$VALIDATED_PID"; start="$VALIDATED_PID_START"
  http="$VALIDATED_PID_HTTP"; server="$VALIDATED_PID_SERVER"
  if validate_managed_process "$i" "$pid" "$start" 0 "$http" "$server"; then
    stop_managed_process_snapshot "$i" "$pid" "$start" "$http" "$server" || return 1
  elif validate_process_instance "$pid" "$start" 0; then
    # The same process is live but no longer has the launcher's exact argv.
    return 1
  fi
  pid_record_matches_snapshot "$i" "$pid" "$start" "$http" "$server" || return 1
  remove_pid_record "$i"
}

pid_indices() {
  local file base digits index
  local -a indices=()
  [ -d "$CLUSTER_DIR" ] || return 0
  for file in "$CLUSTER_DIR"/node*.pid "$CLUSTER_DIR"/node*.pid.meta \
      "$CLUSTER_DIR"/node*.launch "$CLUSTER_DIR"/node*.pid.tmp.* \
      "$CLUSTER_DIR"/node*.pid.meta.tmp.*; do
    [ -e "$file" ] || [ -L "$file" ] || continue
    base="${file##*/}"
    if [[ "$base" =~ ^node([0-9]+)\.(pid|pid\.meta|launch|pid\.tmp\.[0-9]+|pid\.meta\.tmp\.[0-9]+)$ ]]; then
      digits="${BASH_REMATCH[1]}"
      if [ "${#digits}" -gt 5 ]; then
        c_red "error: malformed PID lifecycle artifact: $file" >&2
        return 1
      fi
      index=$((10#$digits))
      if [ "$index" -gt 65535 ]; then
        c_red "error: malformed PID lifecycle artifact: $file" >&2
        return 1
      fi
      indices+=("$index")
    else
      c_red "error: malformed PID lifecycle artifact: $file" >&2
      return 1
    fi
  done
  [ "${#indices[@]}" -eq 0 ] || printf '%s\n' "${indices[@]}" | sort -nu
}

# This launcher is a local/demo environment, so it has one known full key just
# like its known member and anchor seeds. Override it for any shared machine;
# production nodes do not inherit this launcher-only default.
effective_api_key() {
  if [ -n "$CLUSTER_API_KEY" ]; then
    printf '%s' "$CLUSTER_API_KEY"
  else
    printf '%s' "$LOCAL_CLUSTER_API_KEY"
  fi
}

validate_api_key() {
  local key="$1"
  [[ "$key" =~ ^[A-Za-z0-9._~-]+$ ]] \
    && [ "${#key}" -ge 16 ] && [ "${#key}" -le 256 ] \
    || die "YANO_CLUSTER_API_KEY must be 16-256 characters from A-Z, a-z, 0-9, . _ ~ -"
}

api_curl() {
  local key; key="$(effective_api_key)"
  validate_api_key "$key"
  command curl --connect-timeout 5 --max-time 30 --config - "$@" <<EOF
header = "X-API-Key: $key"
EOF
}

range_end() { echo $(( $1 + $2 - 1 )); }

validate_port_base() {
  local value="$1" count="$2" label="$3"
  [[ "$value" =~ ^[0-9]+$ ]] || die "$label must be a decimal TCP port"
  # Force base-10: bash arithmetic treats a leading zero as octal.
  value=$((10#$value))
  [ "$value" -ge 1 ] || die "$label must be at least 1"
  [ "$value" -le 65535 ] || die "$label must be at most 65535"
  [ "$(( value + count - 1 ))" -le 65535 ] \
    || die "$label=$value cannot fit $count contiguous ports below 65536"
  printf '%s' "$value"
}

ranges_overlap() {
  local a="$1" ac="$2" b="$3" bc="$4"
  [ "$a" -le "$(( b + bc - 1 ))" ] && [ "$b" -le "$(( a + ac - 1 ))" ]
}

port_is_busy() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    return $?
  fi
  # Portable fallback when lsof is unavailable. Binding all IPv4 interfaces
  # detects the conflicts relevant to Quarkus/NodeServer's wildcard binds.
  python3 - "$port" <<'PY' >/dev/null 2>&1
import socket, sys
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    s.bind(("0.0.0.0", int(sys.argv[1])))
except OSError:
    sys.exit(0)  # busy
finally:
    s.close()
sys.exit(1)      # free
PY
}

port_range_is_free() {
  local base="$1" count="$2" i
  for ((i=0;i<count;i++)); do
    port_is_busy "$(( base + i ))" && return 1
  done
  return 0
}

first_busy_port() {
  local base="$1" count="$2" i
  for ((i=0;i<count;i++)); do
    if port_is_busy "$(( base + i ))"; then
      printf '%s' "$(( base + i ))"
      return 0
    fi
  done
  return 1
}

find_free_port_base() {
  local start="$1" count="$2" avoid_base="${3:-}" avoid_count="${4:-0}"
  local candidate="$start" max=$(( 65535 - count + 1 ))
  while [ "$candidate" -le "$max" ]; do
    if { [ -z "$avoid_base" ] || ! ranges_overlap "$candidate" "$count" "$avoid_base" "$avoid_count"; } \
        && port_range_is_free "$candidate" "$count"; then
      printf '%s' "$candidate"
      return 0
    fi
    candidate=$(( candidate + 1 ))
  done
  return 1
}

resolve_cluster_ports() {
  local count="$1" requested_http requested_server busy selected
  HTTP_BASE="$(validate_port_base "$HTTP_BASE" "$count" "HTTP base")" || exit 1
  SERVER_BASE="$(validate_port_base "$SERVER_BASE" "$count" "server base")" || exit 1
  requested_http="$HTTP_BASE"; requested_server="$SERVER_BASE"

  # An explicit server range owns its ports; move only the default HTTP range
  # if the two requested ranges overlap.
  if [ "$HTTP_BASE_EXPLICIT" = "1" ]; then
    if ! port_range_is_free "$HTTP_BASE" "$count"; then
      busy="$(first_busy_port "$HTTP_BASE" "$count")"
      die "explicit HTTP range $HTTP_BASE-$(range_end "$HTTP_BASE" "$count") is busy (port $busy)"
    fi
  elif ! port_range_is_free "$HTTP_BASE" "$count" \
      || { [ "$SERVER_BASE_EXPLICIT" = "1" ] && ranges_overlap "$HTTP_BASE" "$count" "$SERVER_BASE" "$count"; }; then
    selected="$(find_free_port_base "$HTTP_BASE" "$count" \
      "$([ "$SERVER_BASE_EXPLICIT" = "1" ] && printf '%s' "$SERVER_BASE")" \
      "$([ "$SERVER_BASE_EXPLICIT" = "1" ] && printf '%s' "$count" || printf '0')")" \
      || die "no free contiguous HTTP range of $count ports at or above $HTTP_BASE"
    HTTP_BASE="$selected"
    c_ylw "HTTP range $requested_http-$(range_end "$requested_http" "$count") unavailable; using $HTTP_BASE-$(range_end "$HTTP_BASE" "$count")"
  fi

  if [ "$SERVER_BASE_EXPLICIT" = "1" ]; then
    ranges_overlap "$HTTP_BASE" "$count" "$SERVER_BASE" "$count" \
      && die "explicit server range $SERVER_BASE-$(range_end "$SERVER_BASE" "$count") overlaps HTTP range $HTTP_BASE-$(range_end "$HTTP_BASE" "$count")"
    if ! port_range_is_free "$SERVER_BASE" "$count"; then
      busy="$(first_busy_port "$SERVER_BASE" "$count")"
      die "explicit server range $SERVER_BASE-$(range_end "$SERVER_BASE" "$count") is busy (port $busy)"
    fi
  elif ! port_range_is_free "$SERVER_BASE" "$count" \
      || ranges_overlap "$HTTP_BASE" "$count" "$SERVER_BASE" "$count"; then
    selected="$(find_free_port_base "$SERVER_BASE" "$count" "$HTTP_BASE" "$count")" \
      || die "no free contiguous server range of $count ports at or above $SERVER_BASE"
    SERVER_BASE="$selected"
    c_ylw "N2N range $requested_server-$(range_end "$requested_server" "$count") unavailable; using $SERVER_BASE-$(range_end "$SERVER_BASE" "$count")"
  fi
}

chain_state_present() {
  [ -f "$(node_dir "$1")/chainstate/CURRENT" ]
}

prepare_devnet_node0_genesis() {
  local target source tmp copied_digest
  target="$(node_dir 0)/shelley-genesis.json"
  source="${DEVNET_GENESIS_FILE_CANON:-$YANO_HOME/config/network/devnet/shelley-genesis.json}"
  revalidate_devnet_genesis_input
  [ -f "$source" ] || die "devnet genesis not found: $source"
  mkdir -p "$(node_dir 0)"

  if [ -f "$target" ]; then
    # An orchestrator-supplied genesis is immutable: launch_node also supplies
    # its systemStart explicitly, so the runtime has no reason to rewrite it.
    # Refuse a different retained identity instead of silently ignoring the
    # currently selected source.
    if [ -n "$DEVNET_GENESIS_FILE_CANON" ]; then
      cmp -s "$source" "$target" \
        || die "retained node 0 genesis differs from YANO_CLUSTER_DEVNET_GENESIS_FILE"
      copied_digest="$(file_digest "$target")" \
        || die "cannot hash retained node 0 devnet genesis"
      [ "$copied_digest" = "$DEVNET_GENESIS_FILE_DIGEST" ] \
        || die "retained node 0 genesis differs from YANO_CLUSTER_DEVNET_GENESIS_FILE"
    fi
    return 0
  fi
  chain_state_present 0 && die "retained node 0 state has no shelley-genesis.json; restore the exact original file or run clean"

  tmp="${target}.tmp.$$"
  [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || die "stale node 0 devnet genesis temporary file"
  if [ -n "$DEVNET_GENESIS_FILE_CANON" ]; then
    if ! (umask 077; cp "$source" "$tmp"); then
      rm -f "$tmp"
      die "cannot copy supplied node 0 devnet genesis"
    fi
    if ! cmp -s "$source" "$tmp"; then
      rm -f "$tmp"
      die "supplied node 0 devnet genesis changed while copying"
    fi
    copied_digest="$(file_digest "$tmp")" \
      || { rm -f "$tmp"; die "cannot hash copied node 0 devnet genesis"; }
    if [ "$copied_digest" != "$DEVNET_GENESIS_FILE_DIGEST" ]; then
      rm -f "$tmp"
      die "supplied node 0 devnet genesis changed while copying"
    fi
  elif ! jq '.epochLength = 500' "$source" > "$tmp" 2>/dev/null; then
    cp "$source" "$tmp" || { rm -f "$tmp"; die "cannot create node 0 devnet genesis"; }
  fi
  mv "$tmp" "$target" || { rm -f "$tmp"; die "cannot install node 0 devnet genesis"; }
}

validate_retained_devnet_followers() {
  local count="$1" i leader target
  leader="$(node_dir 0)/shelley-genesis.json"
  for ((i=1;i<count;i++)); do
    chain_state_present "$i" || continue
    target="$(node_dir "$i")/shelley-genesis.json"
    [ -f "$target" ] || die "retained node $i state has no shelley-genesis.json; restore it or run clean"
    cmp -s "$leader" "$target" \
      || die "retained node $i genesis differs from node 0; refusing to start or overwrite existing chain identity"
  done
}

prepare_devnet_follower_genesis() {
  local i="$1" leader target
  leader="$(node_dir 0)/shelley-genesis.json"
  target="$(node_dir "$i")/shelley-genesis.json"
  mkdir -p "$(node_dir "$i")"

  if chain_state_present "$i"; then
    [ -f "$target" ] || die "retained node $i state has no shelley-genesis.json; restore it or run clean"
    cmp -s "$leader" "$target" \
      || die "retained node $i genesis differs from node 0; refusing to overwrite existing chain identity"
    return 0
  fi
  cp "$leader" "$target" || die "cannot copy devnet genesis to node $i"
}

# `start` records the cluster's network/anchor settings here so later commands
# (anchor-bootstrap, ...) act on the RUNNING cluster without re-passing flags.
env_file()    { echo "$CLUSTER_DIR/cluster.env"; }
save_cluster_env() {
  local target tmp
  target="$(env_file)"
  tmp="$target.tmp.$$"
  [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || die "stale cluster environment temporary file"
  if ! ( umask 077; set -C
    { printf 'NETWORK=%s\n' "$NETWORK"
      printf 'ENABLE_ANCHOR=%s\n' "$ENABLE_ANCHOR"
      printf 'ANCHOR_MODE=%s\n' "$ANCHOR_MODE"
      printf 'HTTP_BASE=%s\n' "$HTTP_BASE"
      printf 'SERVER_BASE=%s\n' "$SERVER_BASE"; } > "$tmp"
  ); then
    rm -f "$tmp"
    die "cannot create cluster environment record"
  fi
  chmod 600 "$tmp" || { rm -f "$tmp"; die "cannot secure cluster environment record"; }
  mv -f "$tmp" "$target" || { rm -f "$tmp"; die "cannot install cluster environment record"; }
}

load_cluster_env() {
  local file mode size line key value
  local parsed_network="" parsed_anchor="" parsed_anchor_mode=""
  local parsed_http="" parsed_server=""
  local seen_network=0 seen_anchor=0 seen_anchor_mode=0 seen_http=0 seen_server=0
  file="$(env_file)"
  [ -e "$file" ] || [ -L "$file" ] || return 0
  [ ! -L "$file" ] && [ -f "$file" ] && [ -r "$file" ] \
    || die "cluster environment record must be a readable regular non-symlink file"
  owned_by_launcher "$file" || die "cluster environment record must be owned by the launcher user"
  mode="$(posix_mode "$file")" || die "cannot inspect cluster environment record permissions"
  (( (8#$mode & 022) == 0 )) \
    || die "cluster environment record must not be group or world writable"
  size="$(posix_size "$file")" || die "cannot inspect cluster environment record size"
  [ "$size" -ge 1 ] && [ "$size" -le 1024 ] || die "cluster environment record is not bounded"

  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^([A-Z_]+)=([A-Za-z0-9_-]+)$ ]] \
      || die "cluster environment record contains an invalid line"
    key="${BASH_REMATCH[1]}"; value="${BASH_REMATCH[2]}"
    case "$key" in
      NETWORK)
        [ "$seen_network" -eq 0 ] || die "cluster environment record contains duplicate NETWORK"
        case "$value" in devnet|preprod|preview|mainnet|sanchonet) ;; *) die "invalid saved network";; esac
        parsed_network="$value"; seen_network=1;;
      ENABLE_ANCHOR)
        [ "$seen_anchor" -eq 0 ] || die "cluster environment record contains duplicate ENABLE_ANCHOR"
        case "$value" in 0|1) ;; *) die "invalid saved anchor flag";; esac
        parsed_anchor="$value"; seen_anchor=1;;
      ANCHOR_MODE)
        [ "$seen_anchor_mode" -eq 0 ] || die "cluster environment record contains duplicate ANCHOR_MODE"
        case "$value" in metadata|script) ;; *) die "invalid saved anchor mode";; esac
        parsed_anchor_mode="$value"; seen_anchor_mode=1;;
      HTTP_BASE)
        [ "$seen_http" -eq 0 ] || die "cluster environment record contains duplicate HTTP_BASE"
        [[ "$value" =~ ^[0-9]{1,5}$ ]] || die "invalid saved HTTP base"
        parsed_http=$((10#$value))
        [ "$parsed_http" -ge 1 ] && [ "$parsed_http" -le 65535 ] || die "invalid saved HTTP base"
        seen_http=1;;
      SERVER_BASE)
        [ "$seen_server" -eq 0 ] || die "cluster environment record contains duplicate SERVER_BASE"
        [[ "$value" =~ ^[0-9]{1,5}$ ]] || die "invalid saved server base"
        parsed_server=$((10#$value))
        [ "$parsed_server" -ge 1 ] && [ "$parsed_server" -le 65535 ] || die "invalid saved server base"
        seen_server=1;;
      *) die "cluster environment record contains an unknown key";;
    esac
  done < "$file"
  [ "$seen_network$seen_anchor$seen_anchor_mode$seen_http$seen_server" = "11111" ] \
    || die "cluster environment record is incomplete"

  [ "$NETWORK_EXPLICIT" = "1" ] || NETWORK="$parsed_network"
  ENABLE_ANCHOR="$parsed_anchor"
  ANCHOR_MODE="$parsed_anchor_mode"
  [ "$HTTP_BASE_EXPLICIT" = "1" ] || HTTP_BASE="$parsed_http"
  [ "$SERVER_BASE_EXPLICIT" = "1" ] || SERVER_BASE="$parsed_server"
}

health_ready() {
  command curl -sf --connect-timeout 1 --max-time 3 \
    "http://localhost:$(http_port "$1")/q/health/ready" >/dev/null 2>&1
}

# Anchor wallet address for chain $1: from node 0's status when the build
# exposes it (metadata mode always; script mode from newer builds), else from
# node 0's startup log (script mode on older builds logs it but omits it from
# status). All chains share node 0's wallet key, so the last log match is fine.
anchor_wallet_addr() {
  local cid="$1" a
  a="$(curl -s --connect-timeout 3 --max-time 10 \
    "http://localhost:$(http_port 0)/api/v1/app-chain/chains/$cid/status" 2>/dev/null \
    | jq -r '.anchor.walletAddress // .anchor.address // empty' 2>/dev/null)"
  if [ -z "$a" ] && [ -f "$(log_file 0)" ]; then
    a="$(grep -E 'script-anchor wallet address:|anchoring enabled \(address:' "$(log_file 0)" 2>/dev/null \
      | grep -oE 'addr(_test)?1[a-z0-9]+' | tail -1)"
  fi
  printf '%s' "$a"
}

# Members CSV (all N pubkeys) and default threshold (majority).
members_csv() { local n="$1" i out=""; for ((i=0;i<n;i++)); do out+="${out:+,}$(node_pub "$i")"; done; echo "$out"; }
default_threshold() { echo $(( $1 / 2 + 1 )); }

# App-chain peers for node i: every OTHER node's localhost:server-port.
peers_csv() {
  local n="$1" self="$2" j out=""
  for ((j=0;j<n;j++)); do [ "$j" -eq "$self" ] && continue; out+="${out:+,}localhost:$(server_port "$j")"; done
  echo "$out"
}

# -D system properties wiring the multi-chain config for node i.
chain_props() {
  local n="$1" i="$2" peer_count="${3:-$1}" members threshold peers proposer idx
  members="$(members_csv "$n")"
  threshold="${THRESHOLD:-$(default_threshold "$n")}"
  peers="$(peers_csv "$peer_count" "$i")"
  proposer="$(node_pub 0)"
  # Anchoring is LEADER-ONLY (node 0): script-mode followers co-sign and adopt
  # the identity with zero anchor config (008.4); metadata-mode followers need
  # nothing. Cadence default: every 2 app blocks on devnet (snappy demo), every
  # 30 on public networks (each anchor is a real fee-paying L1 tx).
  local anchor_every="${ANCHOR_EVERY:-$([ "$NETWORK" = devnet ] && echo 2 || echo 30)}"
  local -a props=()
  for idx in $(chain_indices); do
    # File-supplied private keys are selected through a generated owner-only
    # config overlay. Keep them out of ps-visible Java/native arguments.
    if [ -z "$MEMBER_KEY_DIR_CANON" ]; then
      props+=("-Dyano.app-chain.chains[$idx].signing-key=$(node_seed "$i")")
    fi
    props+=("-Dyano.app-chain.chains[$idx].members=$members")
    props+=("-Dyano.app-chain.chains[$idx].threshold=$threshold")
    props+=("-Dyano.app-chain.chains[$idx].peers=$peers")
    # Injected for every chain; rotating chains ignore it (sequencer.mode wins).
    props+=("-Dyano.app-chain.chains[$idx].sequencer.proposer=$proposer")
    if [ "$ENABLE_ANCHOR" = "1" ] && [ "$i" -eq 0 ]; then
      props+=("-Dyano.app-chain.chains[$idx].anchor.enabled=true")
      props+=("-Dyano.app-chain.chains[$idx].anchor.mode=$ANCHOR_MODE")
      if [ -z "$ANCHOR_KEY_FILE_VALUE" ]; then
        props+=("-Dyano.app-chain.chains[$idx].anchor.signing-key=$(anchor_signing_seed "$i")")
      fi
      props+=("-Dyano.app-chain.chains[$idx].anchor.every-blocks=$anchor_every")
    fi
  done
  printf '%s\n' "${props[@]}"
}

# Launch node i in the background. Node 0 is the devnet block producer; the
# rest follow it (or, with --network <net>, every node relays that network).
launch_node() {
  local n="$1" i="$2" peer_count="${3:-$1}"
  local overlay_location=""
  # Fail before creating node state if the preflight-validated file changed.
  revalidate_node_config_for_launch "$i"
  local api_key; api_key="$(effective_api_key)"
  validate_api_key "$api_key"
  local dir; dir="$(node_dir "$i")"; mkdir -p "$dir"
  local -a args=(
    "-Dquarkus.profile=${PROFILE}"
    "-Dquarkus.http.host=127.0.0.1"
    "-Dquarkus.http.port=$(http_port "$i")"
    "-Dyano.server.port=$(server_port "$i")"
    "-Dyano.storage.path=$dir/chainstate"
    # Relay source-port reuse binds every upstream dial to the node's own server
    # port — a NAT-traversal aid for real relays, but on a localhost cluster all
    # followers dialing node 0 (plus app-peers) collide on the 4-tuple and wedge
    # L1 sync. Off for cluster nodes; real relay deployments keep the default.
    "-Dyano.relay.connection.source-port-reuse=false"
    # Every cluster node shares 127.0.0.1, and each pair holds several sockets
    # (L1 chain-sync + app-peer gossip + catch-up). The default per-IP cap (5)
    # is a real-deployment guard (distinct IPs) that a localhost cluster blows
    # past around the 3rd node — node 0 then drops later followers. Raise it.
    "-Dyano.relay.connection.max-connections-per-ip=500"
  )
  # App transport: shared (default) rides protocols 100/103 on the L1 session
  # to a peer that is also the upstream; dedicated forces separate dials.
  [ -n "$TRANSPORT" ] && args+=("-Dyano.app-chain.transport.mode=$TRANSPORT")
  if [ "$NETWORK" = "devnet" ]; then
    args+=("-Dyano.genesis.shelley-genesis-file=$dir/shelley-genesis.json")
    if [ "$i" -eq 0 ] && [ -n "$DEVNET_GENESIS_TIMESTAMP_MILLIS" ]; then
      args+=("-Dyano.block-producer.genesis-timestamp=$DEVNET_GENESIS_TIMESTAMP_MILLIS")
    fi
    if [ "$i" -ne 0 ]; then
      # Follower: no block production, sync L1 from node 0's server.
      args+=("-Dyano.block-producer.enabled=false" "-Dyano.dev-mode=false"
             "-Dyano.client.enabled=true"
             "-Dyano.remote.host=localhost" "-Dyano.remote.port=$(server_port 0)")
    fi
  fi
  # App-chain multi-chain wiring (per node).
  local -a cprops=(); while IFS= read -r p; do cprops+=("$p"); done \
    < <(chain_props "$n" "$i" "$peer_count")
  args+=("${cprops[@]}")

  local log; log="$(log_file "$i")"
  # Recheck at the last practical point and derive the URI from this final
  # identity, minimizing the validation-to-open window for the child process.
  revalidate_node_config_for_launch "$i"
  overlay_location="$(combined_config_location "$i")" \
    || die "cannot select validated config locations for node $i"
  # Publish a persistent recovery fence before the child can exist. It is
  # cleared only after both validated PID records are durable.
  create_launch_fence "$i"
  if [ "$RUNTIME" = "native" ]; then
    ( cd "$YANO_HOME" || exit
      export YANO_APP_CHAIN_API_AUTH_ENABLED=false YANO_APP_CHAIN_API_KEYS="$api_key"
      # Select through the environment, never a -D selector. The optional
      # operator overlay is ordinal 275; the generated private-key overlay is
      # ordinal 350 and remains below launcher-owned system properties (400).
      [ -n "$overlay_location" ] && export QUARKUS_CONFIG_LOCATIONS="$overlay_location"
      exec "$NATIVE" "${args[@]}" ${YANO_EXTRA_ARGS:-} ) >"$log" 2>&1 &
  else
    ( cd "$YANO_HOME" || exit
      export YANO_APP_CHAIN_API_AUTH_ENABLED=false YANO_APP_CHAIN_API_KEYS="$api_key"
      [ -n "$overlay_location" ] && export QUARKUS_CONFIG_LOCATIONS="$overlay_location"
      exec java ${JAVA_OPTS:-} "${args[@]}" -jar "$JAR" ${YANO_EXTRA_ARGS:-} ) >"$log" 2>&1 &
  fi
  write_pid_record "$i" "$!"
}

wait_ready() {
  local i="$1" port; port="$(http_port "$i")"
  local deadline=$(( $(date +%s) + 180 ))
  until health_ready "$i"; do
    [ "$(date +%s)" -gt "$deadline" ] && die "node $i not ready within 180s (see $(log_file "$i"))"
    if ! managed_node_pid "$i"; then
      die "node $i exited during startup (see $(log_file "$i"))"
    fi
    if grep -qE 'Failed to initialize or auto-start Yano|Failed to start application' "$(log_file "$i")" 2>/dev/null; then
      stop_managed_node_confirmed "$i" \
        || die "node $i runtime failed and its shutdown could not be confirmed; PID records were preserved"
      die "node $i runtime failed during startup (see the first ERROR in $(log_file "$i"))"
    fi
    sleep 2
  done
}

# Non-fatal readiness poll: returns 0 when ready, 1 on timeout/exit. $2=timeout(s).
wait_ready_soft() {
  local i="$1" secs="${2:-90}" port; port="$(http_port "$i")"
  local deadline=$(( $(date +%s) + secs ))
  until health_ready "$i"; do
    [ "$(date +%s)" -gt "$deadline" ] && return 1
    managed_node_pid "$i" || return 1
    sleep 2
  done
  return 0
}

# Start a follower, tolerating the known devnet first-boot chain-sync wedge
# (a follower that connects during the producer's earliest blocks can hang
# short of readiness). Restart it by PID up to $CLUSTER_FOLLOWER_TRIES times.
start_follower_resilient() {
  local n="$1" i="$2" tries="${CLUSTER_FOLLOWER_TRIES:-3}" a=1
  while [ "$a" -le "$tries" ]; do
    launch_node "$n" "$i"
    if wait_ready_soft "$i" 90; then return 0; fi
    [ "$a" -lt "$tries" ] && printf '(retry %d) ' "$a"
    stop_managed_node_confirmed "$i" \
      || die "node $i retry aborted because shutdown could not be confirmed; PID records were preserved"
    sleep 2
    a=$(( a + 1 ))
  done
  die "node $i not ready after $tries attempts (see $(log_file "$i"))"
}

# --- Commands ----------------------------------------------------------------
cmd_start() {
  local n="${1:-3}" i indices
  [[ "$n" =~ ^[0-9]+$ && "$n" -ge 1 ]] || die "node count must be a positive integer"
  [ "$n" -le 32 ] || die "app-chain membership supports at most 32 nodes"
  resolve_runtime
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE (set YANO_HOME to a tree containing config/application-appchain.yml)"
  local -a cids=(); while IFS= read -r c; do cids+=("$c"); done < <(chain_ids)
  [ "${#cids[@]}" -ge 1 ] || die "no chains defined in $CONFIG_FILE"

  PROFILE="appchain"
  [ "$NETWORK" = "devnet" ] && PROFILE="devnet,appchain" || PROFILE="${NETWORK},appchain"

  if [ -e "$CLUSTER_DIR" ] || [ -L "$CLUSTER_DIR" ]; then
    validate_cluster_directory \
      || die "cluster data directory must be launcher-owned, non-symlink, and not group/world writable"
  fi
  indices="$(pid_indices)" \
    || die "cluster contains a malformed PID lifecycle artifact; inspect it before starting"
  for i in $indices; do
    if managed_node_pid "$i"; then
      die "node $i is already running (./cluster.sh stop first)"
    fi
    die "node $i has a stale or untrusted PID record; inspect the process and remove only that record before restarting"
  done
  validate_cluster_key_inputs "$n"
  validate_devnet_genesis_input
  # The demo anchor seed is PUBLIC (checked into the repo) — on a public
  # network anyone could sweep its address. Require a user-supplied key there.
  if [ "$ENABLE_ANCHOR" = "1" ] && [ "$NETWORK" != "devnet" ] \
      && [ -z "$ANCHOR_KEY" ] && [ -z "$ANCHOR_KEY_FILE_VALUE" ]; then
    die "anchoring on $NETWORK needs your own wallet key: use --anchor-key or" \
      "YANO_CLUSTER_ANCHOR_KEY_FILE — the default demo seed is publicly known"
  fi
  validate_api_key "$(effective_api_key)"
  validate_node_config_overlays "$n"
  resolve_cluster_ports "$n"
  if [ ! -e "$CLUSTER_DIR" ]; then
    (umask 077; mkdir -p "$CLUSTER_DIR") \
      || die "cannot create cluster data directory"
  fi
  validate_cluster_directory \
    || die "cluster data directory must be launcher-owned, non-symlink, and not group/world writable"
  ensure_cluster_identity "$n"
  prepare_private_configs "$n"

  if [ "$NETWORK" = "devnet" ]; then
    # Node 0 shifts + persists systemStart in this exact file on first boot.
    # Validate every retained identity before any JVM can mutate chain state.
    prepare_devnet_node0_genesis
    validate_retained_devnet_followers "$n"
  fi

  save_cluster_env

  c_grn "Starting $n-node app-chain cluster"
  echo  "  runtime : $RUNTIME ($([ "$RUNTIME" = native ] && echo "$NATIVE" || echo "$JAR"))"
  echo  "  home    : $YANO_HOME"
  echo  "  network : $NETWORK"
  echo  "  chains  : ${cids[*]}"
  echo  "  members : $n   threshold: ${THRESHOLD:-$(default_threshold "$n")}"
  [ "$ENABLE_ANCHOR" = "1" ] && echo "  anchor  : $ANCHOR_MODE mode (leader: node 0)"
  echo  "  data    : $CLUSTER_DIR"
  echo  "  ports   : http $HTTP_BASE-$(range_end "$HTTP_BASE" "$n")   n2n $SERVER_BASE-$(range_end "$SERVER_BASE" "$n")"
  if [ -n "$CLUSTER_API_KEY" ]; then
    echo "  admin API key: configured by YANO_CLUSTER_API_KEY"
  else
    echo "  admin API key: $LOCAL_CLUSTER_API_KEY (known local-demo key; override with YANO_CLUSTER_API_KEY)"
  fi
  echo

  printf 'node 0 (%s) ... ' "$([ "$NETWORK" = devnet ] && echo 'L1 producer + member' || echo 'relay + member')"
  launch_node "$n" 0; wait_ready 0; c_grn "ready (http $(http_port 0), n2n $(server_port 0))"

  # Let the producer build a few blocks before followers connect — avoids the
  # devnet first-boot chain-sync wedge (a follower joining during node 0's
  # earliest blocks can hang). Skipped for relay networks and single-node.
  if [ "$NETWORK" = "devnet" ] && [ "$n" -gt 1 ]; then
    local warmup="${CLUSTER_WARMUP:-25}"
    printf 'warming up producer %ss before followers join ... ' "$warmup"
    sleep "$warmup"; c_grn "go"
  fi

  for ((i=1;i<n;i++)); do
    if [ "$NETWORK" = "devnet" ]; then
      prepare_devnet_follower_genesis "$i"
    fi
    printf 'node %d (follower + member) ... ' "$i"
    start_follower_resilient "$n" "$i"; c_grn "ready (http $(http_port "$i"), n2n $(server_port "$i"))"
  done

  echo; c_grn "Cluster up."
  echo "  status : $0 status"
  echo "  submit : $0 submit ${cids[0]} <topic> <payload>"
  echo "  logs   : $0 logs <node>"
  echo "  stop   : $0 stop        (clean = stop + wipe data)"

  if [ "$ENABLE_ANCHOR" = "1" ]; then
    echo
    local aaddr
    aaddr="$(anchor_wallet_addr "${cids[0]}")"
    c_ylw "L1 anchoring ($ANCHOR_MODE mode, node 0):"
    [ -n "$aaddr" ] && echo "  anchor wallet : $aaddr" \
      || echo "  anchor wallet : (see node 0 log: 'anchoring enabled')"
    if [ "$NETWORK" = "devnet" ]; then
      if [ "$ANCHOR_MODE" = "script" ]; then
        echo "  next          : $0 anchor-bootstrap <chain>   (auto-funds via devnet faucet)"
      else
        echo "  next          : fund it via the faucet, then anchors start automatically:"
        echo "                  curl -s -X POST http://localhost:$(http_port 0)/api/v1/devnet/fund \\"
        echo "                    -H 'Content-Type: application/json' -d '{\"address\":\"$aaddr\",\"ada\":500}'"
      fi
    else
      echo "  next          : send funds (tADA/ADA) to the anchor wallet address above"
      if [ "$ANCHOR_MODE" = "script" ]; then
        echo "                  then: $0 anchor-bootstrap <chain>   (one-time, per chain)"
      else
        echo "                  anchors then start automatically (no bootstrap in metadata mode)"
      fi
    fi
  fi
}

running_node_count() {
  local n=0 i indices
  indices="$(pid_indices)" || return 1
  for i in $indices; do
    managed_node_pid "$i" && n=$((n+1))
  done
  echo "$n"
}

cmd_status() {
  [ -d "$CLUSTER_DIR" ] || die "no cluster (start one first)"
  local i any=0 indices
  declare -a roots=()
  indices="$(pid_indices)" \
    || die "cluster contains a malformed PID lifecycle artifact; inspect it before status"
  for i in $indices; do
    local port up="down"; port="$(http_port "$i")"
    managed_node_pid "$i" && up="up"
    if [ "$up" = "up" ] && curl -sf "http://localhost:$port/q/health/ready" >/dev/null 2>&1; then
      any=1
      local chains; chains="$(curl -s "http://localhost:$port/api/v1/app-chain/chains" 2>/dev/null)"
      printf 'node %d  [ready]  http %s  n2n %s\n' "$i" "$port" "$(server_port "$i")"
      printf '%s' "$chains" | jq -r '.[] | "    \(.chainId)  tip=\(.tipHeight)  root=\(.stateRoot[0:16])..."' 2>/dev/null \
        || echo "    (chains unavailable)"
    else
      if [ "$up" = "up" ]; then
        printf 'node %d  [starting/down]  http %s\n' "$i" "$port"
      else
        printf 'node %d  [stale/untrusted PID record]  http %s\n' "$i" "$port"
      fi
    fi
  done
  [ "$any" = "1" ] || c_ylw "no nodes are ready yet"
  # Cross-node root agreement per chain
  echo; echo "Consistency (per chain, roots must match across nodes):"
  local cid
  for cid in $(chain_ids); do
    local seen="" mism=0 j
    for j in $indices; do
      managed_node_pid "$j" || continue
      local r; r="$(curl -s "http://localhost:$(http_port "$j")/api/v1/app-chain/chains" 2>/dev/null \
        | jq -r --arg c "$cid" '.[]|select(.chainId==$c)|.stateRoot' 2>/dev/null)"
      [ -n "$r" ] || continue
      [ -z "$seen" ] && seen="$r"
      [ "$r" != "$seen" ] && mism=1
    done
    if [ "$mism" = "0" ] && [ -n "$seen" ]; then c_grn "  $cid: AGREED (${seen:0:16}...)"; else c_red "  $cid: MISMATCH"; fi
  done
}

cmd_submit() {
  local cid="${1:-}" topic="${2:-}" payload="${3:-}"; shift 3 2>/dev/null || true
  [ -n "$cid" ] && [ -n "$topic" ] && [ -n "$payload" ] || die "usage: $0 submit <chain-id> <topic> <payload> [--node i] [--count n]"
  local node=0 count=1
  while [ $# -gt 0 ]; do case "$1" in
    --node) node="$2"; shift 2;; --count) count="$2"; shift 2;; *) die "unknown submit option: $1";; esac; done
  local port; port="$(http_port "$node")"
  curl -sf "http://localhost:$port/q/health/ready" >/dev/null 2>&1 || die "node $node not ready"
  local k
  for ((k=1;k<=count;k++)); do
    local body="$payload"; [ "$count" -gt 1 ] && body="$payload-$k"
    local json; json="$(jq -nc --arg t "$topic" --arg b "$body" '{topic:$t,body:$b}')"
    local resp; resp="$(curl -s -X POST "http://localhost:$port/api/v1/app-chain/chains/$cid/messages" \
      -H 'Content-Type: application/json' -d "$json")"
    local mid; mid="$(printf '%s' "$resp" | jq -r '.messageId // empty' 2>/dev/null)"
    if [ -n "$mid" ]; then echo "submitted ${mid:0:16}... to $cid"; else c_red "submit failed: $resp"; return 1; fi
  done
}

cmd_kv() {
  local cid="${1:-}" op="${2:-}" key="${3:-}" value="${4:-}"; shift 4 2>/dev/null || shift $# 2>/dev/null
  [ -n "$cid" ] && [ -n "$op" ] && [ -n "$key" ] || die "usage: $0 kv <chain-id> set <key> <value> | del <key>  [--node i]"
  local node=0
  while [ $# -gt 0 ]; do case "$1" in --node) node="$2"; shift 2;; *) shift;; esac; done
  local opcode hex
  case "$op" in
    set|put|PUT) [ -n "$value" ] || die "kv set needs a value"; opcode=0; hex="$(kv_cbor 0 "$key" "$value")";;
    del|delete|DEL) opcode=1; hex="$(kv_cbor 1 "$key" "")";;
    *) die "kv op must be 'set' or 'del'";;
  esac
  local port; port="$(http_port "$node")"
  curl -sf "http://localhost:$port/q/health/ready" >/dev/null 2>&1 || die "node $node not ready"
  local json; json="$(jq -nc --arg t kv --arg h "$hex" '{topic:$t,bodyHex:$h}')"
  local resp; resp="$(curl -s -X POST "http://localhost:$port/api/v1/app-chain/chains/$cid/messages" \
    -H 'Content-Type: application/json' -d "$json")"
  local mid; mid="$(printf '%s' "$resp" | jq -r '.messageId // empty' 2>/dev/null)"
  if [ -n "$mid" ]; then echo "kv $op $key -> $cid  (${mid:0:16}...)"; else c_red "kv failed: $resp"; return 1; fi
}

normalize_member_public_key() {
  local value="${1:-}"
  [[ "$value" =~ ^[0-9a-fA-F]{64}$ ]] \
    || die "member public key must be exactly 64 hexadecimal characters"
  printf '%s' "$value" | tr 'A-F' 'a-f'
}

ready_node_indices() {
  local i indices
  indices="$(pid_indices)" || return 1
  for i in $indices; do
    managed_node_pid "$i" && health_ready "$i" && printf '%s\n' "$i"
  done
}

cmd_member_add() {
  local public_key view_node="" indices cid status members_json threshold
  local signer request accepted member_key deadline epoch_from active
  local -a identity_chains=()
  public_key="$(normalize_member_public_key "${1:-}")" || exit 1
  [ -d "$CLUSTER_DIR" ] || die "no cluster (start one first)"
  validate_cluster_directory \
    || die "cluster data directory must be launcher-owned, non-symlink, and not group/world writable"
  load_cluster_app_identity
  indices="$(ready_node_indices)" || die "cannot inspect running cluster nodes"
  [ -n "$indices" ] || die "no cluster nodes are ready"
  view_node="${indices%%$'\n'*}"
  request="$(jq -nc --arg key "$public_key" '{publicKey:$key}')"

  IFS=',' read -r -a identity_chains <<< "$IDENTITY_CHAINS"
  for cid in "${identity_chains[@]}"; do
    status="$(curl -fsS --connect-timeout 3 --max-time 10 \
      "http://localhost:$(http_port "$view_node")/api/v1/app-chain/chains/$cid/status")" \
      || die "cannot read '$cid' status from node $view_node"
    [ "$(printf '%s' "$status" | jq -r '.membershipMode // "static"')" = "governed" ] \
      || die "chain '$cid' uses static membership; configure membership.mode=governed before bootstrap"
    members_json="$(api_curl -fsS \
      "http://localhost:$(http_port "$view_node")/api/v1/app-chain/chains/$cid/admin/members")" \
      || die "cannot read '$cid' membership"
    threshold="$(printf '%s' "$status" | jq -r '.membershipActiveThreshold // 0')"
    [[ "$threshold" =~ ^[0-9]+$ ]] && [ "$threshold" -ge 1 ] \
      || die "chain '$cid' returned an invalid membership threshold"
    if printf '%s' "$members_json" | jq -e --arg key "$public_key" \
        '.members | index($key) != null' >/dev/null; then
      epoch_from="$(printf '%s' "$status" | jq -r '.membershipEpochFromHeight // 0')"
      active="$(printf '%s' "$status" | jq -r '.membershipEpochActive // false')"
      c_ylw "member already scheduled/active on $cid" \
        "(epoch from height $epoch_from, active-for-next-block=$active): $public_key"
      continue
    fi

    accepted=0
    local seen_signers="," node_status
    for signer in $indices; do
      node_status="$(curl -fsS --connect-timeout 3 --max-time 10 \
        "http://localhost:$(http_port "$signer")/api/v1/app-chain/chains/$cid/status")" \
        || continue
      [ "$(printf '%s' "$node_status" | jq -r '.memberActiveForNextBlock // false')" = "true" ] \
        || continue
      member_key="$(printf '%s' "$node_status" | jq -r '.memberKey // empty')"
      [[ "$member_key" =~ ^[0-9a-f]{64}$ ]] || continue
      case "$seen_signers" in *",$member_key,"*) continue;; esac
      api_curl -fsS -X POST \
        "http://localhost:$(http_port "$signer")/api/v1/app-chain/chains/$cid/admin/members/add" \
        -H 'Content-Type: application/json' -d "$request" >/dev/null \
        || die "member approval failed on '$cid' through node $signer after $accepted/$threshold approvals"
      seen_signers+="$member_key,"
      accepted=$((accepted + 1))
      printf 'membership approval %d/%d for %s via node %d\n' \
        "$accepted" "$threshold" "$cid" "$signer"
      [ "$accepted" -ge "$threshold" ] && break
    done
    [ "$accepted" -ge "$threshold" ] \
      || die "chain '$cid' needs $threshold ready current members; only $accepted approved"

    deadline=$(( $(date +%s) + 120 ))
    while :; do
      members_json="$(api_curl -fsS \
        "http://localhost:$(http_port "$view_node")/api/v1/app-chain/chains/$cid/admin/members")" \
        || members_json=""
      if [ -n "$members_json" ] && printf '%s' "$members_json" | jq -e --arg key "$public_key" \
          '.members | index($key) != null' >/dev/null; then
        break
      fi
      [ "$(date +%s)" -le "$deadline" ] \
        || die "timed out waiting for governed member epoch on '$cid'"
      sleep 1
    done
    status="$(curl -fsS --connect-timeout 3 --max-time 10 \
      "http://localhost:$(http_port "$view_node")/api/v1/app-chain/chains/$cid/status")" \
      || die "cannot read '$cid' membership activation status"
    epoch_from="$(printf '%s' "$status" | jq -r '.membershipEpochFromHeight // 0')"
    active="$(printf '%s' "$status" | jq -r '.membershipEpochActive // false')"
    c_grn "member epoch recorded on $cid (from height $epoch_from, active-for-next-block=$active)"
  done
}

wait_joined_node_catchup() {
  local node="$1" reference="$2" deadline cid reference_view joined_view
  local reference_tip joined_tip reference_root joined_root caught
  local -a identity_chains=()
  deadline=$(( $(date +%s) + 180 ))
  while :; do
    caught=1
    IFS=',' read -r -a identity_chains <<< "$IDENTITY_CHAINS"
    for cid in "${identity_chains[@]}"; do
      reference_view="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://localhost:$(http_port "$reference")/api/v1/app-chain/chains/$cid/status" 2>/dev/null)" \
        || { caught=0; break; }
      joined_view="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://localhost:$(http_port "$node")/api/v1/app-chain/chains/$cid/status" 2>/dev/null)" \
        || { caught=0; break; }
      reference_tip="$(printf '%s' "$reference_view" | jq -r '.tipHeight // -1')"
      joined_tip="$(printf '%s' "$joined_view" | jq -r '.tipHeight // -1')"
      reference_root="$(printf '%s' "$reference_view" | jq -r '.stateRoot // empty')"
      joined_root="$(printf '%s' "$joined_view" | jq -r '.stateRoot // empty')"
      if [ "$joined_tip" != "$reference_tip" ] || [ -z "$reference_root" ] \
          || [ "$joined_root" != "$reference_root" ]; then
        caught=0
        break
      fi
    done
    [ "$caught" -eq 0 ] || return 0
    [ "$(date +%s)" -le "$deadline" ] || return 1
    sleep 2
  done
}

cmd_node_join() {
  local index="${1:-}" first_ready existing_indices expected_members public_key j
  [[ "$index" =~ ^[0-9]+$ ]] || die "node index must be a non-negative integer"
  index=$((10#$index))
  [ "$index" -le 31 ] || die "app-chain membership supports node indices 0..31"
  [ -d "$CLUSTER_DIR" ] || die "no cluster (start one first)"
  validate_cluster_directory \
    || die "cluster data directory must be launcher-owned, non-symlink, and not group/world writable"
  load_cluster_app_identity
  [ "$index" -ge "$IDENTITY_MEMBER_COUNT" ] \
    || die "node $index is a bootstrap member; use start/restart rather than join"
  for ((j=IDENTITY_MEMBER_COUNT;j<index;j++)); do
    [ -d "$(node_dir "$j")" ] \
      || die "join nodes in index order; node $j has not been staged"
  done
  if managed_node_pid "$index"; then
    die "node $index is already running"
  fi
  node_record_artifacts_exist "$index" \
    && die "node $index has an incomplete or stale launcher record; inspect it before joining"
  resolve_runtime
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE"
  validate_cluster_key_inputs "$((index + 1))"
  expected_members="$(members_csv "$IDENTITY_MEMBER_COUNT")"
  [ "$expected_members" = "$IDENTITY_MEMBERS" ] \
    || die "configured demo/member-key identities differ from the retained bootstrap marker"
  if [ -n "$THRESHOLD" ] && [ "$THRESHOLD" != "$IDENTITY_THRESHOLD" ]; then
    die "--threshold differs from the retained bootstrap threshold"
  fi
  THRESHOLD="$IDENTITY_THRESHOLD"
  validate_node_config_overlays "$((index + 1))"
  public_key="$(node_pub "$index")"
  case ",$IDENTITY_MEMBERS," in *",$public_key,"*)
    die "node $index key is already part of the immutable bootstrap membership";;
  esac

  HTTP_BASE="$(validate_port_base "$HTTP_BASE" "$((index + 1))" "HTTP base")" || exit 1
  SERVER_BASE="$(validate_port_base "$SERVER_BASE" "$((index + 1))" "server base")" || exit 1
  port_is_busy "$(http_port "$index")" \
    && die "node $index HTTP port $(http_port "$index") is busy"
  port_is_busy "$(server_port "$index")" \
    && die "node $index n2n port $(server_port "$index") is busy"
  [ "$(http_port "$index")" != "$(server_port "$index")" ] \
    || die "node $index HTTP and n2n ports overlap"

  existing_indices="$(ready_node_indices)" || die "cannot inspect running cluster nodes"
  [ -n "$existing_indices" ] || die "no existing cluster node is ready"
  first_ready="${existing_indices%%$'\n'*}"

  # Governance is recorded before the new signer starts. Existing members keep
  # the old threshold, so liveness is unchanged while the new node catches up.
  cmd_member_add "$public_key"

  PROFILE="appchain"
  [ "$NETWORK" = "devnet" ] && PROFILE="devnet,appchain" || PROFILE="${NETWORK},appchain"
  if [ "$NETWORK" = "devnet" ]; then
    prepare_devnet_follower_genesis "$index"
  fi
  prepare_private_config_for_node "$index"
  printf 'node %d (governed joiner) ... ' "$index"
  launch_node "$IDENTITY_MEMBER_COUNT" "$index" "$((index + 1))"
  wait_ready "$index"
  c_grn "ready (http $(http_port "$index"), n2n $(server_port "$index"))"
  printf 'catching up governed history and state roots ... '
  if wait_joined_node_catchup "$index" "$first_ready"; then
    c_grn "agreed"
  else
    stop_managed_node_confirmed "$index" >/dev/null 2>&1 || true
    die "node $index did not converge with node $first_ready within 180s"
  fi
  c_grn "node $index joined and caught up with member key $public_key"
  echo "membership voting activates at the governed per-chain heights reported above"
}

cbor_head() {
  local major="$1" length="$2"
  [ "$length" -ge 0 ] && [ "$length" -le 65535 ] \
    || die "demo CBOR value exceeds 65535 bytes"
  if [ "$length" -lt 24 ]; then
    printf '%02x' "$(( major * 32 + length ))"
  elif [ "$length" -le 255 ]; then
    printf '%02x%02x' "$(( major * 32 + 24 ))" "$length"
  else
    printf '%02x%04x' "$(( major * 32 + 25 ))" "$length"
  fi
}

cbor_text() {
  local value="$1" length
  length="$(LC_ALL=C printf '%s' "$value" | wc -c | tr -d ' ')"
  printf '%s%s' "$(cbor_head 3 "$length")" "$(_hex_of "$value")"
}

cbor_bytes() {
  local value="$1" length
  length="$(LC_ALL=C printf '%s' "$value" | wc -c | tr -d ' ')"
  printf '%s%s' "$(cbor_head 2 "$length")" "$(_hex_of "$value")"
}

submit_hex_message() {
  local cid="$1" topic="$2" body_hex="$3" node="${4:-0}" request response
  request="$(jq -nc --arg topic "$topic" --arg bodyHex "$body_hex" \
    '{topic:$topic,bodyHex:$bodyHex}')"
  response="$(curl -fsS --connect-timeout 5 --max-time 30 -X POST \
    "http://localhost:$(http_port "$node")/api/v1/app-chain/chains/$cid/messages" \
    -H 'Content-Type: application/json' -d "$request")" \
    || die "message submission failed on '$cid'"
  printf '%s' "$response" | jq -e -r '.messageId' >/dev/null \
    || die "message submission on '$cid' returned no messageId"
}

cmd_effect_demo() {
  local cid="effects-chain" node=0 item payload propose approve deadline effects effect status
  local worker claim_request claim_response claimed_effect height ordinal external_ref
  local detail proof payload_text before_tip from_height message="hello from Yano effects"
  [ "$#" -le 1 ] || die "usage: $0 effect demo [\"message\"]"
  [ "$#" -eq 0 ] || message="$1"
  health_ready "$node" || die "node 0 is not ready"
  status="$(curl -fsS --connect-timeout 3 --max-time 10 \
    "http://localhost:$(http_port "$node")/api/v1/app-chain/chains/$cid/status")" \
    || die "cannot inspect '$cid'"
  printf '%s' "$status" | jq -e '.effects.enabled == true' >/dev/null \
    || die "'$cid' is missing or effects are not enabled in the default demo config"
  before_tip="$(printf '%s' "$status" | jq -r '.tipHeight // 0')"
  [[ "$before_tip" =~ ^[0-9]+$ ]] || die "'$cid' returned an invalid tip height"
  from_height=$((before_tip + 1))

  item="demo-$(date +%s)-$$"
  payload="$(jq -nc --arg item "$item" --arg message "$message" \
    '{event:"demo.effect.requested",itemId:$item,message:$message}')"
  propose="85"'00'"$(cbor_text "$item")""$(cbor_bytes "$payload")"'01''00'
  approve="82"'01'"$(cbor_text "$item")"
  submit_hex_message "$cid" demo-effect "$propose" "$node"
  submit_hex_message "$cid" demo-effect "$approve" "$node"

  deadline=$(( $(date +%s) + 120 ))
  effect=""
  while [ -z "$effect" ]; do
    effects="$(curl -fsS --connect-timeout 3 --max-time 10 \
      "http://localhost:$(http_port "$node")/api/v1/app-chain/chains/$cid/effects?fromHeight=$from_height&limit=100")" \
      || effects='{"effects":[]}'
    effect="$(printf '%s' "$effects" | jq -c --arg scope "approvals/$item" \
      '.effects[] | select(.scope == $scope and .type == "demo.webhook")' | tail -1)"
    [ -n "$effect" ] && break
    [ "$(date +%s)" -le "$deadline" ] || die "timed out waiting for the demo effect"
    sleep 1
  done
  height="$(printf '%s' "$effect" | jq -r '.height')"
  ordinal="$(printf '%s' "$effect" | jq -r '.ordinal')"
  c_grn "Effect emitted       $cid height=$height ordinal=$ordinal"
  echo  "Effect type          demo.webhook"

  worker="cluster-demo-worker-$item"
  external_ref=""
  while [ -z "$external_ref" ]; do
    claim_request="$(jq -nc --arg id "$worker" \
      '{executorId:$id,types:["demo.webhook"],max:32,leaseSeconds:60}')"
    claim_response="$(api_curl -fsS -X POST \
      "http://localhost:$(http_port "$node")/api/v1/app-chain/chains/$cid/effects/claim" \
      -H 'Content-Type: application/json' -d "$claim_request")" \
      || die "external demo worker could not claim effects"
    while IFS= read -r claimed_effect; do
      [ -n "$claimed_effect" ] || continue
      local claimed_height claimed_ordinal claimed_id claimed_scope claimed_ref claimed_report
      claimed_height="$(printf '%s' "$claimed_effect" | jq -r '.height')"
      claimed_ordinal="$(printf '%s' "$claimed_effect" | jq -r '.ordinal')"
      claimed_id="$(printf '%s' "$claimed_effect" | jq -r '.effectId')"
      claimed_scope="$(printf '%s' "$claimed_effect" | jq -r '.scope')"
      claimed_ref="demo-worker://confirmed/$claimed_id"
      claimed_report="$(jq -nc --arg id "$worker" --arg ref "$(_hex_of "$claimed_ref")" \
        '{executorId:$id,success:true,externalRefHex:$ref}')"
      api_curl -fsS -X POST \
        "http://localhost:$(http_port "$node")/api/v1/app-chain/chains/$cid/effects/$claimed_height/$claimed_ordinal/report" \
        -H 'Content-Type: application/json' -d "$claimed_report" >/dev/null \
        || die "external demo worker could not report effect $claimed_id"
      if [ "$claimed_scope" = "approvals/$item" ]; then
        external_ref="$claimed_ref"
        payload_text="$(printf '%s' "$claimed_effect" | jq -r '.payloadHex' \
          | python3 -c 'import sys; print(bytes.fromhex(sys.stdin.read().strip()).decode("utf-8"))')" \
          || die "demo effect payload was not UTF-8"
      fi
    done < <(printf '%s' "$claim_response" | jq -c '.effects[]')
    if [ -z "$external_ref" ]; then
      [ "$(date +%s)" -le "$deadline" ] || die "timed out claiming the demo effect"
      sleep 1
    fi
  done

  while :; do
    detail="$(api_curl -fsS \
      "http://localhost:$(http_port "$node")/api/v1/app-chain/chains/$cid/effects/$height/$ordinal")" \
      || detail=""
    [ "$(printf '%s' "$detail" | jq -r '.execution.status // empty')" = "DONE" ] && break
    [ "$(date +%s)" -le "$deadline" ] || die "timed out waiting for demo effect completion"
    sleep 1
  done
  proof="$(curl -fsS --connect-timeout 3 --max-time 10 \
    "http://localhost:$(http_port "$node")/api/v1/app-chain/chains/$cid/effects/$height/$ordinal/proof")" \
    || die "demo effect proof is unavailable"
  printf '%s' "$proof" | jq -e '.effectHashHex and .effectsRootHex and .stateRootHex and .stateProofWireHex' \
    >/dev/null || die "demo effect proof is incomplete"
  echo  "Executor             external demo worker on node 0"
  c_grn "Delivery             CONFIRMED"
  echo  "External reference   $external_ref"
  echo  "Attempts              $(printf '%s' "$detail" | jq -r '.execution.attempts')"
  c_grn "Proof                 AVAILABLE ($(printf '%s' "$proof" | jq -r '.effectHashHex[0:16]')...)"
  echo  "Captured payload      $payload_text"
}

cmd_member() {
  case "${1:-}" in
    add) cmd_member_add "${2:-}";;
    *) die "usage: $0 member add <public-key>";;
  esac
}

cmd_node() {
  case "${1:-}" in
    join) cmd_node_join "${2:-}";;
    *) die "usage: $0 node join <index>";;
  esac
}

cmd_effect() {
  case "${1:-}" in
    demo) shift; cmd_effect_demo "$@";;
    *) die "usage: $0 effect demo [\"message\"]";;
  esac
}

cmd_anchor_bootstrap() {
  local cid="${1:-}"; [ -n "$cid" ] || die "usage: $0 anchor-bootstrap <chain-id>"
  load_cluster_env
  [ "$ANCHOR_MODE" = "script" ] || die "cluster runs anchor mode '$ANCHOR_MODE' — bootstrap only applies to script anchors (metadata mode needs none: fund the wallet and anchors start automatically)"
  local port; port="$(http_port 0)"
  # Learn the wallet address; on devnet fund it from the faucet first — on a
  # public network the wallet must already hold funds (sent externally).
  local addr; addr="$(anchor_wallet_addr "$cid")"
  [ -n "$addr" ] || die "no anchor wallet on '$cid' (start with --anchor / --anchor-mode script)"
  if [ "$NETWORK" = "devnet" ]; then
    c_ylw "Funding anchor wallet via devnet faucet + bootstrapping the script anchor for '$cid'..."
    curl -s --connect-timeout 3 --max-time 30 -X POST \
      "http://localhost:$port/api/v1/devnet/fund" -H 'Content-Type: application/json' \
      -d "{\"address\":\"$addr\",\"ada\":500}" >/dev/null
    sleep 6
  else
    c_ylw "Bootstrapping the script anchor for '$cid' on $NETWORK..."
    echo "(anchor wallet $addr must already be funded — if bootstrap fails with"
    echo " 'No usable UTxO', send tADA/ADA there and re-run; a just-sent tx may"
    echo " also need a minute to land in the node's UTxO view)"
  fi
  api_curl -fsS -X POST "http://localhost:$port/api/v1/app-chain/chains/$cid/admin/anchor/bootstrap" | python3 -m json.tool 2>/dev/null \
    || die "bootstrap failed (check YANO_CLUSTER_API_KEY and node log)"
}

cmd_logs() {
  local i="${1:-0}" follow=""; [ "${2:-}" = "-f" ] && follow="-f"
  local f; f="$(log_file "$i")"; [ -f "$f" ] || die "no log for node $i"
  tail $follow -n 60 "$f"
}

cmd_keys() {
  local n="${1:-3}" i
  echo "node  seed(hex, 32 bytes)                                              member-pubkey"
  for ((i=0;i<n;i++)); do printf '%-4d  %s  %s\n' "$i" "$(node_seed "$i")" "$(node_pub "$i")"; done
}

cmd_chains() {
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE"
  echo "Chains defined in $CONFIG_FILE:"; chain_ids | sed 's/^/  - /'
}

cmd_stop() {
  local wipe="${1:-}"
  local i killed=0 indices="" remaining="" unsafe=0
  indices="$(pid_indices)" || {
    c_red "error: malformed PID lifecycle state; inspect it before stop/clean" >&2
    return 1
  }
  for i in $indices; do
    if stop_managed_node_confirmed "$i"; then
      killed=$((killed+1))
    else
      c_ylw "node $i has an incomplete/untrusted record or could not be confirmed stopped"
      unsafe=1
    fi
  done
  # A launch fence, metadata-only record, or publication temporary is a
  # recovery fence too. Never report success while any such artifact remains.
  if ! remaining="$(pid_indices)"; then
    unsafe=1
  elif [ -n "$remaining" ]; then
    unsafe=1
  fi
  c_grn "stopped $killed node(s)"
  if [ "$unsafe" -ne 0 ]; then
    c_red "error: one or more PID records were untrusted or still live; inspect them before releasing ownership or deleting state" >&2
    return 1
  fi
  if [ "$wipe" = "wipe" ]; then
    remaining="$(pid_indices)" \
      || { c_red "error: refusing to wipe malformed PID lifecycle state" >&2; return 1; }
    [ -z "$remaining" ] \
      || { c_red "error: refusing to wipe data while PID records remain" >&2; return 1; }
    validate_cluster_directory \
      || { c_red "error: refusing to wipe an unsafe cluster data directory" >&2; return 1; }
    rm -rf "$CLUSTER_DIR" || return 1
    c_grn "wiped $CLUSTER_DIR"
  fi
}

usage() {
  cat <<EOF
Yano app-chain cluster launcher

Usage:
  $0 start [N] [options]        start an N-node cluster (default N=3)
  $0 status                     health + per-chain tips/roots + consistency
  $0 node join <index>          govern, start, and catch up one later member
  $0 member add <public-key>    governance-only add across configured chains
  $0 effect demo ["message"]    emit, execute, and prove a demo effect
  $0 submit <chain> <topic> <payload> [--node i] [--count n]
  $0 kv <chain> set <key> <value> [--node i]   put into a kv-registry chain
  $0 kv <chain> del <key> [--node i]           delete from a kv-registry chain
  $0 loadtest <chain> [-n M] [-c C] [--spread]  parallel throughput test
  $0 anchor-bootstrap <chain>   bootstrap a script anchor (one-time; devnet auto-funds)
  $0 logs <node> [-f]           show/tail a node log
  $0 keys [N]                   print derived member seeds + pubkeys
  $0 chains                     list chains from the config
  $0 stop                       stop nodes (keep data)
  $0 clean                      stop nodes + wipe data
  $0 help

start options:
  --network <net>    devnet (default) | preprod | preview | mainnet | sanchonet
                     devnet: node 0 produces L1, others follow. A public network:
                     every node relays that network (needs sync; see README).
  --jar | --native   force runtime (default: auto-detect the available build)
  --threshold <t>    finality threshold (default: majority = N/2 + 1)
  --transport <m>    app transport: shared (default — app protocols ride the
                     L1 session to the upstream peer; one TCP connection per
                     peer pair) | dedicated (separate app connections, e.g.
                     for bandwidth isolation)
  --anchor           enable L1 anchoring on every chain, script mode (node 0
                     is the anchor leader; followers need no anchor config)
  --anchor-mode <m>  metadata | script (implies --anchor).
                     metadata: plain tx with the anchor in tx metadata — just
                       fund the wallet, no bootstrap, works on any network.
                     script: Plutus V3 thread-NFT + threshold co-signed
                       advances — one-time 'anchor-bootstrap <chain>' per chain.
  --anchor-key <hex> anchor wallet key (32-byte Ed25519 seed, 64 hex chars).
                     Default: a deterministic demo seed. On a public network
                     pass your own (or use YANO_CLUSTER_ANCHOR_KEY_FILE) and
                     fund the enterprise address the node prints (a CIP-1852
                     wallet mnemonic can NOT be converted to this seed —
                     generate one: openssl rand -hex 32).
  --anchor-every <n> anchor cadence in app blocks (default: 2 devnet, 30 public)
  --data-dir <dir>   cluster data/logs dir (default: $CLUSTER_DIR)
  --http-base <p>    node i HTTP port = p + i. The default ($HTTP_BASE) moves
                     automatically to a free contiguous range when occupied;
                     an explicit value is strict and fails when busy.
  --server-base <p>  node i n2n port = p + i. The default ($SERVER_BASE) moves
                     automatically; an explicit value is strict.

Chains come from \$YANO_HOME/config/application-appchain.yml — edit it to add/
remove app chains or change their state machine / sequencer. See ./README.md.

Environment (run a RELEASED build with no local compile):
  YANO_HOME     tree holding config/ (application-appchain.yml + network
                genesis). Nodes launch with cwd=HOME. Default: the repo's app/.
  YANO_JAR      path to a yano uber-jar (overrides auto-detect; any location).
  YANO_NATIVE   path to a yano native binary (overrides auto-detect).
  YANO_CLUSTER_API_KEY
                full key for privileged cluster operations. Reads and
                submissions remain public on the loopback-only demo API.
                Default: $LOCAL_CLUSTER_API_KEY (local demo only).
  YANO_CLUSTER_NODE_CONFIG_DIR
                optional directory containing exactly node0.properties ...
                node<N-1>.properties. Files must be regular, non-symlink,
                owned/readable only by the launcher user (chmod 600), and
                contain exactly one literal config_ordinal=275 line. Their
                contents are loaded as per-node Quarkus config overlays.
  YANO_CLUSTER_MEMBER_KEY_DIR
                optional owner-only directory (chmod 700) containing
                node0.seed + node0.public ... node<N-1>.seed +
                node<N-1>.public. Every file must be launcher-owned, regular,
                non-symlink, chmod 400/600, and contain one 64-hex value.
                Public keys must derive from their paired seeds and be unique.
                Supplied seeds are loaded through generated private config,
                never Java/native process arguments.
                When unset, the deterministic demo identities are unchanged.
                The 'keys' command always prints those demo identities; it
                never reads this production-key directory.
  YANO_CLUSTER_ANCHOR_KEY_FILE
                optional launcher-owned, regular, non-symlink chmod 400/600
                file containing one 64-hex anchor seed. Its parent directory
                must be owner-only. Use with --anchor; on public networks it
                replaces --anchor-key. Configuring both is an error. The seed
                is loaded through generated private config, not process argv.
  YANO_CLUSTER_PRIVATE_CONFIG_DIR
                optional owner-only output directory for generated per-node
                private config overlays. It must be outside the cluster data
                directory. Recommended for managed demos: place it under the
                secret root. Default: <data-dir>/private-config (standalone
                compatibility; removed by 'clean').
  YANO_CLUSTER_DEVNET_GENESIS_FILE
                optional launcher-owned, regular, non-symlink devnet Shelley
                genesis. The file must be valid bounded JSON and neither it
                nor its parent may be writable by other users. On a fresh
                cluster its exact bytes become node 0's identity and are
                copied to followers. Retained genesis files are never
                overwritten. Supported only with --network devnet.
  YANO_CLUSTER_APPCHAIN_IDENTITY_MARKER
                optional orchestrator-owned canonical app-chain identity JSON
                when app-chain state is stored separately from retained L1
                state. The selected network, chains, members, threshold,
                proposer, and anchor identity must match. The marker cannot
                replace a standalone cluster-appchain-identity.json.
  Examples:
    # local dev (auto): uses app/build/yano.jar + app/config
    ./cluster.sh start 3
    # released tree: /opt/yano/{yano.jar, config/...}
    YANO_HOME=/opt/yano ./cluster.sh start 3
    # binary and config in different places
    YANO_HOME=/data/yano YANO_JAR=/downloads/yano.jar ./cluster.sh start 3
  (loadtest.sh / soaktest.sh need no binary — they just hit the running
  cluster's HTTP ports, so they work against any running Yano.)
EOF
}

# --- Arg parsing -------------------------------------------------------------
[ $# -ge 1 ] || { usage; exit 1; }
CMD="$1"; shift || true

# Global options may appear after the subcommand's positionals; pull them out.
POS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --network)      NETWORK="$2"; NETWORK_EXPLICIT=1; shift 2;;
    --jar)          RUNTIME="jar"; shift;;
    --native)       RUNTIME="native"; shift;;
    --threshold)    THRESHOLD="$2"; shift 2;;
    --transport)    TRANSPORT="$2"; shift 2
                    case "$TRANSPORT" in shared|dedicated) ;; *) die "--transport must be shared or dedicated";; esac;;
    --anchor)       ENABLE_ANCHOR=1; shift;;
    --anchor-mode)  ANCHOR_MODE="$2"; ENABLE_ANCHOR=1; shift 2
                    case "$ANCHOR_MODE" in metadata|script) ;; *) die "--anchor-mode must be metadata or script";; esac;;
    --anchor-key)   ANCHOR_KEY="$2"; ENABLE_ANCHOR=1; shift 2;;
    --anchor-every) ANCHOR_EVERY="$2"; shift 2;;
    --data-dir)     CLUSTER_DIR="$2"; shift 2;;
    --http-base)    HTTP_BASE="$2"; HTTP_BASE_EXPLICIT=1; shift 2;;
    --server-base)  SERVER_BASE="$2"; SERVER_BASE_EXPLICIT=1; shift 2;;
    *)              POS+=("$1"); shift;;
  esac
done
set -- "${POS[@]:-}"

# Cluster-dependent commands discover the selected ports and anchor settings
# from the start invocation. CLI/environment port overrides remain authoritative.
case "$CMD" in
  start|keys|chains|help|-h|--help) ;;
  *) load_cluster_env;;
esac

case "$CMD" in
  start)             cmd_start "${1:-3}";;
  status)            cmd_status;;
  node)              cmd_node "$@";;
  member)            cmd_member "$@";;
  effect)            cmd_effect "$@";;
  submit)            cmd_submit "$@";;
  kv)                cmd_kv "$@";;
  loadtest)          YANO_CLUSTER_HTTP_BASE="$HTTP_BASE" exec "$SCRIPT_DIR/loadtest.sh" "$@";;
  anchor-bootstrap)  cmd_anchor_bootstrap "${1:-}";;
  logs)              cmd_logs "${1:-0}" "${2:-}";;
  keys)              cmd_keys "${1:-3}";;
  chains)            cmd_chains;;
  stop)              cmd_stop;;
  clean)             cmd_stop wipe;;
  help|-h|--help)    usage;;
  *)                 usage; exit 1;;
esac
