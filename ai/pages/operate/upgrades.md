# Versions & upgrades

Keep source, artifacts, storage, and plugins compatible.

Canonical URL: https://getyano.dev/operate/upgrades/

Yano is pre-release. Pin library, node, and plugin versions together. Check the [documentation build manifest](/ai/manifest.json) for the source revision and version used to generate these docs.

The current Java namespace is `org.yanoproject.*`, and Maven artifacts use group `org.yanoproject`. Do not rename dependency packages such as `com.bloxbean.cardano.client.*` or `com.bloxbean.cardano.yaci.*`: those belong to separate projects.

## Preview release changes

Start each preview node release with a fresh sync directory. Storage compatibility
between preview releases is not guaranteed, and Yano does not provide a general
chainstate migration path yet. Keep an old database only when you need it for
comparison or rollback to its matching executable.

See [release change details](/reference/upgrading/) for configuration and behavior
changes that may affect a new deployment.
