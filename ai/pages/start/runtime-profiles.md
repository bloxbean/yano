# Choose a runtime profile

Start Yano with resource sizing and optional wallet indexes.

Canonical URL: https://getyano.dev/start/runtime-profiles/

Yano's resource profiles provide practical starting points for the Java heap,
RocksDB memory, and the decoded-block queue. Add one profile after the network:

```bash
./yano.sh start:mainnet,small
./yano.sh start:mainnet,medium
./yano.sh start:mainnet,large
```

Use only one resource profile at a time. The standalone ZIP's `yano.sh` applies its maximum heap to
both JVM and native distributions. An explicit `-Xmx` in `JAVA_OPTS` overrides
that value. For the native distribution, `YANO_NATIVE_MAX_HEAP` also overrides
the profile default, and an explicit `-Xmx` in `JAVA_OPTS` still takes
precedence over it.

In the **Docker Compose distribution**, these profiles apply the RocksDB and
queue settings, but do not automatically set the maximum heap. Set it explicitly,
for example `JAVA_OPTS="-Xmx384m" ./yano.sh start:mainnet,small`.
See [Docker profiles and memory](/start/docker/#profiles-and-memory) for details.

These profiles are available when the matching file exists in the extracted
`config/` directory, such as `config/application-small.yml`. Older releases
without those files do not provide them.

## Pick a size

| Profile                        |                         Host RAM guidance |                            Maximum heap | When to use it                                          |
| ------------------------------ | ----------------------------------------: | --------------------------------------: | ------------------------------------------------------- |
| Standard (no resource profile) |          3 GiB minimum; 4 GiB recommended | JVM ergonomics; 1536 MiB native default | General use                                             |
| `xsmall`                       |                         1.5 GiB candidate |                                 384 MiB | Experimental, severely constrained systems; slower sync |
| `small`                        | 3 GiB recommended for public-network sync |                                 384 MiB | Lowest validated full-sync settings                     |
| `medium`                       |                             4 GiB minimum |                                1536 MiB | More heap and read-cache headroom                       |
| `large`                        |          6 GiB minimum; 8 GiB recommended |                                   2 GiB | Experimental throughput testing                         |

Host RAM is not the same as Java heap. RocksDB, native allocations, thread
stacks, mapped files, and the operating system also use memory. Treat these as
starting points and monitor resident memory during a full sync.

## Settings applied

All memory values below are aggregate budgets shared across RocksDB column
families.

| Profile  | Decoded queue | Read cache | Write buffers | Background jobs | Open files | SST target | Allow write stall |
| -------- | ------------: | ---------: | ------------: | --------------: | ---------: | ---------: | ----------------- |
| Standard |        16 MiB |    256 MiB |       256 MiB |               2 |        256 |     64 MiB | No                |
| `xsmall` |         4 MiB |     16 MiB |        32 MiB |               1 |        128 |    128 MiB | Yes               |
| `small`  |         4 MiB |    128 MiB |       128 MiB |               2 |        256 |     64 MiB | No                |
| `medium` |        16 MiB |    512 MiB |       256 MiB |               2 |        256 |     64 MiB | No                |
| `large`  |        32 MiB |      1 GiB |       512 MiB |               4 |        512 |     64 MiB | No                |

The queue limit measures encoded block bytes; decoded Java objects can occupy
more heap. The RocksDB cache and write buffers use native memory outside the
Java heap.

## Add wallet discovery and scans

The `wallet` feature profile composes with a network and a resource profile:

```bash
./yano.sh start:mainnet,small,wallet
```

Enable it before the first sync into a fresh database. It provides:

- exact-address first-seen lookup at
  `GET /api/v1/addresses/{address}/first-seen`;
- a streaming, filtered wallet scan at `POST /api/v1/scan`;
- complete UTxO storage and retained block bodies needed by those queries.

The wallet profile does not enable archival history, hold private keys, create
wallets, or sign transactions. See [wallet discovery and scans](/node/wallet-indexes/)
for request formats and coverage rules.

For example, a larger wallet-indexing node can use:

```bash
./yano.sh start:mainnet,medium,wallet
```
