# Troubleshooting

Diagnose startup, synchronization, and unavailable data.

Canonical URL: https://getyano.dev/operate/troubleshooting/

## The process will not start

Check `java -version` (JDK 25), the working directory, configured storage ownership, and whether ports `7070` or `13337` are already in use. Start the launcher inside the complete extracted distribution; Java is needed only for the JVM ZIP.

Do not run two processes against one RocksDB directory. Keep node storage and network configuration together when moving an installation.

## The node is healthy but still behind

Read `/api/v1/node/status` (local/remote tips, sync progress and mode, selected peer, upstream state, and any runtime-degraded reason) and `/api/v1/node/tip`; `/api/v1/status` reports the chain tip plus UTxO store, prune, and history state. Readiness means the application is ready to serve; public-network synchronization can still be in progress. The status response also exposes recovery reasons, rejected validation details, and time since application progress; consult logs for additional context.

Confirm the genesis/network magic and upstream reachability. Repeatedly restarting or deleting state can hide the original error; preserve logs and configuration first.

## A wallet query returns unavailable

Wallet indexes must be enabled before a fresh sync. An existing database does not gain historical completeness when you flip a flag. Scan queries also need retained block bodies. A `503` is not an empty account or an unused address. See [wallet indexes](/node/wallet-indexes/).

## Report a problem

Include the source revision or artifact version, JVM/native platform, enabled profiles, redacted configuration, steps to reproduce, expected behavior, and relevant logs. Do not include signing keys, API keys, or private payloads. Open an issue in [the Yano repository](https://github.com/bloxbean/yano/issues).
