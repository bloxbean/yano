#!/usr/bin/env bash
# Build amaru_validator.wasm (ADR-057 §1) and check it.
#
#   scripts/build-wasm.sh
#
# Output: target/wasm-out/amaru_validator.wasm and amaru_validator.wasm.sha256
# (under $CARGO_TARGET_DIR instead of target/ when that is set).
#
# C dependencies (blst, secp256k1-sys) need a C compiler that targets wasm32-wasip1; Apple clang
# cannot. The script picks, in order:
#   1. wasi-sdk, when WASI_SDK_PATH is set (CI, Linux; also works on macOS);
#   2. zig (`$ZIG`, else `zig` on PATH), through scripts/zig-cc-wasm32 and scripts/zig-ar.
# Other inputs: WASM_OPT (default `wasm-opt`, binaryen), PYTHON (default `python3`).
set -euo pipefail

CRATE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET=wasm32-wasip1
TARGET_DIR="${CARGO_TARGET_DIR:-$CRATE_DIR/target}"
OUT_DIR="$TARGET_DIR/wasm-out"
WASM_NAME=amaru_validator.wasm
WASM_OPT="${WASM_OPT:-wasm-opt}"
PYTHON="${PYTHON:-python3}"

cd "$CRATE_DIR"

if [[ -n "${WASI_SDK_PATH:-}" ]]; then
  [[ -x "$WASI_SDK_PATH/bin/clang" ]] || { echo "WASI_SDK_PATH=$WASI_SDK_PATH has no bin/clang" >&2; exit 1; }
  export CC_wasm32_wasip1="$WASI_SDK_PATH/bin/clang"
  export AR_wasm32_wasip1="$WASI_SDK_PATH/bin/llvm-ar"
  export CFLAGS_wasm32_wasip1="--sysroot=$WASI_SDK_PATH/share/wasi-sysroot"
  echo "C toolchain: wasi-sdk at $WASI_SDK_PATH"
else
  ZIG="${ZIG:-$(command -v zig || true)}"
  [[ -n "$ZIG" && -x "$ZIG" ]] || {
    echo "No wasm C toolchain: set WASI_SDK_PATH (wasi-sdk) or ZIG / put zig on PATH" >&2
    exit 1
  }
  export ZIG
  export CC_wasm32_wasip1="$CRATE_DIR/scripts/zig-cc-wasm32"
  export AR_wasm32_wasip1="$CRATE_DIR/scripts/zig-ar"
  export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$TARGET_DIR/zig-cache}"
  echo "C toolchain: zig $("$ZIG" version) at $ZIG"
fi

command -v "$WASM_OPT" >/dev/null || { echo "wasm-opt not found (set WASM_OPT)" >&2; exit 1; }

echo "rustc: $(rustc --version)"
cargo build --release --locked --target "$TARGET"

mkdir -p "$OUT_DIR"
RAW="$TARGET_DIR/$TARGET/release/$WASM_NAME"
"$WASM_OPT" -O3 "$RAW" -o "$OUT_DIR/$WASM_NAME"
echo "wasm-opt: $("$WASM_OPT" --version)"
printf 'raw: %s bytes, optimised: %s bytes\n' "$(wc -c < "$RAW" | tr -d ' ')" "$(wc -c < "$OUT_DIR/$WASM_NAME" | tr -d ' ')"

"$PYTHON" "$CRATE_DIR/scripts/wasm_check.py" "$OUT_DIR/$WASM_NAME"

cd "$OUT_DIR"
if command -v sha256sum >/dev/null; then
  sha256sum "$WASM_NAME" > "$WASM_NAME.sha256"
else
  shasum -a 256 "$WASM_NAME" > "$WASM_NAME.sha256"
fi
cat "$WASM_NAME.sha256"
