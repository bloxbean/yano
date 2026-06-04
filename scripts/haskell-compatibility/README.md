# Haskell-node compatibility scripts

Helpers used by the `test-haskell-sync`, `test-past-time-travel`,
`test-native-haskell-sync`, and `test-native-past-time-travel` skills
(under `.claude/skills/`) to drive end-to-end regression tests between
Yano devnet and an upstream Haskell `cardano-node`.

These scripts intentionally live under `scripts/` (not `.claude/skills/`)
because they are regular project utilities, not Claude-Code-specific
tooling: any developer can run them directly from a shell to spin up a
test Haskell node, regardless of whether they use Claude Code. The four
SKILL.md runbooks under `.claude/skills/` simply call into them.

## Files

- **`setup-haskell-test-node.sh`** — idempotent. Downloads the requested
  `cardano-node` release (default `11.0.1`, override with
  `HASKELL_NODE_VERSION=…`) into `test-data-dir/haskell-node/`, writes
  `configuration.json`, `files/topology.json`, `files/dijkstra-genesis.json`,
  and a convenience `tip.sh`. Also pulls a 251-entry preprod
  `conway-genesis.json` into `test-data-dir/genesis-overrides/`
  (cardano-node 11.0.x rejects Yano's bundled 297-entry plutusV3 cost model).
  On macOS it picks the `macos-amd64` asset (arm64 has known issues).
- **`copy-devnet-genesis-to-haskell.sh`** — copies all four genesis files
  (shelley/byron/alonzo/conway) from the source directory passed as `$1`
  (default `app/config/network/devnet/`) into the Haskell node's `files/`
  folder. If the source directory has no `conway-genesis.json`, the script
  falls back to the downloaded override at
  `test-data-dir/genesis-overrides/conway-genesis.json`.

The regression skills now point at `app/config/network/devnet/pv10/`, which
ships Haskell-11.0.1-compatible cost-model sizes (alonzo 166 entries,
conway 251 entries). The downloaded override is only consulted when a
caller points at the PV11 devnet folder directly.

## Quick usage

```bash
# Install/refresh the Haskell test node (also writes a 251-entry conway
# override for legacy callers)
bash scripts/haskell-compatibility/setup-haskell-test-node.sh

# After Yano devnet has started, copy genesis from pv10/ into the Haskell node
bash scripts/haskell-compatibility/copy-devnet-genesis-to-haskell.sh \
  app/config/network/devnet/pv10

# Check the running Haskell node's tip (from anywhere)
bash test-data-dir/haskell-node/tip.sh
```

The full step-by-step runbooks live in the `.claude/skills/test-*` folders.
