# Tutorial 2 — A Provable Shared Registry

[Open this outcome in App-Chain Studio](../../../appchain/appchain-studio/src/main/web/index.html#recipe=owned-registry&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=shared-registry&chainId=shared-registry)

- **Level:** beginner to intermediate
- **Time:** about 15 minutes
- **Outcome:** write owner-controlled data, demonstrate an unauthorized no-op,
  and retrieve an MPF proof for the current value.

The default cluster also hosts `registry-chain`, backed by the stock
`kv-registry` state machine. The first writer becomes the key owner; only that
member can update or delete it.

## 1. Start the default cluster

Skip this section if Tutorial 1's cluster is still running.

```bash
cd app/appchain-cluster
export YANO_CLUSTER_DIR=/tmp/yano-tutorial-registry
./cluster.sh start 3
```

## 2. Create a registry entry

Write through node 1 so that node 1 becomes the authenticated owner:

```bash
./cluster.sh kv registry-chain set supplier-42 active --node 1
sleep 3
./cluster.sh status
```

The command body is CBOR, but the launcher builds it for you. Application
clients can use the standard-library encoder instead of hand-writing CBOR.

## 3. Prove the current value

The physical state key is the UTF-8 business key. Convert it to hex for the
proof endpoint:

```bash
KEY_HEX=$(python3 -c 'print("supplier-42".encode().hex())')

curl -s \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/registry-chain/proof/$KEY_HEX" \
  | jq .
```

The proven value contains both owner identity and registry value. A verifier
must verify the MPF path against the expected state root; displaying JSON from
one node alone is not independent verification.

## 4. Demonstrate authorization as a deterministic no-op

Capture the root, attempt an update through node 2, and compare:

```bash
ROOT_BEFORE=$(curl -s http://127.0.0.1:7070/api/v1/app-chain/chains \
  | jq -r '.[] | select(.chainId == "registry-chain") | .stateRoot')

./cluster.sh kv registry-chain set supplier-42 suspended --node 2
sleep 3

ROOT_AFTER=$(curl -s http://127.0.0.1:7070/api/v1/app-chain/chains \
  | jq -r '.[] | select(.chainId == "registry-chain") | .stateRoot')

printf 'before=%s\nafter =%s\n' "$ROOT_BEFORE" "$ROOT_AFTER"
```

The unauthorized command can appear in a finalized block, but it must not
change the registry entry. This is intentional: the deterministic application
result, not “the HTTP call succeeded” or “the block height increased,” is the
authorization decision.

Check the value again:

```bash
curl -s \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/registry-chain/proof/$KEY_HEX" \
  | jq .
```

Now update through the owner and verify the proof changes:

```bash
./cluster.sh kv registry-chain set supplier-42 suspended --node 1
sleep 3
curl -s \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/registry-chain/proof/$KEY_HEX" \
  | jq .
```

## 5. Clean up

```bash
./cluster.sh clean
unset YANO_CLUSTER_DIR
```

## Where this pattern fits

- consortium allow-lists;
- product, asset, credential, or DID-document registries;
- shared configuration with explicit ownership;
- a current pointer whose exact value must be proven to a third party.

It does not provide organization roles, multi-party policy governance, or
arbitrary document indexing. Use the role workflow or a domain plugin when the
authorization model is richer than “first writer owns this key.”

## Go deeper

- Verify the returned `proofWireHex` using the Java app-chain client rather
  than trusting the serving node.
- Start with `--anchor-mode metadata`, fund the printed anchor wallet on a
  public test network, and connect the proof root to its anchor.
- Review `kv-registry.value-format` (`raw`, `utf8`, or `cbor`) in the
  [user guide](../../APP_CHAIN_USER_GUIDE.md).
- Continue with the [stock state-machine cookbook](03-stock-state-machines.md).
