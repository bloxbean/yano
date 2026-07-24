# ADR-UTXO-002: Unified Disposable EUTxO Demo Experience and Resumable L1/L2 Workflows

## Status

Proposed — version 1

This ADR defines a disposable, development-only product experience for the
non-ZK and ZeroJ-backed EUTxO capabilities selected by ADR-UTXO-001. It does
not change the EUTxO ledger, bridge, validity circuit, settlement contracts,
or production operator trust model.

## Date

2026-07-24

## Decision owners

Yano maintainers and app-chain/EUTxO capability owners.

## Parent and related decisions

- [ADR-UTXO-001](001-eutxo-state-machine-and-cardano-settlement.md) owns the
  EUTxO ledger, Cardano bridge, ZeroJ validity settlement, transaction
  profiles, contracts, network posture, and production-safety boundaries.
- [ADR-DX-0001](../dx/0001-unified-appchain-onboarding-configuration-and-lifecycle.md)
  owns generic app-chain project initialization, rendering, validation,
  doctor, lock files, and lifecycle tooling.
- [ADR-011](../011-plugin-architecture.md) and its follow-ons own plugin
  isolation and lifecycle.
- [ADR-022](../022-out-of-box-appchain-capabilities-and-extensible-product-catalog.md)
  owns the out-of-box capability catalog and extension model.
- [ADR-028](../../028-unified-console-ui-module.md) owns runtime-console UI
  concerns. This ADR may provide links and public demo status, but it does not
  define new console architecture.

If this ADR conflicts with ADR-UTXO-001 on consensus, custody, proof security,
or network posture, ADR-UTXO-001 wins.

## 1. Decision in plain words

Yano will provide a disposable EUTxO demo experience that reduces the current
manual setup and L1/L2 round trip to a few commands:

```bash
./yano.sh appchain eutxo demo setup --scenario zk
./yano.sh appchain eutxo demo start
./yano.sh appchain eutxo demo round-trip
```

The same facade supports three scenarios:

| Scenario | Purpose | L1 operations | Proof system |
|---|---|---:|---|
| `ledger` | Virtual-genesis EUTxO ledger and Cardano-shaped L2 transfers | No | None |
| `bridge` | Cardano deposit, L2 spend, and non-ZK bridge withdrawal | Yes | Existing non-ZK bridge path |
| `zk` | Cardano deposit, Jubjub-authorized L2 spend, Groth16 settlement, and proof withdrawal | Yes | ZeroJ Groth16 |

The default scenario is `ledger`. Selecting `bridge` or `zk` must always be
explicit.

The implementation is split into:

```text
appchain/extensions/eutxo/appchain-eutxo-demo
appchain/extensions/eutxo-zk/appchain-eutxo-zk-demo
```

The base module owns the reusable demo workspace, identity generation,
project provisioning, Cardano/EUTxO operation journal, cluster lifecycle, and
the `ledger` and `bridge` scenarios. The optional ZK module depends on the
base module and contributes only the `zk` scenario, Jubjub key handling,
development ceremony, proof generation, validity-root settlement, and
proof-withdrawal orchestration.

A thin packaged shell launcher may locate the distribution and invoke the
Java demo CLI. Java, not shell, constructs and signs transactions, handles
secret material, applies protocol bounds, persists the operation journal,
and decides whether a step can be safely resumed.

## 2. Context

### 2.1 Current manual experience

A complete disposable ZK cluster currently requires an operator to:

1. resolve and export distribution, workspace, project, chain, and endpoint
   paths;
2. create Cardano operator, payout, and script-vault identities through a
   separate Java or JShell session;
3. copy non-secret values without accidentally retaining output labels;
4. create and encrypt a Jubjub session key;
5. supply every recipe answer to `appchain init`;
6. copy and populate three app-chain member secret files;
7. validate, doctor, and start a generated project;
8. build and sign multiple Cardano transactions;
9. wait for L1 stability and L2 mirroring;
10. construct and submit a Cardano-shaped L2 transaction;
11. export the exact finalized transition;
12. prove, settle, withdraw, reconcile, and verify.

These are useful protocol boundaries, but they are not an appropriate quick
start.

### 2.2 Verified failure mode

During manual testing, a shell value was copied as:

```text
L2_ADDRESS=addr_test1...
```

instead of:

```text
addr_test1...
```

The malformed value passed project generation, reached plugin construction,
and appeared to the user only as a node-readiness failure. A secondary
plugin-loader failure then obscured the original configuration error.

This illustrates three separate gaps:

