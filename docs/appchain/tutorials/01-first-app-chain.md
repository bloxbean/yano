# Tutorial 1 — Your First App Chain

[Open this outcome in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=audit-log&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=my-appchain&chainId=my-appchain)

- **Level:** beginner
- **Time:** about 15 minutes
- **Outcome:** three members finalize the same ordered event and expose the same
  state root.

This tutorial uses Yano's self-contained Cardano devnet and the built-in
`ordered-log` state machine. It needs no external Cardano node, wallet, funds,
Kafka, or plugin.

## 1. Build and start

From the repository root:

```bash
./gradlew :app:quarkusBuild -PskipSigning=true

cd app/appchain-cluster
export YANO_CLUSTER_DIR=/tmp/yano-tutorial-first-chain
./cluster.sh start 3
```

The launcher starts:

- node 0 as the local Cardano L1 producer and app-chain proposer;
- nodes 1 and 2 as app-chain voting members; and
- `orders-chain` (`ordered-log`) plus `registry-chain` (`kv-registry`).

The expected HTTP ports are `7070`, `7071`, and `7072`. If those ports are
busy, the launcher prints the free range it selected; use that range in the
commands below.

## 2. Confirm agreement

```bash
./cluster.sh status
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
./cluster.sh submit orders-chain orders \
  '{"event":"order-created","orderId":"A-1001","quantity":4}' \
  --node 1
```

Member 1 authenticates and gossips the envelope. The proposer orders it, a
threshold signs the block, and all members apply the same bytes.

Wait a couple of seconds, then inspect the finalized history and agreement:

```bash
curl -s http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/blocks | jq .
./cluster.sh status
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

## 5. Preserve and restart

`stop` keeps both L1 and app-chain data:

```bash
./cluster.sh stop
./cluster.sh start 3
./cluster.sh status
```

The retained tips and roots should return unchanged before new traffic is
finalized. This is a useful distinction:

- **restart:** same chain identity and retained state;
- **clean:** delete the chain and create a new identity/history.

When finished:

```bash
./cluster.sh clean
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
- Run `./cluster.sh loadtest orders-chain -n 1000 -c 20` and compare submission
  throughput with finalization throughput.
- Read [the consensus guide](../../APP_CHAIN_CONSENSUS_GUIDE.md) for proposer,
  vote, certificate, replay, and catch-up mechanics.
- Continue with [registry ownership and state proofs](02-registry-and-proofs.md).
