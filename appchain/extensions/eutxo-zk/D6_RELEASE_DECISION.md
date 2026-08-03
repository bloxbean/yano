# D6 release decision

<a id="release-decision"></a>

## Decision

Ship the integrated capability as `EXPERIMENTAL_TESTNET_ONLY`.

The `eutxo-zeroj-preview` recipe is a product-shaped testnet lifecycle, but it
does not have preview-grade security or live-network acceptance yet. The name
describes its intended testnet workflow; the catalog maturity and release
decision remain experimental.

The exact release contract is
[`acceptance/preview-release-contract-v1.json`](acceptance/preview-release-contract-v1.json).
The build binds it to the capability catalog and network evidence by SHA-256
and copies it into the JVM distribution. The contract fixes:

- JVM-only packaging;
- devnet, Preview, and Preprod support;
- unconditional mainnet rejection;
- `zeroj-jubjub-dev-v1`, trusted prover, host-only point checks, and
  disposable-test-funds policy;
- Cardano-shaped L2 body plus Yano Jubjub envelope;
- `cardano-payment-b16`, its digest, circuit, ZeroJ, Julc, Groth16, and curve
  identities; and
- the open external security and acceptance gates.

Changing any security-significant identity requires a new contract version or
an updated digest reviewed with the catalog. A hardened authorization profile
must also receive new circuit, R1CS, ceremony, proving-key, verification-key,
validator, and release identities.

## Compatibility statement

| Tool or boundary | Current support |
|---|---|
| Cardano Client Lib | Reuse Conway transaction-body and output builders; Yano adapter supplies L2 parameters/UTxOs and wraps the body in the Jubjub authorization envelope |
| `cardano-cli` | Build/sign ordinary Cardano L1 deposit, settlement, withdrawal, registration, and recovery transactions |
| Backend wallets | Normal Ed25519 L1 signing; no wallet change required for L1 |
| CIP-30 `signTx` | Normal L1 operations only; virtual L2 inputs are not assumed to be accepted by a browser wallet |
| CIP-30 `signData` | Optional deterministic L2 key derivation exists in the SDK design, but cross-wallet stability/security evidence is still required |
| Cardano body builders | Body format is reusable for the supported ADA-only subset; L2 authorization is an additive Yano envelope |

Unsupported L2 purposes remain rejected: minting, native assets, staking,
governance, certificates, withdrawals, reference inputs/scripts, collateral,
and general Plutus execution.

## Key and custody model

L1 remains ordinary Cardano Ed25519. A Cardano wallet signs deposits,
registration/recovery, batch settlement, and withdrawal transactions as
appropriate. User Jubjub keys never replace Cardano VKey witnesses and
individual L2 signatures are not published on L1.

For L2, clients choose one of:

- a cryptographically random Jubjub session key encrypted locally with
  AES-256-GCM/PBKDF2, backed up and rotated explicitly; or
- a domain-separated key derived from one CIP-8/CIP-30 `signData` result.

The deterministic derivation is convenient but remains experimental until
wallet determinism, domain presentation, phishing resistance, hardware-wallet
behavior, account switching, and recovery are tested. A wallet producing
unstable signatures must use the random-key path. Neither Cardano private
keys, Jubjub scalars, proving-key material, nor API-key values belong in logs,
project locks, evidence, or L1 batch data.

## Operational and security limitations

- A trusted sequencer/prover is required because not all host-enforced
  transaction semantics and Jubjub point constraints are adversarially
  constrained in the current circuit. In particular, the integrated
  development lifecycle derives and matches the settlement context,
  transaction-manifest commitment, and aggregate withdrawal at its host
  boundary; the circuit exposes them as public inputs but does not yet derive
  the withdrawal from Cardano-shaped transaction CBOR.
- The b16 development setup is single-party and unsuitable for production.
- Yano devnet staging acceptance, stable deposit credit, root/vault
  deployment, validity-root settlement, payout from accepted custody funds,
  and stable withdrawal reconciliation have a disposable-funds source test.
  Maximum-batch, rollback/recovery, and independent-reconstruction evidence
  remain open on devnet; all live rows remain `NOT_EXERCISED` on Preview and
  Preprod.
- Data publication/reconstruction, rollback, disaster recovery, censorship,
  proof availability, operator loss, and long-running performance need live
  evidence.
- The 16-transaction bound is immutable for this profile. b32 and b64 are
  unmeasured candidates, not runtime settings.
- Mainnet cannot be enabled by acknowledgement, configuration override, or
  hidden flag. It requires a later ADR and accountable release decision.

## Graduation

The capability may move to preview only after a hardened authorization
profile and its independent audit, ceremony, live-network, recovery, and
reconstruction gates pass. The non-selectable
`rollup:zeroj-cardano` label remains unavailable until the stricter Z5/Z6
production-readiness gates and a separate funds-custody decision are complete.
