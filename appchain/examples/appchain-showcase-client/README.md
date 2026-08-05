# Showcase Java Client Demo

Demo-only walkthroughs of the release-matched Java client
(`appchain-client`) against a running showcase cluster: the
authenticated-map chains and the EUTxO bridge chain. It
shows exactly what an integrating Java application does: build canonical
commands with the contract classes, submit through `StdlibAppChainClient`,
poll the root-attested receipt, read exact entries, and decode state only
after client-side merkle-proof verification.

Every scenario resolves the chain's committed genesis first, so the same
commands work against both showcase map chains:

- `authenticated-map-chain` — governed MPF chain (owner/member/schema/GTIN
  collections plus `governed-catalog` and `released-products`)
- `authenticated-map-jmt-chain` — basic classic-JMT contrast chain
  (`kv-open`, `documents`, `notes`)

## Prerequisites

A running showcase instance (see `appchain/examples/appchain-showcase`):

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance demo
```

The node API is then at `http://127.0.0.1:7070/api/v1` (node 0; add the
`--http-base` offset if you changed it).

## Run

**Fat jar** (recommended for demos — copy it anywhere, no Gradle needed):

```bash
./gradlew :appchain-showcase-client:shadowJar
# → build/libs/yano-showcase-client-<version>-all.jar

java -jar yano-showcase-client-<version>-all.jar authmap \
  http://127.0.0.1:7070/api/v1 authenticated-map-jmt-chain \
  basic-put kv-open java-demo-key java-demo-value
```

The first argument selects the chain-family demo (`authmap` today; sibling
demos for other showcase chains can register in `ShowcaseClientDemos` later
without changing this command shape).

Or from the repository root, via the Gradle `application` plugin (note: the
`run` task goes through the dispatcher too, so prefix the args with
`authmap`):

```bash
# Basic write to an open/owner/member collection (authorization kind is
# discovered from the chain's committed genesis):
./gradlew :appchain-showcase-client:run --args="authmap \
  http://127.0.0.1:7070/api/v1 authenticated-map-jmt-chain \
  basic-put kv-open java-demo-key java-demo-value"

# Governed direct-role write (issuer-a signs the authorization preimage
# in-JVM with the deterministic DEMO seed — showcase-only material; a real
# integration exports signingPreimage() to a wallet/KMS/HSM):
./gradlew :appchain-showcase-client:run --args="authmap \
  http://127.0.0.1:7070/api/v1 authenticated-map-chain \
  governed-put java-gov-key java-gov-value"

# Root-attested reads (exact entry + genesis identity + collection catalog):
./gradlew :appchain-showcase-client:run --args="authmap \
  http://127.0.0.1:7070/api/v1 authenticated-map-jmt-chain \
  reads kv-open java-demo-key"

# Bulk load: N unique-key writes through a small concurrent pool, with
# backpressure retries and a throughput/finality report (count 1-1000):
./gradlew :appchain-showcase-client:run --args="authmap \
  http://127.0.0.1:7070/api/v1 authenticated-map-jmt-chain \
  load kv-open 100"

# Trust-boundary demo: the entry value is decoded only after its merkle
# proof verifies against a caller-pinned trusted root:
./gradlew :appchain-showcase-client:run --args="authmap \
  http://127.0.0.1:7070/api/v1 authenticated-map-jmt-chain \
  verified-entry kv-open java-demo-key"
```

Each write prints the four §10.3 trust levels as they happen: HTTP accepted
for sequencing → finalized → state-machine APPLIED/REJECTED (with the
receipt's error code) → the re-read entry state. The governed scenario also
prints the one-time `(actor, authorizationId)` consumption record.

## EUTxO bridge chain (ADR-UTXO-008)

The `eutxo` demo drives `payment-chain-l1bridge`: deposit YOUR OWN L1 funds
into the chain's vault (with the mandatory inline datum — a plain wallet
transfer to the vault address is NOT a deposit), operate on the L2, create a
withdrawal claim, and settle it as the operator. Every call — L1 UTxO
queries, protocol parameters, submission, L2 reads — goes through the same
Yano node's Blockfrost-compatible API.

```bash
# user role: mnemonic via file (0600) or interactive prompt, never argv
(umask 077; printf '%s\n' 'word1 word2 … word24' > user.mnemonic)

java -jar yano-showcase-client-<version>-all.jar eutxo \
  http://127.0.0.1:7070 payment-chain-l1bridge \
  deposit --mnemonic-file user.mnemonic --amount 8000000
# → stages, pays the vault with the inline datum, waits for the L2 mirror

… eutxo http://127.0.0.1:7070 payment-chain-l1bridge utxos --mnemonic-file user.mnemonic
… eutxo … transfer --mnemonic-file user.mnemonic --to <l2-address> --amount 1000000
… eutxo … claim --mnemonic-file user.mnemonic --amount 2000000
# → prints the chain-assigned claim id and the operator command

# operator role (vault keyholder; on devnet the PUBLIC showcase demo seed):
… eutxo … settle --operator-seed-file operator.seed --claim-id <id>
# → pays the claim's L1 payout address and waits for CONFIRMED

… eutxo … receipt --tx <l2-transaction-id>
```

Notes:

- On a devnet instance, fund the depositor first:
  `curl -X POST http://127.0.0.1:7070/api/v1/devnet/fund -H 'Content-Type: application/json' -d '{"address":"<addr>","ada":50}'`.
  On preprod, use your own funded test wallet — and remember the showcase
  vault identities are PUBLIC deterministic demo keys (the operator key IS
  custody): only demo amounts.
- The claim's payout defaults to your own L1 address
  (`--payout-address` overrides); the L2 owner defaults to your own address
  (`--l2-owner-address` overrides).
- Defaults target the showcase's deterministic vault/withdrawal identities;
  `--vault-address` / `--withdrawal-address` support other bridge chains.
- The claim id embeds a chain-assigned sequence, so the client resolves it
  by probing the withdrawal snapshot after finality.

## What to read next

- `ShowcaseAuthMapClientDemo.java` — the complete flow in ~250 lines.
- `StdlibAppChainClient` (module `appchain-client`) — the typed submit/read
  surface used here, including `authenticatedMapApprovalCommand` and the
  governance topics not exercised by this demo.
- `AuthenticatedMapProofBundle` — composite proof-bundle verification
  (entry + receipt + policy + actor facts against one trusted root).
- `docs/appchain/state-machines/authenticated-map.md` — the wire/state
  contract behind every call.

## Caveats

- Demo seeds (`sha256("yano-showcase-demo-actor:" + actorId)`) exist only in
  showcase geneses. Never reuse them elsewhere.
- `verified-entry` pins the trusted root from the same node's proof envelope
  to keep the demo self-contained; production callers must obtain the root
  independently (finality certificate quorum or a confirmed L1 anchor).
- If the node enables API-key auth, add `.apiKey(...)` to the builder in
  `ShowcaseAuthMapClientDemo`.
