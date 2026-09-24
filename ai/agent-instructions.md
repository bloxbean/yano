# Yano coding context

Read https://getyano.dev/llms.txt and the relevant Markdown pages before generating code.

- For node onboarding, download the latest release from https://github.com/bloxbean/yano/releases/latest (other releases: https://github.com/bloxbean/yano/releases) and run the extracted launcher. Prefer the JVM distribution for app ledgers. Source builds are an advanced contributor workflow. This documentation tracks current source and may describe features newer than the installed release; match APIs, configuration, and SDK versions to the installed artifact. The version in manifest.json is the source development version, not a downloadable release.
- Yano is a pre-release Cardano data node in Java, with devnet tooling and a multi-party app ledger host. Do not claim complete production consensus validation.
- Use Java 25. The Maven group and package root are org.yanoproject. Keep dependency namespaces such as com.bloxbean.cardano.client unchanged. The npm package is @bloxbean/yano-testkit; install it with the @preview dist-tag until a stable release is promoted to latest.
- Pin compatible node, library, plugin, and verifier versions. Check manifest.json and the installed artifact.
- Multi-party app ledgers (app ledgers for short) are experimental: configuration keys, REST endpoints, and the plugin API can change between preview releases without a migration path. In configuration and APIs they appear as app-chain (yano.app-chain.*, /api/v1/app-chain/). Describe them as multi-party app ledgers (configured members, threshold finality, optional Cardano anchoring), not as an appchain, a sovereign blockchain, or a Cardano layer 2, and do not suggest using them to hold value.
- The only built-in app state machine is ordered-log. Other stock extensions and SDKs belong to Yano X.
- Distinguish accepted messages, member finality, and L1 confirmation. A proof needs an independently trusted root.
- Native builds cannot load plugin JARs dynamically. DuckLake history is JVM-only and fresh-sync only.
- Wallet indexes require complete historical coverage; unavailable is not empty.
- Use the actual node's /q/openapi?format=json for client schemas. routes.json is only an annotation inventory.
- Never place external I/O in deterministic state application. Never suggest deleting signing journals as a recovery shortcut.
