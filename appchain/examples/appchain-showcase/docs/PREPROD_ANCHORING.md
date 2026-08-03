# Preprod L1 Anchoring

For the narrated devnet, metadata, one-node, and full preprod flows, start with
[ANCHORING_DEMO.md](ANCHORING_DEMO.md). This page is the compact preprod
operator checklist.

Preprod anchoring spends real test ADA and is explicit. The showcase never
accepts a mnemonic. It requires an owner-only file containing one lowercase
64-hex raw Ed25519 seed:

```bash
mkdir -m 700 ./private-anchor
openssl rand -hex 32 > ./private-anchor/anchor.seed
chmod 600 ./private-anchor/anchor.seed
```

Start:

```bash
./showcase.sh up --network preprod --nodes 3 --instance preprod-demo \
  --anchor-mode script \
  --anchor-key-file "$(pwd)/private-anchor/anchor.seed" \
  --confirm-public-anchor preprod
```

New instances default to anchoring only `workflow-chain`. Node 0 is the transaction builder;
members co-sign script-anchor rounds at the current app-chain threshold. Use
`config show` and status/UI to obtain and verify the anchor address. Fund that
address externally. Prefer multiple clean pure-ADA UTxOs with sufficient fee
and collateral headroom. A mnemonic or CIP-1852 account is not the raw seed this
option expects; use the displayed address as the transfer destination.

To add registry anchoring later without resetting its history:

```bash
./showcase.sh anchor enable registry-chain --instance preprod-demo \
  --confirm-public-anchor preprod
./showcase.sh anchor bootstrap registry-chain --instance preprod-demo
```

To expand the retained scope to every configured chain:

```bash
./showcase.sh anchor enable all --instance preprod-demo \
  --confirm-public-anchor preprod
./showcase.sh anchor bootstrap all --instance preprod-demo
```

The enable operation restarts the nodes on their existing state and spends no
tADA. Script bootstrap is separate and fee-paying. Supply enough independent
pure-ADA UTxOs for the desired chains; `bootstrap all` waits for each chain's
L1 confirmation before proceeding to avoid reusing an input prematurely.

Wait until the preprod node has synchronized far enough to observe the funding
transaction and until the configured L1 stability view accepts it. Then, once
for a new instance:

```bash
./showcase.sh anchor bootstrap workflow-chain --instance preprod-demo
```

Bootstrap mints the thread NFT and establishes the script/datum identity. It
is not a per-restart action. After it succeeds, submit finalized L2 activity:

```bash
./showcase.sh run composite --instance preprod-demo
./showcase.sh load composite --count 6 --instance preprod-demo
./showcase.sh status --instance preprod-demo
```

The public-network default is one ordinary anchor per 30 finalized
`workflow-chain` blocks, so one composite run may not reach the next cadence.

Verify the L2 height/root and finality certificate, Cardano transaction ID and
output reference, anchored high-water mark, and follower L1 observations in
the status UI. If bootstrap reports no usable UTxO, do not reset or create a
new identity: confirm the wallet address, funding transaction, node sync, and
stability depth, then retry the same bootstrap.

For a faster local validation, a synchronized preprod chainstate may be copied
into a new disposable instance before first start. Never mutate or test in the
source demo directory itself, and never combine app-chain retained state with
a different L1 identity marker.
