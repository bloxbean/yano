#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOL="$SCRIPT_DIR/../tools/anchor_binding.py"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-anchor-binding.XXXXXX")"
TMP="$(cd "$TMP" && pwd -P)"
trap 'rm -rf "$TMP"' EXIT
chmod 700 "$TMP"

STATUS_DIR="$TMP/status"
BINDING="$TMP/anchor-binding.json"
mkdir "$STATUS_DIR"
chmod 700 "$STATUS_DIR"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}

common_args=(--binding "$BINDING" --network devnet --instance binding-test \
  --deployment compose --chain-id evidence-chain-binding-test \
  --state-machine evidence-registry)
MEMBER_0="$(python3 -c 'print("aa" * 32)')"
MEMBER_1="$(python3 -c 'print("bb" * 32)')"
MEMBER_2="$(python3 -c 'print("cc" * 32)')"
MEMBER_KEYS="$MEMBER_0,$MEMBER_1,$MEMBER_2"
member_args=(--member-keys "$MEMBER_KEYS")
status_args=(--status "$STATUS_DIR/node0.json" --status "$STATUS_DIR/node1.json" \
  --status "$STATUS_DIR/node2.json")

write_statuses() {
  python3 - "$STATUS_DIR" "$1" <<'PY'
import json
import os
from pathlib import Path
import sys

directory = Path(sys.argv[1])
mode = sys.argv[2]
policy = "11" * 28
script = "22" * 28
address = "addr_test1" + "q" * 40
member_keys = ["aa" * 32, "bb" * 32, "cc" * 32]

def anchor(leader, height, script_hash=script):
    return {
        "enabled": True,
        "mode": "script",
        "leader": leader,
        "bootstrapped": True,
        "identityCandidatePending": False,
        "threadPolicyId": policy,
        "scriptHash": script_hash,
        "scriptAddress": address,
        "address": address,
        "lastAnchoredHeight": height,
    }

height = 0 if mode in ("pending", "nonzero-pending") else int(mode[-1]) \
    if mode.startswith("adopted") else 1
tip = 0 if mode == "pending" else max(1, height)
root = "0" * 64 if tip == 0 else "33" * 32
documents = []
for index in range(3):
    document = {
        "chainId": "evidence-chain-binding-test",
        "memberKey": member_keys[index],
        "members": 3,
        "threshold": 2,
        "running": True,
        "sequencing": True,
        "role": "proposer" if index == 0 else "member",
        "stateMachine": "evidence-registry",
        "tipHeight": tip,
        "stateRoot": root,
        "poolSize": 0,
        "submitted": 0,
        "received": 0,
        "relayed": 0,
        "duplicates": 0,
        "seenIds": 0,
        "storedMessages": 0,
    }
    if index == 0:
        document["anchor"] = anchor(True, height)
    elif mode.startswith("adopted") or mode == "identity-mismatch":
        document["anchor"] = anchor(False, height,
            "44" * 28 if mode == "identity-mismatch" and index == 2 else script)
    elif mode == "mixed" and index == 1:
        document["anchor"] = anchor(False, height)
    documents.append(document)

if mode == "nonzero-pending":
    documents[0]["submitted"] = 1

for index, document in enumerate(documents):
    path = directory / f"node{index}.json"
    path.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
                    encoding="utf-8")
    os.chmod(path, 0o600)
PY
}

expect_fatal_status_mutation() {
  mutation="$1"
  label="$2"
  write_statuses pending
  python3 - "$STATUS_DIR" "$mutation" <<'PY'
import json
import os
from pathlib import Path
import sys

directory = Path(sys.argv[1])
mutation = sys.argv[2]
paths = [directory / f"node{index}.json" for index in range(3)]
documents = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
if mutation == "duplicate-member":
    documents[1]["memberKey"] = documents[0]["memberKey"]
elif mutation == "wrong-topology":
    documents[0]["members"] = 99
    documents[0]["threshold"] = 99
elif mutation == "wrong-state-machine":
    documents[2]["stateMachine"] = "ordered-log"
elif mutation == "wrong-role":
    documents[1]["role"] = "proposer"
elif mutation == "multiple-anchor-leaders":
    documents[1]["anchor"] = dict(documents[0]["anchor"])
elif mutation == "boolean-counter":
    documents[0]["submitted"] = False
elif mutation == "boolean-tip":
    documents[0]["tipHeight"] = False
elif mutation == "boolean-anchor-height":
    documents[0]["anchor"]["lastAnchoredHeight"] = False
else:
    raise SystemExit("unknown mutation")
for path, document in zip(paths, documents):
    path.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
                    encoding="utf-8")
    os.chmod(path, 0o600)
PY
  result=0
  if python3 "$TOOL" reconcile "${common_args[@]}" "${member_args[@]}" \
      --allow-pristine-pending "${status_args[@]}" \
      >"$TMP/$mutation.out" 2>&1; then
    fail "$label unexpectedly succeeded"
  else
    result=$?
  fi
  [ "$result" -eq 2 ] || fail "$label was not rejected as malformed live status"
  cmp -s "$BINDING" "$TMP/pending-binding.json" \
    || fail "$label modified the persisted binding"
}

write_statuses pending
state="$(python3 "$TOOL" reconcile "${common_args[@]}" \
  "${member_args[@]}" --allow-pristine-pending "${status_args[@]}")"
