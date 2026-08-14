# Yano app-chain core

Yano contains the generic L1 app-chain host framework and ships one reference
state machine, `ordered-log`, out of the box. The host, extension SPIs,
consensus/finality machinery, state commitments, proof/query surfaces, and L1
anchoring remain part of Yano so they can be built and qualified for GraalVM.

Start with:

- [App-chain consensus and state-machine internals](../APP_CHAIN_CONSENSUS_GUIDE.md)
- [Plugin query and domain API contract](../APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md)
- [Plugin operations](../PLUGIN_OPERATIONS.md)
- [`ordered-log` reference](state-machines/ordered-log.md)
- [Console UI status](../console-ui.md)

Additional state machines, capabilities, connectors, products, examples, and
JVM-only tooling live in [Yano X](https://github.com/bloxbean/yano-x). They use
the stable Java package namespace `com.bloxbean.cardano.yano.appchain.*` and are
loaded through Yano's plugin contracts.

The console UI remains in Yano temporarily; UI extraction is outside the
current repository split.
