# Haskell-node compatibility scripts

Helpers used by the `test-haskell-sync`, `test-past-time-travel`,
`test-native-haskell-sync`, and `test-native-past-time-travel` skills
(under `.claude/skills/`) to drive end-to-end regression tests between
Yano devnet and an upstream Haskell `cardano-node`.

These scripts intentionally live under `scripts/` (not `.claude/skills/`)
because `.claude/` is gitignored — keeping them here means a fresh git
checkout can run the tests without re-creating the skill folder.

## Files

- **`setup-haskell-test-node.sh`** — idempotent. Downloads the requested
  `cardano-node` release (default `11.0.1`, override with
  `HASKELL_NODE_VERSION=…`) into `test-data-dir/haskell-node/`, writes
  `configuration.json`, `files/topology.json`, `files/dijkstra-genesis.json`,
  and a convenience `tip.sh`. Also pulls a 251-entry preprod
  `conway-genesis.json` into `test-data-dir/genesis-overrides/`
  (cardano-node 11.0.x rejects Yano's bundled 297-entry plutusV3 cost model).
  On macOS it picks the `macos-amd64` asset (arm64 has known issues).
- **`copy-devnet-genesis-to-haskell.sh`** — copies shelley/byron/alonzo
  genesis from `app/config/network/devnet/` into the Haskell node's
  `files/` folder, and overlays the override `conway-genesis.json` so
  Yano and Haskell load byte-identical conway genesis.

## Quick usage

```bash
# Install/refresh the Haskell test node + override conway genesis
bash scripts/haskell-compatibility/setup-haskell-test-node.sh

# After Yano devnet has started, copy genesis into the Haskell node folder
bash scripts/haskell-compatibility/copy-devnet-genesis-to-haskell.sh

# Check the running Haskell node's tip (from anywhere)
bash test-data-dir/haskell-node/tip.sh
```

The full step-by-step runbooks live in the `.claude/skills/test-*` folders.
