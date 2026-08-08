# The settlement chain (`payment-chain-settlement`)

The showcase's Cardano L1 custody boundary. Deposits into a **Plutus vault**
mirror into an app chain; withdrawal claims are settled back out to the L1 by
the **federation itself** — no operator command, no single custody key.

Design and implementation log:
`adr/app-layer/utxo/009-claim-settlement-process-and-vault-conditions.md`.

> **Demo material.** Every key here is PUBLIC and derived from a published
> formula. The chain runs the **devnet-only** machine profile
> (`yano-eutxo-v3-bridge-settlement-devnet`), whose relaxed fallback-delay
> floor exists so the permissionless exit is demoable. Never use this
> profile, or these keys, for real funds.

## 1. What makes it different from a single-key bridge

The retired `payment-chain-l1bridge` held funds at a native-script address
derived from one operator key: that key *was* custody, and settlement was the
operator manually spending it. This chain replaces both halves:

| | single-key bridge (retired) | settlement chain |
|---|---|---|
| Vault | native script, one key | Plutus script |
| Who releases funds | the operator | the federation **threshold** |
| Settlement trigger | operator runs `settle` | the chain emits an effect; the node settles |
| Double-settlement | prevented by bookkeeping | prevented **on-chain** by nullifier shards |
| If the federation stops | funds stuck | anyone may exit provable claims (A3) |

## 2. The five-command demo

```bash
./showcase.sh quickstart --profile light --nodes 3
./showcase.sh settlement bootstrap     # deploy the L1 identity (once)
./showcase.sh settlement deposit       # L1 deposit -> mirrored L2 funds
./showcase.sh settlement withdraw      # L2 claim -> then just watch
./showcase.sh settlement status        # PENDING ... CONFIRMED
```

No restart after `bootstrap`: the devnet plan is deterministic and its
validators ship with the distribution, so the executor is already wired at
startup.

Between `withdraw` and `status` **nobody runs a settle command**. The chain:

1. forms a claim (payout + a governed executor bounty),
2. emits an `l1.settlement` effect once the batch trigger fires,
3. the owner node resolves the batch, reconstructs the nullifier mirror and
   checks it against the on-chain shard root, builds the Settle transaction,
4. members verify the proposed body against their OWN committed state and
   co-sign it over `~bridge/settlement/*`,
5. the batch lands on the L1; the batch confirmation observer sees the vault
   spend, and the ledger marks the claim CONFIRMED and reconciles the reserve.

`settlement info` prints the live identity, the deploy state, and the console
link at any time.

## 3. What `bootstrap` deploys

Two one-shot mints and the genesis threads:

- the **accepted-root thread** (an NFT) carrying the federation member set,
  threshold, accepted state root, and the governed `fallbackDelaySlots`;
- **16 nullifier shard threads**, each holding an MPF root of settled claim
  ids (a claim's shard is its id's last nibble);
- the **vault** itself.

It also writes the two parameterized validators into `config/settlement/` —
the chain config points at those files rather than carrying ~22 KB of script
hex. The command is idempotent: a live root thread short-circuits it.

On devnet everything is deterministic (the seed transaction spends a known
genesis UTxO), so the packaged chain config is static and golden-tested.

## 4. Deploying on preprod

Supported, with your own operator key — see
[PREPROD_SETTLEMENT.md](PREPROD_SETTLEMENT.md). In short:

```bash
mkdir -m 700 ./private-settlement
(umask 077; openssl rand -hex 32 > ./private-settlement/operator.seed)

./showcase.sh settlement prepare   --instance pp --settlement-key-file <file>
#   -> prints the address to fund and the exact amount
./showcase.sh settlement bootstrap --instance pp --settlement-key-file <file> \
    --confirm-public-settlement preprod
#   -> deploys on the PRODUCTION profile and prints the chain block to adopt
```

The production profile's fallback floor is 21,600 slots (~6 h), the real
safety window, so a *live* A3 exit demo takes hours rather than seconds. The
packaged demo actors are refused on every public network: their seeds come
from a published formula.

## 5. The nullifier mirror

L1 holds only the per-shard MPF **root**. The full trie is maintained
off-chain, and it is a cache rather than a trust dependency — the root is a
pure function of the settled-id set, so anyone can rebuild any shard from L1
history alone and verify it:

```bash
./yano.sh appchain eutxo nullifier reconstruct --ids settled-ids.txt \
    --shard 5 --expected-root <on-chain-root-hex>
./yano.sh appchain eutxo nullifier proof <claim-id-hex> --ids settled-ids.txt
```

That is what makes the permissionless exit real: a cranker needs no
surviving L2 node.

## 6. Facts worth knowing

- **The withdrawal address is the ONLY claim-forming L2 address.** Paying any
  other address is an ordinary L2 transfer.
- **A plain wallet transfer to the vault address is NOT a deposit** — the
  observer credits L2 owners from the inline vault datum, so use the tooling.
- **Deposits wait for L1 stability** (2 blocks) before mirroring.
- **Claims carry a bounty** on top of the payout; it pays whoever executes
  the settlement, and it is what funds the permissionless exit path.
- **One node owns settlement.** Exactly one member (node 0) runs the executor
  and leads co-sign rounds; the others verify and sign. That is coordination,
  not custody — releasing funds still needs the threshold.

## 7. Deeper reading

- `adr/app-layer/utxo/009-…` — settlement process, vault conditions,
  implementation log, and the open follow-ups (§13).
- `adr/app-layer/utxo/008-…` — the retired single-key bridge it replaces.
- `appchain/extensions/eutxo/README.md` — the eutxo-ledger machine and its
  config keys.
