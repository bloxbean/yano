# Contribute to Yano

Build, test, and improve Yano through its public modules and contracts.

Canonical URL: https://getyano.dev/contribute/guide/

To run Yano, [download a release](/start/installation/). To change the node itself, follow [build from source](/contribute/build-from-source/) for the checkout, Java prerequisites, and build commands.

[Building and testing](/contribute/build-and-test/) explains the test tiers and extended checks. Use the suite that exercises your change; native packaging and multi-node behavior have additional gates.

## Repository boundaries

| Area | Responsibility |
| --- | --- |
| `core-api` | Public contracts, role interfaces, plugin SPI |
| `runtime` | Node assembly and runtime implementation |
| `ledger-state`, `ledger-rules` | Ledger state and validation contracts |
| `p2p`, `consensus` | Networking and consensus components |
| `devnet-toolkit`, `testkit`, `testkit-ccl` | Local development and Java testing |
| `app` | Quarkus API and runnable distribution |
| `appchain/` | Retained app-chain configuration, test, and proof modules |
| `archive-modules/` | Optional history projection and DuckLake backend |
| `www/` | This documentation site |

Yano owns `org.yanoproject.*`. Use domain names for new top-level packages; product names are reserved for sibling repositories. Yano X uses `org.yanoproject.x.*`. Keep implementation imports readable and use simple class names unless a collision requires qualification.

## Extension contributions

Put additional stock app-chain machines, connectors, products, and JVM tooling in [Yano X](https://github.com/bloxbean/yano-x). Extend the public SPI without coupling the host to product-specific source paths or dependencies.

## Documentation contributions

Edit Markdown under `www/src/content/docs/`, then run from `www/`:

```bash
npm ci
npm run check
npm run build
```

The build refreshes AI artifacts and configuration references from this checkout and checks local links. Keep commands, units, defaults, and source ownership accurate. Describe user-facing behavior and include meaningful validation in your PR.
