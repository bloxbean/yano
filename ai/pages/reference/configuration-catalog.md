# Configuration catalog

Active packaged configuration values and declared property keys, generated from source.

Canonical URL: https://getyano.dev/reference/configuration-catalog/

This reference is generated from the checked-out source. It keeps **bundled defaults and profile overrides separate**. Values are not a merged runtime configuration. Environment expressions retain their exact syntax. Comments and commented examples are excluded.

See [configuration layering](/reference/configuration/) before applying settings. Download [the JSON catalog](/ai/configuration.json). Secret-like populated fields are redacted; the local app-chain demo identity is intentionally not an operator credential.

## Bundled application defaults

Source: [app/src/main/resources/application.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/src/main/resources/application.yml)

| Property | Packaged value |
| --- | --- |
| `quarkus.http.port` | `7070` |
| `quarkus.http.cors.enabled` | `${YANO_HTTP_CORS_ENABLED:false}` |
| `quarkus.http.test-port` | `0` |
| `quarkus.http.filter.plugin-dashboard-security.matches` | `/ui/plugins/.*` |
| `quarkus.http.filter.plugin-dashboard-security.header.Content-Security-Policy` | `connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'` |
| `quarkus.http.filter.plugin-dashboard-security.header.X-Frame-Options` | `DENY` |
| `quarkus.http.filter.plugin-dashboard-security.header.X-Content-Type-Options` | `nosniff` |
| `quarkus.http.filter.plugin-dashboard-security.header.Referrer-Policy` | `no-referrer` |
| `quarkus.http.filter.plugin-dashboard-security.header.Cache-Control` | `no-store` |
| `quarkus.http.filter.console-security.matches` | `/ui(?!/plugins/).*` |
| `quarkus.http.filter.console-security.header.Content-Security-Policy` | `connect-src 'self'${YANO_CONSOLE_CONNECT_SRC_EXTRA:}; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'` |
| `quarkus.http.filter.console-security.header.X-Frame-Options` | `DENY` |
| `quarkus.http.filter.console-security.header.X-Content-Type-Options` | `nosniff` |
| `quarkus.http.filter.console-security.header.Referrer-Policy` | `no-referrer` |
| `quarkus.http.filter.console-security.header.Cache-Control` | `no-store` |
| `quarkus.swagger-ui.always-include` | `${YANO_SWAGGER_UI_ENABLED:true}` |
| `quarkus.swagger-ui.path` | `/q/swagger-ui` |
| `quarkus.swagger-ui.urls.Core API` | `/q/openapi-core` |
| `quarkus.swagger-ui.urls.App Chain API` | `/q/openapi-app-chain` |
| `quarkus.swagger-ui.urls.Devnet API` | `/q/openapi-devnet` |
| `quarkus.swagger-ui.urls.Admin API` | `/q/openapi-admin` |
| `quarkus.swagger-ui.urls.History API` | `/q/openapi-history` |
| `quarkus.swagger-ui.urls.All APIs` | `/q/openapi` |
| `quarkus.swagger-ui.urls-primary-name` | `Core API` |
| `yano.mempool.admin.enabled` | `false` |
| `yano.plugins.enabled` | `true` |
| `yano.plugins.directory` | `plugins` |
| `yano.plugins.allow-list` | `[]` |
| `yano.plugins.deny-list` | `[]` |
| `yano.plugins.auto-register-annotated` | `false` |
| `yano.plugins.logging.enabled` | `false` |
| `yano.network` | `preprod` |
| `yano.auto-sync-start` | `true` |
| `yano.client.enabled` | `true` |
| `yano.remote.host` | `preprod-node.world.dev.cardano.org` |
| `yano.remote.port` | `30000` |
| `yano.remote.protocol-magic` | `1` |
| `yano.upstream.validation.level` | `none` |
| `yano.upstream.validation.body-level` | `none` |
| `yano.upstream.validation.opcert-counter-mode` | `none` |
| `yano.upstream.validation.start.mode` | `era` |
| `yano.upstream.validation.start.era` | `conway` |
| `yano.upstream.validation.start.slot` | `69638426` |
| `yano.upstream.validation.start.hash` | `ecde79b23e343becca15618fc26281ba1aaea2eb1b66ab8828d7a127f5dbc30f` |
| `yano.relay.auto-discovery` | `false` |
| `yano.relay.advertised-host` | `auto` |
| `yano.relay.advertised-port` | `0` |
| `yano.relay.allow-private-addresses` | `false` |
| `yano.relay.connection.max-inbound-connections` | `100` |
| `yano.relay.connection.max-connections-per-ip` | `5` |
| `yano.relay.connection.source-port-reuse` | `true` |
| `yano.tx.mempool.max-txs` | `10000` |
| `yano.tx.mempool.max-bytes` | `134217728` |
| `yano.tx.mempool.max-utxo-index-entries` | `100000` |
| `yano.tx.mempool.ttl-seconds` | `10800` |
| `yano.tx.diffusion.enabled` | `true` |
| `yano.tx.diffusion.limits.max-in-flight-txs-per-peer` | `100` |
| `yano.tx.diffusion.limits.max-in-flight-bytes-per-peer` | `1048576` |
| `yano.tx.diffusion.limits.peer-cooldown-ms` | `60000` |
| `yano.app-chain.storage.path` | `${YANO_APP_CHAIN_STORAGE_PATH:appchain-chainstate}` |
| `yano.dns.cache` | `null` |
| `yano.server.enabled` | `true` |
| `yano.server.port` | `13337` |
| `yano.storage.rocksdb` | `true` |
| `yano.storage.path` | `./chainstate` |
| `yano.genesis.shelley-genesis-file` | `config/network/preprod/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `162d29c4e1cf6b8a84f2d692e67a3ac6bc7851bc3e6e4afe64d15778bed8bd86` |
| `yano.genesis.byron-genesis-file` | `config/network/preprod/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/preprod/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/preprod/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/preprod/protocol-param.json` |
| `yano.dev-mode` | `false` |
| `yano.account-state.enabled` | `true` |
| `yano.account.stake-balance-index-enabled` | `true` |
| `yano.epoch-boundary.reward-mode` | `streaming` |
| `yano.epoch-boundary.max-batch-operations` | `10000` |
| `yano.epoch-boundary.max-batch-bytes` | `4194304` |
| `yano.history.dir` | `./history` |
| `yano.history.archive.engine` | `ducklake` |
| `yano.history.archive.finality-blocks` | `auto` |
| `yano.history.archive.wait-warn-seconds` | `30` |
| `yano.history.archive.stuck-operation-seconds` | `300` |
| `yano.history.archive.ducklake.target-file-size` | `4MB` |
| `yano.history.archive.ducklake.row-group-size` | `100000` |
| `yano.history.archive.ducklake.snapshot-retention-hours` | `168` |
| `yano.history.archive.ducklake.cleanup-grace-hours` | `24` |
| `yano.history.rollback.retention-blocks` | `auto` |
| `yano.history.duckdb.max-total-memory` | `256MB` |
| `yano.history.duckdb.max-concurrent-queries` | `2` |
| `yano.history.duckdb.max-temp-directory-size` | `2GB` |
| `yano.history.duckdb.steady-state.memory-limit` | `128MB` |
| `yano.history.duckdb.steady-state.threads` | `1` |
| `yano.history.duckdb.bulk-catch-up.memory-limit` | `128MB` |
| `yano.history.duckdb.bulk-catch-up.threads` | `1` |
| `yano.history.duckdb.bulk-catch-up.max-concurrent-jobs` | `1` |
| `yano.epoch-snapshot.amounts-enabled` | `true` |
| `yano.adapot.enabled` | `true` |
| `yano.rewards.enabled` | `true` |
| `yano.epoch-params.tracking-enabled` | `true` |
| `yano.governance.enabled` | `true` |
| `yano.chain.block-body-prune-depth` | `0` |
| `yano.chain.block-prune-batch-size` | `500000` |
| `yano.chain.block-prune-interval-seconds` | `300` |
| `yano.address-first-seen.enabled` | `false` |
| `yano.scan.index.enabled` | `false` |
| `yano.scan.max-concurrent` | `2` |
| `yano.filters.utxo.enabled` | `false` |
| `yano.filters.utxo.addresses` | `[]` |
| `yano.filters.utxo.payment-credentials` | `[]` |
| `yano.metrics.enabled` | `true` |
| `yano.metrics.sample.rocksdb.seconds` | `30` |
| `yano.validation.default-validator-enabled` | `true` |
| `yano.validation.supplementary-rules-enabled` | `false` |
| `yano.utxo.rebuild-unmarked-from-genesis` | `false` |
| `yano.utxo.prune.schedule.seconds` | `5` |
| `yano.utxo.metrics.lag.logSeconds` | `10` |
| `yano.utxo.lag.failIfAbove` | `-1` |
| `yano.block-producer.enabled` | `false` |
| `yano.block-producer.block-time-millis` | `0` |
| `yano.block-producer.lazy` | `false` |
| `yano.block-producer.genesis-timestamp` | `0` |
| `yano.block-producer.slot-length-millis` | `0` |
| `yano.block-producer.start-epoch` | `0` |
| `yano.block-producer.past-time-travel-mode` | `false` |
| `yano.block-producer.past-time-travel-slot-leader-mode` | `false` |
| `yano.block-producer.tx-evaluation` | `true` |

## Bundled application defaults — devnet profile

Source: [app/src/main/resources/application.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/src/main/resources/application.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `devnet` |
| `yano.auto-sync-start` | `true` |
| `yano.dev-mode` | `true` |
| `yano.client.enabled` | `false` |
| `yano.remote.protocol-magic` | `42` |
| `yano.genesis.shelley-genesis-file` | `config/network/devnet/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `` |
| `yano.genesis.byron-genesis-file` | `config/network/devnet/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/devnet/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/devnet/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/devnet/protocol-param.json` |
| `yano.account-state.enabled` | `true` |
| `yano.history.projection.enabled` | `true` |
| `yano.history.projection.sink` | `ducklake` |
| `yano.epoch-snapshot.amounts-enabled` | `true` |
| `yano.adapot.enabled` | `true` |
| `yano.rewards.enabled` | `true` |
| `yano.epoch-params.tracking-enabled` | `true` |
| `yano.governance.enabled` | `true` |
| `yano.utxo.enabled` | `true` |
| `yano.block-producer.enabled` | `true` |
| `yano.block-producer.block-time-millis` | `0` |
| `yano.block-producer.lazy` | `false` |
| `yano.block-producer.genesis-timestamp` | `0` |
| `yano.block-producer.slot-length-millis` | `0` |
| `yano.block-producer.vrf-skey-file` | `config/network/devnet/vrf.skey` |
| `yano.block-producer.kes-skey-file` | `config/network/devnet/kes.skey` |
| `yano.block-producer.opcert-file` | `config/network/devnet/opcert.cert` |
| `yano.block-producer.past-time-travel-slot-leader-mode` | `false` |

