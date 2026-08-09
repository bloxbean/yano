# Master Demo Script

This is the complete, presenter-followable path. Commands assume the extracted
showcase root as the current directory. Use a fresh instance name for each
rehearsal.

## 0. Explain the topology

Say: “Yano provides sequencing, member finality, deterministic state roots,
MPF proofs, plugins, effects, and Cardano anchoring. The light showcase
assembles existing capabilities through one `AppStateMachine` contract. Its
three deliberate business compositions are orders + approval + effects,
documents + roles + approval, and authenticated map + direct role/approval.
The current light distribution directly contains the first and third; ADR-033
adds the lean `document-review-chain` form of the already proven role-evidence
reuse path. It does not introduce another consensus implementation.”

Also explain that `payment-chain-settlement` starts in the default light
profile. It demonstrates L1 observers and settlement effects, but it does not
automatically deploy public-network scripts or move funds. Membership
governance is a separate concern from application roles and approvals.

```bash
./showcase.sh profiles
./showcase.sh describe light
./showcase.sh doctor --profile light
```

Expected: Java 25, Python 3, curl, jq, the Yano JAR, config, and plugin are
reported present. Python uses only its standard library.

## 1. Start three nodes and bootstrap the devnet script anchor

```bash
./showcase.sh quickstart --profile light --instance master-demo --nodes 3
```

This starts nodes on HTTP ports 7070–7072 and n2n ports 13337–13339. The MPF
threshold defaults to 2. It bootstraps the one-time script identity for
`workflow-chain`, runs the composite and authenticated-map scenarios, checks
converged roots, and prints the UI URL.

Expected checkpoints:

- all three nodes become ready;
- every named chain reports the same root on all nodes;
- opaque, canonical-CBOR, schema-validated, and plugin-validated map values
  finalize while two deliberately invalid values are filtered before
  finalization;
- the release effect becomes confirmed;
- exactly one JSON receipt appears below the instance outbox; and
- the status UI URL is `http://127.0.0.1:7070/ui/app-chain/`.

If quickstart was already run, use the restart path instead:

```bash
./showcase.sh restart --instance master-demo
```

Never re-bootstrap an already bootstrapped script identity. Normal restart
reuses it.

## 2. Inspect configuration without searching the data tree

```bash
./showcase.sh config show --instance master-demo
./showcase.sh config paths --instance master-demo
./showcase.sh config export ./master-demo-config.json --instance master-demo
```

Point out the network, bootstrap member count, chain IDs, anchor chain,
config/plugin digests, port allocation, live state-machine IDs, and the
separate active versus scheduled membership/threshold fields.
The export redacts the anchor-key path and never contains key material.

## 3. Open the status UI

```bash
./showcase.sh ui --instance master-demo
```

Open the printed address. Show chain height, state root, block certificate,
messages, proof/anchor information, and `workflow-chain` effects. Explain that
only node 0 owns the local executor; an empty executor panel on other nodes or
non-effect chains is expected.

## 4. Ordered log — immutable ordering plus proof

Say: “The sequencer orders messages, but threshold member signatures—not the
sequencer alone—finalize the block.”

```bash
./demos/submit-orders.sh master-demo '{"order":"A-100","event":"created"}'
```

Expected: a finalized height and an MPF inclusion proof bound to the current
state root.

## 5. Key/value registry — owner-guarded deterministic state

```bash
./demos/submit-registry.sh master-demo product:A-100 active
```

Expected: the registry value and its inclusion proof. The first writer owns
the key; a different member’s update is a deterministic no-op.

## 6. Authenticated map — multiple collections and validation

```bash
./demos/submit-authenticated-map.sh master-demo
```

The scenario writes an opaque attachment, a canonical-CBOR event, a product
that matches the genesis-bound schema, and a GTIN accepted by the first-party
validator plugin. It then proves that an unknown product status and an invalid
GTIN check digit are filtered before finalization and leave exclusion proofs.
Finally it prints both the root-attested product query and the native MPF proof
for the physical collection/key leaf.

Say: “Collection encoding, schema IR, validator provider and parameters, and
the exact validator artifact closure are committed by genesis. An application
can supply another trusted validator through the same public SPI, but it is
consensus code shared by every node—not an uploaded runtime script.”

