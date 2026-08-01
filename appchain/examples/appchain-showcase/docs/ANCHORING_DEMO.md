# L1 Anchoring Demo

The showcase enables anchoring at cluster start; it does not require an edit to
`application-appchain.yml`. The launcher injects anchor settings only for
`workflow-chain`, records the mode/key reference in the immutable instance
marker, and keeps all other out-of-box chains available.

Use a new instance when turning anchoring on or changing its mode/key. An
already prepared non-anchor instance intentionally rejects that identity drift.
Do not delete or hand-edit retained anchor state to bypass the check.

## Fast devnet script-anchor demo

This is the recommended presentation path:

```bash
./showcase.sh quickstart --profile light --instance anchor-demo --nodes 3
```

For the light profile, `quickstart` enables `script` anchoring automatically.
It starts a 2-of-3 app chain, funds the demo anchor wallet from the local
devnet faucet, performs the one-time `workflow-chain` bootstrap, finalizes the
composite/effect flow, and verifies a confirmed Cardano anchor. The local
devnet seed is public demo material and must never be reused on another
network.

Open the status UI and select `workflow-chain`:

```bash
./showcase.sh ui --instance anchor-demo
./showcase.sh status --instance anchor-demo
curl -fsS \
  http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/status \
  | jq .anchor
```

Show the anchor wallet address, bootstrap/script output reference, last anchor
transaction ID, anchored L2 height/root, lag, and member L1 observations.

## Manual devnet flow

Use this version when you want to narrate bootstrap separately:

```bash
./showcase.sh up --profile light --instance anchor-manual --nodes 3 \
  --anchor-mode script

./showcase.sh config show --instance anchor-manual
./showcase.sh anchor bootstrap workflow-chain --instance anchor-manual
./showcase.sh run composite --instance anchor-manual
./showcase.sh verify all --instance anchor-manual
```

Bootstrap mints the thread NFT and establishes the script/datum identity. Run
it exactly once for a new script-anchor instance. Ordinary anchors occur
automatically after finalized `workflow-chain` activity; restart reuses the
identity and must not bootstrap again.

A one-node demonstration is also supported:

```bash
./showcase.sh quickstart --profile light --instance anchor-one \
  --nodes 1 --threshold 1
```

That proves the mechanics, but a three-node 2-of-3 run better demonstrates
threshold co-signing and follower observation.

## Metadata mode

Metadata mode is lighter and has no thread-NFT bootstrap. The anchor wallet
must be funded, after which finalized roots are submitted automatically:

```bash
./showcase.sh up --profile light --instance metadata-demo --nodes 3 \
  --anchor-mode metadata

ADDR="$(curl -fsS \
  http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/status \
  | jq -r '.anchor.walletAddress // .anchor.address')"
curl -fsS -X POST http://127.0.0.1:7070/api/v1/devnet/fund \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg address "$ADDR" '{address:$address,ada:500}')"

./showcase.sh run composite --instance metadata-demo
./showcase.sh status --instance metadata-demo
```

Use script mode for the stronger demo: current app-chain members co-sign the
L1 state transition at the app-chain threshold. Metadata mode is useful when
you only need a simple L1 checkpoint transaction.

## Preprod script-anchor flow

Preprod spends test ADA and uses the live network's UTxO view. The wallet is
derived from an owner-only file containing one raw 32-byte Ed25519 seed—not a
mnemonic and not a CIP-1852 phrase.

1. Create or securely supply the key file outside logs and shell arguments:

   ```bash
   mkdir -m 700 ./private-anchor
   (umask 077; openssl rand -hex 32 > ./private-anchor/anchor.seed)
   chmod 600 ./private-anchor/anchor.seed
   ```

2. Start a fresh preprod instance with anchoring enabled:

   ```bash
   ./showcase.sh up --profile light --network preprod --nodes 3 \
     --instance preprod-anchor --anchor-mode script \
     --anchor-key-file "$(pwd)/private-anchor/anchor.seed" \
     --confirm-public-anchor preprod
   ```

3. Copy the printed `addr_test...` anchor wallet address and fund it from a
   separate preprod wallet. Prefer clean pure-ADA UTxOs with enough value for
   bootstrap, script execution/collateral, fees, and later anchors. Keeping
   more than one usable UTxO avoids a transaction builder being blocked while
   another input is spent.

4. Wait until node 0 is synchronized far enough to see the funding transaction
   and the configured stability window accepts it. Confirm the address and
   inspect node 0 without exposing the seed:

   ```bash
   ./showcase.sh config show --instance preprod-anchor
   ./showcase.sh status --instance preprod-anchor
   ./showcase.sh logs --node 0 --instance preprod-anchor
   ```

5. Bootstrap the script identity exactly once:

   ```bash
   ./showcase.sh anchor bootstrap workflow-chain --instance preprod-anchor
   ```

   Node 0 builds the Cardano transaction with the anchor wallet. The current
   app-chain members co-sign the script-anchor round at the current threshold.
   For 3 nodes the default is 2 signatures; for 1 node it is 1.

6. Finalize `workflow-chain` activity. Public-network cadence defaults to one
   anchor per 30 workflow blocks to avoid needless fees, so a single composite
   run proves the flow but may not yet advance the next ordinary anchor. For a
   prompt rehearsal, run enough complete composite flows:

   ```bash
   ./showcase.sh load composite --count 6 --instance preprod-anchor
   ./showcase.sh verify all --instance preprod-anchor
   ```

7. Verify both L2 finality and L1 confirmation:

   ```bash
   curl -fsS \
     http://127.0.0.1:7070/api/v1/app-chain/chains/workflow-chain/status \
     | jq '{tipHeight,stateRoot,threshold,members,anchor}'
   ./showcase.sh ui --instance preprod-anchor
   ```

8. Restart normally; never repeat bootstrap:

   ```bash
   ./showcase.sh restart --instance preprod-anchor
   ./showcase.sh verify all --instance preprod-anchor
   ```

If bootstrap or an anchor fails with `TxBuildException`, first check node sync,
stable visibility of the funding UTxOs, pure-ADA input availability, collateral
and fee headroom, and current member readiness. Creating another usable UTxO
can resolve input contention, but do not create a new app-chain identity. See
[PREPROD_ANCHORING.md](PREPROD_ANCHORING.md) and
[TROUBLESHOOTING.md](TROUBLESHOOTING.md) for the shorter operator checklist.