- setup is too easy to perform inconsistently;
- project answers need stronger type- and grammar-aware validation; and
- generated launchers need to surface the first actionable startup failure.

Automation reduces exposure to these errors, but it must not replace
validation. Manual production paths still require the same validation.

### 2.3 Existing reusable foundations

The repository already contains:

- generic `appchain init`, render, validate, doctor, and generated project
  launchers;
- EUTxO transaction, UTxO, proof, and doctor clients;
- Cardano bridge observation and withdrawal components;
- EUTxO validity lifecycle, encrypted Jubjub key, proof, and reconciliation
  components;
- Cardano Client Lib transaction construction and signing;
- Julc-compiled development validators;
- a live `EutxoZkRollupDevnetE2ETest` exercising disposable L1 deposit,
  stable L2 credit, Jubjub-authorized L2 spending, exact transition proving,
  L1 root advancement, payout, and L2 reconciliation.

The demo must turn these foundations into reusable product code. It must not
copy the E2E implementation into a shell script.

## 3. Decision drivers

1. A new user should observe a working EUTxO cluster within minutes.
2. The demo should explain the protocol boundaries without requiring users
   to assemble every low-level artifact manually.
3. Non-ZK and ZK experiences should feel consistent.
4. The base EUTxO distribution must not depend on ZeroJ.
5. Transactions, proofs, and journals must be deterministic and resumable.
6. Demo key generation must never be confused with production provisioning.
7. Existing project generation, validation, lifecycle, and client code should
   remain authoritative.
8. New scenarios and optional plugins should be addable without rewriting the
   launcher.
9. Shell code should remain thin and portable.
10. Failure output must identify the first actionable problem.

## 4. Goals and non-goals

### 4.1 Goals

- One public command namespace for all disposable EUTxO demos.
- One-command project setup and optional setup-plus-start.
- Scenario-specific complete round trips.
- Granular commands corresponding to meaningful protocol transitions.
- Safe restart and resume after interruption.
- Inspectable public artifacts and operation history.
- JVM distribution packaging and source-tree parity.
- Strict devnet and disposable-funds enforcement.
- Reuse by automated E2E and final-distribution acceptance tests.
- Extension points for additional EUTxO profiles without shell rewrites.

### 4.2 Non-goals

- Generating production or public-testnet custody keys.
- Automating mainnet, Preview, or Preprod signing.
- Replacing a production wallet, HSM, threshold signer, or ceremony process.
- Hiding trust assumptions of the non-ZK bridge or development ZK profile.
- Making demo-generated configuration a supported production baseline.
- Adding new EUTxO consensus rules.
- Moving Cardano transaction construction into Yano core.
- Providing a general-purpose wallet CLI.
- Treating a successful demo as production-security evidence.

## 5. Options considered

### 5.1 Documentation and manual commands only

Keep the current walkthrough as the only experience.

Advantages:

- no new code;
- every protocol step remains visible.

Disadvantages:

- high error rate;
- poor first-run experience;
- manual setup and the automated E2E path can drift;
- difficult to demonstrate repeatedly.

Rejected as the primary onboarding experience. The walkthrough remains the
advanced/manual reference.

### 5.2 Implement the complete workflow in Bash

Advantages:

- quick initial implementation;
- easy to inspect.

Disadvantages:

- unsafe secret and CBOR handling;
- platform-specific parsing and file-permission behavior;
- duplicates Java transaction and validation logic;
- fragile recovery between build, submit, stability, and reconciliation;
- difficult to test adversarially.

Rejected. Shell is a launcher, not the workflow engine.

### 5.3 Put all scenarios in one ZK-aware demo module

Advantages:

- one implementation artifact.

Disadvantages:

- makes the base EUTxO demo depend on ZeroJ;
- obscures optional packaging;
- makes non-ZK users carry ZK ceremony and prover dependencies.

Rejected.

### 5.4 Base demo module plus optional scenario extension

Advantages:

- shared UX and journaling;
- preserves optional ZK packaging;
- supports additional scenario providers;
- reusable by E2E tests;
- keeps scenario-specific security checks close to their owning extension.

Selected.

## 6. Public command contract

### 6.1 Primary commands

```bash
./yano.sh appchain eutxo demo setup [options]
./yano.sh appchain eutxo demo start [options]
./yano.sh appchain eutxo demo up [options]
./yano.sh appchain eutxo demo status [options]
./yano.sh appchain eutxo demo stop [options]
./yano.sh appchain eutxo demo reset --yes [options]
./yano.sh appchain eutxo demo round-trip [options]
./yano.sh appchain eutxo demo verify [options]
```

Common options:

