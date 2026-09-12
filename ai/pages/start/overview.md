# What is Yano?

Understand Yano, choose a starting point, and build your first Cardano workflow.

Canonical URL: https://getyano.dev/start/overview/

Yano is a **Cardano data node written in Java**. It follows the blockchain, keeps local ledger state, and gives your application APIs to read that state and submit transactions. You can run it as an application or embed it inside your own Java process.

The same project also gives you a local, block-producing development network, integration testkits, and a host for application-specific chains.

## Choose your starting point

| I want to… | Start with |
| --- | --- |
| Try Yano without syncing a public network | [Your first local node](/start/quickstart/) |
| Read Cardano data and submit transactions | [Run a data node](/node/networks/) |
| Test a Java application against real ledger state | [Java testkit](/develop/java-testkit/) |
| Test from JavaScript or TypeScript | [JavaScript testkit](/develop/javascript-testkit/) |
| Give several participants a shared, verifiable event history | [Your first app chain](/app-chains/quickstart/) |
| Add application-specific rules | [State machines and plugins](/app-chains/extensions/) |
| Give an AI assistant accurate Yano context | [Build with AI](/ai/overview/) |

See [Yano in use](/develop/devkit/) for Yaci DevKit and UVerify Sandbox integrations.

## Use your favorite language and SDK

Yano runs in Java, but your dApp does not have to. Use its [Blockfrost-compatible HTTP API](/develop/blockfrost/) from CCL, MeshJS, Evolution SDK, or another language’s HTTP client to query data, evaluate scripts, and submit signed transactions.

## Three capabilities, one foundation

**Data node.** Synchronize Cardano blocks, maintain UTxOs and ledger state, query REST endpoints, evaluate scripts, and submit transactions. Choose upstream peers and optional indexes for your workload.

**Development network.** Produce local blocks, fund test addresses, save snapshots, trigger rollbacks, and control time. Java and JavaScript testkits manage the lifecycle for repeatable tests.

**App-chain host.** Run independent application ledgers with signed messages, deterministic execution, threshold finality, state proofs, and optional Cardano anchoring. The built-in `ordered-log` records opaque events. More state machines and integrations live in [Yano X](https://github.com/bloxbean/yano-x).

## Current status

Yano is **pre-release**. APIs and storage formats can change. Use it for development, testing, experimentation, and downstream prototyping; production validation is not yet the project's release claim. Public-network data synchronization does not imply complete Cardano consensus validation.

The Java package root and Maven group are `org.yanoproject`. The JavaScript testkit retains its `@bloxbean/yano-testkit` npm name. Yaci, Cardano Client Lib, JuLC, and Zeroj keep their own dependency namespaces.

## A few words you will see

- **UTxO:** an unspent transaction output; the spendable state of a Cardano address.
- **Slot / epoch:** Cardano's units of chain time; an epoch contains many slots.
- **Devnet:** an isolated network with test funds and its own genesis.
- **State root:** a compact commitment to the state of an app chain.
- **Finality certificate:** evidence that the configured member threshold certified an app block.
- **Anchor:** a Cardano transaction that commits an app-chain root to L1.
