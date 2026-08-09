# Cardano History product

Cardano History is an optional app-chain plugin that assembles the reusable ADR-028 epoch observers
and state-machine components into one installable product. It does not copy Cardano-specific logic
into the Yano core API and it does not define another proof format.

## Presets

| Preset | Protocol parameters | Epoch stake | Proposals and DRep distribution |
|---|---:|---:|---:|
| `params-only-v1` | yes | no | no |
| `params-stake-v1` | yes | yes | no |
| `params-governance-v1` | yes | no | yes |
| `full-v1` | yes | yes | yes |

The default is `params-only-v1`. Stake and governance traversal are never enabled implicitly. The
selected preset and commitment identity are genesis-time inputs; preview chains start fresh when
either changes.

## Read API

The plugin contributes bounded, read-only routes below
`/api/v1/plugins/com.bloxbean.cardano.yano.appchain.cardano-history/`. Every request requires
`chain=<chain-id>`. Routes cover status, epochs, protocol parameters, stake, DRep distribution, and
proposal history.

Responses identify the exact `committedHeight` and `stateRoot` and return typed proof coordinates,
not proof bytes. Stake, DRep, and proposal reads query the fact and completeness metadata in one
composite aggregate operation. An incomplete dataset returns `complete=false` and
`absenceProvable=false`; it is never interpreted as zero stake or non-membership.

## Java client and CLI

The client requires an explicit node API URL and chain ID:

```java
var history = CardanoHistoryClient.builder(
        "http://localhost:17070/api/v1", "cardano-history-chain").build();
var stake = history.stake(170, 0, credentialHash);
```

The CLI never assumes port 7070 or derives a chain from an arbitrary showcase instance name:

```bash
yano-cardano-history status \
  --url http://localhost:17070/api/v1 \
  --chain cardano-history-chain

yano-cardano-history query params --epoch 170 \
  --url http://localhost:17070/api/v1 \
  --chain cardano-history-chain

yano-cardano-history proof params --epoch 170 --output params-proof.json \
  --url http://localhost:17070/api/v1 \
  --chain cardano-history-chain

yano-cardano-history verify --bundle params-proof.json \
  --trusted-root independently-obtained-cardano-root.json
```

Exit code `0` means a fully L1-authenticated proof or successful non-proof command, `3` means data
is unavailable/incomplete, `4` means invalid, and `5` means the trie proof is valid against the
pinned root but the Cardano anchor was not independently checked. A bundle generated from the same
node is deliberately `ROOT_VERIFIED_ANCHOR_UNCHECKED` until the caller supplies an independently
verified Cardano anchor context.

The optional `--trusted-root` document is a separate caller input. The verifier ignores any trust
source embedded in a proof bundle, so editing a bundle cannot promote a root-only result to an L1
authenticated result. A `CARDANO_ANCHOR` trusted root must come from the caller's independently
verified Cardano source.

See [Authenticated snapshots](AUTHENTICATED_SNAPSHOTS.md) for retention and
[Plugin UI extensions](PLUGIN_UI_EXTENSIONS.md) for frontend isolation.