## 7. Approval chain — proposal and approvals are separate messages

```bash
./demos/approval-propose.sh master-demo approval-A-100 \
  '{"order":"A-100","action":"release"}' 2
./demos/approval-approve.sh master-demo approval-A-100 1
./demos/approval-approve.sh master-demo approval-A-100 2
```

Say: “The proposal stores a payload hash and required voter count. Node 1 and
node 2 each send a separate application approval message. This two-voter
business rule is independent from the two-of-three MPF block-finality rule.”

Expected: a root-attested approval record with approved status and two distinct
application approvers.

## 8. Balances and rejection behavior

```bash
./demos/submit-balances.sh master-demo
```

Expected: node 0 mints to its own member account, transfers 250 to
`demo-recipient`, and proves the recipient balance. Overspending cannot make a
balance negative; it finalizes as a deterministic no-op.

## 9. Document trail — compact tamper-evident audit head

```bash
./demos/submit-documents.sh master-demo case-A-100
```

Expected: a per-entity count/head-hash state leaf and proof. Documents remain
off-chain; hashes and references form the ordered trail.

## 10. Composite approval → effect flow, one step at a time

Keep the JSON byte-identical between register and propose:

```bash
ORDER='{"id":"order-C-100","amount":42,"currency":"USD"}'

./demos/composite-register-order.sh master-demo order-C-100 "$ORDER"
./demos/composite-propose.sh master-demo proposal-C-100 "$ORDER"
./demos/composite-approve.sh master-demo proposal-C-100 1
./demos/composite-approve.sh master-demo proposal-C-100 2
./demos/composite-release.sh master-demo release-C-100 order-C-100 proposal-C-100
./demos/composite-verify.sh master-demo release-C-100
```

Narrate the boundaries:

1. `orders` stores canonical order bytes;
2. `approvals` stores their Blake2b-256 hash;
3. the release workflow verifies the exact binding and approved status;
4. the same atomic transition appends the audit head and emits
   `showcase.outbox.write`;
5. the deterministic state machine does no I/O;
6. after app finality, node 0’s executor writes one idempotent receipt;
7. the executor reports the result; and
8. a member-finalized result callback marks the release confirmed.

Run `composite verify` twice. The same effect identity still maps to one file;
no duplicate business event is created.

## 11. Roles and EUTxO capability discovery

```bash
./demos/inspect-roles.sh master-demo
./demos/inspect-eutxo.sh master-demo
```

The light cluster hosts generic `role-approvals` and experimental
`eutxo-ledger`. For the full actor/policy/key-rotation flow use the evidence
role variant. For signed ledger/bridge/ZK flows use the EUTxO profile:

```bash
./showcase.sh quickstart --profile evidence --variant role --instance evidence-role
./showcase.sh quickstart --profile eutxo --variant ledger --instance eutxo-ledger
```

Docker is needed only for evidence. Run these optional profiles in a longer
session, not in the minimal light rehearsal.

## 12. Load demonstration

First repeat fully verified business scenarios:

```bash
./demos/load.sh master-demo orders 25
# Or a heavier stateful flow:
./demos/load.sh master-demo composite 10
```

Then show a parallel burst or short sustained run:

```bash
./demos/load-test.sh master-demo orders --count 500 --concurrency 10 --spread
./demos/soak-test.sh master-demo orders --duration 60 --rate 25 \
  --concurrency 4 --sample 5 --spread
```

The first path verifies each scenario. The burst reports acceptance versus
certified finalization. The soak writes a CSV and checks cross-node root
consistency. See [LOAD_AND_SOAK.md](LOAD_AND_SOAK.md).

Then show convergence:

```bash
./showcase.sh verify all --instance master-demo
```

## 13. Govern a new member into the running cluster

The next index after a three-node bootstrap is 3:

```bash
./showcase.sh member join 3 --instance master-demo
./showcase.sh status --instance master-demo
./showcase.sh config show --instance master-demo
./showcase.sh governance activate --instance master-demo
```

Expected sequence for every chain:

