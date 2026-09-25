---
title: "Run with Docker Compose"
description: "Start the Docker distribution, combine profiles, and configure mounts and memory."
sidebar:
  order: 4
---

Install Docker Engine or Docker Desktop with Compose v2. Download
`yano-docker-<version>.zip` from [Yano Releases](https://github.com/bloxbean/yano/releases)
and extract the complete archive. The ZIP contains configuration and launchers;
Docker downloads the executable image separately. No host Java installation is needed.

The following commands use a POSIX shell, including a terminal on macOS or Linux.

```bash
VERSION="<version>" # Replace with the version in the release asset name, without v.
unzip "yano-docker-${VERSION}.zip"
cd "yano-docker-${VERSION}"
./yano.sh config:devnet
./yano.sh start:devnet
```

Open the console at `http://localhost:7070/ui/`. Check readiness with:

```bash
curl -fsS http://localhost:7070/q/health/ready
./yano.sh status
```

`./yano.sh logs` follows the container logs. `./yano.sh stop` removes the container
while preserving the host's state directories.

## Images and ports

`compose/.env` pins `YANO_IMAGE_TAG` to the ZIP's release version and defaults
`YANO_IMAGE_FLAVOR` to `jvm`, selecting `bloxbean/yano:<version>-jvm`. Set the
flavor to `native` to select the matching native image. Use the JVM flavor for
dynamic plugins and JVM-only capabilities such as DuckLake history.

Preview releases use their versioned image tags; do not rely on a `latest` tag.
Keep the image version compatible with the ZIP's config files. Change
`YANO_HTTP_PORT`, `YANO_N2N_PORT`, or `INSTANCE_NAME` in `compose/.env` as needed
before starting.

To run two instances, extract into separate directories and give each a distinct
`INSTANCE_NAME`, `YANO_HTTP_PORT`, and `YANO_N2N_PORT`. Compose uses
`yano-<INSTANCE_NAME>` as its project name, so stopping one instance does not
stop another. Separate folders alone are insufficient when their instance names
are the same; the launcher then refuses to start, restart, or stop the
container that the other directory owns. The same applies if you set
`COMPOSE_PROJECT_NAME`: each directory needs its own value. Keep any explicitly configured storage
paths separate too.

Older Compose ZIPs ran every instance as project `compose`. After upgrading a
directory in place, `./yano.sh start` reports the old container. Run
`./yano.sh stop` or `./yano.sh restart` from that directory to replace it.

## Profiles and memory

Put the network first, then add comma-separated Quarkus profiles:

```bash
./yano.sh config:preprod,wallet,praos-lite,small
JAVA_OPTS="-Xmx384m" ./yano.sh start:preprod,wallet,praos-lite,small
```

The complete list reaches Quarkus. The first profile selects the network and
its storage directories. Enable `wallet` before the first sync into a fresh
database; see [wallet indexes](/node/wallet-indexes/) for coverage requirements.
Choose profiles supported by your image and release.

Resource profiles such as `small` and `medium` apply their YAML settings for
RocksDB and the decoded-block queue. **The Docker launcher does not select a
heap limit from the resource profile.** Set `JAVA_OPTS` explicitly for either
image flavor, or persist it in `compose/.env`:

```dotenv
JAVA_OPTS=-Xmx384m
YANO_PROFILE=preprod,wallet,praos-lite,small
```

With these settings saved, `./yano.sh start` and `./yano.sh restart` use them.
For a one-command override, repeat the settings when restarting. See
[runtime profiles](/start/runtime-profiles/) for suggested heap sizes and
off-heap memory budgets.

The native image uses `YANO_NATIVE_MAX_HEAP` (default `1536m`) when `JAVA_OPTS`
has no `-Xmx`. An explicit `-Xmx` takes precedence. The JVM image uses JVM
ergonomics unless you supply a heap limit.

## Configuration and storage

Edit `config/application.yml` or `config/application-<profile>.yml`, then restart
with the intended profile list. You can add your own profile files alongside
them. The complete `config` directory is mounted read-only at `/app/config`.
The nested `config/network` mount is writable for missing-file seeding and
devnet genesis updates. Older Compose ZIPs may mount only `application.yml`;
check their `compose/yano.yml` before relying on external profile files.

`config/env` supplies container environment variables. `compose/.env` controls
image selection, ports, profiles, and other Compose settings. Logs and plugins
are mounted from the extracted `logs/` and `plugins/` directories.

The launcher combines `compose/yano.yml` with the matching network override
for `mainnet`, `preview`, `sanchonet`, or `devnet`, and supplies each network's
data paths. Inspect the result with `./yano.sh config:<profiles>` before starting.

Each network keeps its data in one folder beside `compose/`:

```text
data-preprod/
  chainstate/            L1 database (/app/data/chainstate)
  runtime-data/          snapshots, epoch checkpoints, peer store, projection history (/app/data)
  appchain-chainstate/   authoritative app-chain state
  appchain-indexers/     rebuildable app-chain read indexes
```

With the default layout, back up or move a network by copying its whole
`data-<network>/` folder. If you set any of the path variables below, or an upgraded
installation still uses folders from the earlier layout, back up every resolved
path instead: `./yano.sh config:<network>` lists them as volume `source` entries.

To place one of these folders elsewhere, set `YANO_CHAINSTATE_PATH`,
`YANO_RUNTIME_DATA_PATH`, `YANO_APPCHAIN_STATE_PATH` or `YANO_APPCHAIN_INDEXER_PATH`
in `compose/.env`; values there may reference other variables defined in the file,
such as `${DATA_ROOT}/chainstate`. The Compose file sets the container storage and
history paths, so an older `config/env` does not need changes.

Folders from earlier bundles, such as `chainstate-preprod/` beside `compose/`,
stay in use while their variable is unset, so upgrading does not start a new sync.
To adopt the new layout, stop Yano and move each into `data-<network>/`, for
example `mv chainstate-preprod data-preprod/chainstate`.

On Linux, set `YANO_UID` and `YANO_GID` in `compose/.env` to the owner of the
writable host directories if it differs from the default `1000:1000`.
