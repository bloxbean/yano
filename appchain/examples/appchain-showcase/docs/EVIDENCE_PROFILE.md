# Evidence Profile — Operator Walkthrough

## Table of contents

1. [What this profile demonstrates](#1-what-this-profile-demonstrates)
2. [Prerequisites and port planning](#2-prerequisites-and-port-planning)
   — Docker required; ports come from `DEMO_*` env vars, not `--http-base`
3. [Create and start (composite variant)](#3-create-and-start-composite-variant)
4. [Role variant](#4-role-variant)
5. [Everyday operations](#5-everyday-operations)
6. [Submitting evidence yourself](#6-submitting-evidence-yourself)
   — publish, republish, verify, replay: one record at a time
7. [Load: many records](#7-load-many-records)
8. [Stop, clean, and full teardown](#8-stop-clean-and-full-teardown)
9. [Gotchas](#9-gotchas)
10. [Deeper reading](#10-deeper-reading)

## 1. What this profile demonstrates

The evidence profile delegates to the maintained `appchain-effects-demo`
harness packaged inside the ZIP (`profiles/evidence/demo/`, with release-built
runner and connector bundles under `profiles/evidence/artifacts/`). It does
not copy or reimplement the evidence state machine or the connectors — the
showcase is a thin facade over the real product harness, and nothing reaches
back into a source checkout or runs Gradle.

What actually runs: **three Yano nodes** (fixed 2-of-3 member threshold) each
also producing their own devnet L1, one app chain `evidence-chain`, and
**real external services** via Docker Compose — Apache Kafka (KRaft, one
broker), an S3-compatible object store (rustfs, with versioned buckets
`evidence-staging` / `evidence-archive` and real IAM users), and IPFS (Kubo).
None of these are stand-ins.

An **evidence record** is one business id (e.g. `inspection-2026-0716`)
carrying an immutable, explicitly versioned document — the packaged sample is
a cold-chain vaccine inspection certificate. A publish walks the whole
effects pipeline:

1. stage the document in the `evidence-staging` bucket,
2. commit the evidence command on the app chain (threshold finality),
3. server-side copy + verify into the versioned `evidence-archive` bucket,
4. pin the document CID in IPFS,
5. incorporate the signed effect results under the app-chain state root,
6. publish `evidence.available.v1` to Kafka,
7. verify everything in one report: state proof, finality bundle, S3
   version, exact CID bytes, Kafka acknowledgement, and the Cardano
   state-thread anchor linkage.

Each command prints a single machine-readable result line, e.g.
`PASS command=run scenario=…` (failures print `FAIL code=…` and exit 2), and
writes a JSON report with named checks (`THRESHOLD_FINALITY_BUNDLES`,
`COMPOSED_EFFECT_PROOFS`, `KAFKA_ACKNOWLEDGEMENT_AND_EVENT`, …) that the
Evidence UI serves.

Two variants:

| Variant | State machine | What it shows |
|---|---|---|
| `composite` (default) | `evidence-v1-gated` | Registry identity → proposal + approval → gated `evidence.release.v1`, applied atomically with the document trail |
| `role` | `evidence-role-v1` (ADR-019) | Member-count approval replaced by governed business-actor signatures: manufacturer proposer, two auditors from **distinct organizations**, one regulator; the scenario proves onboarding, key rotation, stale/revoked-credential rejection, and proposal cancellation with MPF proofs |

## 2. Prerequisites and port planning

**Docker is required** (Compose is the only showcase deployment for this
profile). Check readiness first:

```bash
./showcase.sh doctor --profile evidence
```

**Ports are controlled only by `DEMO_*` environment variables.** The
showcase's `--http-base`/`--server-base` flags are ignored for this profile —
the packaged harness reads its port plan from the environment (defaults:
nodes 7070-7072, UI 7080, Kafka 9092, S3 9000, IPFS 5001). To coexist with a
running light profile (7070+) or eutxo instances, export a different plan.

The evidence profile writes no showcase identity marker, so the environment
and flags must be identical on **every** command. Put them in a file once and
source it in any shell you use:

```bash
cat > evidence.env <<'EOF'
export DEMO_HTTP_BASE=28070        # nodes 28070, 28071, 28072
export DEMO_UI_PORT=28080          # evidence report UI
export DEMO_KAFKA_PORT=29092
export DEMO_S3_PORT=29000
export DEMO_IPFS_PORT=25001
export DEMO_CONNECTOR_SUBNET=172.31.13.0/24
export DEMO_S3_IP=172.31.13.10
export DEMO_KUBO_IP=172.31.13.11
export DEMO_KAFKA_IP=172.31.13.12
EOF
source evidence.env
```

(The connector subnet only needs changing when you run two evidence
environments side by side — each needs a distinct subnet, instance, and port
plan. `DEMO_SERVER_BASE` matters only for `--deployment host`; in Compose the
nodes talk over the internal network.)

## 3. Create and start (composite variant)

From the extracted showcase root, with `evidence.env` sourced:

```bash
source evidence.env
./showcase.sh quickstart --profile evidence --variant composite --instance evidence
```

`quickstart` = `prepare` (stage plugins/runner, build the two Docker images,
generate private config + per-instance secrets) + `up` (start the three-node
profile, warm up the devnet L1, bootstrap the script anchor, probe
Yano/Kafka/S3/IPFS) + `run` (the guided scenario: publish the default
evidence id `inspection-2026-0716` as version 1, drive the full effects
pipeline, verify everything). Expect `PASS command=run scenario=…` at the
end. The steps can also be run individually:

```bash
source evidence.env
./showcase.sh prepare --profile evidence --variant composite --instance evidence
./showcase.sh up      --profile evidence --variant composite --instance evidence
./showcase.sh run     --profile evidence --variant composite --instance evidence
```

Where to look afterwards:

- Node consoles: `http://127.0.0.1:28070/ui/app-chain/` (and 28071, 28072)
- Evidence report UI: `http://127.0.0.1:28080/` (serves the JSON check
  reports)
- Ignore `./showcase.sh ui` for this profile — it prints 7070-based URLs
  regardless of your `DEMO_*` plan.

Re-running `run` is safe: if the default id already exists with matching
bytes it performs a read-only verification instead of a second publish
(`REPUBLISH_REQUIRED` if the bytes differ). `prepare` is idempotent with
identical options; changing machine/preset/chain-id on an existing instance
fails closed — use a new `--instance`.

## 4. Role variant

Use a separate instance (and, if the composite instance is still running, a
separate port plan and connector subnet — e.g. `28170`/`28180`, Kafka
`29192`, S3 `29100`, IPFS `25101`, subnet `172.31.14.0/24`):

```bash
source role-evidence.env    # like evidence.env but with the second port plan
./showcase.sh quickstart --profile evidence --variant role --instance role-evidence
```

The role quickstart runs the maintained `role-lifecycle` scenario instead of
the publish scenario: governed onboarding of a dedicated `recovery-probe`
actor, key rotation (proving the stale revision can no longer authorize while
the new one can), revocation (proving the revoked credential is rejected),
cancellation of the probe proposals, and MPF-proof verification of all
retained actor revisions. Success looks like:

```
PASS command=role-lifecycle actor=recovery-probe revision=3 rotation=verified revocation=verified …
```

For the role variant, `./showcase.sh run --variant role …` re-runs the
lifecycle scenario and `./showcase.sh verify --variant role …` runs the
readiness/proof probe. Always pass `--variant role` — `run roles` without it
fails (the composite machine cannot run the role scenario).

## 5. Everyday operations

Always `source evidence.env` first; always repeat `--profile evidence
--variant … --instance …` (no marker adoption for this profile).

```bash
# container status for the instance
./showcase.sh status --profile evidence --variant composite --instance evidence

# read-only verification of the default evidence id (composite)
./showcase.sh verify --profile evidence --variant composite --instance evidence

# re-run the guided scenario (publish-if-absent, else verify)
./showcase.sh run --profile evidence --variant composite --instance evidence

# stop containers; ALL data survives (L1, journals, connector data, reports)
./showcase.sh stop --profile evidence --variant composite --instance evidence

# resume later exactly where you left off
./showcase.sh up --profile evidence --variant composite --instance evidence
```

Data lives under `data/showcase/<instance>/evidence/` (node logs in
`…/instances/<instance>/compose/logs/node{0,1,2}`, JSON reports in
`…/reports/`). Secrets and staged runtime live inside the packaged harness at
`profiles/evidence/demo/.demo-secrets/` and `.demo-runtime/` — **not** under
the instance directory. The node API key is printed by `up`
(`API key file: …/yano-api-key`).

Not available for this profile: `restart`, `logs`, `config`, `anchor`,
`authmap`, `load-test`/`soak-test`, and `--count` on any showcase command
(bulk load goes through the harness directly — §7).

## 6. Submitting evidence yourself

The showcase facade only exposes the guided scenario. For individual records
— new ids, explicit versions, historical verification — call the packaged
harness directly. It is the same maintained CLI; you only replicate the two
things the facade sets (the prebuilt-artifact root and the common flags):

```bash
source evidence.env
export DEMO_PREBUILT_ARTIFACT_ROOT="$PWD/profiles/evidence/artifacts"
DEMO=profiles/evidence/demo/demo.sh
COMMON=(--deployment compose --machine composite --network devnet \
        --instance evidence --data-dir "$PWD/data/showcase/evidence/evidence")

# publish a NEW evidence id (always creates business version 1)
"$DEMO" publish "${COMMON[@]}" --evidence-id inspection-product-b \
  --sample-file profiles/evidence/demo/samples/inspection-certificate-product-b.json

# create the exact next immutable version of an existing id
"$DEMO" republish "${COMMON[@]}" --evidence-id inspection-product-b \
  --business-version 2 \
  --sample-file profiles/evidence/demo/samples/inspection-certificate-product-a-v2.json

# read-only verification — latest, or any retained historical version
"$DEMO" verify "${COMMON[@]}" --evidence-id inspection-product-b
"$DEMO" verify "${COMMON[@]}" --evidence-id inspection-product-b --business-version 1

# explicitly finalize an accepted command as a deterministic no-op
"$DEMO" replay "${COMMON[@]}" --evidence-id inspection-product-b \
  --business-version 2 \
  --sample-file profiles/evidence/demo/samples/inspection-certificate-product-a-v2.json
```

Rules: evidence ids match `[a-z][a-z0-9-]{0,62}`; `publish` rejects
`--business-version` (v1 by definition) while `republish`/`replay` require
it; `verify` never submits, stages, pins, or writes to Kafka. The sample file
must be a regular file you own, 1 byte–16 MiB. Versions are immutable —
republishing does not replace v1, it adds v2 and both stay queryable.

## 7. Load: many records

`load` publishes many unique records with bounded concurrency, each waiting
for real finality, effect completion, connector re-reads, and proof
verification — a functional capacity runner, not a rate driver (committed
capacity is 8 releases per block). Same setup as §6:

```bash
# 8 full publish workflows, 3 workers, ids lifecycle-aug-000001…000008
"$DEMO" load "${COMMON[@]}" --count 8 --concurrency 3 --id-prefix lifecycle-aug \
  --sample-file profiles/evidence/demo/samples/inspection-certificate.json

# staged pipeline mode (composite only): higher in-flight bound
"$DEMO" load "${COMMON[@]}" --load-mode pipeline --count 8 --concurrency 8 \
  --max-in-flight 8 --id-prefix pipeline-aug \
  --sample-file profiles/evidence/demo/samples/inspection-certificate.json
```

Result line:
`PASS command=load load=… requested=8 succeeded=8 failed=0 … successfulPerSecond=…`.
Bounds: `--count` 1..50000, `--concurrency` 1..16 (≤ count), `--id-prefix`
`[a-z][a-z0-9-]{0,55}`. Re-using a completed prefix demonstrates
immutable-id rejection — it does not resume. Pipeline mode is composite-only,
and load is refused while a public anchor-enabled profile is active.

## 8. Stop, clean, and full teardown

**Stop (data preserved):**

```bash
source evidence.env
./showcase.sh stop --profile evidence --variant composite --instance evidence
```

This runs Compose down, and only after Docker confirms zero project
containers does it release the shared L1 lease. Everything survives; `up`
resumes.

**Scoped clean** (harness command; deployment must be stopped first). Scopes:
`observability`, `reports`, `runtime` are independently disposable;
`instance` retires the app chain + connectors + reports + logs together and
requires a fresh `--new-instance` name (chain ids are permanent network-wide
claims, even after retirement); `l1` and `all` go further. Example:

```bash
"$DEMO" clean "${COMMON[@]}" --scope reports --yes
```

**Full factory reset and recreate** — stops every managed devnet evidence
project and deletes L1, instances, connectors, runtime, and the generated
devnet identity (secrets and Docker images are kept), so the next quickstart
starts completely fresh:

```bash
source evidence.env
export DEMO_PREBUILT_ARTIFACT_ROOT="$PWD/profiles/evidence/artifacts"
profiles/evidence/demo/demo.sh reset-devnet --yes

./showcase.sh quickstart --profile evidence --variant composite --instance evidence
```

**Do not** use the showcase's generic `reset --profile evidence --yes` as
your teardown: it deletes the instance data directory **without stopping the
Docker services** and without touching the harness's secrets/runtime roots.
If you use it at all, run `./showcase.sh stop …` first.

## 9. Gotchas

- **No identity marker.** Every command needs the same `DEMO_*` environment
  (source `evidence.env`) and the same `--profile/--variant/--instance`
  flags. Nothing is remembered between commands.
- **`--http-base` is silently ignored** for this profile; only `DEMO_*` env
  vars move ports. `./showcase.sh ui` prints 7070-based URLs regardless.
- **Never pass `--count` to showcase evidence commands** — the harness
  rejects it for everything except its own `load` command (§7).
- **`run roles` requires `--variant role`.** Without it, the composite
  machine is asked to run the role scenario and dies.
- The verify default id is `inspection-2026-0716` (the guided scenario's
  record); for other ids use the direct harness `verify` (§6).
- Two concurrent evidence environments need distinct instances, port plans,
  **and** `DEMO_CONNECTOR_SUBNET`s.
- Domain routes and the report UI populate only after the first scenario
  completes; `probe` (`./showcase.sh verify --variant role …`) is the
  readiness check.
- The facade hard-rejects `--nodes`/`--threshold` — the maintained topology
  is exactly three nodes, 2-of-3, by design.

## 10. Deeper reading

- `profiles/evidence/demo/README.md` — the authoritative harness document:
  load semantics, composite and role profiles, port table, persistence
  layout, cleanup, host deployment, public-network (preview/preprod) anchor
  profiles with `--anchor-key-file` / `--confirm-public-anchor`.
- `DEMO_SHOWCASE.md` §13 — how this profile relates to the light and eutxo
  profiles.
- ADR-019 (domain roles) and the effects ADRs — the design behind
  `evidence-role-v1` and the effects pipeline.
