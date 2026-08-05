# payment-chain-l1bridge — L1 Custody Boundary Walkthrough

## Table of contents

1. [What this chain demonstrates](#1-what-this-chain-demonstrates)
2. [Custody model — read this first](#2-custody-model--read-this-first)
3. [Devnet: the automated flow](#3-devnet-the-automated-flow)
4. [Staged flow: deposit → L2 transfers → withdrawal](#4-staged-flow-deposit--l2-transfers--withdrawal)
5. [Public networks (preprod): your own funds](#5-public-networks-preprod-your-own-funds)
6. [Gotchas](#6-gotchas)
7. [Deeper reading](#7-deeper-reading)

## 1. What this chain demonstrates

The light cluster's `payments-chain` is a *virtual* EUTxO ledger — funds are
genesis-minted state and never touch the L1. `payment-chain-l1bridge`
(chains[10], ADR-UTXO-008) closes that gap on the same cluster: it is the
same `eutxo-ledger` state machine in **bridge mode**, with **no virtual
genesis** — every L2 fund enters through a real Cardano L1 deposit and
leaves through an operator-settled withdrawal.

The mechanics, all consensus-side per-chain config:

1. A user pays into the **vault script address** with an inline datum naming
   the L2 owner.
2. Every node's `eutxo-vault-deposit-v1` observer watches applied L1 blocks;
   after the deposit is `l1.stability-depth` (2) blocks deep, the proposer
   injects a `~l1/bridge-deposits` message and the deposit **mirrors** as an
   L2 UTxO.
3. L2 transfers are ordinary signed eutxo transactions.
4. An L2 output paying the chain's fixed **withdrawal address** becomes an
   irrevocable **withdrawal claim**.
5. The **operator** (vault keyholder) settles on L1: unlocks the vault, pays
   the claim's payout, and the `eutxo-withdrawal-confirmation-v1` observer
   reconciles the claim to `CONFIRMED`.

Start with the facts and live status any time:

```bash
./showcase.sh bridge info --instance <name>
```

## 2. Custody model — read this first

- The vault is a **single-operator-key native script** — whoever holds the
  operator key IS custody. Suitable for capped demo amounts, not real funds
  (ADR-UTXO-006/007 describe the hardened custody tiers).
- Both bridge identities are **PUBLIC deterministic demo keys** (seed =
  `sha256("yano-showcase-demo-actor:bridge-operator")` and `…:bridge-payout`)
  — the same convention as every other showcase demo actor. Anyone can derive
  them. Never reuse them outside showcase demos.
- Deposits are bounded by the observer's `max-lovelace` (100 ADA per
  deposit); withdrawals by `max-withdrawal-lovelace` (50 ADA).
- **A plain wallet transfer to the vault address is NOT a deposit.** The
  observer credits L2 owners from the inline vault datum; a datum-less
  payment is ignored and effectively strands the funds with the operator.
  Always build deposits with the provided tooling.

## 3. Devnet: the automated flow

Works on any devnet light instance (the flow faucet-funds disposable Alice /
Bob / operator wallets, so it cannot run on public networks). One command,
full custody round-trip:

```bash
./showcase.sh bridge run --instance <name>
```

fund → Alice deposits 20 ADA + Bob 10 ADA into the vault → both mirror as
L2 UTxOs → Alice pays Bob on the L2 → Bob's spend pays the withdrawal
address, creating the claim → operator unlocks the vault and pays the payout
address on L1 → claim reconciles to `CONFIRMED` → verified. Watch it in the
console: `/ui/app-chain/eutxo/?chain=payment-chain-l1bridge`.

Rounds are journaled and idempotent — re-running `bridge run` is a no-op;
new activity needs a higher round count:

```bash
./showcase.sh bridge run --count 2 --instance <name>   # rounds 1..2 (max 16)
```

Under the hood this is the maintained `appchain eutxo demo` CLI in **attach
mode**: the first bridge command creates a workspace at
`data/showcase/<instance>/bridge-workspace/` (wallets, journal, retained
signed transactions under `artifacts/l2/`) pinned to the running cluster; it
never starts or stops the cluster itself.

## 4. Staged flow: deposit → L2 transfers → withdrawal

Each stage runs the journaled pipeline up to that step — pause between
stages and inspect the console (already-complete rounds skip):

```bash
# 1. Deposits only (fund + vault deposits + mirrored L2 UTxOs):
./showcase.sh bridge deposit --instance <name>

# 2. The L2 transactions (Alice's payment, then Bob's claim-creating spend):
./showcase.sh bridge transfer --instance <name>

# 3. The withdrawal settlement and its CONFIRMED reconciliation:
./showcase.sh bridge settle --instance <name>
./showcase.sh bridge verify --instance <name>

# journal + workspace state any time:
./showcase.sh bridge status --instance <name>
```

Add `--count N` to any stage to batch rounds (two L2 transactions per
round), exactly like the eutxo profile's staged flow.

## 5. Public networks (preprod): your own funds

There is no faucet on preprod, so the automated flow refuses to run there —
you deposit **your own funds** with the showcase Java client instead. This
path is live-verified against a preprod-connected instance: the node's
Blockfrost-compatible API serves address UTxOs and protocol parameters
directly, so the client needs no external provider.

```bash
# your own funded preprod wallet (test ADA), 0600, or omit the flag for an
# interactive prompt — the mnemonic never appears in argv:
(umask 077; printf '%s\n' 'word1 word2 … word24' > user.mnemonic)

java -jar yano-showcase-client-<version>-all.jar eutxo \
  http://127.0.0.1:7070 payment-chain-l1bridge \
  deposit --mnemonic-file user.mnemonic --amount 5000000
# expect ~40s before MIRRORED (stability depth 2 at preprod block times)

java -jar … eutxo http://127.0.0.1:7070 payment-chain-l1bridge \
  utxos --mnemonic-file user.mnemonic
java -jar … eutxo http://127.0.0.1:7070 payment-chain-l1bridge \
  claim --mnemonic-file user.mnemonic --amount 2000000
# the claim pays out to YOUR address by default; the printed settle command
# is the operator's step
```

Settlement on preprod: the operator role signs with the PUBLIC deterministic
demo seed, and the operator address
(`addr_test1vrtrcvnxkm5rwf9mzf0v8aatp9mvjrc2enqjrlzyanaw79guu9kr0`) must
hold a little test ADA for fees before `settle` can run. See
`../appchain-showcase-client/README.md` for the full scenario reference;
`bridge info` always prints the current copy-paste command.

Custody reminder: on a public network the deterministic demo operator key is
still PUBLIC — anyone can derive it and take the vault funds. Preprod demos
are for walking the flow with test-ADA amounts you are willing to lose.

## 6. Gotchas

- **Pending membership epochs need a bridge block.** The chain admits only
  eutxo transactions and proposer-injected L1 observations, so after member
  changes the pending epoch cannot activate until the next bridge block —
  drive one with `./showcase.sh bridge deposit`. `governance activate` skips
  this chain with a note for the same reason.
- **The withdrawal address is the ONLY claim-forming L2 address.** Paying
  any other address is an ordinary L2 transfer; paying the withdrawal
  address is irreversible claim creation.
- **Deposit mirroring waits for stability.** Two L1 blocks must build on top
  of the deposit before it mirrors — on the devnet's 1s blocks that is
  seconds; on preprod it is ~40s typical.
- The attach workspace pins the target (`http://127.0.0.1:<http-base>`);
  commands repeat `--instance`/`--http-base` like every eutxo-family flow.
- `bridge run` on a freshly reset instance starts a new journal — earlier
  rounds' wallets are gone, but the chain's history of course remains.

## 7. Deeper reading

- `adr/app-layer/utxo/008-showcase-bridge-chain-and-user-driven-l1-deposits.md`
  — decisions and implementation log.
- `appchain/extensions/eutxo/README.md` — the eutxo-ledger machine, bridge
  config keys, and the maintained demo CLI (attach mode included).
- `docs/EUTXO_PROFILE.md` — the self-managed eutxo profile (ledger, bridge
  with its own devnet, zk ceremony).
- ADR-UTXO-006 / 007 — hardened custody beyond the single-key demo vault.
