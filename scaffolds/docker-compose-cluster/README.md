# App-chain cluster scaffold (docker-compose)

A ready-to-run 3-node Yano app-chain cluster: one sequencer (`node-a`) and two
followers, sharing a single app chain (`demo-chain`) with the `kv-registry`
standard-library state machine and a 2-of-3 finality threshold.

## Run

```bash
# from the repo root
./gradlew :app:quarkusBuild
docker build -t yano-node:local -f scaffolds/docker-compose-cluster/Dockerfile .

cd scaffolds/docker-compose-cluster
docker compose up
```

Each node exposes REST:

| Node   | Role      | REST                 | appmsg |
|--------|-----------|----------------------|--------|
| node-a | sequencer | http://localhost:18081 | 13337 |
| node-b | follower  | http://localhost:18082 | —      |
| node-c | follower  | http://localhost:18083 | —      |

## Try it

```bash
# submit to the sequencer (kv-registry PUT is topic-agnostic; body is the command)
curl -XPOST localhost:18081/api/v1/app-chain/messages \
     -H 'content-type: application/json' \
     -d '{"topic":"registry","bodyHex":"<kv-registry PUT command hex>"}'

# tip + state root, from any node (should converge)
curl localhost:18081/api/v1/app-chain/tip
curl localhost:18082/api/v1/app-chain/tip

# a state proof from a follower — verifiable client-side (appchain-client SDK)
curl localhost:18083/api/v1/app-chain/proof/<keyHex>
```

## Customize

- **Keys**: the seeds here are demo-only. Generate real Ed25519 32-byte seeds
  and update `YANO_APP_CHAIN_SIGNING_KEY` / `_PROPOSER` / `_MEMBERS`.
- **State machine**: set `YANO_APP_CHAIN_STATE_MACHINE` to `ordered-log`,
  `kv-registry`, `approvals`, `balances`, `doc-trail`, or a custom plugin id
  (see `../plugin-template`).
- **Push/anchor/auth/retention**: add the corresponding `YANO_APP_CHAIN_*`
  environment variables (see `docs/APP_CHAIN_USER_GUIDE.md`).
- **Plugins**: mount a directory of plugin jars and set
  `YANO_PLUGINS_DIRECTORY=/plugins`.
