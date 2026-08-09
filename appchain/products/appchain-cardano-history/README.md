# Cardano History plugin

This optional product assembles the reusable ADR-028 protocol-parameter, epoch-stake, proposal,
and DRep components as one normal `AppStateMachine`. It owns no epoch transition or canonical
codec.

Released presets:

| Preset | Enabled datasets |
|---|---|
| `params-only-v1` | Protocol parameters (default) |
| `params-stake-v1` | Parameters and epoch stake |
| `params-governance-v1` | Parameters, proposals, and DRep distribution |
| `full-v1` | All datasets |

Configure `state-machine: cardano-history`, set
`machines.cardano-history.preset`, and configure exactly the observer types required by the chosen
preset. Stake and governance scans are never activated by the default preset. MPF plus a SCRIPT
anchor is required when results will be consumed on-chain; JMT is supported for off-chain-only
verification.

Build the drop-in artifact with:

```bash
./gradlew :appchain-cardano-history:shadowJar
```

The `-bundle.jar` contains product-owned classes and service metadata only. Yano host APIs,
composition code, stdlib implementations, and canonical contracts are supplied by the runtime.
