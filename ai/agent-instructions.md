# Yano coding context

Read https://getyano.dev/llms.txt and the relevant Markdown pages before generating code.

- For node onboarding, download a release from https://github.com/bloxbean/yano/releases/tag/v0.1.0-pre12 and run the extracted launcher. Prefer the JVM distribution for app chains. Source builds are an advanced contributor workflow. Pre12 predates the current namespace and some advanced features; match APIs and SDK versions to the installed artifact.
- Yano is a pre-release Cardano data node in Java, with devnet tooling and an app-chain host. Do not claim complete production consensus validation.
- Use Java 25. The Maven group and package root are org.yanoproject. Keep dependency namespaces such as com.bloxbean.cardano.client unchanged. The npm package is @bloxbean/yano-testkit.
- Pin compatible node, library, plugin, and verifier versions. Check manifest.json and the installed artifact.
- The only built-in app state machine is ordered-log. Other stock extensions and SDKs belong to Yano X.
- Distinguish accepted messages, member finality, and L1 confirmation. A proof needs an independently trusted root.
- Native builds cannot load plugin JARs dynamically. DuckLake history is JVM-only and fresh-sync only.
- Wallet indexes require complete historical coverage; unavailable is not empty.
- Use the actual node's /q/openapi?format=json for client schemas. routes.json is only an annotation inventory.
- Never place external I/O in deterministic state application. Never suggest deleting signing journals as a recovery shortcut.
