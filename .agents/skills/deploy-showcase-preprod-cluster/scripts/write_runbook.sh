#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:?target is required}"
INSTANCE="${2:?instance is required}"

python3 - "$TARGET/PREPROD_CLUSTER.md" "$TARGET" "$INSTANCE" <<'PY'
from pathlib import Path
import sys

output = Path(sys.argv[1])
target = sys.argv[2]
instance = sys.argv[3]
output.write_text(f"""# Yano three-node preprod showcase

Deployment: `{target}`

Instance: `{instance}`

HTTP: `17070`, `17071`, `17072`
N2N: `17337`, `17338`, `17339`

This cluster uses public Cardano preprod. Anchor, settlement, and smoke commands
can submit real preprod transactions. Never print the seed files under
`private-anchor/` or `private-settlement/`.

## Operate without submitting new traffic

```bash
cd {target}
./showcase.sh status --instance {instance}
./showcase.sh up --instance {instance}
./showcase.sh stop --instance {instance}
./showcase.sh anchor audit all --instance {instance}
```

## Optional demonstrations

The following can advance app-chain state and subsequently create new anchor
transactions:

```bash
./showcase.sh run orders --instance {instance}
./showcase.sh run registry --instance {instance}
./showcase.sh run authenticated-map --instance {instance}
./showcase.sh run document-review --instance {instance}
```

Do not use a settlement deposit or withdrawal as a health check; those are real
preprod custody flows.

Use `./showcase.sh describe light` for the authoritative thirteen-chain catalog,
including the Cardano History product. This preprod reference deployment uses
`params-stake-v1` and retains the latest two completed L1 source snapshots.
The console capability matrix at `http://127.0.0.1:17070/ui/app-chain/` is
derived from each chain's immutable capability manifest.

At boundary `E-1 -> E`, protocol parameters are labelled `E` and the complete
stake distribution is labelled `E-1`. On startup the three members
deterministically reconcile the latest two completed boundaries retained in
their L1 account-state stores. This provides the latest available end-of-epoch
stake data without inventing mutable current-epoch state. Each completed stake
dataset becomes a separate immutable authenticated snapshot/root in the app
chain. If the source has no retained complete boundary, product routes can
return HTTP 404 until the next stable transition.

Open `http://127.0.0.1:17070/ui/app-chain/cardano-history/` for the native,
capability-gated console. The product plugin bundle contains no frontend code.

## Validate from the repository

```bash
cd /Users/satya/work/bloxbean/yano
bash .agents/skills/deploy-showcase-preprod-cluster/scripts/validate_cluster.sh \\
  {target} {instance} 17070
```

## Storage layout

Each node under `data/showcase/{instance}/cluster/nodeN/` has three sibling roots:

- `chainstate/`: imported authoritative L1 state only
- `appchain-chainstate/`: authoritative app-chain ledgers
- `appchain-indexers/`: rebuildable node-local read indexes

The deployment excludes the legacy `chainstate/appchains` directory from the
retained source. The validator rejects any derived checkpoint ahead of the
authoritative app-chain tip.
""", encoding="utf-8")
PY
