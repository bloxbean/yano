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

`JAVA_OPTS` applies only to the JVM image. For native-image runtime flags, use `YANO_EXTRA_ARGS`.

To inspect the resolved compose file for a network:

```bash
./yano.sh config:mainnet
./yano.sh config:devnet
```

## Configuration

Runtime environment is in `config/env`.

Network genesis and protocol parameter files are in `config/network`. The compose file mounts this directory to `/app/config/network`, so edits on the host are visible in the container.

The Docker image also contains an immutable copy of the default network files. On startup, Yano seeds any missing files from that default copy. If you accidentally edit or remove a file, delete the host copy and restart Yano to restore the bundled default.

`YANO_PROFILE` selects the bundled profile. The default `config/env` does not set `YANO_NETWORK`, so the selected profile controls the network unless you explicitly override it.

Each network uses its own chainstate directory by default:

```text
chainstate-preprod/
chainstate-mainnet/
chainstate-preview/
chainstate-sanchonet/
chainstate-devnet/
```

To use a custom host chainstate path, set `YANO_CHAINSTATE_PATH` in `compose/.env` or for one command:

```bash
YANO_CHAINSTATE_PATH=/data/yano-mainnet ./yano.sh start:mainnet
```

The container runs as UID/GID from `YANO_UID` and `YANO_GID`, defaulting to `1000:1000`. On Linux hosts with a different user ID, set these values in `compose/.env` to match the user that owns `chainstate-*`, `logs/`, `plugins/`, and `config/network`.

For a custom network, add its files under `config/network/<name>` and run with a matching custom Quarkus profile:

```bash
./yano.sh start:<name>
```

See `CUSTOM_PROFILE.md` for the full setup.