[ "$state" = pending-genesis ] || fail 'pristine height-0 cluster was not recorded as pending'
[ "$(mode "$BINDING")" = 600 ] || fail 'binding is not owner-only mode 0600'
jq -e '.schemaVersion == 2 and .verificationState == "pending-genesis"
  and .verifiedHeight == 0 and .verifiedMembers == 1' "$BINDING" >/dev/null \
  || fail 'pending binding schema is incorrect'
python3 "$TOOL" validate "${common_args[@]}" >/dev/null \
  || fail 'canonical pending binding did not validate'
cp "$BINDING" "$TMP/pending-binding.json"

# The same binding topology validator accepts the other immutable stock demo
# provider only when all member statuses and the expected provider agree.
COMPOSITE_BINDING="$TMP/composite-anchor-binding.json"
python3 - "$STATUS_DIR" <<'PY'
import json
import os
from pathlib import Path
import sys

for path in Path(sys.argv[1]).glob("node*.json"):
    document = json.loads(path.read_text(encoding="utf-8"))
    document["stateMachine"] = "composite"
    path.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
                    encoding="utf-8")
    os.chmod(path, 0o600)
PY
composite_args=(--binding "$COMPOSITE_BINDING" --network devnet \
  --instance binding-test --deployment compose --chain-id evidence-chain-binding-test \
  --state-machine composite)
state="$(python3 "$TOOL" reconcile "${composite_args[@]}" \
  "${member_args[@]}" --allow-pristine-pending "${status_args[@]}")"
[ "$state" = pending-genesis ] \
  || fail 'composite height-0 cluster was not recorded as pending'

expect_fatal_status_mutation duplicate-member 'duplicate member identity'
expect_fatal_status_mutation wrong-topology 'wrong membership topology'
expect_fatal_status_mutation wrong-state-machine 'wrong state machine'
expect_fatal_status_mutation wrong-role 'wrong node role'
expect_fatal_status_mutation multiple-anchor-leaders 'multiple script-anchor leaders'
expect_fatal_status_mutation boolean-counter 'boolean pristine counter'
expect_fatal_status_mutation boolean-tip 'boolean app-chain tip'
expect_fatal_status_mutation boolean-anchor-height 'boolean anchor height'

result=0
write_statuses pending
if python3 "$TOOL" reconcile "${common_args[@]}" "${member_args[@]}" "${status_args[@]}" \
    >"$TMP/require-adopted.out" 2>&1; then
  fail 'adoption-required reconciliation accepted a pending genesis'
else
  result=$?
fi
[ "$result" -eq 3 ] || fail 'pending genesis did not return the transient status'

write_statuses nonzero-pending
result=0
if python3 "$TOOL" reconcile "${common_args[@]}" "${member_args[@]}" --allow-pristine-pending \
    "${status_args[@]}" >"$TMP/nonzero.out" 2>&1; then
  fail 'nonzero app activity was accepted as pending genesis'
else
  result=$?
fi
[ "$result" -eq 3 ] || fail 'nonzero pending state did not fail closed as transient'
cmp -s "$BINDING" "$TMP/pending-binding.json" \
  || fail 'rejected nonzero pending state modified the binding'

write_statuses mixed
result=0
if python3 "$TOOL" reconcile "${common_args[@]}" "${member_args[@]}" --allow-pristine-pending \
    "${status_args[@]}" >"$TMP/mixed.out" 2>&1; then
  fail 'mixed follower adoption was accepted'
else
  result=$?
fi
[ "$result" -eq 3 ] || fail 'mixed follower adoption did not fail closed as transient'

write_statuses adopted1
state="$(python3 "$TOOL" reconcile "${common_args[@]}" "${member_args[@]}" "${status_args[@]}")"
[ "$state" = member-adopted ] || fail 'three-member adoption did not upgrade the binding'
jq -e '.verificationState == "member-adopted" and .verifiedHeight == 1
  and .verifiedMembers == 3' "$BINDING" >/dev/null \
  || fail 'adopted binding schema is incorrect'

write_statuses pending
result=0
if python3 "$TOOL" reconcile "${common_args[@]}" "${member_args[@]}" --allow-pristine-pending \
    "${status_args[@]}" >"$TMP/downgrade.out" 2>&1; then
  fail 'adopted binding was downgraded to pending'
else
  result=$?
fi
[ "$result" -eq 2 ] || fail 'binding downgrade was not a fatal rejection'

write_statuses identity-mismatch
result=0
if python3 "$TOOL" reconcile "${common_args[@]}" "${member_args[@]}" "${status_args[@]}" \
    >"$TMP/identity.out" 2>&1; then
  fail 'different member anchor identities were accepted'
else
  result=$?
fi
[ "$result" -eq 2 ] || fail 'identity mismatch was not a fatal rejection'

python3 - "$BINDING" <<'PY'
import json
import os
import sys
path = sys.argv[1]
document = json.load(open(path, encoding="utf-8"))
document["verificationState"] = "pending-genesis"
document["verifiedMembers"] = 1
document["verifiedHeight"] = 9
with open(path, "w", encoding="utf-8") as output:
    json.dump(document, output, sort_keys=True, separators=(",", ":"))
    output.write("\n")
os.chmod(path, 0o600)
PY
result=0
if python3 "$TOOL" validate "${common_args[@]}" >"$TMP/tampered.out" 2>&1; then
  fail 'nonzero pending binding unexpectedly validated'
else
  result=$?
fi
[ "$result" -eq 2 ] || fail 'tampered pending binding was not rejected fatally'

printf '%s\n' 'PASS: anchor binding safely progresses from pristine pending to three-member adoption'