```text
--scenario ledger|bridge|zk     default: ledger
--workspace <directory>         default: <distribution>/eutxo-demo
--name <project-name>           default: payments-eutxo
--chain-id <chain-id>           default: project name
--members <count>               default: 3; bounded by project tooling
--http-port-base <port>         default: 7070
--server-port-base <port>       default: 13337
--format text|json              default: text
```

`setup` creates a project but does not start processes. `start` requires an
existing setup. `up` performs `setup` when no project exists, starts the
cluster, and performs scenario preflight. It must never overwrite an existing
workspace.

The selected scenario is persisted in the demo manifest. Later commands do
not require the user to repeat `--scenario`. Supplying a conflicting scenario
must fail.

### 6.2 Scenario operations

```bash
./yano.sh appchain eutxo demo fund
./yano.sh appchain eutxo demo deposit
./yano.sh appchain eutxo demo transfer
./yano.sh appchain eutxo demo prove
./yano.sh appchain eutxo demo settle
./yano.sh appchain eutxo demo withdraw
./yano.sh appchain eutxo demo reconcile
./yano.sh appchain eutxo demo verify
```

Unsupported commands fail with a scenario-specific explanation:

| Command | `ledger` | `bridge` | `zk` |
|---|---:|---:|---:|
| `fund` | Not required | Yes | Yes |
| `deposit` | Not required | Yes | Yes |
| `transfer` | Yes | Yes | Yes |
| `prove` | No | No | Yes |
| `settle` | No | Bridge-specific settlement only | Validity-root settlement |
| `withdraw` | No L1 withdrawal | Yes | Yes |
| `reconcile` | Local verification | Yes | Yes |
| `verify` | Yes | Yes | Yes |

The first implementation may expose only the commands used by the maintained
scenario plans. It must not advertise a placeholder command as working.

### 6.3 Scenario round trips

`ledger`:

```text
virtual genesis
  -> Cardano-shaped zero-fee L2 transfer
  -> app-chain finality
  -> UTxO and MPF proof verification
```

`bridge`:

```text
devnet faucet
  -> L1 staging transaction
  -> L1 deposit acceptance
  -> L1 stability
  -> mirrored L2 UTxO
  -> L2 transfer/withdrawal claim
  -> non-ZK settlement path
  -> L1 payout
  -> L2 reconciliation
```

`zk`:

```text
devnet faucet
  -> L1 staging transaction
  -> L1 deposit acceptance
  -> L1 stability
  -> mirrored L2 UTxO
  -> Jubjub-authorized Cardano-shaped L2 transaction
  -> app-chain finality
  -> exact finalized-transition export
  -> b16 Groth16 proof
  -> proof verification
  -> L1 validity-root settlement
  -> L1 proof withdrawal
  -> L1 stability
  -> L2 reconciliation
```

`round-trip` executes the selected plan and resumes an existing incomplete
plan by default. It does not silently start a different operation when an
incompatible incomplete operation exists.

### 6.4 Demonstration defaults

The maintained bridge and ZK scenarios use bounded, visibly disposable
defaults, for example:

```text
faucet amount:       100 ADA
accepted deposit:     10 ADA
withdrawal:            3 ADA
expected L2 change:    7 ADA
```

The exact accepted defaults are versioned with the scenario descriptor and
may change after fee/budget tests. User-provided amounts must satisfy the
scenario and contract bounds before any transaction is built.

### 6.5 Expected human output

Setup:

```text
EUTXO_DEMO_READY

Scenario : zk
Project  : .../eutxo-demo/payments-eutxo
Network  : devnet
Nodes    : 3
Profile  : cardano-payment-b16
Ports    : 7070-7072
Secrets  : disposable development identities

Next:
  ./yano.sh appchain eutxo demo start
```

Successful ZK round trip:

```text
EUTXO_ZK_DEMO_ROUND_TRIP_PASS

Funding Tx    : <tx-id>
Deposit Tx    : <tx-id>
L2 Tx         : <tx-id>
App Height    : <height>
Proof         : <proof-id>
Settlement Tx : <tx-id>
Withdrawal Tx : <tx-id>
Validity Root : <root>
L1 Payout     : <lovelace>
Profile       : cardano-payment-b16
Console       : http://127.0.0.1:7070/ui/app-chain/
```

JSON output carries the same fields with stable status codes. It never carries
a mnemonic, private key, key password, signed witness, or secret path
contents.

## 7. Module architecture

### 7.1 Base module

```text
appchain/extensions/eutxo/appchain-eutxo-demo
```

Responsibilities:

