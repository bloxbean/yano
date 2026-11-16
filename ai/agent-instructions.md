# Yano coding context

Read https://getyano.dev/llms.txt and the relevant Markdown pages before generating code.

- For node onboarding, download the latest release from https://github.com/bloxbean/yano/releases/latest (other releases: https://github.com/bloxbean/yano/releases) and run the extracted launcher. Source builds are an advanced contributor workflow. This documentation tracks current source and may describe features newer than the installed release; match APIs, configuration, and SDK versions to the installed artifact. The version in manifest.json is the source development version, not a downloadable release.
- Yano is a pre-release Cardano data node in Java, with devnet tooling and an embeddable runtime. Do not claim complete production consensus validation.
- Use Java 25. The Maven group and package root are org.yanoproject. Keep dependency namespaces such as com.bloxbean.cardano.client unchanged. The npm package is @bloxbean/yano-testkit; install it with the @preview dist-tag until a stable release is promoted to latest.
- Pin compatible node, library, and plugin versions. Check manifest.json and the installed artifact.
- Yano does not host application ledgers (app chains). They are a separate product, Yano X (https://github.com/bloxbean/yano-x), built on Yano.
- Distinguish accepted transactions from on-chain confirmation.
- Native builds cannot load plugin JARs dynamically. DuckLake history is JVM-only and fresh-sync only.
- Wallet indexes require complete historical coverage; unavailable is not empty.
- Use the actual node's /q/openapi?format=json for client schemas. routes.json is only an annotation inventory.
- Never place external I/O in deterministic state application.
