# Custom Profiles

Yano distributions can run any Quarkus profile defined in `config/application.yml`.

## Add Network Files

Create a network directory:

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

This is equivalent to:

```bash
./yano.sh --profile=mydevnet
```

`--profile=mydevnet` remains supported, but `start:mydevnet` is the preferred command form.

Profile names may contain letters, numbers, dot, underscore, and dash.

## Chainstate

The jar and native zip distributions use the configured `yano.storage.path`, which defaults to `./chainstate`.

For a separate chainstate directory, set a property or environment override:

```bash
YANO_STORAGE_PATH=./chainstate-mydevnet ./yano.sh start:mydevnet
```

If genesis values change after a chainstate already exists, stop Yano and reset that chainstate directory before starting again.