- public demo CLI and scenario-provider SPI;
- distribution/workspace resolution;
- demo manifest and operation journal;
- safe filesystem layout, permissions, and locking;
- disposable app-chain member identities;
- disposable Cardano operator, payout, and vault identities;
- generic cluster project generation and lifecycle;
- bounded Cardano transaction submission and stability polling;
- shared L1 deposit and non-ZK withdrawal orchestration;
- virtual-ledger and bridge scenario providers;
- public artifact and verification report generation.

Illustrative types:

```text
EutxoDemoCli
EutxoDemoScenario
EutxoDemoScenarioProvider
EutxoDemoWorkspace
EutxoDemoManifest
EutxoDemoJournal
EutxoDemoIdentityService
EutxoDemoCluster
EutxoDemoWorkflow
EutxoDemoVerifier
```

These names are descriptive, not frozen API names.

### 7.2 Optional ZK module

```text
appchain/extensions/eutxo-zk/appchain-eutxo-zk-demo
```

Responsibilities:

- `zk` scenario provider;
- encrypted Jubjub session-key provisioning;
- development-ceremony orchestration;
- finalized-transition export;
- b16 proof job creation, polling, and verification;
- validity-root settlement transaction orchestration;
- proof-withdrawal transaction orchestration;
- ZK-specific verification report and security labels.

The module depends on `appchain-eutxo-demo`. The base module has no compile or
runtime dependency on ZeroJ, Julc ZK validators, or the ZK prover.

### 7.3 Scenario discovery

The demo CLI discovers scenario providers from the installed distribution.
The maintained distribution must expose a deterministic scenario inventory.

Rules:

- duplicate scenario IDs fail startup;
- a selected but unavailable scenario fails before workspace creation;
- scenario availability is derived from installed, verified artifacts;
- external providers cannot upgrade their maturity or security label;
- scenario providers contribute code, while catalog metadata remains
  data-only and trust-verified under ADR-022;
- the selected provider ID and artifact identity are pinned in the demo
  manifest.

### 7.4 Reusable transaction code

Transaction, datum, redeemer, proof, and commitment builders that implement a
protocol rule belong in the existing ledger, bridge, client, on-chain, or ZK
modules—not in demo code.

The demo module owns orchestration and disposable identity policy only.

Reusable logic currently embedded in
`EutxoZkRollupDevnetE2ETest` must be extracted into its appropriate production
module. The E2E test and demo scenario then consume the same implementation.
Test source must not become a runtime dependency.

### 7.5 Shell and `yano.sh`

The distribution may package:

```text
<distribution>/eutxo-demo/demo.sh
```

The script may:

- resolve the distribution root;
- establish a bounded JVM invocation;
- delegate arguments to `EutxoDemoCli`;
- preserve the Java process exit code.

It must not:

- construct or sign transactions;
- parse or modify YAML/JSON project files;
- manage private keys;
- decide recovery or idempotency;
- implement scenario state transitions.

The documented public facade remains:

```bash
./yano.sh appchain eutxo demo ...
```

`yano.sh` delegates to the packaged demo launcher. The direct script is useful
for maintainers but is not a second independent command contract.

## 8. Workspace and secret model

### 8.1 Layout

```text
eutxo-demo/
  demo.yaml
  project/
    appchain.yaml
    appchain.lock
    config/
    scripts/
    data/
    logs/
  secrets/
    members/
    cardano/
    l2/
  runtime/
    journal/
    validity/
    locks/
  artifacts/
    l1/
    l2/
    proofs/
    reports/
```

The exact names may be adjusted to reuse the generated project layout. The
following properties are mandatory:

- one explicit demo marker at the workspace root;
- secrets are outside generated configuration;
- public artifacts and secret artifacts are not mixed;
- runtime journals survive process restart;
- a workspace is self-contained and movable only while stopped;
- generated project digest rules remain authoritative.

### 8.2 Secret handling

The demo generates disposable secrets only on `devnet`.

Requirements:

- secret directories use mode `0700` where supported;
- secret files use mode `0600` where supported;
- secrets and runtime ceremony state are gitignored;
- private values never appear in `appchain.yaml`, lock files, generated
  consensus configuration, console output, JSON output, process arguments, or
  logs;
- secret files are created without following symbolic links;
- existing secret files are never overwritten;
- the Jubjub key remains encrypted at rest;
- the generated password is stored as a secret file, not exported as a
  long-lived shell variable;
- the demo operator mnemonic is marked disposable and is never accepted by a
  non-demo lifecycle command as production evidence.

### 8.3 No silent overwrite

`setup` refuses:

