# Yano Docker Compose

This compose bundle runs Yano from `bloxbean/yano`.

Released image tag for this bundle: `@YANO_IMAGE_TAG@`

## Start

From the extracted distribution root:

```bash
./yano.sh start
```

Build instructions for jar, native, and Docker compose zip distributions are in `docs/BUILD_DISTRIBUTIONS.md` in the source repository.

Network-specific start commands are available:

```bash
./yano.sh start:preprod
./yano.sh start:preprod,relay
./yano.sh start:preprod,relay,praos-lite
./yano.sh start:mainnet
./yano.sh start:preview
./yano.sh start:sanchonet
./yano.sh start:devnet
```

Custom profiles use the same command shape:

```bash
./yano.sh start:mydevnet
```

The configured image is:

```text
bloxbean/yano:${YANO_IMAGE_TAG:-latest}-jvm
```

Release bundles are pinned to the released version in `compose/.env`. Use `latest` only when you explicitly want floating Docker tags.

Yano publishes flavor-specific image tags. Use the `-jvm` or `-native` suffix explicitly when pulling images outside this compose bundle.

## Native Image

Set the image flavor in `compose/.env`:

```text
YANO_IMAGE_FLAVOR=native
```

Then restart the service:

```bash
./yano.sh restart
```

`JAVA_OPTS` is honored by both image flavors. The native image defaults to a
1536 MiB maximum heap; set `YANO_NATIVE_MAX_HEAP`, or provide `-Xmx` in
`JAVA_OPTS`, to override it. `YANO_EXTRA_ARGS` remains available for additional
runtime arguments.

The shared Compose file still mounts `plugins/` for the JVM flavor. A native
image cannot load JARs from that mount; any JARs there are reported as ignored.
Native Yano embeds only retained core providers. Run the Yano X JVM distribution
when optional state machines, connectors, or product plugins are required.

To inspect the resolved compose file for a network:

```bash
./yano.sh config:mainnet
./yano.sh config:devnet
```

## Configuration

Runtime environment is in `config/env`.

The complete `config` directory is mounted read-only at `/app/config`. Edits to
`application.yml` and `application-<profile>.yml`, including newly added custom
profiles, take effect when the node restarts. This directory replaces the image's
bundled configuration, so keep the extracted config files together and use a
distribution compatible with the selected image version.

Network genesis and protocol parameter files are in `config/network`. The compose file mounts this directory to `/app/config/network`, so edits on the host are visible in the container.

This nested network mount is writable: startup can seed missing files and devnet
can update genesis files without making the application YAML files writable.

The Docker image also contains an immutable copy of the default network files. On startup, Yano seeds any missing files from that default copy. If you accidentally edit or remove a file, delete the host copy and restart Yano to restore the bundled default.

`YANO_PROFILE` and `YANO_NETWORK` select the bundled profile and network. The launcher sets both when you use `start:<profile>`.
`YANO_PROFILE` may be a comma-separated Quarkus profile list such as
`preprod,relay,praos-lite`; `YANO_NETWORK` remains the first profile, such as
`preprod`.

The launcher combines `yano.yml` with `yano-<network>.yml` for `mainnet`,
`preview`, `sanchonet`, and `devnet`. These Compose overrides select network-specific
state directories; the application settings come from the YAML profiles in
`config`. Preprod uses the base Compose file. Custom networks use the base file
with state paths supplied by the launcher.

Each network uses separate L1 and app-chain state directories by default. The launcher creates both selected directories before Docker Compose starts, so they are owned by the user running `yano.sh`:

```text
chainstate-preprod/
chainstate-mainnet/
chainstate-preview/
chainstate-devnet/
appchain-chainstate-preprod/
appchain-chainstate-mainnet/
appchain-chainstate-preview/
appchain-chainstate-devnet/
appchain-indexers-preprod/
appchain-indexers-mainnet/
appchain-indexers-preview/
appchain-indexers-devnet/
```

`start:sanchonet` and custom profiles use the same `chainstate-<profile>/` and
`appchain-chainstate-<profile>/` conventions.

To use a custom host chainstate path, set `YANO_CHAINSTATE_PATH` in `compose/.env` or for one command:

```bash
YANO_CHAINSTATE_PATH=/data/yano-mainnet ./yano.sh start:mainnet
```

Set `YANO_APPCHAIN_STATE_PATH` the same way when app-chain state should live at
a custom host path.

Set `YANO_APPCHAIN_INDEXER_PATH` for the rebuildable app-chain read-index root.
Do not place it below either authoritative state directory.

`runtime-data-<network>/` is mounted at `/app/data` for devnet snapshots,
automatic epoch checkpoints, the upstream peer store, and the `projection`
history archive (`history/`). Override its host path with
`YANO_RUNTIME_DATA_PATH`. The existing `chainstate-<network>/` directory is
mounted inside it at `/app/data/chainstate`, so database files do not move. Keep
both directories when backing up runtime data. The Compose file sets
`YANO_STORAGE_PATH` and `YANO_HISTORY_DIR` to these container paths, overriding
any older values in `config/env`.

For simultaneous instances, use separate extracted directories and set distinct
`INSTANCE_NAME`, `YANO_HTTP_PORT`, and `YANO_N2N_PORT` values in each
`compose/.env`. The Compose project is named `yano-${INSTANCE_NAME:-default}`;
separate folders alone do not distinguish instances. For example, use
`INSTANCE_NAME=devnet` in one folder and `INSTANCE_NAME=preprod` in another.
An explicit `COMPOSE_PROJECT_NAME` still overrides the project name.

The launcher manages only the container that this directory's Compose file
created. If another directory already runs a container with the same name,
`start`, `restart`, and `stop` exit with an error instead of replacing it.

Bundles without a Compose project name ran every instance as project
`compose`. After upgrading such a directory in place, `./yano.sh start` reports
the old container; run `./yano.sh stop` or `./yano.sh restart` to remove it and
continue under the new project name. If the old container was started from a
different directory, stop it from that directory.
If you customize storage paths, give each instance its own `YANO_CHAINSTATE_PATH`,
`YANO_RUNTIME_DATA_PATH`, `YANO_APPCHAIN_STATE_PATH`, and `YANO_APPCHAIN_INDEXER_PATH`.

The container runs as UID/GID from `YANO_UID` and `YANO_GID`, defaulting to `1000:1000`. On Linux hosts with a different user ID, set these values in `compose/.env` to match the user that owns `chainstate-*`, `runtime-data-*`, `appchain-chainstate-*`, `appchain-indexers-*`, `logs/`, `plugins/`, and `config/network`.

For a custom network, add its files under `config/network/<name>` and run with a matching custom Quarkus profile:

```bash
./yano.sh start:<name>
./yano.sh start:<name>,relay
```

See `CUSTOM_PROFILE.md` for the full setup.
