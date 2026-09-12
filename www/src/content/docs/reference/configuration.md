---
title: "Configuration guide"
description: "Understand configuration layers, defaults, profiles, and build-time settings."
sidebar:
  order: 1
---

Yano's runnable application uses Quarkus configuration. The bundled defaults are in `app/src/main/resources/application.yml`; operator files are in `app/config/`.

## Layering

Run from the extracted distribution directory or `app/`. Put persistent local overrides in `config/application.yml`; compose optional `application-<profile>.yml` files through the launcher:

```bash
./yano.sh start:preprod,relay,praos-lite
```

System properties use the full property name, such as `-Dquarkus.http.port=7071`. When invoking Java directly, JVM `-D` arguments go **before** `-jar`.

```bash
java -Dquarkus.profile=devnet -Dquarkus.http.port=7071 -jar build/yano.jar
```

Environment aliases explicitly supplied by packaged YAML are preserved in the [generated configuration catalog](/reference/configuration-catalog/). That catalog separates each file/profile; a profile value is not a universal default.

## Common settings

| Setting | Meaning |
| --- | --- |
| `quarkus.http.port` | HTTP API and console port; bundled value `7070` |
| `yano.network` | Network identity; bundled value `preprod` |
| `yano.remote.host` / `port` | Selected upstream connection |
| `yano.storage.path` | Local node database |
| `yano.server.port` | Node-to-node server port |
| `yano.app-chain.storage.path` | Separate app-chain database root |
| `yano.block-producer.block-time-millis` | `0` derives timing from genesis |
| `yano.plugins.directory` | JVM plugin directory |

## Find every documented setting

The [configuration catalog](/reference/configuration-catalog/) is generated from active YAML values in the current checkout. It also lists declared Yano property keys, including settings that are not assigned in packaged YAML. A declared key is not a promise that every possible value is supported.

Download [configuration JSON](/ai/configuration.json) for tooling. Comments and commented examples are deliberately excluded from the active-value tables; consult feature guides for semantics and constraints.

## Build-time REST prefix

`/api/v1` is the normal REST prefix. A custom prefix is selected when building using `-PyanoApiPrefix=/your-prefix`. It is fixed in the artifact and cannot be changed at launch by setting `yano.api-prefix`, `quarkus.resteasy.path`, or `quarkus.http.root-path`.

See [distribution details](/reference/distributions/) before building a custom-prefix artifact.
