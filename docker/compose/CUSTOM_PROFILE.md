# Custom Profiles

The Docker compose distribution can run any Quarkus profile defined in `config/application.yml`.

## Add Network Files

Create a network directory in the extracted Docker distribution:

```text
config/network/mydevnet/
```

Add the genesis and protocol parameter files required by your profile:

```text
config/network/mydevnet/shelley-genesis.json
config/network/mydevnet/byron-genesis.json
config/network/mydevnet/alonzo-genesis.json
config/network/mydevnet/conway-genesis.json
config/network/mydevnet/protocol-param.json
```

For a block-producing devnet profile, also add:

```text
config/network/mydevnet/vrf.skey
config/network/mydevnet/kes.skey
config/network/mydevnet/opcert.cert
```

## Add A Profile

Edit `config/application.yml` and add a matching profile:

```yaml
"%mydevnet":
  yano:
    network: mydevnet
    auto-sync-start: true
    dev-mode: true
    client:
      enabled: false
    remote:
      protocol-magic: 42
    genesis:
      shelley-genesis-file: config/network/mydevnet/shelley-genesis.json
      shelley-genesis-hash: ""
      byron-genesis-file: config/network/mydevnet/byron-genesis.json
      alonzo-genesis-file: config/network/mydevnet/alonzo-genesis.json
      conway-genesis-file: config/network/mydevnet/conway-genesis.json
      protocol-parameters-file: config/network/mydevnet/protocol-param.json
    block-producer:
      enabled: true
      vrf-skey-file: config/network/mydevnet/vrf.skey
      kes-skey-file: config/network/mydevnet/kes.skey
      opcert-file: config/network/mydevnet/opcert.cert
```

## Start

Run the profile by name:

```bash
./yano.sh start:mydevnet
```

Inspect the resolved compose file first:

```bash
./yano.sh config:mydevnet
```

Restart the same profile:

```bash
./yano.sh restart:mydevnet
```

Profile names may contain letters, numbers, dot, underscore, and dash.

## Chainstate

For a custom profile, Docker uses this default host chainstate path:

```text
chainstate-mydevnet/
```

To use a different path, set `YANO_CHAINSTATE_PATH`:

```bash
YANO_CHAINSTATE_PATH=/data/yano-mydevnet ./yano.sh start:mydevnet
```

You can also set it permanently in `compose/.env`:

```text
YANO_CHAINSTATE_PATH=/data/yano-mydevnet
```

If genesis values change after a chainstate already exists, stop Yano and reset that chainstate directory before starting again.
