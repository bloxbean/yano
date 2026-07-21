# Yano Plugin Catalog CLI

`yano-plugins` is the supported offline JVM tool for validating and inspecting
manifested Yano plugin artifacts. It scans JAR files or exploded artifact
directories as resources only: it does not load provider classes, run static
initializers, or call constructors.

The scanner first captures immutable evidence so the accepted metadata and
digest describe the same bytes. One JAR, or the aggregate regular files in one
exploded artifact, is limited to 1 GiB; larger inputs are rejected before and
during temporary capture.

Every schema-v1 manifest must declare both plugin API compatibility dimensions:

```json
"yanoApi": { "min": 1, "max": 1, "minLevel": 1 }
```

`min`/`max` are the inclusive supported API majors. `minLevel` is the minimum
global additive API level the plugin needs. Validation succeeds only when the
target host major is in range and its level is at least `minLevel`; a mismatch
is rejected without loading or constructing a provider. The global level is
incremented for every additive public plugin API symbol or contribution kind
and never resets on a major bump. The bundle `version` remains an independent
artifact/product SemVer.

Build the standalone distribution with JDK 25:

```bash
./gradlew :plugin-catalog:distZip -PskipSigning=true
```

The archive is written to:

```text
plugin-catalog/build/distributions/yano-plugins-<version>.zip
```

After extracting it, run:

```bash
./yano-plugins-<version>/bin/yano-plugins validate plugin.jar
./yano-plugins-<version>/bin/yano-plugins inspect plugin.jar
./yano-plugins-<version>/bin/yano-plugins inspect --format json plugin.jar
```

The normal Yano JVM zip embeds the same application distribution. From its
extracted root, use:

```bash
./tools/yano-plugins/bin/yano-plugins validate plugins/example.jar
./tools/yano-plugins/bin/yano-plugins inspect --format json plugins/example.jar
```

Policy options must precede artifact paths:

```text
--api-major <positive-int>
--api-level <positive-int>
--allow <bundle-id>
--deny <bundle-id>
--
```

The API options default to this Yano build's
`PluginApiVersion.CURRENT_MAJOR` and `CURRENT_LEVEL` (`1` and `1` for the
initial contract). Supply both when validating for a different target host.
Inspection output includes the target major, level and a fingerprint that binds
both host values plus every selected manifest's `minLevel`.

Use `--` before an artifact path that begins with `--`. Exit codes are stable:

| Code | Meaning |
|---:|---|
| `0` | Catalog is valid and the command completed. |
| `2` | Artifact metadata or the selected catalog is invalid. |
| `64` | Command syntax or policy is invalid. |
| `74` | Artifact evidence could not be read safely. |

This is intentionally a JVM-only offline tool. It is not embedded into the
Quarkus native executable or native zip, and it cannot make a native node load
a dropped JAR. Native operators can run `yano-plugins` on another JDK 25 host
before build/deployment, then use the native node's protected plugin operations
endpoint to inspect its build-time catalog and node-local health.
