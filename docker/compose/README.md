# Yano Docker Compose

This compose bundle runs Yano from `bloxbean/yano`.

Released image tag for this bundle: `@YANO_IMAGE_TAG@`

## Start

From the extracted distribution root:

```bash
./yano.sh start
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

## Devnet

Devnet mode is explicit:

```bash
./yano.sh start:devnet
```

To inspect the devnet overlay:

```bash
./yano.sh config:devnet
```

## Configuration

Runtime environment is in `config/env`.

The image contains immutable network files under `/app/config/network`. The compose file mounts only `config/application.yml` over `/app/config/application.yml`, so bundled network configs remain available.

`YANO_PROFILE` selects the bundled profile. The default `config/env` does not set `YANO_NETWORK`, so the selected profile controls the network unless you explicitly override it.

The container runs as UID/GID from `YANO_UID` and `YANO_GID`, defaulting to `1000:1000`. On Linux hosts with a different user ID, set these values in `compose/.env` to match the user that owns `chainstate/`, `logs/`, and `plugins/`.

For a custom network, add an explicit mount for that network directory:

```yaml
volumes:
  - ../config/network/custom:/app/config/network/custom:ro
```
