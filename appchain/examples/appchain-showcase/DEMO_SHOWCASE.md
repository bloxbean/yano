# Yano App-Chain Showcase — Full Demo Guide

One guide to demonstrate every app-chain capability with the packaged
showcase: all eleven chains and their state machines, regular and bulk
submission, deterministic effects, L1 anchoring on devnet and preprod,
verifiable reads with historical versioning, the browser console, and the
Java client (including a runnable fat jar).

Last verified live: 2026-08-05 on a 3-node cluster.

## Table of contents

1. [Build and extract](#1-build-and-extract)
2. [Start a cluster](#2-start-a-cluster)
3. [Everyday cluster operations](#3-everyday-cluster-operations)
4. [The console pages](#4-the-console-pages)
5. [Chain-by-chain demos](#5-chain-by-chain-demos)
   - [orders-chain — ordered log](#orders-chain--ordered-log)
   - [registry-chain — key/value](#registry-chain--keyvalue)
   - [approvals-chain — threshold approvals](#approvals-chain--threshold-approvals)
   - [balances-chain — token balances](#balances-chain--token-balances)
   - [documents-chain — document trail](#documents-chain--document-trail)
   - [workflow-chain — composite + effects](#workflow-chain--composite--effects)
   - [roles-chain — role approvals](#roles-chain--role-approvals)
   - [payments-chain — EUTxO ledger](#payments-chain--eutxo-ledger)
   - [payment-chain-l1bridge — L1 custody boundary](#payment-chain-l1bridge--l1-custody-boundary)
   - [authenticated-map-chain — governed MPF map](#authenticated-map-chain--governed-mpf-map)
   - [authenticated-map-jmt-chain — basic classic-JMT map](#authenticated-map-jmt-chain--basic-classic-jmt-map)
6. [Deterministic effects deep-dive](#6-deterministic-effects-deep-dive)
7. [Bulk load and soak](#7-bulk-load-and-soak)
8. [Java client (fat jar)](#8-java-client-fat-jar)
9. [L1 anchoring: enable, bootstrap, verify](#9-l1-anchoring-enable-bootstrap-verify)
10. [Historical state: read any key at any height](#10-historical-state-read-any-key-at-any-height)
11. [Authenticated-map curl deep-dive](#11-authenticated-map-curl-deep-dive)
12. [Cheat sheet: demo actors and seeds](#12-cheat-sheet-demo-actors-and-seeds)
13. [Other profiles: EUTxO and evidence](#13-other-profiles-eutxo-and-evidence)
14. [Running on non-default ports / side-by-side instances](#14-running-on-non-default-ports--side-by-side-instances)

---

## 1. Build and extract

```bash
cd ~/work/bloxbean/yano
./gradlew :appchain-showcase:distZip
# → appchain/examples/appchain-showcase/build/distributions/yano-showcase-<version>.zip

unzip yano-showcase-*.zip -d ~/showcase && cd ~/showcase/yano-showcase-*
./showcase.sh doctor          # Java 25, Python 3, curl, jq, packaged artifacts
```

## 2. Start a cluster

**Local devnet** (fast path; script anchoring is enabled for ALL chains,
auto-funded from the devnet faucet, and auto-bootstrapped for
`workflow-chain` — bootstrap the rest selectively, see §9):

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance demo
```

**Preprod** (spends test ADA; anchor key is a raw 32-byte Ed25519 seed):

```bash
mkdir -m 700 ./private-anchor
(umask 077; openssl rand -hex 32 > ./private-anchor/anchor.seed)
chmod 600 ./private-anchor/anchor.seed

./showcase.sh up --profile light --network preprod --nodes 3 \
  --instance preprod-anchor --anchor-mode script \
  --anchor-key-file "$(pwd)/private-anchor/anchor.seed" \
  --confirm-public-anchor preprod
# Fund the printed addr_test... wallet (several clean pure-ADA UTxOs), wait
# for node 0 to see the funding stably, then bootstrap ONCE per chain — §9.
```

Node 0's API is `http://127.0.0.1:7070/api/v1` (`--http-base` shifts it).
All eleven chains report at `GET /api/v1/app-chain/chains`.

## 3. Everyday cluster operations

```bash
./showcase.sh status --instance demo            # per-chain tips, anchor, members
./showcase.sh ui --instance demo                # console URLs
./showcase.sh logs --node 0 --instance demo
./showcase.sh verify all --instance demo        # end-to-end verification pass
./showcase.sh governance activate --instance demo   # membership-epoch heartbeat
                                                    # (also produces a new chain's first block)
./showcase.sh member join 3 --instance demo     # add node 3 (late join)
./showcase.sh threshold set 3 --instance demo
./showcase.sh restart --instance demo           # same data dirs; never re-bootstrap anchors
./showcase.sh stop --instance demo              # teardown (there is no "down")
./showcase.sh config show|paths --instance demo
```

Generic message submission to any chain/topic:

```bash
curl -X POST http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' -d '{"topic":"orders.command.v1","body":"hello"}'
# then: GET .../messages/<messageId>   (404 until finalized, then height+body)
# live: GET .../stream                 (SSE feed, also on the console page)
```

## 4. The console pages

| URL (`http://127.0.0.1:7070` + …) | Shows |
|---|---|
| `/ui/app-chain/?chain=<id>` | generic ops: tip, blocks, live SSE feed, capabilities, proofs |
| `/ui/app-chain/authenticated-map/?chain=<id>` | authenticated-map console: collections, lookup+proofs, mutations, governed tabs |
| `/ui/app-chain/eutxo/?chain=payments-chain` | EUTxO lifecycle explorer |
| `/ui/app-chain/eutxo/?chain=payment-chain-l1bridge` | Bridge chain lifecycle explorer |
| `/ui/status/` , `/ui/observability/` | L1 node status, Prometheus charts |
| `/ui/plugins/` | plugin operations (privileged; needs API key) |

## 5. Chain-by-chain demos

Every chain has a scripted one-liner (`./showcase.sh run <name> --instance demo`)
and `run all` chains them together. Below, what each demonstrates plus the
manual path.

### orders-chain — ordered log

Plain text messages, total ordering, per-message inclusion proof.

```bash
./showcase.sh run orders --instance demo
# manual: submit text (above), then
curl -s $BASE/app-chain/chains/orders-chain/proof/<messageIdHex> | jq '{committedHeight,stateRoot}'
```

### registry-chain — key/value

Real CBOR PUT/DELETE commands with root-attested reads.

```bash
./showcase.sh run registry --instance demo       # kv put + native proof
```

### approvals-chain — threshold approvals

Propose an item, N-of-M members approve, state flips atomically.

```bash
./showcase.sh run approvals --instance demo
# or step by step:
./showcase.sh approvals propose my-item '{"action":"release-demo"}' 2 --instance demo
./showcase.sh approvals approve my-item 1 --instance demo
./showcase.sh approvals approve my-item 2 --instance demo
```

### balances-chain — token balances

Mint to a member, transfer, prove the recipient balance.

```bash
./showcase.sh run balances --instance demo
```

### documents-chain — document trail

Hash-committed document references per entity.

```bash
./showcase.sh run documents --instance demo
```

### workflow-chain — composite + effects

The flagship flow: a composite state machine (order registry + approvals +
release) whose release emits a **deterministic effect** executed exactly once
off-chain. See §6 for the effects deep-dive.

```bash
./showcase.sh run composite --instance demo
# or narrate each step:
./showcase.sh composite register-order order-1 '{"id":"order-1","amount":42,"currency":"USD"}' --instance demo
./showcase.sh composite propose prop-1 '{"id":"order-1","amount":42,"currency":"USD"}' --instance demo
./showcase.sh composite approve prop-1 1 --instance demo
./showcase.sh composite approve prop-1 2 --instance demo
./showcase.sh composite release rel-1 order-1 prop-1 --instance demo
./showcase.sh composite verify rel-1 --instance demo
```

### roles-chain — role approvals

Generic role-approvals profile (actors/policies/proposals). The scripted run
shows the status surface; the complete governed lifecycle is demonstrated on
the authenticated-map chain (§5/§11) and in the evidence profile.

```bash
./showcase.sh run roles --instance demo
```

### payments-chain — EUTxO ledger

L2 UTxO transactions with the browser lifecycle explorer.

```bash
./showcase.sh run eutxo --instance demo
# console: /ui/app-chain/eutxo/?chain=payments-chain
```

### authenticated-map-chain — governed MPF map

Six collections covering all five authorization modes, consensus-side value
validation (schema + GS1 plugin), governed direct-role and two-organization
approval flows, receipts, consumptions, and proofs.

```bash
# full scripted arc (basic + rejected-by-validator + direct-role + approval):
./showcase.sh run authenticated-map --instance demo

# one-liners for your own data:
./showcase.sh authmap put attachments doc-1 "hello" --instance demo
./showcase.sh authmap governed-put item-1 "governed value" --instance demo
./showcase.sh authmap entry attachments doc-1 --instance demo
./showcase.sh authmap receipt <message-id> --instance demo
```

`governed-put` discovers policy/actor/key/genesis from the chain and prints
every offline-signing step. Private key: `--seed-file <64-hex raw Ed25519
seed>` for a real actor; without it the deterministic DEMO seed for the named
showcase actor is derived (showcase-only material). Full curl anatomy: §11.

### authenticated-map-jmt-chain — basic classic-JMT map

The contrast chain: `jmt-blake2b256-v1` backend, ungoverned collections
`kv-open` (open) / `documents` (owner) / `notes` (member, canonical-CBOR).
Console shows basic-only views — compare side by side with the governed MPF
chain.

```bash
# ships in new builds; adopt on an EXISTING instance (history retained):
#   copy showcase.sh, tools/showcase_identity.py,
#   yano/config/application-appchain.yml and the showcase plugin bundle jar
#   from a newly built ZIP, then:
./showcase.sh chain add authenticated-map-jmt-chain --instance demo
./showcase.sh governance activate --instance demo     # first block

./showcase.sh authmap put kv-open k1 "v1" --chain authenticated-map-jmt-chain --instance demo
./showcase.sh authmap put notes note-1 \
  "$(python3 tools/showcase_codec.py authmap-value event audit-note 1)" \
  --chain authenticated-map-jmt-chain --instance demo   # member-gated, CBOR value
```

Authorization modes on this chain: `kv-open` anyone writes; `documents`
first writer owns the key; `notes` only current chain members write
(height-versioned membership — rotated-out members lose access).

### payment-chain-l1bridge — L1 custody boundary

The same EUTxO machine in **bridge mode** (ADR-UTXO-008): no virtual
genesis — funds enter via real devnet-L1 vault deposits and leave via
operator-settled withdrawals. Full walkthrough: `docs/BRIDGE_CHAIN.md`.

```bash
./showcase.sh bridge info                   # vault facts + live status (any network)
./showcase.sh bridge run                    # automated round-trip (devnet)
./showcase.sh bridge deposit                # staged: deposits only …
./showcase.sh bridge transfer               # … the L2 payments …
./showcase.sh bridge settle && ./showcase.sh bridge verify   # … withdrawal + CONFIRMED
./showcase.sh bridge run --count 2          # rounds are journaled; higher count = new activity
# console: /ui/app-chain/eutxo/?chain=payment-chain-l1bridge
```

The vault is a single-operator-key native script over PUBLIC deterministic
demo identities — the operator key IS custody; demo amounts only. A plain
wallet transfer to the vault address is NOT a deposit (the observer credits
L2 owners from the inline datum). On preprod there is no faucet: `bridge
info` prints the Java-client command for depositing your own funds.

## 6. Deterministic effects deep-dive

Effects are the intent → finality gate → external execution → on-chain result
pattern. In the showcase, `workflow-chain`'s release emits a
`showcase.outbox.write` effect; only node 0 runs the executor, which writes an
idempotent receipt file; all nodes validate and finalize the result.

```bash
BASE=http://127.0.0.1:7070/api/v1
./showcase.sh run composite --instance demo          # produces one effect end to end

# 1. The effect ledger (scope showcase/order-release/<release>):
curl -s "$BASE/app-chain/chains/workflow-chain/effects?fromHeight=0&limit=100" \
  | jq '.effects[] | {height, ordinal, type, scope, status}'

# 2. Aggregate lifecycle counters:
curl -s "$BASE/app-chain/chains/workflow-chain/effects/stats" | jq

# 3. One effect's detail and its inclusion proof:
curl -s "$BASE/app-chain/chains/workflow-chain/effects/<height>/<ordinal>" | jq
curl -s "$BASE/app-chain/chains/workflow-chain/effects/<height>/<ordinal>/proof" | jq '{committedHeight,stateRoot}'

# 4. The off-chain side: the executor's idempotent receipt on disk (node 0):
ls data/showcase/demo/outbox/

# 5. Operator controls (privileged; X-API-Key required):
#    POST .../effects/<h>/<o>/requeue | /cancel?reason=... | /claim | /report
```

Talking points: the intent is deterministic and consensus-validated; execution
happens once, only after finality; the result is itself finalized and
provable; a crashed executor resumes idempotently (receipt file), and stuck
effects are operator-recoverable via requeue/cancel. The console's app-chain
page has an Effects panel showing the same lifecycle.

## 7. Bulk load and soak

```bash
# scripted scenario loops (full flow per message):
./showcase.sh load orders --count 25 --instance demo        # also: registry|approvals|documents|composite

# raw burst driver (ingress throughput, pool backpressure, --spread across nodes):
./showcase.sh load-test orders --count 500 --concurrency 8 --payload-bytes 128 --spread --instance demo
./showcase.sh load-test registry --count 200 --concurrency 4 --instance demo

# sustained-rate soak with latency samples and a report directory:
./showcase.sh soak-test orders --duration 60 --rate 25 --concurrency 4 \
  --sample 5 --report-dir /tmp/soak --instance demo

# authenticated-map bulk load via the Java client (see §8):
java -jar yano-showcase-client-*-all.jar authmap http://127.0.0.1:7070/api/v1 \
  authenticated-map-jmt-chain load kv-open 100
```

The Java `load` prints submit throughput, applied/rejected counts, end-to-end
finality time, and 10 sample keys (spread across the range) ready to paste
into `reads`, `verified-entry`, or the console's Lookup tab.

## 8. Java client (fat jar)

Module `appchain/examples/appchain-showcase-client` — what an integrating Java
application looks like, using the release-matched `appchain-client`.

```bash
./gradlew :appchain-showcase-client:shadowJar
# → build/libs/yano-showcase-client-<version>-all.jar (copy anywhere)

JAR=yano-showcase-client-*-all.jar
API=http://127.0.0.1:7070/api/v1

java -jar $JAR authmap $API authenticated-map-jmt-chain basic-put kv-open jk1 jv1
java -jar $JAR authmap $API authenticated-map-chain     governed-put gk1 gv1
java -jar $JAR authmap $API authenticated-map-jmt-chain reads kv-open jk1
java -jar $JAR authmap $API authenticated-map-jmt-chain verified-entry kv-open jk1
java -jar $JAR authmap $API authenticated-map-jmt-chain load kv-open 100
```

- Writes print the four trust levels: accepted → finalized → APPLIED/REJECTED
  → re-read entry.
- `governed-put` signs in-JVM with the demo seed — the line a real app
  replaces with its wallet/KMS (export `signingPreimage()`).
- `verified-entry` decodes the value only after client-side merkle
  verification at the entry's composite physical key.
- The first argument (`authmap`) selects the chain-family demo; sibling demos
  can register in `ShowcaseClientDemos` later without changing the command.
- See the module's `README.md` for details and caveats.

## 9. L1 anchoring: enable, bootstrap, verify

Defaults: new instances anchor-ENABLE **all chains** — enabling is
config-only and never spends: an enabled chain stays dormant (`bootstrapped:
false`, no L1 transactions) until you bootstrap it. Spending starts at
bootstrap (thread setup) and continues per anchor transaction afterwards, so
bootstrap selectively. Devnet `quickstart` auto-bootstraps `workflow-chain`
only; preprod requires the explicit flags at `up` (§2) and manual funding.
Instances created before this default carry a narrower retained scope —
widen them with the additive migration (no L1 cost, restarts nodes):

```bash
./showcase.sh anchor enable all --instance preprod-anchor --confirm-public-anchor preprod

# bootstrap mints each chain's thread NFT — exactly once per chain, ever:
./showcase.sh anchor bootstrap workflow-chain --instance preprod-anchor
./showcase.sh anchor bootstrap all --instance preprod-anchor    # waits for each L1 confirmation

# verify:
curl -s $BASE/app-chain/chains/workflow-chain/status | jq .anchor
curl -s $BASE/app-chain/chains/workflow-chain/anchor/commitment | jq
./showcase.sh verify all --instance preprod-anchor
```

Notes: public-network cadence is ~1 anchor per 30 finalized blocks per chain
(fee hygiene) — sustained activity advances it; never re-run bootstrap after a
restart; keep the seed file — it is the anchor identity.

## 10. Historical state: read any key at any height

Entries carry a logical `revision`; old values are read at **previous
heights**, each with a merkle proof against that height's co-signed root:

```bash
PK=$(curl -s "$DOMAIN/authenticated-map/entries/kv-open/$(printf 'k1' | xxd -p)?chain=authenticated-map-jmt-chain" | jq -r .proofKey)
C=$BASE/app-chain/chains/authenticated-map-jmt-chain
curl -s "$C/proof/$PK"            | jq '{h:.committedHeight, presence, valueHex}'   # current revision
curl -s "$C/proof/$PK?height=3"   | jq '{h:.committedHeight, presence, valueHex}'   # value as of height 3
curl -s "$C/proof/$PK?height=2"   | jq '{h:.committedHeight, presence}'             # provably ABSENT before creation
curl -s "$C/state/oldest-provable" | jq                                             # retention fence
```

Works on both map chains (JMT is natively versioned; the runtime provides
height retention for MPF). The console's Proofs tab has the height field for
the same demo. Governed records (actors/policies/authorities) are additionally
revision-addressed directly: `?revision=N` on the domain API.

## 11. Authenticated-map curl deep-dive

What happens underneath the one-liners — full anatomy against
`authenticated-map-chain`. Helpers (run from the extract root):

```bash
BASE=http://127.0.0.1:7070/api/v1
CHAIN=authenticated-map-chain
CODEC=tools/showcase_codec.py
DOMAIN=$BASE/plugins/com.bloxbean.cardano.yano.appchain.stdlib

# Wrap a codec-built command into the final v1 envelope the chain admits.
# kind = open | owner | member (the collection's authorization mode)
wrap() { local a; a=$(yano/yano.sh appchain authenticated-map action \
          --command-hex "$2" --assignments "0:$1::0"); \
        yano/yano.sh appchain authenticated-map command --action-hex "$a" --evidence-hex ''; }

submit() { curl -fsS -X POST "$BASE/app-chain/chains/$CHAIN/messages" \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"authenticated-map.command.v1\",\"bodyHex\":\"$1\"}" | jq -r .messageId; }

finalized() { for i in $(seq 1 30); do \
  curl -fsS "$BASE/app-chain/chains/$CHAIN/messages/$1" 2>/dev/null | jq -e .height && return; \
  sleep 2; done; echo "not finalized"; }
```

**Opaque owner write** (`attachments` — submitting node's member key becomes
the entry's controller):

```bash
VALUE=$(python3 $CODEC authmap-value opaque "hello-preprod")
ID=$(submit "$(wrap owner "$(python3 $CODEC authmap put attachments doc-001 "$VALUE")")")
finalized $ID
curl -fsS "$DOMAIN/authenticated-map/receipts/$ID?chain=$CHAIN" | jq .record  # status 0 = APPLIED
```

**Member-gated canonical-CBOR event** (`canonical-events`):

```bash
VALUE=$(python3 $CODEC authmap-value event order-created 1)
ID=$(submit "$(wrap member "$(python3 $CODEC authmap put canonical-events evt-001 "$VALUE")")")
```

**Schema-validated product** (`products`) and a consensus rejection (invalid
status never finalizes — stays HTTP 404):

```bash
VALUE=$(python3 $CODEC authmap-value product sku-1001 25 active "first batch")
ID=$(submit "$(wrap owner "$(python3 $CODEC authmap put products sku-1001 "$VALUE")")")

BAD=$(submit "$(wrap owner "$(python3 $CODEC authmap put products sku-bad \
  "$(python3 $CODEC authmap-value product sku-bad 5 unknown)")")")
sleep 8; curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/app-chain/chains/$CHAIN/messages/$BAD"  # → 404
```

**GS1 GTIN plugin validator** (`gtins`): `95012346` (valid check digit)
applies; `95012345` is filtered exactly like the schema case.

**Reads and verified proof** — always prove the `proofKey` the domain API
returns (the composite physical key):

```bash
curl -fsS "$DOMAIN/authenticated-map?chain=$CHAIN" | jq .record      # catalog + governed flag
KEY=$(printf 'sku-1001' | xxd -p)
ENTRY=$(curl -fsS "$DOMAIN/authenticated-map/entries/products/$KEY?chain=$CHAIN")
PK=$(echo "$ENTRY" | jq -r .proofKey)
PROOF=$(curl -fsS "$BASE/app-chain/chains/$CHAIN/proof/$PK")
curl -fsS -X POST "$BASE/app-chain/chains/$CHAIN/proof/verify" -H 'Content-Type: application/json' \
  -d "$(echo "$PROOF" | jq '{mode:"inclusion", profile, presence, expectedRootHex:.stateRoot, keyHex:.key, valueHex, proofWireHex}')" | jq .valid
```

**Governed direct-role write** (full offline-signing chain — or just use
`authmap governed-put`, which prints these exact steps):

```bash
SEED=$(python3 -c 'import hashlib;print(hashlib.sha256(b"yano-showcase-demo-actor:issuer-a").hexdigest())')
PUB=$(python3 tools/showcase_signer.py public-key "$SEED")
GEN=$(curl -fsS "$DOMAIN/authenticated-map?chain=$CHAIN" | jq -r .record.genesisId)
H=$(curl -fsS "$BASE/app-chain/chains/$CHAIN/tip" | jq -r .height)
AUTHZ=$(python3 -c 'import secrets;print(secrets.token_hex(32))')

CMD=$(python3 $CODEC authmap put governed-catalog item-001 "$(python3 $CODEC authmap-value opaque governed-entry)")
ACTION=$(yano/yano.sh appchain authenticated-map action --command-hex "$CMD" --assignments 0:governed-role:issuer-write:1)
ARGS=(--action-hex "$ACTION" --authorization-id "$AUTHZ" --chain $CHAIN --genesis-id "$GEN" \
  --indexes 0 --policy issuer-write --policy-revision 1 --actor issuer-a --actor-revision 1 \
  --key issuer-a-k1 --public-key "$PUB" --issued-height "$H" --deadline-height "$((H+90))")
PRE=$(yano/yano.sh appchain authenticated-map direct-preimage "${ARGS[@]}")
SIG=$(python3 tools/showcase_signer.py sign "$SEED" "$PRE")           # the "wallet/HSM" step
EV=$(yano/yano.sh appchain authenticated-map direct-complete "${ARGS[@]}" --signature "$SIG")
ID=$(submit "$(yano/yano.sh appchain authenticated-map command --action-hex "$ACTION" --evidence-hex "$EV")")
finalized $ID
curl -fsS "$DOMAIN/authenticated-map/direct-consumptions/issuer-a/$AUTHZ?chain=$CHAIN" | jq .record
```

**Approval flow** (`released-products` — PROPOSE + two auditor APPROVEs from
distinct organizations, then execute): run it scripted and inspect:

```bash
./showcase.sh run authenticated-map --instance demo
curl -fsS "$DOMAIN/pending/approvals?chain=$CHAIN" | jq .record
curl -fsS "$DOMAIN/proposals/<proposal-id>?chain=$CHAIN" | jq .record.decisions
curl -fsS "$DOMAIN/authenticated-map/approval-consumptions/<proposal-id>?chain=$CHAIN" | jq .record
```

**Governed lookups** (any time):

```bash
curl -fsS "$DOMAIN/actors/issuer-a?chain=$CHAIN" | jq .record
curl -fsS "$DOMAIN/organizations/acme-manufacturing?chain=$CHAIN" | jq .record
curl -fsS "$DOMAIN/policies/product-release?chain=$CHAIN" | jq .record
curl -fsS "$DOMAIN/authenticated-map/direct-policies/issuer-write?chain=$CHAIN" | jq .record
curl -fsS "$DOMAIN/authenticated-map/administrator-authorities/registry-admins?chain=$CHAIN" | jq .record
curl -fsS "$DOMAIN/stats?chain=$CHAIN" | jq .record
```

## 12. Cheat sheet: demo actors and seeds

| Actor | Organization | Roles | Key id |
|---|---|---|---|
| `registry-admin-a` | acme-manufacturing | registry-admin | registry-admin-a-k1 |
| `issuer-a` | acme-manufacturing | issuer | issuer-a-k1 |
| `auditor-a` | auditor-guild-a | auditor | auditor-a-k1 |
| `auditor-b` | auditor-guild-b | auditor | auditor-b-k1 |

Seed derivation (showcase-only material — never reuse):
`sha256("yano-showcase-demo-actor:" + actorId)`. The chain trusts none of
this — only the public keys committed in the governed genesis; every
signature is verified inside consensus against committed actor records.

Other operations (PUT_IF_ABSENT, compare-and-set, transfer, revoke, restore)
are built by the console's Mutations tab with the same envelope. Never run
`anchor bootstrap` twice for a chain. `./showcase.sh stop` is the teardown.

## 13. Other profiles: EUTxO and evidence

The light profile keeps one out-of-box `eutxo-ledger` chain visible
(payments-chain, §5). For the full EUTxO round-trip scenarios use the
dedicated profile, which delegates to the maintained `yano.sh appchain eutxo
demo` CLI (devnet-only by design; the ledger variant uses test keys and no
real funds):

```bash
./showcase.sh quickstart --profile eutxo --variant ledger --instance ledger
    # experimental virtual ledger: setup, round-trip transactions, verify
./showcase.sh quickstart --profile eutxo --variant bridge --instance bridge
    # Cardano custody/federation boundary demo
./showcase.sh quickstart --profile eutxo --variant zk     --instance zk
    # maintained validity (proof) flow

# afterwards — the eutxo profile keeps no identity marker, so ALWAYS repeat
# the profile/variant/instance (and non-default port) flags:
./showcase.sh run eutxo   --profile eutxo --variant ledger --instance ledger
./showcase.sh verify all  --profile eutxo --variant ledger --instance ledger
./showcase.sh stop        --profile eutxo --variant ledger --instance ledger
```

The showcase maps `prepare`→`setup`, `run`→`round-trip`, and passes ports and
member count through; for the zk variant, quickstart also runs the one-time
trusted-setup `ceremony` automatically (existing instances:
`./showcase.sh ceremony --profile eutxo --variant zk --instance zk`). The
full walkthrough — what each round-trip does, workspace layout, timings,
gotchas — is `docs/EUTXO_PROFILE.md`. The evidence profile
(`--profile evidence`, `docs/EVIDENCE_PROFILE.md`) similarly delegates to the
packaged evidence product demo (Kafka/S3/IPFS sinks, role lifecycle).

## 14. Running on non-default ports / side-by-side instances

The light and eutxo profiles accept `--http-base` and `--server-base`; node N
listens on base+N. For the light profile, specify them once at
`prepare`/`up`/`quickstart` — they are recorded in the instance identity and
restored automatically by every later command via `--instance` (the eutxo
profile keeps no marker, so repeat them on every command). The **evidence**
profile ignores these flags entirely — its ports come from `DEMO_*`
environment variables (`DEMO_HTTP_BASE`, `DEMO_UI_PORT`, `DEMO_KAFKA_PORT`,
…); see `docs/EVIDENCE_PROFILE.md` §2.

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance demo2 \
  --http-base 27070 --server-base 23370
./showcase.sh authmap put attachments k1 v1 --instance demo2   # ports adopted
# console: http://127.0.0.1:27070/ui/app-chain/
```

This is also how to run several instances side by side (e.g. a preprod
instance on 7070 and a devnet playground on 27070) — each instance keeps its
own data directory, identity marker, and port range.
