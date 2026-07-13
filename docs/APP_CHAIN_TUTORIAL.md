# App Chain Tutorial — Default Yano Distribution

A hands-on, copy-pasteable walkthrough in two parts:

- **Part 1** — run an app chain with the **out-of-the-box `ordered-log`
  implementation**: two Yano nodes from the default distribution, config only,
  no code.
- **Part 2** — build a **custom app chain as a plugin jar** (a small
  key-value store), drop it into the plugins directory of the same default
  distribution, and use it — still no Yano rebuild.

Reference documentation (all config options, REST reference, ops):
`docs/APP_CHAIN_USER_GUIDE.md`. Internals (consensus round, state-machine
wire formats): `docs/APP_CHAIN_CONSENSUS_GUIDE.md`. Design:
`adr/app-layer/005-yano-app-chain-framework.md`.

---

## Part 0 — Get the distribution

Yano ships in three official forms per release
([github.com/bloxbean/yano/releases](https://github.com/bloxbean/yano/releases)),
plus building from source. The app chain works identically in all of them —
only *where the configuration lives* differs.

> **Shortcut:** if you just want a running multi-node app chain in one
> command, the zip ships a cluster launcher —
> `./appchain-cluster/cluster.sh start 3` — with load-test and anchoring
> helpers included (see `appchain-cluster/README.md` and the user guide
> §3.5). This tutorial instead walks the two nodes by hand so you see every
> moving part.

### Option A — Official zip (JVM)

```bash
unzip yano-<version>.zip && cd yano-<version>
./yano.sh start:devnet \
  -Dyano.app-chain.enabled=true \
  -Dyano.app-chain.chain-id=tutorial-chain \
  ...                                   # any -D flag from this tutorial
```

The zip contains `yano.jar`, the `yano.sh` launcher and a `config/` directory.
Instead of `-D` flags you can put the same keys under `yano.app-chain.*` in
`config/application.yml` — the recommended place for a permanent setup. Run
from the unzipped directory (network genesis files resolve relative to it).
`JAVA_OPTS` / `YANO_EXTRA_ARGS` env vars are honored.

Native binaries (`yano-native-<version>-<os>-<arch>.zip`) have the same layout
with a `yano` binary instead of the jar — same flags, same `config/`. Note:
**plugin jars (Kafka sink, ZK, custom state machines) require the JVM
distribution**; the native image cannot load them.

### Option B — Docker

```bash
unzip yano-docker-<version>.zip && cd yano-docker-<version>
# app-chain settings: edit config/application.yml (mounted read-only into the
# container) or add -D flags via YANO_EXTRA_ARGS in config/env
./yano.sh start:devnet          # or start:preprod / start:preview / start
./yano.sh logs:yano
```

The compose bundle runs the `bloxbean/yano` image (`-jvm` or `-native`
flavor), exposes REST on `7070` and N2N on `13337`, and mounts:

| Host path | In container | Use for |
|---|---|---|
| `config/application.yml` | `/app/config/application.yml` | `yano.app-chain.*` settings |
| `plugins/` | `/app/plugins` | T3 plugin jars (`yaci.plugins.directory` is preset) |
| `chainstate-*/` | `/app/chainstate` | persistent chain + app-chain ledgers |

For the two-node tutorial cluster below, run two copies of the compose bundle
with distinct `INSTANCE_NAME`, `YANO_HTTP_PORT` and `YANO_N2N_PORT`, and point
each node's `yano.app-chain.peers` at the other's published N2N port
(`host.docker.internal:<port>` from inside a container, or put both services
on one compose network).

### Option C — From source

```bash
./gradlew :app:quarkusBuild        # → app/build/yano.jar (uber-jar)
```

Everything below uses Option C's `java -jar` form with explicit `-D` flags —
the flags map 1:1 to `config/application.yml` keys in Options A/B.

---

## Part 1 — Out-of-the-box app chain (`ordered-log`)

You will run two nodes on one machine: **node A** is a devnet L1 block
producer *and* the app-chain **sequencer**; **node B** follows A's L1 chain
and is an app-chain **member**. The app chain is a tamper-evident, replicated,
ordered log of opaque messages with MPF inclusion proofs.

> The devnet L1 is used so the tutorial is self-contained. The app chain is
> independent of the L1 role — the same flags work on a preprod/mainnet relay.

### 1.1 Demo keys (DO NOT use in production)

This tutorial uses fixed Ed25519 seeds so outputs are reproducible:

| Who | Private seed (hex) | Public key (hex) |
|---|---|---|
| Member A (sequencer) | `01…01` (32×`01`) | `8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c` |
| Member B | `02…02` (32×`02`) | `8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394` |
| Anchor wallet | `03…03` (32×`03`) | (address printed by node A at startup) |

Generate real keys for real deployments:

```bash
jshell --class-path app/build/yano.jar - <<'EOF'
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
byte[] seed = new byte[32]; new java.security.SecureRandom().nextBytes(seed);
System.out.println("private (seed): " + HexUtil.encodeHexString(seed));
System.out.println("public        : " + HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed)));
EOF
```

### 1.2 Start node A (devnet producer + app-chain sequencer + anchoring)

```bash
export PUB_A=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c
export PUB_B=8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394

mkdir -p /tmp/appchain-tutorial
cp app/config/network/devnet/shelley-genesis.json /tmp/appchain-tutorial/genesis-a.json

cd app
java -Dquarkus.profile=devnet -Dquarkus.http.port=7070 \
  -Dyano.genesis.shelley-genesis-file=/tmp/appchain-tutorial/genesis-a.json \
  -Dyano.storage.path=/tmp/appchain-tutorial/chainstate-a \
  -Dyano.app-chain.enabled=true \
  -Dyano.app-chain.chain-id=tutorial-chain \
  -Dyano.app-chain.signing-key=0101010101010101010101010101010101010101010101010101010101010101 \
  -Dyano.app-chain.members="$PUB_A,$PUB_B" \
  -Dyano.app-chain.peers=localhost:13338 \
  -Dyano.app-chain.sequencer.proposer=$PUB_A \
  -Dyano.app-chain.threshold=2 \
  -Dyano.app-chain.block.interval-ms=1000 \
  -Dyano.app-chain.anchor.enabled=true \
  -Dyano.app-chain.anchor.signing-key=0303030303030303030303030303030303030303030303030303030303030303 \
  -Dyano.app-chain.anchor.every-blocks=2 \
  -jar build/yano.jar > /tmp/appchain-tutorial/node-a.log 2>&1 &

until curl -sf http://localhost:7070/q/health/ready > /dev/null; do sleep 1; done
echo "node A up"
```

What the flags mean: this node participates in chain `tutorial-chain` as
member A, connects to its peer B on port 13338, is the only member allowed to
sequence blocks (`sequencer.proposer`), requires **both** members to co-sign
every block (`threshold: 2`), and anchors the state root to devnet L1 every 2
app blocks.

### 1.3 Start node B (L1 follower + app-chain member)

Node B needs **A's effective genesis** (the devnet producer writes the actual
`systemStart` into the genesis file it loaded — copy it *after* A started):

```bash
cp /tmp/appchain-tutorial/genesis-a.json /tmp/appchain-tutorial/genesis-b.json

java -Dquarkus.profile=devnet -Dquarkus.http.port=7071 \
  -Dyano.genesis.shelley-genesis-file=/tmp/appchain-tutorial/genesis-b.json \
  -Dyano.storage.path=/tmp/appchain-tutorial/chainstate-b \
  -Dyano.server.port=13338 \
  -Dyano.block-producer.enabled=false \
  -Dyano.dev-mode=false \
  -Dyano.client.enabled=true \
  -Dyano.remote.host=localhost -Dyano.remote.port=13337 \
  -Dyano.app-chain.enabled=true \
  -Dyano.app-chain.chain-id=tutorial-chain \
  -Dyano.app-chain.signing-key=0202020202020202020202020202020202020202020202020202020202020202 \
  -Dyano.app-chain.members="$PUB_A,$PUB_B" \
  -Dyano.app-chain.peers=localhost:13337 \
  -Dyano.app-chain.sequencer.proposer=$PUB_A \
  -Dyano.app-chain.threshold=2 \
  -jar build/yano.jar > /tmp/appchain-tutorial/node-b.log 2>&1 &

until curl -sf http://localhost:7071/q/health/ready > /dev/null; do sleep 1; done
echo "node B up"; sleep 10   # let L1 sync + app peers connect
```

Note `sequencer.proposer` is still `$PUB_A` — it names *who may sequence*,
not who this node is. Node B verifies, co-signs and stores every block.

### 1.4 Fund the anchor wallet (devnet faucet)

```bash
ANCHOR_ADDR=$(grep -oE 'anchor wallet address: (addr[a-z0-9_]+)' /tmp/appchain-tutorial/node-a.log | head -1 | sed 's/.*: //')
curl -s -X POST http://localhost:7070/api/v1/devnet/fund \
  -H 'Content-Type: application/json' -d "{\"address\":\"$ANCHOR_ADDR\",\"ada\":100}"
```

### 1.5 Check status

```bash
curl -s http://localhost:7070/api/v1/app-chain/status
```

Expected (abridged):

```json
{"chainId":"tutorial-chain","running":true,"sequencing":true,"role":"proposer",
 "tipHeight":0,"stateMachine":"ordered-log","peers":{"localhost:13338":true}}
```

Node B (`:7071`) shows `"role":"member"` and `"peers":{"localhost:13337":true}`.

### 1.6 Submit messages, watch them finalize everywhere

```bash
# Submit on A
curl -s -X POST http://localhost:7070/api/v1/app-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"order #1 created"}'
# → {"messageId":"6d1be6...","chainId":"tutorial-chain","topic":"orders"}

# Submit on B — any member accepts submissions
curl -s -X POST http://localhost:7071/api/v1/app-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"order #1 approved"}'

sleep 3

# Same tip and IDENTICAL state root on both nodes:
curl -s http://localhost:7070/api/v1/app-chain/tip
curl -s http://localhost:7071/api/v1/app-chain/tip
# → {"chainId":"tutorial-chain","height":2,"stateRoot":"95edf7..."}   (both!)

# Inspect a finalized block (note certSignatures: 2 — both members signed)
curl -s http://localhost:7071/api/v1/app-chain/blocks/1
```

### 1.7 Get a proof, verify the anchor

```bash
MSG_ID=<messageId from step 1.6>

# MPF inclusion proof: this message was finalized at this position,
# verifiable against the state root — the same root that gets anchored to L1
curl -s http://localhost:7071/api/v1/app-chain/proof/$MSG_ID
# → {"key":"...","stateRoot":"95edf7...","proofWireHex":"82d879...","finalizedAtHeight":1}

# The anchor: node A submitted a Cardano tx with the state root as metadata
grep "Anchor CONFIRMED on L1" /tmp/appchain-tutorial/node-a.log
curl -s http://localhost:7070/api/v1/app-chain/status | python3 -m json.tool | grep -A6 anchor
```

That is the complete out-of-the-box product: a multi-party, tamper-evident,
L1-anchored ordered log — Kafka-like submission ergonomics, but no single
party controls the broker and every record is provable against Cardano.

### 1.8 Cleanup

```bash
lsof -ti:7070,7071,13337,13338 | xargs kill -9 2>/dev/null
rm -rf /tmp/appchain-tutorial
```

---

## Part 2 — Custom app chain as a plugin jar

The framework never interprets message bodies — a **state machine** does.
Here you build one: a replicated **key-value store** where message bodies are
commands (`set:<key>=<value>` / `del:<key>`), packaged as a plugin jar for the
default distribution. No Yano rebuild.

### 2.1 Plugin project

```
kv-appchain-plugin/
├── build.gradle
├── settings.gradle
└── src/main/
    ├── java/com/example/kvchain/
    │   ├── KvStateMachine.java
    │   └── KvStateMachineProvider.java
    └── resources/META-INF/services/
        └── com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider
```

`settings.gradle`:

```gradle
rootProject.name = 'kv-appchain-plugin'
```

`build.gradle` — the node provides these APIs at runtime, so they are
`compileOnly` (run `./gradlew publishToMavenLocal` in the yano repo first if
you are working against a local build):

```gradle
plugins { id 'java' }

repositories { mavenLocal(); mavenCentral() }

java { sourceCompatibility = 21; targetCompatibility = 21 }

dependencies {
    compileOnly 'com.bloxbean.cardano:yano-core-api:0.1.0-pre8'   // AppStateMachine SPI
    // yaci-core (AppMessage) comes in transitively via yano-core-api
}
```

### 2.2 The state machine

`src/main/java/com/example/kvchain/KvStateMachine.java`:

```java
package com.example.kvchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.nio.charset.StandardCharsets;

/**
 * A replicated KV store. Message bodies are commands:
 *   set:<key>=<value>
 *   del:<key>
 * Every key/value written here becomes part of the MPF state root, so any
 * key's presence AND value are provable against the (anchorable) root.
 */
public class KvStateMachine implements AppStateMachine {

    @Override
    public String id() {
        return "kv-store";
    }

    /** Mempool admission: cheap, side-effect free. Malformed commands never enter blocks. */
    @Override
    public AdmissionResult validate(AppMessage message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        if (body.startsWith("set:") && body.indexOf('=') > 4) return AdmissionResult.accept();
        if (body.startsWith("del:") && body.length() > 4) return AdmissionResult.accept();
        return AdmissionResult.reject("expected set:<key>=<value> or del:<key>");
    }

    /**
     * Deterministic transition — called exactly once per finalized block, in
     * height order, on EVERY member. No wall clock, randomness, or I/O here:
     * each member re-executes this and the resulting state root must match
     * the proposer's byte-for-byte, or the block is rejected.
     */
    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (body.startsWith("set:")) {
                int eq = body.indexOf('=');
                writer.put(body.substring(4, eq).getBytes(StandardCharsets.UTF_8),
                           body.substring(eq + 1).getBytes(StandardCharsets.UTF_8));
            } else if (body.startsWith("del:")) {
                writer.delete(body.substring(4).getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
```

`src/main/java/com/example/kvchain/KvStateMachineProvider.java`:

```java
package com.example.kvchain;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

public class KvStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return "kv-store";          // referenced by yano.app-chain.state-machine
    }

    @Override
    public AppStateMachine create() {
        return new KvStateMachine();
    }
}
```

The ServiceLoader manifest —
`src/main/resources/META-INF/services/com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider`:

```
com.example.kvchain.KvStateMachineProvider
```

Build it:

```bash
cd kv-appchain-plugin && ./gradlew jar
# → build/libs/kv-appchain-plugin.jar
```

### 2.3 Deploy on the default distribution

Drop the jar into the node's plugins directory and select the machine by id —
on **every member** (all members must run the same state machine, or their
state roots diverge and blocks are rejected):

```bash
mkdir -p app/plugins
cp kv-appchain-plugin/build/libs/kv-appchain-plugin.jar app/plugins/
```

Config (only two things change relative to Part 1):

```
-Dyaci.plugins.directory=plugins            # default is already "plugins"
-Dyano.app-chain.state-machine=kv-store     # instead of ordered-log
-Dyano.app-chain.chain-id=kv-chain          # a new chain id = fresh ledger
```

Start both nodes exactly as in Part 1 with those flags. At startup you'll see:

```
App-chain state machine 'kv-store' loaded via provider com.example.kvchain.KvStateMachineProvider
```

A wrong id fails fast:
`Unknown app-chain state machine: kv-storee (available: [ordered-log, kv-store])`.

### 2.4 Use it

```bash
# Set a key (submit on A)...
curl -s -X POST http://localhost:7070/api/v1/app-chain/messages \
  -H 'Content-Type: application/json' -d '{"topic":"kv","body":"set:color=blue"}'
sleep 3

# ...and read the replicated, PROVABLE value from B: for the kv-store machine
# the state key is your own key ("color" = hex 636f6c6f72)
curl -s http://localhost:7071/api/v1/app-chain/proof/636f6c6f72
# → {"key":"636f6c6f72","stateRoot":"...","proofWireHex":"...","valueHex":"626c7565"}  ("blue")

# Malformed commands are rejected at admission and never enter a block
curl -s -X POST http://localhost:7070/api/v1/app-chain/messages \
  -H 'Content-Type: application/json' -d '{"topic":"kv","body":"nonsense"}'
# (accepted into diffusion, but the sequencer's admission drops it — check node A log)

# Delete
curl -s -X POST http://localhost:7070/api/v1/app-chain/messages \
  -H 'Content-Type: application/json' -d '{"topic":"kv","body":"del:color"}'
```

With anchoring enabled, `set:color=blue` is now a fact provable against a
Cardano transaction: fetch the anchor tx metadata (label 7014), take its
`state_root`, and verify the MPF proof for key `color` — no access to any
Yano node required.

### 2.5 Rules for custom state machines

1. **Determinism is the contract.** `apply()` runs on every member and the
   MPF root must match byte-for-byte. No `System.currentTimeMillis()`, no
   `Random`, no network/file I/O, no iteration over unordered collections
   when order affects writes. Everything you need is in the block
   (`block.timestamp()` is the proposer's clock and is part of consensus —
   use that, never your own).
2. **`validate()` is advisory pre-filtering**, not security: it runs at the
   sequencer's admission. Envelope authentication (signature + membership) is
   already enforced by the framework before your code sees a message.
3. **The body is yours.** Text, CBOR, JSON, protobuf — the framework treats
   it as opaque bytes end to end.
4. **State keys are yours too.** Whatever you `put()` becomes provable: pick
   keys external verifiers will want proofs for (business ids, not synthetic
   ones).
5. **Same jar + same id on every member.** Version your machine id
   (`"kv-store-v2"`) when you change apply() semantics, and start a new
   chain-id for incompatible changes — finalized history is immutable.

---

## Where to go next

- Full config & REST reference, ops, troubleshooting:
  `docs/APP_CHAIN_USER_GUIDE.md`
- Automated cluster regression: `.claude/skills/test-app-chain-cluster`
- Design, trust model, roadmap (rotating sequencer, script anchors,
  chain-governed membership): `adr/app-layer/005-yano-app-chain-framework.md`
- Wire-format CDDL (build a compatible node in another language):
  yaci `core/src/main/cddl/appmsg/`, yano `core-api/src/main/cddl/appchain/`

---

## Where to go next: enterprise extensions

Everything above is the core framework. The same distribution also ships an
enterprise extension set — see `docs/APP_CHAIN_USER_GUIDE.md` sections 8–17:

- **Skip writing a state machine** — before building your own (Part 2), check
  the standard library: `kv-registry` covers this tutorial's KV store out of
  the box, plus `approvals`, `balances` and `doc-trail` (guide §9). Select one
  with `yano.app-chain.state-machine` — no jar needed.
- **Spring Boot clients** — `yano-appchain-spring-boot-starter` gives your
  application an auto-configured `AppChainTemplate` and `@AppChainListener`
  over the Java SDK (guide §16) — no curl in production code.
- **Tests** — `yano-appchain-testkit`: `@AppChainCluster(nodes = 3)` spins an
  embedded multi-node chain inside a JUnit 5 test (guide §16).
- **Scaffolds** — `scaffolds/docker-compose-cluster` (ready 3-node cluster)
  and `scaffolds/plugin-template` (Part 2 of this tutorial as a ready-made
  Gradle plugin project).
