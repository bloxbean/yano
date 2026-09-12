# Yano in use

Use Yano as part of a complete local Cardano development environment.

Canonical URL: https://getyano.dev/develop/devkit/

Yano is used in **[Yaci DevKit](https://github.com/bloxbean/yaci-devkit)** as a local Cardano devnet node. This is a practical use of Yano's block production and node-to-node protocol support: a broader development environment can run around the chain it produces.

## Yaci DevKit

### When to use DevKit

Choose Yaci DevKit when you want its integrated local Cardano environment and tooling. Follow the setup and configuration instructions for your chosen DevKit version; the services and node choices are owned by that project.

Choose [standalone Yano](/start/quickstart/) when you want to run and configure the node directly. Choose [Yano testkit](/develop/java-testkit/) when each test should own an isolated node lifecycle.

## Connecting a downstream consumer

A Yano devnet serves blocks on its configured node-to-node port (normally `13337`) with devnet network magic `42`. An indexer or compatible downstream node must use the same network identity and genesis. Container hostnames and exposed ports depend on the DevKit deployment; `localhost` inside one container does not refer to a different container.

Yano's faucet and time controls belong to an isolated development network. Use the DevKit workflow for the services it manages, rather than independently modifying a database that its node already owns.

## UVerify Sandbox

UVerify Sandbox uses Yano as its local Cardano devnet node, together with Yaci Store for indexing. It starts from a prepared chain snapshot with UVerify contracts already deployed, making it useful for template development and SDK integration tests. See the [official UVerify Sandbox guide](https://docs.uverify.io/sandbox) for setup and supported workflows.

Yano supplies local block production and snapshot/time controls; UVerify owns its application, sandbox orchestration, and funding workflow. The [UVerify engineering walkthrough](https://uverify.io/blog/sandbox-yano-yaci-store) explains how the services fit together.