- current members submit threshold-many `ADD` governance commands;
- the command finalizes under the old 2-of-3 threshold;
- a later membership epoch is scheduled for node 3;
- node 3 starts before activation, replays the governed history, catches up; and
- its per-chain tip and state root match an existing node;
- existing processes refresh their local peer endpoint set one at a time,
  without another governance command;
- valid state-machine-specific demo traffic advances each chain to the
  activation height; and
- one proof block finalizes with node 3 in the active member profile.

No live YAML member list is edited. Application state, membership history,
and old block verification remain height-versioned.

## 14. Govern a new MPF finality threshold

After node 3 has joined, demonstrate 3-of-4:

```bash
./showcase.sh threshold set 3 --instance master-demo
./showcase.sh config show --instance master-demo
./showcase.sh governance activate --instance master-demo
./showcase.sh verify all --instance master-demo
./showcase.sh status --instance master-demo
```

The old threshold (2) authorizes the threshold-change command. The new
threshold activates only in the recorded later epoch. It changes member block
finality, not existing approval items’ business voter counts.

`config show` displays both active 2-of-3 and scheduled 3-of-4 values with
each chain's activation height before `governance activate`. The activation
command sends explicitly labeled, normally sequenced messages and then
finalizes one block under 3-of-4 on every chain. Each chain receives a valid
state-machine-specific command; the role-policy probe is a deliberate
business no-op and the EUTxO command is a signed virtual-funds payment. These
are demo messages, not synthetic database edits.

To lower it again:

```bash
./showcase.sh threshold set 2 --instance master-demo
./showcase.sh governance activate --instance master-demo
```

## 15. Restart and prove retained identity

```bash
./showcase.sh restart --instance master-demo
./showcase.sh verify all --instance master-demo
./showcase.sh config show --instance master-demo
```

Expected: bootstrap nodes restart from retained state; previously joined nodes
use the retained `resume` path (no second governance vote), roots converge,
the outbox receipt remains one file, and the anchor identity is reused.

## 16. Preprod anchor branch (optional, fee-paying)

Read [ANCHORING_DEMO.md](ANCHORING_DEMO.md) and
[PREPROD_ANCHORING.md](PREPROD_ANCHORING.md) first. Never put a
mnemonic in a command. Supply an owner-only raw 32-byte Ed25519 seed file:

```bash
./showcase.sh up --instance preprod-demo --network preprod --nodes 3 \
  --anchor-mode script \
  --anchor-key-file /absolute/private/anchor.seed \
  --confirm-public-anchor preprod

./showcase.sh config show --instance preprod-demo
```

Fund the printed anchor address externally. Wait until the copied/synced
preprod node can observe a stable funding UTxO, then bootstrap exactly once:

```bash
./showcase.sh anchor bootstrap workflow-chain --instance preprod-demo
./showcase.sh run composite --instance preprod-demo
./showcase.sh status --instance preprod-demo
```

Show the L2 state root, finality certificate, Cardano transaction ID/output,
and each node’s L1 observation in the UI/status. Bootstrap establishes the
thread-NFT identity; ordinary later anchors and restarts do not repeat it.
Public-network cadence defaults to one anchor per 30 `workflow-chain` blocks;
use the complete steps in [ANCHORING_DEMO.md](ANCHORING_DEMO.md) when rehearsing
an ordinary post-bootstrap anchor.

To demonstrate late anchoring without resetting an existing chain, expand the
retained scope, restart automatically on the same data, and then perform the
new chain's one-time bootstrap:

```bash
./showcase.sh anchor enable registry-chain --instance preprod-demo \
  --confirm-public-anchor preprod
./showcase.sh anchor bootstrap registry-chain --instance preprod-demo
```

Use `anchor enable all` and `anchor bootstrap all` to cover every configured
chain. The latter waits for each L1 bootstrap confirmation before submitting
the next transaction.

## 17. Stop or explicitly reset

```bash
./showcase.sh stop --instance master-demo
```

State is preserved. Only after the demo, if deliberate:

```bash
./showcase.sh reset --instance master-demo --yes
```

Reset deletes the named showcase instance and is not recoverable through this
tool.
