# Tutorial 1 — Your First App Chain

[Open this outcome in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=audit-log&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=my-appchain&chainId=my-appchain)

- **Level:** beginner
- **Time:** about 15 minutes; 25 with the optional load and effects exercises
- **Outcome:** three members finalize the same ordered event and expose the same
  state root.

This tutorial uses Yano's self-contained Cardano devnet and the built-in
`ordered-log` state machine. It needs no external Cardano node, wallet, funds,
Kafka, or plugin.

## On this page

1. [Choose the working directory and start](#1-choose-the-working-directory-and-start)
2. [Confirm agreement](#2-confirm-agreement)
3. [Submit a business event](#3-submit-a-business-event)
4. [Capture a message ID and its proof](#4-capture-a-message-id-and-its-proof)
5. [Run a small `ordered-log` load test](#5-run-a-small-ordered-log-load-test)
6. [Preserve and restart](#6-preserve-and-restart)
7. [Try effects and governed member onboarding](#7-try-effects-and-governed-member-onboarding)
8. [Review what you proved](#what-you-just-proved)
9. [Go deeper](#go-deeper)

## 1. Choose the working directory and start

The commands in every tutorial run `./yano.sh` from the directory that contains
it. Choose one setup:

From an extracted release distribution, run this in its top-level directory
(the directory containing `yano.sh` and `yano.jar` or the native executable):

```bash
cd /path/to/extracted/yano-{version}
./yano.sh appchain help
```

From a source checkout, run this from the repository root:

```bash
./gradlew :app:quarkusBuild -PskipSigning=true
cd app
./yano.sh appchain help
```

The remaining commands are identical for either setup:

```bash
export YANO_CLUSTER_DIR=/tmp/yano-tutorial-first-chain
./yano.sh appchain cluster start 3
```

The launcher starts:

- node 0 as the local Cardano L1 producer and app-chain proposer;
- nodes 1 and 2 as app-chain voting members; and
- `orders-chain` (`ordered-log`), `registry-chain` (`kv-registry`), and
  `effects-chain` (`approvals`) for the dependency-free effects demo.

The expected HTTP ports are `7070`, `7071`, and `7072`. If those ports are
busy, the launcher prints the free range it selected; use that range in the
commands below.

## 2. Confirm agreement

```bash
./yano.sh appchain cluster status
```

Look for:

```text
orders-chain: AGREED (...)
registry-chain: AGREED (...)
```

`AGREED` means the members expose the same authenticated application root. It
does not merely mean that all three processes are running.

Open the status pages if you prefer a UI:

- <http://127.0.0.1:7070/ui/app-chain/>
- <http://127.0.0.1:7071/ui/app-chain/>
- <http://127.0.0.1:7072/ui/app-chain/>

## 3. Submit a business event

Submit through member 1 rather than directly through the proposer:

```bash
./yano.sh appchain cluster submit orders-chain orders \
  '{"event":"order-created","orderId":"A-1001","quantity":4}' \
  --node 1
```

Member 1 authenticates and gossips the envelope. The proposer orders it, a
threshold signs the block, and all members apply the same bytes.

Wait a couple of seconds, then inspect the finalized history and agreement:

```bash
curl -s http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/blocks | jq .
./yano.sh appchain cluster status
```

The tip advances on every member and the roots remain equal.

## 4. Capture a message ID and its proof

For a complete proof-oriented submission, call the same public API directly:

```bash
RESPONSE=$(curl -s -X POST \
  http://127.0.0.1:7072/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"{\"event\":\"packed\",\"orderId\":\"A-1001\"}"}')

echo "$RESPONSE" | jq .
MESSAGE_ID=$(echo "$RESPONSE" | jq -r .messageId)
sleep 3

curl -s \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/proof/$MESSAGE_ID" \
  | jq .
```

The proof response connects the message state key to the member's committed
state root. Tutorial 7 connects that root to a Cardano anchor.

## 5. Run a small `ordered-log` load test

The distribution includes a parallel load driver for the running local
cluster. Start with a bounded workload of 500 messages, 10 concurrent
submitters, and payloads of approximately 256 bytes:

```bash
./yano.sh appchain cluster loadtest orders-chain -n 500 -c 10 -s 256
```

Plain load-test mode is intended for any-bytes machines such as
`ordered-log`. It submits numbered UTF-8 bodies on the `load` topic, waits
for the pending pool to drain, and reports two different rates:

```text
==================== throughput ====================
  submitted attempts : 500
  accepted (2xx)     : 500
  dropped  (429 pool): 0
  errors             : 0

  SUBMIT rate        : ... msg/s
  finalized msgs     : 500   in ... block(s)
  FINALIZE rate      : ... msg/s
  end-to-end rate    : ... msg/s
====================================================
```

- **SUBMIT rate** measures how quickly an ingress REST API accepts messages.
- **FINALIZE rate** measures how quickly messages enter threshold-certified
  blocks; this is the meaningful chain-throughput measurement.
- **dropped (429 pool)** means backpressure worked because the pending pool
  filled. Reduce concurrency or tune the pool and block limits deliberately.
- **errors** are non-backpressure request failures and should be investigated.

Spread submissions across all ready members to exercise gossip from every
ingress path:

```bash
./yano.sh appchain cluster loadtest orders-chain -n 1000 -c 20 -s 256 --spread
```

Then confirm that every member still exposes the same committed root:

```bash
./yano.sh appchain cluster status
```

This is a functional throughput exercise, not a production benchmark. Results
include the local machine, JVM/native runtime, devnet producer, configured
block interval, payload size, and member count. Record those inputs when
comparing runs. The test appends real finalized messages and therefore advances
the retained `orders-chain` history.

For capacity settings and workload boundaries, see the
[`ordered-log` reference](../state-machines/ordered-log.md#operational-tuning).

## 6. Preserve and restart

`stop` keeps both L1 and app-chain data:

```bash
./yano.sh appchain cluster stop
./yano.sh appchain cluster start 3
./yano.sh appchain cluster status
```

The retained tips and roots should return unchanged before new traffic is
finalized. This is a useful distinction:

- **restart:** same chain identity and retained state;
- **clean:** delete the chain and create a new identity/history.

## 7. Try effects and governed member onboarding

### Effect demo

The default `effects-chain` can demonstrate the full emit, external-worker,
result, and proof lifecycle without Kafka, S3, IPFS, or a real webhook:

```bash
./yano.sh appchain cluster effect demo
./yano.sh appchain cluster effect demo "order A-1001 approved"
```

With no argument, the demo uses `hello from Yano effects`; one quoted argument
replaces that message. The command prints the captured payload, confirmed
delivery, and proof availability.

Briefly, the command:

1. creates a unique one-approval item on the separate `effects-chain`;
2. wraps your text in a JSON payload, then submits `PROPOSE` and `APPROVE`
   commands to its `approvals` state machine;
3. keeps the item decision `APPROVED` and emits one generic app-final
   `demo.webhook` effect when the approval threshold is reached;
4. acts as a simulated external worker that claims the effect and reports a
   synthetic successful delivery—no real webhook is called; and
5. feeds the result back through the effect lifecycle and checks that the
   finalized effect proof is available.

The supplied text is illustrative data. It is not automatically linked to an
event on `orders-chain`, even if it contains an order id. A production workflow
should carry an explicit business id or finalized source message id in its
committed command/effect payload. The proof establishes that the effect intent
was committed; a real external action additionally depends on a trusted
executor and verifiable receipt.

For the complete lifecycle and production webhook configuration, continue
with [webhook effects](06-webhook-effects.md).

### Governed member onboarding

You can also govern, start, catch up, and verify a fourth node on the same host:

```bash
./yano.sh appchain cluster node join 3
./yano.sh appchain cluster status
```

For an externally managed node, the lower-level
`appchain cluster member add <public-key>` command records membership but does
not configure or start the external process. Same-host `node join` performs
both steps.

When finished:

```bash
./yano.sh appchain cluster clean
unset YANO_CLUSTER_DIR
```

## What you just proved

- A submission can enter through any member.
- A threshold, not one REST server, finalizes the ordered block.
- All members deterministically derive the same state root.
- A retained restart recovers the same application history.
- A message can have an MPF inclusion proof against that root.

You did **not** yet prove Cardano settlement or the truth of the order fields.
Those are separate trust layers.

## Go deeper

- Change `--threshold 3` and observe that all three votes are now required.
- Read [the consensus guide](../../APP_CHAIN_CONSENSUS_GUIDE.md) for proposer,
  vote, certificate, replay, and catch-up mechanics.
- Continue with [registry ownership and state proofs](02-registry-and-proofs.md).