## Bundled application defaults — devnet-slotleader profile

Source: [app/src/main/resources/application.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/src/main/resources/application.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `devnet` |
| `yano.auto-sync-start` | `true` |
| `yano.dev-mode` | `true` |
| `yano.client.enabled` | `false` |
| `yano.remote.protocol-magic` | `42` |
| `yano.genesis.shelley-genesis-file` | `config/network/devnet/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `` |
| `yano.genesis.byron-genesis-file` | `config/network/devnet/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/devnet/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/devnet/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/devnet/protocol-param.json` |
| `yano.account-state.enabled` | `true` |
| `yano.epoch-snapshot.amounts-enabled` | `true` |
| `yano.adapot.enabled` | `true` |
| `yano.rewards.enabled` | `true` |
| `yano.epoch-params.tracking-enabled` | `true` |
| `yano.governance.enabled` | `true` |
| `yano.utxo.enabled` | `true` |
| `yano.block-producer.enabled` | `true` |
| `yano.block-producer.slot-leader-mode` | `true` |
| `yano.block-producer.lazy` | `false` |
| `yano.block-producer.genesis-timestamp` | `0` |
| `yano.block-producer.slot-length-millis` | `0` |
| `yano.block-producer.start-epoch` | `0` |
| `yano.block-producer.vrf-skey-file` | `config/network/devnet/vrf.skey` |
| `yano.block-producer.kes-skey-file` | `config/network/devnet/kes.skey` |
| `yano.block-producer.opcert-file` | `config/network/devnet/opcert.cert` |
| `yano.block-producer.past-time-travel-slot-leader-mode` | `false` |

## Bundled application defaults — mainnet profile

Source: [app/src/main/resources/application.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/src/main/resources/application.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `mainnet` |
| `yano.upstream.validation.start.mode` | `era` |
| `yano.upstream.validation.start.era` | `conway` |
| `yano.upstream.validation.start.slot` | `133660855` |
| `yano.upstream.validation.start.hash` | `9aa420cf998dbcceec1abaf83ab26294d278d25527e779050ab334c1fadab16c` |
| `yano.remote.host` | `backbone.cardano.iog.io` |
| `yano.remote.port` | `3001` |
| `yano.remote.protocol-magic` | `764824073` |
| `yano.genesis.shelley-genesis-file` | `config/network/mainnet/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `1a3be38bcbb7911969283716ad7aa550250226b76a61fc51cc9a9a35d9276d81` |
| `yano.genesis.byron-genesis-file` | `config/network/mainnet/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/mainnet/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/mainnet/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/mainnet/protocol-param.json` |

## Bundled application defaults — preview profile

Source: [app/src/main/resources/application.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/src/main/resources/application.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `preview` |
| `yano.upstream.validation.start.mode` | `era` |
| `yano.upstream.validation.start.era` | `conway` |
| `yano.upstream.validation.start.slot` | `34905604` |
| `yano.upstream.validation.start.hash` | `cbc88c5f633ace6671f6cbb54a0913e4d7c57697869e951bc032e093f6db5f46` |
| `yano.remote.host` | `preview-node.play.dev.cardano.org` |
| `yano.remote.port` | `3001` |
| `yano.remote.protocol-magic` | `2` |
| `yano.genesis.shelley-genesis-file` | `config/network/preview/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `363498d1024f84bb39d3fa9593ce391483cb40d479b87233f868d6e57c3a400d` |
| `yano.genesis.byron-genesis-file` | `config/network/preview/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/preview/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/preview/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/preview/protocol-param.json` |

## Bundled application defaults — sanchonet profile

Source: [app/src/main/resources/application.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/src/main/resources/application.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `sanchonet` |
| `yano.upstream.validation.start.mode` | `era` |
| `yano.upstream.validation.start.era` | `conway` |
| `yano.remote.host` | `sanchonet-node.play.dev.cardano.org` |
| `yano.remote.port` | `3001` |
| `yano.remote.protocol-magic` | `4` |
| `yano.genesis.shelley-genesis-file` | `config/network/sanchonet/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `f94457ec45a0c6773057a529533cf7ccf746cb44dabd56ae970e1dbfb55bfdb2` |
| `yano.genesis.byron-genesis-file` | `config/network/sanchonet/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/sanchonet/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/sanchonet/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/sanchonet/protocol-param.json` |

## Bundled application defaults — test profile

Source: [app/src/main/resources/application.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/src/main/resources/application.yml)

| Property | Packaged value |
| --- | --- |
| `yano.plugins.enabled` | `false` |
| `yano.plugins.logging.enabled` | `false` |
| `yano.auto-sync-start` | `false` |
| `yano.client.enabled` | `false` |
| `yano.server.enabled` | `true` |
| `yano.storage.rocksdb` | `false` |
| `yano.storage.path` | `./chainstate-test` |

## application-appchain.yml

Source: [app/config/application-appchain.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-appchain.yml)

| Property | Packaged value |
| --- | --- |
| `yano.app-chain.storage.path` | `${YANO_APP_CHAIN_STORAGE_PATH:appchain-chainstate}` |
| `yano.app-chain.chains[0].chain-id` | `orders-chain` |
| `yano.app-chain.chains[0].signing-key` | `<configure privately; see local demo profile for test-only identity>` |
| `yano.app-chain.chains[0].members` | `8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c` |
| `yano.app-chain.chains[0].threshold` | `1` |
| `yano.app-chain.chains[0].state-machine` | `ordered-log` |
| `yano.app-chain.chains[0].state.commitment-profile` | `mpf-blake2b256-v1` |
| `yano.app-chain.chains[0].state.format-fingerprint` | `91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b` |
| `yano.app-chain.chains[0].state.genesis-id` | `c2b9c92a865dfa7c218a1a6e49f1dd88163372e40466009876458c01609d0d70` |
| `yano.app-chain.chains[0].membership.mode` | `governed` |
| `yano.app-chain.chains[0].block.interval-ms` | `1000` |
| `yano.app-chain.chains[0].sequencer.proposer` | `8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c` |

## application-header-signature.yml

Source: [app/config/application-header-signature.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-header-signature.yml)

| Property | Packaged value |
| --- | --- |
| `yano.upstream.validation.level` | `header-signature` |
| `yano.upstream.validation.body-level` | `none` |
| `yano.upstream.validation.opcert-counter-mode` | `none` |

## application-mainnet.yml

Source: [app/config/application-mainnet.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-mainnet.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `mainnet` |
| `yano.remote.host` | `backbone.mainnet.cardanofoundation.org` |
| `yano.remote.port` | `3001` |
| `yano.remote.protocol-magic` | `764824073` |
| `yano.genesis.shelley-genesis-file` | `config/network/mainnet/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `1a3be38bcbb7911969283716ad7aa550250226b76a61fc51cc9a9a35d9276d81` |
| `yano.genesis.byron-genesis-file` | `config/network/mainnet/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/mainnet/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/mainnet/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/mainnet/protocol-param.json` |
| `yano.upstream.discovery.peer-snapshot-urls` | `["https://book.play.dev.cardano.org/environments/mainnet/peer-snapshot.json"]` |
| `yano.upstream.validation.start.mode` | `era` |
| `yano.upstream.validation.start.era` | `conway` |
| `yano.upstream.validation.start.slot` | `133660855` |
| `yano.upstream.validation.start.hash` | `9aa420cf998dbcceec1abaf83ab26294d278d25527e779050ab334c1fadab16c` |

## application-opcert-strict.yml

Source: [app/config/application-opcert-strict.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-opcert-strict.yml)

| Property | Packaged value |
| --- | --- |
| `yano.upstream.validation.opcert-counter-mode` | `strict` |

## application-praos-ledger.yml

Source: [app/config/application-praos-ledger.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-praos-ledger.yml)

| Property | Packaged value |
| --- | --- |
| `yano.upstream.validation.level` | `praos-ledger` |
| `yano.upstream.validation.body-level` | `none` |
| `yano.upstream.validation.opcert-counter-mode` | `compat` |

## application-praos-lite.yml

Source: [app/config/application-praos-lite.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-praos-lite.yml)

| Property | Packaged value |
| --- | --- |
| `yano.upstream.validation.level` | `praos-lite` |
| `yano.upstream.validation.body-level` | `none` |
| `yano.upstream.validation.opcert-counter-mode` | `none` |

## application-preprod.yml

Source: [app/config/application-preprod.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-preprod.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `preprod` |
| `yano.remote.host` | `preprod-node.world.dev.cardano.org` |
| `yano.remote.port` | `30000` |
| `yano.remote.protocol-magic` | `1` |
| `yano.genesis.shelley-genesis-file` | `config/network/preprod/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `162d29c4e1cf6b8a84f2d692e67a3ac6bc7851bc3e6e4afe64d15778bed8bd86` |
| `yano.genesis.byron-genesis-file` | `config/network/preprod/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/preprod/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/preprod/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/preprod/protocol-param.json` |
| `yano.upstream.discovery.peer-snapshot-urls` | `["https://book.play.dev.cardano.org/environments/preprod/peer-snapshot.json"]` |
| `yano.upstream.validation.start.mode` | `era` |
| `yano.upstream.validation.start.era` | `conway` |
| `yano.upstream.validation.start.slot` | `69638426` |
| `yano.upstream.validation.start.hash` | `ecde79b23e343becca15618fc26281ba1aaea2eb1b66ab8828d7a127f5dbc30f` |

## application-preview.yml

Source: [app/config/application-preview.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-preview.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `preview` |
| `yano.remote.host` | `preview-node.play.dev.cardano.org` |
| `yano.remote.port` | `3001` |
| `yano.remote.protocol-magic` | `2` |
| `yano.genesis.shelley-genesis-file` | `config/network/preview/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `363498d1024f84bb39d3fa9593ce391483cb40d479b87233f868d6e57c3a400d` |
| `yano.genesis.byron-genesis-file` | `config/network/preview/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/preview/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/preview/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/preview/protocol-param.json` |
| `yano.upstream.discovery.peer-snapshot-urls` | `["https://book.play.dev.cardano.org/environments/preview/peer-snapshot.json"]` |
| `yano.upstream.validation.start.mode` | `era` |
| `yano.upstream.validation.start.era` | `conway` |
| `yano.upstream.validation.start.slot` | `34905604` |
| `yano.upstream.validation.start.hash` | `cbc88c5f633ace6671f6cbb54a0913e4d7c57697869e951bc032e093f6db5f46` |

## application-projection.yml

Source: [app/config/application-projection.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-projection.yml)

| Property | Packaged value |
| --- | --- |
| `yano.history.dir` | `${YANO_HISTORY_DIR:./history}` |
| `yano.history.projection.enabled` | `true` |
| `yano.history.projection.sink` | `${YANO_PROJECTION_SINK:ducklake}` |
| `yano.history.projection.drain-interval-millis` | `${YANO_PROJECTION_DRAIN_INTERVAL_MILLIS:250}` |
| `yano.history.projection.maintenance.housekeeping-interval-minutes` | `${YANO_PROJECTION_HOUSEKEEPING_INTERVAL_MINUTES:30}` |
| `yano.history.projection.maintenance.housekeeping-budget-seconds` | `${YANO_PROJECTION_HOUSEKEEPING_BUDGET_SECONDS:30}` |
| `yano.history.projection.maintenance.compaction-interval-minutes` | `${YANO_PROJECTION_COMPACTION_INTERVAL_MINUTES:360}` |
| `yano.history.projection.maintenance.compaction-budget-seconds` | `${YANO_PROJECTION_COMPACTION_BUDGET_SECONDS:300}` |
| `yano.history.projection.maintenance.compaction-rewrite-bytes` | `${YANO_PROJECTION_COMPACTION_REWRITE_BYTES:8589934592}` |
| `yano.history.projection.sink-options.target-file-size-bytes` | `${YANO_PROJECTION_TARGET_FILE_SIZE_BYTES:4194304}` |
| `yano.history.projection.sink-options.snapshot-retention-hours` | `${YANO_PROJECTION_SNAPSHOT_RETENTION_HOURS:168}` |
| `yano.history.projection.sink-options.cleanup-grace-hours` | `${YANO_PROJECTION_CLEANUP_GRACE_HOURS:24}` |
| `yano.history.projection.disk.soft-bytes` | `${YANO_PROJECTION_DISK_SOFT_BYTES:8589934592}` |
| `yano.history.projection.disk.hard-bytes` | `${YANO_PROJECTION_DISK_HARD_BYTES:34359738368}` |
| `yano.history.projection.disk.low-water-bytes` | `${YANO_PROJECTION_DISK_LOW_WATER_BYTES:4294967296}` |
| `yano.history.projection.disk.free-space-reserve-bytes` | `${YANO_PROJECTION_DISK_FREE_RESERVE_BYTES:17179869184}` |

## application-relay.yml

Source: [app/config/application-relay.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-relay.yml)

| Property | Packaged value |
| --- | --- |
| `yano.client.enabled` | `true` |
| `yano.server.enabled` | `true` |
| `yano.server.port` | `13337` |
| `yano.relay.auto-discovery` | `true` |
| `yano.relay.advertised-host` | `auto` |
| `yano.relay.advertised-port` | `0` |
| `yano.relay.allow-private-addresses` | `false` |
| `yano.relay.connection.max-inbound-connections` | `100` |
| `yano.relay.connection.max-connections-per-ip` | `5` |
| `yano.relay.connection.source-port-reuse` | `true` |
| `yano.upstream.mode` | `p2p-relay` |
| `yano.upstream.sync.bulk-source` | `single-trusted` |
| `yano.upstream.sync.fan-in-start` | `near-tip` |
| `yano.upstream.discovery.enabled` | `true` |
| `yano.upstream.discovery.peer-sharing` | `true` |
| `yano.upstream.discovery.seeds` | `[]` |
| `yano.upstream.discovery.topology-file` | `` |
| `yano.upstream.discovery.peer-snapshot-limit` | `64` |
| `yano.upstream.discovery.ledger-peers` | `false` |
| `yano.upstream.discovery.use-ledger-after-slot` | `-1` |
| `yano.upstream.discovery.allow-private-addresses` | `false` |
| `yano.upstream.selection.policy` | `trusted-or-quorum-within-rollback-window` |
| `yano.upstream.selection.quorum` | `2` |
| `yano.upstream.selection.tie-break` | `deterministic` |
| `yano.upstream.governor.enabled` | `true` |
| `yano.upstream.governor.targets.cold` | `150` |
| `yano.upstream.governor.targets.warm` | `8` |
| `yano.upstream.governor.targets.hot` | `3` |
| `yano.upstream.governor.max-concurrent-dials` | `4` |
| `yano.tx.diffusion.enabled` | `true` |
| `yano.tx.diffusion.limits.max-in-flight-txs-per-peer` | `100` |
| `yano.tx.diffusion.limits.max-in-flight-bytes-per-peer` | `1048576` |
| `yano.tx.diffusion.limits.peer-cooldown-ms` | `60000` |

## application-sanchonet.yml

Source: [app/config/application-sanchonet.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-sanchonet.yml)

| Property | Packaged value |
| --- | --- |
| `yano.network` | `sanchonet` |
| `yano.remote.host` | `sanchonet-node.play.dev.cardano.org` |
| `yano.remote.port` | `3001` |
| `yano.remote.protocol-magic` | `4` |
| `yano.genesis.shelley-genesis-file` | `config/network/sanchonet/shelley-genesis.json` |
| `yano.genesis.shelley-genesis-hash` | `f94457ec45a0c6773057a529533cf7ccf746cb44dabd56ae970e1dbfb55bfdb2` |
| `yano.genesis.byron-genesis-file` | `config/network/sanchonet/byron-genesis.json` |
| `yano.genesis.alonzo-genesis-file` | `config/network/sanchonet/alonzo-genesis.json` |
| `yano.genesis.conway-genesis-file` | `config/network/sanchonet/conway-genesis.json` |
| `yano.genesis.protocol-parameters-file` | `config/network/sanchonet/protocol-param.json` |
| `yano.upstream.validation.start.mode` | `era` |
| `yano.upstream.validation.start.era` | `conway` |

## application-static-multi.yml

Source: [app/config/application-static-multi.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-static-multi.yml)

| Property | Packaged value |
| --- | --- |
| `yano.upstream.mode` | `static-multi` |
| `yano.upstream.sync.bulk-source` | `single-trusted` |
| `yano.upstream.sync.fan-in-start` | `near-tip` |
| `yano.upstream.discovery.enabled` | `false` |
| `yano.upstream.discovery.peer-sharing` | `false` |
| `yano.upstream.discovery.peer-snapshot-urls` | `[]` |
| `yano.upstream.discovery.peer-snapshot-files` | `[]` |
| `yano.upstream.discovery.seeds` | `[]` |
| `yano.upstream.selection.policy` | `trusted-or-quorum-within-rollback-window` |
| `yano.upstream.selection.quorum` | `2` |
| `yano.upstream.selection.tie-break` | `deterministic` |
| `yano.upstream.governor.enabled` | `true` |
| `yano.upstream.governor.targets.cold` | `32` |
| `yano.upstream.governor.targets.warm` | `4` |
| `yano.upstream.governor.targets.hot` | `3` |
| `yano.upstream.governor.max-concurrent-dials` | `2` |
| `yano.tx.diffusion.enabled` | `true` |

## application-structural-validation.yml

Source: [app/config/application-structural-validation.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-structural-validation.yml)

| Property | Packaged value |
| --- | --- |
| `yano.upstream.validation.level` | `structural` |
| `yano.upstream.validation.body-level` | `none` |
| `yano.upstream.validation.opcert-counter-mode` | `none` |

## application-trusted-peers.yml

Source: [app/config/application-trusted-peers.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-trusted-peers.yml)

| Property | Packaged value |
| --- | --- |
| `yano.upstream.mode` | `trusted-failover` |
| `yano.upstream.sync.bulk-source` | `single-trusted` |
| `yano.upstream.sync.fan-in-start` | `disabled` |
| `yano.upstream.discovery.enabled` | `false` |
| `yano.upstream.discovery.peer-sharing` | `false` |
| `yano.upstream.discovery.peer-snapshot-urls` | `[]` |
| `yano.upstream.discovery.peer-snapshot-files` | `[]` |
| `yano.upstream.discovery.seeds` | `[]` |
| `yano.upstream.governor.enabled` | `false` |
| `yano.upstream.validation.level` | `none` |
| `yano.upstream.validation.body-level` | `none` |
| `yano.upstream.validation.opcert-counter-mode` | `none` |

## application-wallet.yml

Source: [app/config/application-wallet.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application-wallet.yml)

| Property | Packaged value |
| --- | --- |
| `yano.scan.index.enabled` | `true` |
| `yano.address-first-seen.enabled` | `true` |
| `yano.chain.block-body-prune-depth` | `0` |
| `yano.utxo.enabled` | `true` |
| `yano.filters.utxo.enabled` | `false` |

## application.yml

Source: [app/config/application.yml](https://github.com/bloxbean/yano/blob/6f0a8f49fe05132d2196fb755d77a035185cfa4c/app/config/application.yml)

| Property | Packaged value |
| --- | --- |
| `quarkus.http.port` | `7070` |
| `yano.exit-on-epoch-calc-error` | `false` |
| `yano.epoch-boundary.reward-mode` | `streaming` |
| `yano.epoch-boundary.max-batch-operations` | `10000` |
| `yano.epoch-boundary.max-batch-bytes` | `4194304` |

## Declared property keys

These literal property names are declared by the public configuration contract. Presence here does not imply a default or support for arbitrary values. Consult the feature guide and runtime validation for constraints.

- `yano.account-state.enabled`
- `yano.account-state.epoch-block-data-retention-lag`
- `yano.account-state.snapshot-retention-epochs`
- `yano.account.stake-balance-index-enabled`
- `yano.adapot.enabled`
- `yano.address-first-seen.enabled`
- `yano.api-prefix`
- `yano.app-chain.anchor.enabled`
- `yano.app-chain.anchor.every-blocks`
- `yano.app-chain.anchor.fallback-fee-lovelace`
- `yano.app-chain.anchor.max-interval-minutes`
- `yano.app-chain.anchor.metadata-label`
- `yano.app-chain.anchor.mode`
- `yano.app-chain.anchor.script.thread-policy`
- `yano.app-chain.anchor.script.validator`
- `yano.app-chain.anchor.signing-key`
- `yano.app-chain.anchor.validity-slots`
- `yano.app-chain.api.auth.enabled`
- `yano.app-chain.api.keys`
- `yano.app-chain.api.snapshot-admin-keys`
- `yano.app-chain.block.interval-ms`
- `yano.app-chain.block.max-bytes`
- `yano.app-chain.block.max-messages`
- `yano.app-chain.chain-id`
- `yano.app-chain.chains`
- `yano.app-chain.default-ttl-seconds`
- `yano.app-chain.dx.release-catalog-digest`
- `yano.app-chain.dx.resolved-config-digest`
- `yano.app-chain.enabled`
- `yano.app-chain.l1.stability-depth`
- `yano.app-chain.max-message-bytes`
- `yano.app-chain.max-ttl-seconds`
- `yano.app-chain.members`
- `yano.app-chain.message.enforce-sender-seq`
- `yano.app-chain.peers`
- `yano.app-chain.pool.max-messages`
- `yano.app-chain.retention.enabled`
- `yano.app-chain.retention.keep-blocks`
- `yano.app-chain.sequencer.proposer`
- `yano.app-chain.signing-key`
- `yano.app-chain.state-machine`
- `yano.app-chain.state.l1-proof-consumption-required`
- `yano.app-chain.state.proof-pruning.enabled`
- `yano.app-chain.state.proof-pruning.interval-seconds`
- `yano.app-chain.state.proof-pruning.retain-heights`
- `yano.app-chain.storage.path`
- `yano.app-chain.threshold`
- `yano.app-chain.transport.mode`
- `yano.app-chain.validation.strict`
- `yano.app-chain.webhooks`
- `yano.auto-checkpoint-interval`
- `yano.auto-sync-start`
- `yano.block-producer.backfill-block-interval-slots`
- `yano.block-producer.block-time-millis`
- `yano.block-producer.enabled`
- `yano.block-producer.genesis-timestamp`
- `yano.block-producer.initial-epoch`
- `yano.block-producer.initial-epoch-nonce`
- `yano.block-producer.kes-skey-file`
- `yano.block-producer.lazy`
- `yano.block-producer.opcert-file`
- `yano.block-producer.past-time-travel-mode`
- `yano.block-producer.past-time-travel-slot-leader-mode`
- `yano.block-producer.process-skipped-epochs`
- `yano.block-producer.script-evaluator`
- `yano.block-producer.slot-leader-mode`
- `yano.block-producer.slot-length-millis`
- `yano.block-producer.stake-data-provider-url`
- `yano.block-producer.start-epoch`
- `yano.block-producer.tx-evaluation`
- `yano.block-producer.vrf-skey-file`
- `yano.bodyFetch.maxBatchSize`
- `yano.bodyFetch.realtimeFallbackPollMs`
- `yano.bodyFetch.slowEpochTransitionWarnMs`
- `yano.bootstrap.addresses`
- `yano.bootstrap.block-number`
- `yano.bootstrap.blockfrost.api-key`
- `yano.bootstrap.blockfrost.base-url`
- `yano.bootstrap.enabled`
- `yano.bootstrap.koios.base-url`
- `yano.bootstrap.provider`
- `yano.bootstrap.utxos`
- `yano.chain.block-body-prune-depth`
- `yano.chain.block-prune-batch-size`
- `yano.chain.block-prune-interval-seconds`
- `yano.chainstate.recoveryHeaderScanBlocks`
- `yano.client.enabled`
- `yano.debug.rollback-to-epoch`
- `yano.debug.rollback-to-slot`
- `yano.dev-mode`
- `yano.dns.cache.negative.ttl`
- `yano.dns.cache.ttl`
- `yano.epoch-boundary.max-batch-bytes`
- `yano.epoch-boundary.max-batch-operations`
- `yano.epoch-boundary.reward-mode`
- `yano.epoch-params.tracking-enabled`
- `yano.epoch-snapshot.amounts-enabled`
- `yano.epoch-snapshot.balance-mode`
- `yano.exit-on-epoch-calc-error`
- `yano.filters.utxo.addresses`
- `yano.filters.utxo.enabled`
- `yano.filters.utxo.payment-credentials`
- `yano.genesis.alonzo-genesis-file`
- `yano.genesis.byron-genesis-file`
- `yano.genesis.conway-genesis-file`
- `yano.genesis.protocol-parameters-file`
- `yano.genesis.shelley-genesis-file`
- `yano.genesis.shelley-genesis-hash`
- `yano.governance.enabled`
- `yano.headerAppliedEvent.queueCapacity`
- `yano.history.`
- `yano.ledger-apply.max-queued-decoded-bytes`
- `yano.ledger-apply.max-queued-items`
- `yano.ledger-apply.reserved-control-slots`
- `yano.mempool.admin.api-key`
- `yano.mempool.admin.enabled`
- `yano.metrics.enabled`
- `yano.metrics.sample.rocksdb.seconds`
- `yano.network`
- `yano.pipeline.epochBoundaryFallbackWaitMs`
- `yano.pipeline.headerContinuityValidationBlocks`
- `yano.pipeline.nonRecoveringRollbackWaitMs`
- `yano.pipeline.slowBodyCallbackWarnMs`
- `yano.plugins.allow-list`
- `yano.plugins.auto-register-annotated`
- `yano.plugins.deny-list`
- `yano.plugins.directory`
- `yano.plugins.enabled`
- `yano.plugins.logging.enabled`
- `yano.relay.advertised-host`
- `yano.relay.advertised-port`
- `yano.relay.allow-private-addresses`
- `yano.relay.auto-discovery`
- `yano.relay.connection.max-connections-per-ip`
- `yano.relay.connection.max-inbound-connections`
- `yano.relay.connection.source-port-reuse`
- `yano.remote.host`
- `yano.remote.port`
- `yano.remote.protocol-magic`
- `yano.resource-profile`
- `yano.rewards.enabled`
- `yano.rocksdb.atomic_flush`
- `yano.rocksdb.block-cache-bytes`
- `yano.rocksdb.max-background-jobs`
- `yano.rocksdb.max-open-files`
- `yano.rocksdb.pipelined_write`
- `yano.rocksdb.target-file-size-bytes`
- `yano.rocksdb.tuning.enabled`
- `yano.rocksdb.write-buffer-allow-stall`
- `yano.rocksdb.write-buffer-bytes`
- `yano.rollback-retention-epochs`
- `yano.scan.index.enabled`
- `yano.server.enabled`
- `yano.server.port`
- `yano.storage.path`
- `yano.storage.rocksdb`
- `yano.tx.diffusion.enabled`
- `yano.tx.diffusion.limits.max-in-flight-bytes-per-peer`
- `yano.tx.diffusion.limits.max-in-flight-txs-per-peer`
- `yano.tx.diffusion.limits.peer-cooldown-ms`
- `yano.tx.diffusion.mode`
- `yano.tx.mempool.max-bytes`
- `yano.tx.mempool.max-txs`
- `yano.tx.mempool.max-utxo-index-entries`
- `yano.tx.mempool.ttl-seconds`
- `yano.upstream.discovery.allow-private-addresses`
- `yano.upstream.discovery.allowlist`
- `yano.upstream.discovery.denylist`
- `yano.upstream.discovery.enabled`
- `yano.upstream.discovery.ledger-peers`
- `yano.upstream.discovery.peer-sharing`
- `yano.upstream.discovery.peer-snapshot-files`
- `yano.upstream.discovery.peer-snapshot-limit`
- `yano.upstream.discovery.peer-snapshot-urls`
- `yano.upstream.discovery.seeds`
- `yano.upstream.discovery.topology-file`
- `yano.upstream.discovery.use-ledger-after-slot`
- `yano.upstream.failover.cooldown-ms`
- `yano.upstream.failover.max-failures-before-cooldown`
- `yano.upstream.governor.enabled`
- `yano.upstream.governor.max-concurrent-dials`
- `yano.upstream.governor.targets.cold`
- `yano.upstream.governor.targets.hot`
- `yano.upstream.governor.targets.warm`
- `yano.upstream.mode`
- `yano.upstream.peers`
- `yano.upstream.selection.policy`
- `yano.upstream.selection.quorum`
- `yano.upstream.selection.require-body-before-adoption`
- `yano.upstream.selection.rollback-window-slots`
- `yano.upstream.selection.tie-break`
- `yano.upstream.selection.trust-policy`
- `yano.upstream.sync.bulk-source`
- `yano.upstream.sync.fan-in-start`
- `yano.upstream.tx.forwarding`
- `yano.upstream.validation.body-level`
- `yano.upstream.validation.level`
- `yano.upstream.validation.opcert-counter-mode`
- `yano.upstream.validation.start.era`
- `yano.upstream.validation.start.hash`
- `yano.upstream.validation.start.mode`
- `yano.upstream.validation.start.slot`
- `yano.utxo.applyAsync`
- `yano.utxo.delta.selfContained`
- `yano.utxo.enabled`
- `yano.utxo.index-contributors`
- `yano.utxo.index.address_hash`
- `yano.utxo.index.payment_credential`
- `yano.utxo.indexingStrategy`
- `yano.utxo.lag.failIfAbove`
- `yano.utxo.metrics.lag.logSeconds`
- `yano.utxo.prune.schedule.seconds`
- `yano.utxo.pruneBatchSize`
- `yano.utxo.pruneDepth`
- `yano.utxo.rebuild-unmarked-from-genesis`
- `yano.utxo.rollbackWindow`
- `yano.validation.default-validator-enabled`
- `yano.validation.supplementary-rules-enabled`