- an existing non-empty output directory;
- a workspace without the exact demo marker;
- a workspace whose manifest or project lock cannot be verified;
- a symbolic-link target;
- an output path outside the caller-selected workspace after normalization.

`reset --yes` operates only on a verified demo workspace and only on paths
listed by its manifest. It stops processes before removing disposable data.
It must never accept the distribution root, repository root, user home, or a
broad unresolved path as a deletion target.

## 9. Manifest and operation journal

### 9.1 Demo manifest

The non-secret manifest pins:

```text
schema version
tool version
scenario ID and version
scenario-provider artifact identity
network = devnet
project and chain identity
runtime and deployment selection
member count and public member keys
public Cardano addresses and script hashes
EUTxO profile and digest
bridge profile and digest, when selected
validity, authorization, batch, circuit, ceremony, and validator identities,
  when selected
workspace-relative public and secret references
created-at informational timestamp outside deterministic project identity
```

The manifest contains references to secret files, never secret values.

### 9.2 Operation journal

Each meaningful external operation has a stable operation ID and progresses
through explicit states:

```text
PLANNED
BUILT
SUBMITTED
OBSERVED
STABLE
RECONCILED
VERIFIED
FAILED_RETRYABLE
FAILED_TERMINAL
```

Not every operation uses every state. State transitions are monotonic except
for an explicit L1 rollback transition that returns a previously stable
observation to the correct recovery state.

The journal records only public or digest material:

- operation and scenario IDs;
- signed-CBOR digest and bounded relative artifact path;
- transaction ID and output index;
- expected datum/redeemer/script identities;
- app-chain message and transaction IDs;
- app height and state/validity roots;
- proof and public-input digests;
- submission endpoint identity without credentials;
- observation and stability status;
- failure class and safe diagnostic code.

The journal never records mnemonics, private keys, passwords, raw secret
witnesses, API keys, or unbounded remote error text.

### 9.3 Resume and concurrency

- A workspace has a single-owner operation lock.
- Re-running a command resumes the same operation when its request digest
  matches.
- A conflicting request fails rather than replacing an in-flight operation.
- A `BUILT` signed transaction is resubmitted byte-for-byte.
- A `SUBMITTED` operation is queried before any resubmission.
- A stable L1 operation is rechecked for rollback before dependent actions.
- A proof is reused only when all pinned identities and public inputs match.
- `round-trip` is a scenario plan over journalled operations, not a second
  unjournalled implementation.

## 10. Scenario behavior

### 10.1 `ledger`

Setup selects the maintained `eutxo-ledger` recipe and virtual genesis
funding. It generates a Cardano key-controlled genesis address and a second
recipient address.

The round trip:

1. starts the three-node project;
2. confirms identical chain/profile identity on all nodes;
3. builds a supported Cardano transaction body;
4. signs it with the disposable Cardano VKey;
5. submits the canonical transaction through the existing EUTxO client;
6. waits for app-chain finality;
7. verifies spent and created UTxOs;
8. verifies MPF inclusion/exclusion proofs and identical roots across nodes.

This scenario does not start an L1 deposit, bridge, ceremony, or prover.

### 10.2 `bridge`

Setup selects the maintained `eutxo-cardano-bridge` recipe and creates the
disposable operator, payout, staging, and accepted-vault identities required
by ADR-UTXO-001.

The round trip:

1. funds the disposable operator through the Yano devnet faucet;
2. builds and signs the staging transaction;
3. builds and signs the acceptance transaction;
4. submits and waits for stability;
5. verifies exact stable L1 observation and mirrored L2 UTxO;
6. builds and signs a supported L2 spend/withdrawal claim;
7. waits for finality;
8. executes the maintained non-ZK settlement path;
9. waits for stable L1 payout;
10. reconciles and verifies reserve, claim, payout, and node roots.

The scenario must display the selected non-ZK trust boundary. It must not
describe a federated or threshold-accepted root as a validity proof.

### 10.3 `zk`

Setup selects `eutxo-zeroj-preview`, creates the bridge identities, generates
and encrypts the Jubjub session key, and pins every development validity
identity required by ADR-UTXO-001.

The development ceremony is an explicit durable action:

```bash
./yano.sh appchain eutxo demo ceremony --yes
```

`up --with-ceremony --yes` may include it for a one-command demonstration.
Without `--yes`, a memory-intensive ceremony is not started implicitly.

The round trip:

