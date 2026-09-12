# Connect to Cardano

Configure public-network profiles and upstream peers.

Canonical URL: https://getyano.dev/node/networks/

Yano defaults to Cardano **preprod**, with the upstream host `preprod-node.world.dev.cardano.org`, port `30000`, and network magic `1` in the bundled application configuration.

From an extracted distribution:

```bash
./yano.sh start:preprod
```

Other packaged network profiles include `mainnet`, `preview`, and `sanchonet`. Use a **separate storage directory for each network**. Do not point a new network profile at another network's chain state.

## Configure the node

Place overrides in `config/application.yml` relative to the working directory. For example:

```yaml
yano:
  storage:
    path: ./chainstate-preprod
  remote:
    host: preprod-node.world.dev.cardano.org
    port: 30000
```

Profiles compose with a comma-separated list. Network profiles set genesis/network values; additional profiles enable optional behavior:

```bash
./yano.sh start:preprod,relay
./yano.sh start:preprod,wallet
```

The first enables the packaged relay profile. The second enables wallet discovery indexes and requires a fresh sync for complete history.

## Observe synchronization

```bash
curl -fsS http://localhost:7070/api/v1/status
curl -fsS http://localhost:7070/api/v1/node/tip
```

Readiness and sync completion are different. A healthy process can still be catching up. Compare the local tip, remote tip, peer state, and recent progress before relying on current data.

The N2N server normally listens on `13337`. A downstream consumer must use matching network magic and genesis. For a local devnet, that magic is `42`; do not connect a public-network consumer using it.

## Upstream selection and validation

Yano supports trusted-single, trusted-failover, static-multi, and p2p-relay modes. See [upstream configuration](/node/upstream/) for their tradeoffs and the current validation boundary.
