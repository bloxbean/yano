# Yano app-chain scaffolds

Copy-and-run starting points that turn the tutorial into a few commands
(ADR app-layer/006 E1.5).

| Scaffold | What it gives you |
|----------|-------------------|
| [`docker-compose-cluster/`](docker-compose-cluster/) | A 3-node app-chain cluster (1 sequencer + 2 followers) over docker-compose — build the node image, `docker compose up`, submit and read replicated messages. |
| [`plugin-template/`](plugin-template/) | A standalone Gradle project for a custom `AppStateMachine` plugin jar — build, drop on `yaci.plugins.directory`, select with `yano.app-chain.state-machine`. |

Both are self-contained and independent of the main Gradle build (the plugin
template resolves published Yano artifacts from `mavenLocal`/Maven Central).

See also: `docs/APP_CHAIN_USER_GUIDE.md`, `docs/APP_CHAIN_TUTORIAL.md`,
`docs/APP_CHAIN_USE_CASES.md`.
