#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CLI="$ROOT/profile-governance.py"
OUT="$(python3 "$CLI" --chain chain-a begin --encode-only \
  --proposal-id "$(printf '01%.0s' {1..32})" \
  --base-digest "$(printf '02%.0s' {1..32})" \
  --membership-digest "$(printf '03%.0s' {1..32})" \
  --target-digest "$(printf '04%.0s' {1..32})" \
  --total-bytes 1024 --chunk-count 2 \
  --activation-height 50 --expiry-height 100)"

EXPECTED_BODY="8a01015820$(printf '01%.0s' {1..32})5820$(printf '02%.0s' {1..32})5820$(printf '03%.0s' {1..32})5820$(printf '04%.0s' {1..32})1904000218321864"
python3 - "$OUT" "$EXPECTED_BODY" <<'PY'
import json
import sys

actual = json.loads(sys.argv[1])
assert actual["bodyHex"] == sys.argv[2]
assert actual["proposalHash"] == "0a20f6b0edbb94139eaf12a0cc91aaed70975ab0faeffe9d42a4ffdcdc556c25"
PY

python3 - "$CLI" <<'PY'
import importlib.util
import sys
import urllib.error

spec = importlib.util.spec_from_file_location("profile_governance", sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
try:
    module.NoRedirectHandler().redirect_request(
        type("Request", (), {"full_url": "http://member-a:7070/api"})(),
        None, 302, "Found", {}, "https://attacker.example/")
    raise AssertionError("redirect handler accepted a privileged redirect")
except urllib.error.HTTPError as error:
    assert error.code == 302
    assert str(error.reason) == "redirect refused"
PY

if python3 "$CLI" --chain chain-a begin --encode-only \
  --proposal-id "$(printf '01%.0s' {1..32})" \
  --base-digest "$(printf '02%.0s' {1..32})" \
  --target-digest "$(printf '04%.0s' {1..32})" \
  --total-bytes 1024 --chunk-count 2 \
  --activation-height 50 --expiry-height 100 >/dev/null 2>&1; then
  echo "FAIL: offline begin accepted no membership digest" >&2
  exit 1
fi

echo "PASS: profile governance CLI canonical vectors"
