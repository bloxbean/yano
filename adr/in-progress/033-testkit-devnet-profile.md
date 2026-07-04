# ADR-033: Testkit Devnet Profile From Runtime Devnet Genesis Resources

## Status

Proposed

## Date

2026-07-03

## Context

The application devnet profile and the JVM testkit devnet currently do not start
from the same defaults.

The application `%devnet` profile is file-backed. It points at
`app/config/network/devnet` and enables the derived ledger-state subsystems that
make the devnet useful as an integration-test target:

- account state;
- epoch snapshot amount aggregation;
- AdaPot tracking;
- rewards;
- epoch protocol-parameter tracking;
- governance;
- block production in dev mode.

The current testkit default is different. `YanoDevnetTestConfig.builder()` starts
from `YanoConfig.devnetDefault(0)`, injects hand-written in-memory genesis data,
and enables only UTXO tracking in `RuntimeOptions`. Tests that need normal
devnet behavior must remember to enable account state, epoch snapshots, AdaPot,
rewards, epoch parameter tracking, and governance themselves.

This drift caused issue #26 to be less obvious in testkit. A devnet started
through testkit should behave like the default devnet unless a test explicitly
chooses a narrower configuration.

The repository already contains devnet files in `app/config/network/devnet`,
including `protocol-param.json` with the current protocol version. The runtime
module already packages public-network genesis files under
`runtime/src/main/resources/genesis`. Testkit depends on runtime, not on app.

We want testkit defaults to use file-backed devnet genesis data without adding
Gradle copy tasks or other build-time synchronization magic.

## Decision

Add a manually maintained devnet resource copy under:

```text
runtime/src/main/resources/genesis/devnet
```

The resource directory should contain a copy of the top-level application devnet
directory files needed by the runtime and testkit default profile:

- `shelley-genesis.json`
- `byron-genesis.json`
- `alonzo-genesis.json`
- `conway-genesis.json`
- `protocol-param.json`
- `vrf.skey`
- `kes.skey`
- `opcert.cert`

The runtime resource copy should intentionally be a source-controlled duplicate
of the corresponding top-level files in `app/config/network/devnet`. It should
not be generated during the build.

Do not copy `app/config/network/devnet/pv10`. The testkit default devnet should
start directly in protocol 11, matching the regular distribution devnet profile.
Protocol-10 compatibility overlays remain app/external-process fixtures and must
be opted into explicitly by tests that need them.

Add a small README in the devnet resource directory, and optionally one beside
the app config, stating:

- `app/config/network/devnet` is the application devnet profile copy;
- `runtime/src/main/resources/genesis/devnet` is the library/testkit resource
  copy;
- both must be updated together when protocol version, cost models, genesis
  protocol parameters, devnet keys/certificates, or devnet bootstrap values
  change;
- `pv10/` is intentionally not duplicated in runtime resources because the
  default library/testkit devnet starts at protocol 11;
- the copied `*.skey` files are public non-production devnet fixtures checked
  into source control; they must never be reused for public networks or real
  funds;
- tests should verify that the two copies agree on key values such as protocol
  major/minor version and network magic.

The testkit default should configure VRF/KES/opcert paths to the copied devnet
fixtures so it behaves like the application `%devnet` profile. Tests that need
dummy signatures or custom keys can override or clear those paths explicitly.

Change the testkit default profile to be file-backed:

1. On `YanoDevnetTestConfig.Builder.build()`, allocate the existing temporary
   storage root for temporary RocksDB mode.
2. Copy `genesis/devnet` classpath resources into a test-owned directory under
   that storage root, for example:

   ```text
   <storageRoot>/config/network/devnet
   ```

3. Point `YanoConfig` genesis file paths, `protocolParametersFile`, and
   VRF/KES/opcert paths at the copied files.
4. Keep `devMode=true`, `enableBlockProducer=true`, `enableClient=false`, and
   `protocolMagic=42`.
5. Use runtime options that mirror the app `%devnet` derived ledger-state
   defaults.

The testkit must copy resources to writable test-owned paths before startup.
Runtime devnet startup can persist a resolved `systemStart` into
`shelley-genesis.json`; classpath resources and repository config files must not
be mutated during tests.

Testkit builder overrides remain layered on top of the profile:

