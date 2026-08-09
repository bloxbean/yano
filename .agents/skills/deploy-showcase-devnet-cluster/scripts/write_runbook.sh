#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:?target is required}"
INSTANCE="${2:?instance is required}"

python3 - "$TARGET/DEVNET_CLUSTER.md" "$TARGET" "$INSTANCE" <<'PY'
from pathlib import Path
import sys

output = Path(sys.argv[1])
target = sys.argv[2]
instance = sys.argv[3]
output.write_text(f"""# Yano three-node devnet showcase

Deployment: `{target}`

Instance: `{instance}`

HTTP: `7070`, `7071`, `7072`
N2N: `13337`, `13338`, `13339`

## Operate

```bash
cd {target}
./showcase.sh status --instance {instance}
./showcase.sh up --instance {instance}
./showcase.sh stop --instance {instance}
```

## Run demonstrations

```bash
./showcase.sh run orders --instance {instance}
./showcase.sh run registry --instance {instance}
./showcase.sh run authenticated-map --instance {instance}
./showcase.sh run all --instance {instance}
```

All configured chains use SCRIPT anchors. Check or bootstrap them with:

```bash
./showcase.sh anchor audit all --instance {instance}
./showcase.sh anchor bootstrap all --instance {instance}
```

## Validate from the repository

```bash
cd /Users/satya/work/bloxbean/yano
bash .agents/skills/deploy-showcase-devnet-cluster/scripts/validate_cluster.sh \\
  {target} {instance} 7070
```

## Storage layout

Each node under `data/showcase/{instance}/cluster/nodeN/` has three sibling roots:

- `chainstate/`: authoritative L1 ledger state
- `appchain-chainstate/`: authoritative app-chain ledgers
- `appchain-indexers/`: rebuildable node-local read indexes

Never restore `appchain-indexers` as part of either authoritative store. The
validator rejects legacy derived indexes below `chainstate/appchains` and any
derived checkpoint ahead of the authoritative app-chain tip.
""", encoding="utf-8")
PY