1. performs the stable bridge deposit;
2. builds a supported Cardano-shaped L2 transaction;
3. signs the L2 envelope with the encrypted Jubjub key;
4. submits through the existing app-chain message endpoint/client;
5. waits for app finality;
6. exports the exact finalized transition and witnesses;
7. schedules the transition in the fixed b16 profile;
8. generates and verifies the Groth16 proof;
9. builds, signs, and submits the Cardano validity-root transaction;
10. waits for stable root advancement;
11. builds, signs, and submits the proof-withdrawal transaction;
12. waits for stable payout;
13. reconciles L2 withdrawal state;
14. verifies public inputs, roots, payout, claim, and all node identities.

Every output labels the scenario as the current experimental,
trusted-prover, disposable-funds development profile.

## 11. Validation and startup diagnostics

Automation does not waive validation.

### 11.1 Project-answer validation

Before rendering, maintained recipes validate:

- Cardano address syntax and expected network;
- no assignment-label prefixes such as `NAME=value`;
- 28-byte lowercase script-hash encoding;
- fixed-size lowercase public-key encoding;
- positive key epoch and amount bounds;
- relationship between the L2 address and registered payment credential;
- scenario/recipe/network compatibility;
- selected artifact and profile availability.

Invalid answers fail `appchain init`, Studio generation, and demo setup with
the same safe diagnostic code.

### 11.2 Startup lifecycle

Generated `start`:

- runs project and distribution preflight before spawning a node;
- validates secret references without printing values;
- captures the first node initialization failure;
- prints a bounded actionable diagnostic and the log path;
- stops partial processes;
- preserves logs and journal state for inspection.

Metrics or status initialization must not make an otherwise retryable
configuration failure terminal by closing the shared plugin-loader handle.
Runtime startup remains single-owner, and observers read the constructed
runtime rather than independently forcing competing construction attempts.

This is application/runtime lifecycle hardening, not an EUTxO consensus
change.

## 12. Distribution and source-tree experience

The JVM distribution contains:

- the base EUTxO demo module;
- the ZK demo module only when the ZK capability is advertised as bundled;
- the thin demo launcher;
- scenario descriptors;
- all existing project, client, lifecycle, and runtime artifacts required by
  advertised scenarios.

The release capability index must not advertise a scenario whose provider is
absent.

From an extracted distribution:

```bash
./yano.sh appchain eutxo demo up --scenario ledger
```

From source:

```bash
./app/yano.sh appchain eutxo demo up --scenario ledger
```

Tutorial commands use `./yano.sh`; their introduction explains the source
wrapper versus extracted-distribution location, following ADR-DX-0001.

## 13. Extensibility

### 13.1 New maintained scenarios

A scenario provider declares:

```text
scenario ID and version
required recipe and capabilities
supported networks
required installed artifacts
operation plan
configuration-answer producers
public defaults and bounds
secret references it owns
verification checks
security/trust label
documentation and acceptance scenario
```

The shared engine supplies workspace, journal, cluster, Cardano submission,
stability, safe output, and lifecycle primitives.

### 13.2 Custom plugins

Custom plugins may contribute a demo scenario only through a reviewed,
trust-verified scenario-provider artifact. External catalog metadata alone
cannot inject executable scripts or Java classes.

Custom scenario rules:

- cannot select mainnet through the disposable demo facade;
- cannot weaken file, secret, path, amount, or journal bounds;
- cannot claim a higher maturity/security tier than its verified catalog;
- cannot replace built-in scenario IDs;
- cannot write outside the verified workspace;
- cannot introduce unbounded command output or telemetry.

The first implementation may support maintained providers only while keeping
the SPI package-private or explicitly experimental. Public custom-provider
support graduates only after lifecycle and trust tests exist.

## 14. Observability and educational output

The demo is not a black box.

For each completed operation, text output shows:

- what protocol transition occurred;
- the public transaction/message/proof identity;
- whether the state is built, submitted, observed, stable, or verified;
- which underlying Yano capability performed it;
- where public artifacts were written;
- the next granular command.

`status` summarizes:

```text
cluster readiness
L1 tip and stability
app-chain tip/finality/root agreement
deposit state
L2 transaction state
proof state
validity-root settlement state
withdrawal/reconciliation state
security profile and warnings
```

The console URL may be printed. The console reads existing runtime APIs and
metrics; this ADR does not add a second demo-only monitoring protocol.

## 15. Testing and build gates

### 15.1 Unit and contract tests

- manifest and journal canonicalization;
- crash-safe atomic journal replacement;
- request digest and resume behavior;
- workspace locking;
- no-silent-overwrite and path/symlink rejection;
- secret file permissions and output redaction;
- scenario discovery, collision, and missing-artifact failure;
- scenario command matrix;
- amount and identity validation;
- stable text and JSON status codes;
- `yano.sh` and direct launcher argument parity.

