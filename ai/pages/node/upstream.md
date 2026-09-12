# Upstream peers and validation

Choose peer selection and inspect validation without overstating trust.

Canonical URL: https://getyano.dev/node/upstream/

Upstream mode decides where headers and block bodies come from. Configure it under `yano.upstream`.

| Mode | Purpose |
| --- | --- |
| `trusted-single` | One selected upstream; simplest synchronization setup |
| `trusted-failover` | A configured trusted set with failover |
| `static-multi` | Multiple explicitly configured peers and header observations |
| `p2p-relay` | Peer governor and discovery for relay experiments |

Packaged profiles include `trusted-peers`, `static-multi`, and `relay`. Read their exact settings in the [configuration catalog](/reference/configuration-catalog/). A mode or profile is not a claim that all public-network consensus rules are enforced.

## Header validation

The current bundled configuration describes these levels:

| Level | Additional checks |
| --- | --- |
| `none` | Default; upstream header validation disabled |
| `structural` | Header structure |
| `header-signature` | Structure, KES, and operational-certificate signatures |
| `praos-lite` | Shelley+ VRF checks when epoch nonce tracking is available |
| `praos-ledger` | Ledger-view checks and persisted operational-certificate counters when available |

`yano.upstream.validation.body-level` currently supports `none`. Do not interpret the header presets as complete transaction/body or Cardano consensus validation.

Validation start settings can delay checks to an era or checkpoint. Packaged network profiles carry network-specific anchors. Preserve the profile's genesis and checkpoint identity when selecting a validation preset.

## Operational-certificate counters

`yano.upstream.validation.opcert-counter-mode` supports `none`, `compat`, and `strict`. `compat` checks stored counters when present; `strict` applies the registered-issuer baseline when stored state is absent. Understand the coverage of the database before enabling stricter checks.

## Forwarding and diffusion

Upstream transaction forwarding is configured separately from transaction diffusion. The current base config enables `yano.tx.diffusion.enabled` and bounds transactions, bytes, and peer cooldown. Use status and logs to confirm actual active peers and behavior; do not infer relay completeness from a single flag.

## Troubleshooting

If progress stalls, inspect the selected peer, recovery reason, validation failures, and retained history. Check network identity and connectivity before changing validation or storage. See [troubleshooting](/operate/troubleshooting/).