1. built-in devnet profile defaults;
2. copied devnet resource files;
3. builder overrides such as storage mode, `blockTimeMillis`, `epochLength`,
   `timeTravel`, and `runtimeOption`;
4. final caller-provided `YanoConfig` escape hatch where explicitly used.

Overrides for genesis-backed values must patch the copied genesis file as well
as the runtime `YanoConfig` field. For example, `epochLength(5)` cannot only
call `YanoConfig#setEpochLength(5)`, because runtime startup reloads
`epochLength` from Shelley genesis and will overwrite the config field.

Do not expose the runtime's in-memory storage or in-memory genesis modes through
testkit devnet. The supported testkit profile is file-backed and RocksDB-backed
so BF-compatible endpoint, ledger-state, epoch-param tracking, snapshot, and
restore behavior stays representative of the regular devnet profile.

## Default Testkit Runtime Options

The default testkit devnet runtime options should match the derived ledger-state
shape of the app `%devnet` profile:

```text
yano.utxo.enabled = true
yano.account-state.enabled = true
yano.epoch-snapshot.amounts-enabled = true
yano.adapot.enabled = true
yano.rewards.enabled = true
yano.epoch-params.tracking-enabled = true
yano.governance.enabled = true
```

Account history stays disabled by default, matching the application profile.

Individual tests can still call `runtimeOption(key, value)` to disable a
subsystem or enable a specialized one.

## Consequences

Testkit defaults become representative of the application devnet profile. Tests
that start `YanoDevnetTestKit.devnet(YanoDevnetTestConfig.builder().build())`
should get protocol-parameter tracking, account state, epoch snapshots, AdaPot,
rewards, and governance without additional boilerplate.

The duplicate devnet files introduce maintenance cost. This is intentional and
visible. The README and verification tests are the guardrails instead of hidden
build-time synchronization.

The runtime artifact gains devnet profile resources for the current default
protocol-11 devnet. This is acceptable because runtime already packages
public-network genesis resources and testkit depends on runtime. The devnet
signing files are intentionally public non-production fixtures; the README must
make that obvious to avoid confusing them with private operational keys.

Tests that use very short epochs must mutate the copied Shelley genesis before
startup. This preserves the existing ergonomic testkit API while keeping runtime
startup behavior honest: genesis-backed values come from genesis files.

## Implementation Plan

1. Copy the top-level devnet profile files into
   `runtime/src/main/resources/genesis/devnet`.
2. Add README documentation for the duplicate-file policy and manual update
   procedure.
3. Add a test that compares app and runtime devnet copies for network magic,
   epoch length, and protocol major/minor version.
4. Change `YanoDevnetTestConfig` defaults from in-memory genesis to file-backed
   devnet profile resources.
5. Add a resource-copy helper in testkit that copies `genesis/devnet` resources
   to the test-owned storage root.
6. Update testkit default `RuntimeOptions` to mirror app `%devnet` derived
   ledger-state settings.
7. Update `epochLength(long)` to patch copied Shelley genesis, or defer the
   patch until build time after resources are copied.
8. Remove ad hoc profile setup from tests that should use the default devnet
   behavior.
9. Run `:testkit:test` and the snapshot/restore protocol-params tests.

## Alternatives Considered

### Build-Time Copy From App Config

Gradle could copy `app/config/network/devnet` into runtime or testkit resources.
This reduces manual drift but hides the dependency between modules and makes the
published runtime/testkit artifact depend on app-local configuration layout.

Rejected. The explicit duplicate is easier to reason about and review.

### Testkit Reads App Config Directly

Testkit could locate `app/config/network/devnet` in the repository at test time.
This works in the monorepo but fails for published `yano-testkit` consumers and
for projects that do not have the Yano app source tree.

Rejected.

### Keep In-Memory Genesis As Default Or Opt-In

This keeps testkit lightweight, but it preserves the drift that caused default
testkit devnet behavior to differ from the app profile. It also requires
hand-maintaining protocol versions and protocol parameters in Java fixtures.

Rejected. Runtime assembly can still support specialized in-memory devnets, but
testkit devnet should not expose that as a supported profile.

### Add A New Shared Config Module

A dedicated fixture/config module could own devnet resources for app, runtime,
and testkit.

Deferred. It may be cleaner long term, but it adds module and publishing
surface area for a small set of files. The explicit runtime resource copy is the
more pragmatic first step.