### 15.2 Transaction and protocol tests

- transaction builders remain in their owning reusable modules;
- demo and E2E consumers produce identical canonical CBOR;
- build/reload/resubmit preserves exact signed bytes;
- datum, redeemer, script, profile, and network identities are pinned;
- L2 authorization uses the exact registered identity;
- proof public inputs derive from the exact finalized transition;
- withdrawal output and reconciliation match the proof/claim.

### 15.3 Failure-injection tests

Interrupt after each boundary:

```text
identity generation
project render
node 0 start
partial cluster start
transaction build
transaction submit
L1 observation
L1 stability
L2 submission
app finality
transition export
proof generation
root submission
withdrawal submission
reconciliation
```

Restart must either resume safely or emit a terminal diagnostic. It must not
generate a conflicting transaction, proof, key, or project.

### 15.4 Live scenario tests

- `ledger`: three-node finality, UTxO state, proof, and root agreement;
- `bridge`: real disposable Yano-devnet L1 → L2 → L1 round trip;
- `zk`: real disposable Yano-devnet deposit → L2 → Groth16 proof → root
  settlement → proof withdrawal → reconciliation;
- rollback around deposit, settlement, and withdrawal stability;
- restart after each submitted transaction;
- final-distribution execution with no source-tree classpath.

### 15.5 Build gates

The build fails when:

- a documented scenario is absent from the packaged inventory;
- a scenario advertises an unavailable artifact;
- the source and distribution launchers disagree;
- a recipe accepts a maintained malformed demo identity;
- a demo secret appears in a public generated file or captured output;
- a scenario E2E uses test-only transaction construction instead of the
  reusable runtime implementation;
- final-distribution setup/start/round-trip contract tests fail.

Expensive live proof tests may remain a separately named acceptance task, but
the release gate must record their exact result rather than silently skipping
them.

## 16. Delivery plan

Each milestone should be implemented, tested, reviewed, and merged into the
EUTxO integration branch before the next milestone begins.

### DEMO-M0 — Startup correctness and preflight

Scope:

- add grammar-aware validation for maintained EUTxO/bridge/ZK recipe answers;
- reject labeled address values and malformed public identities during init;
- ensure metrics/status observers do not poison plugin-loader ownership after
  an initialization failure;
- make generated cluster startup surface the first actionable error;
- add regression coverage for the observed malformed-L2-address failure.

Exit criteria:

- the previously observed bad address fails before project rendering;
- a valid generated ZK project reaches node readiness;
- failure output identifies the configuration field without reflecting
  secrets;
- existing generic app-chain scenarios remain unaffected.

### DEMO-M1 — Shared demo contracts, workspace, and journal

Scope:

- create `appchain-eutxo-demo`;
- implement the CLI, scenario SPI, manifest, workspace safety, operation lock,
  journal, output model, and disposable identity boundary;
- provide deterministic installed-scenario discovery;
- add packaged-launcher contract tests.

Exit criteria:

- setup can create a safe empty workspace and identities without project
  generation;
- restart reads the same manifest/journal;
- secret scanning, collision, symlink, overwrite, and concurrency tests pass.

### DEMO-M2 — Non-ZK virtual-ledger experience

Scope:

- implement `ledger` setup/start/up/status/stop/reset;
- call the existing `eutxo-ledger` project recipe;
- provision generated member secrets;
- implement transfer and proof verification;
- extract any reusable CCL transaction construction from tests.

Exit criteria:

- three commands produce and verify a three-node virtual-ledger demo;
- `round-trip` resumes after injected failures;
- source and extracted-distribution experiences match.

### DEMO-M3 — Non-ZK bridge round trip

Scope:

- implement disposable Cardano operator, payout, staging, and vault setup;
- reuse bridge transaction builders and lifecycle journals;
- implement fund, deposit, transfer, non-ZK withdraw, reconcile, and verify;
- expose the bridge trust boundary in every status/report.

Exit criteria:

- a real disposable Yano-devnet L1 → L2 → L1 round trip passes;
- deposit and withdrawal rollback/restart tests pass;
- no ZK artifact is required or loaded.

### DEMO-M4 — Optional ZK setup and ceremony

Scope:

- create `appchain-eutxo-zk-demo`;
- contribute the `zk` scenario through installed-provider discovery;
- provision encrypted Jubjub session keys;
- generate the `eutxo-zeroj-preview` project;
- implement explicit development ceremony and ZK doctor/status integration.

Exit criteria:

- base demo tests pass without the ZK module;
- installed ZK scenario setup/start/ceremony passes from the final JVM
  distribution;
- missing or mismatched proof identities fail before transaction submission.

### DEMO-M5 — ZK round-trip orchestration

Scope:

- implement Jubjub L2 transaction build/sign/submit;
- export and batch exact finalized transitions;
- prove and verify;
- construct/submit root settlement and proof withdrawal;
- wait for stability, reconcile, and produce the final report;
- refactor `EutxoZkRollupDevnetE2ETest` to consume the shared workflow.

Exit criteria:

- `round-trip --scenario zk` prints
  `EUTXO_ZK_DEMO_ROUND_TRIP_PASS`;
- maximum maintained b16 and partial-batch paths are verified;
- interruption at every journal boundary resumes safely;
- public artifacts independently verify against pinned identities.

### DEMO-M6 — Packaging, documentation, and stabilization

Scope:

- package providers and launcher in the JVM distribution;
- add capability/recipe/scenario inventory parity gates;
- update the EUTxO, bridge, ZK, tutorials, and distribution documentation;
- document granular commands and manual production equivalents;
- add final-distribution and clean-machine acceptance;
- retain experimental labels and external security gates.

Exit criteria:

- a new user can build/extract Yano and complete each maintained scenario
  without JShell, manual YAML editing, or exported secret variables;
- the manual operator path remains documented and independently usable;
- no native, Preview, Preprod, mainnet, or production-security claim is
  implied by the demo.

## 17. Consequences

### 17.1 Positive

- First-run EUTxO and ZK demonstrations become short and repeatable.
- Manual copy/paste and environment-variable mistakes are eliminated from the
  happy path.
- The live E2E test and user-facing demo share transaction/workflow code.
- Non-ZK users do not inherit ZeroJ dependencies.
- Operation journals make failures inspectable and resumable.
- The scenario provider boundary supports additional maintained or reviewed
  plugin scenarios.
- Granular commands preserve the educational value of the protocol.

### 17.2 Negative

- Two additional JVM modules and a maintained scenario SPI are introduced.
- Disposable key custody, even with strong warnings and file protections,
  adds security-sensitive code.
- Live bridge and proof tests are comparatively slow and resource intensive.
- Transaction-builder extraction may reveal test-only assumptions that need
  production-quality error and bound handling.
- Scenario/version migration must be managed as formats evolve.

### 17.3 Neutral but important

- The demo remains experimental even when it works reliably.
- The development ceremony remains unsuitable for production.
- The current Jubjub development profile remains trusted-prover-only.
- A demo-generated project is useful for inspection, not production
  promotion.
- Production operators continue to use generic init/lifecycle commands and
  external key custody.

## 18. Rejected shortcuts

- Store mnemonics in the blueprint or lock file.
- Print generated mnemonics or passwords for convenience.
- Pass secrets on the command line.
- Build Cardano transactions with ad hoc shell/JSON manipulation.
- Copy transaction construction from the E2E test into the demo module.
- Treat readiness polling as semantic project validation.
- Retry by creating a new transaction after submission uncertainty.
- Delete an unverified workspace during reset.
- Allow `--network mainnet` with only a warning.
- Load ZeroJ for the `ledger` or `bridge` scenario.
- Claim ZK rollup security from the current development authorization profile.
- Hide non-ZK bridge trust assumptions behind the common command facade.
- Add demo-specific consensus behavior to Yano core.

## 19. Follow-on decisions

1. Whether reviewed custom scenario providers become a public SPI or remain a
   maintained first-party extension point.
2. Whether a future Preview/Preprod rehearsal command may orchestrate public
   observations while requiring all signing from an external wallet/HSM.
3. Whether a future local web wizard invokes the same demo workflow or only
   generates a project plan.
4. Whether ceremony artifacts may be cached across verified disposable
   workspaces by exact circuit/security identity.
5. Whether the bridge demo should expose multiple reviewed non-ZK settlement
   modes or keep one maintained default.

None of these follow-ons may weaken the devnet-only disposable-secret
boundary selected here.

## 20. Review questions

1. Is one scenario-based command facade clearer than separate unrelated
   scripts for ledger, bridge, and ZK?
2. Are the base and optional-ZK module boundaries small enough?
3. Do the journal states cover every externally visible ambiguity?
4. Are setup, ceremony, and round-trip responsibilities separated correctly?
5. Does the demo remain educational while still providing a three-command
   happy path?
6. Are devnet-only and disposable-funds restrictions strong enough to prevent
   production confusion?
7. Are startup validation and plugin-loader lifecycle hardening correctly
   treated as prerequisites rather than hidden by automation?
