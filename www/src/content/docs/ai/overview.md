---
title: "Build with AI"
description: "Give coding agents compact, accurate, versioned Yano context."
sidebar:
  order: 1
---

Give your coding assistant the same documentation you use. Yano publishes plain Markdown and structured inventories so an agent can find current APIs and configuration without scraping navigation.

## Choose an artifact

| Artifact | Use |
| --- | --- |
| [llms.txt](/llms.txt) | Compact index and key boundaries |
| [llms-full.txt](/llms-full.txt) | Full public documentation in one text file |
| [Agent instructions](/ai/agent-instructions.md) | Ready-to-use Yano context for a coding agent |
| [Documentation manifest](/ai/manifest.json) | Source revision, version, page URLs, checksums |
| [Configuration JSON](/ai/configuration.json) | Packaged active values by source/profile, plus declared keys |
| [Route inventory](/ai/routes.json) | REST annotation paths extracted from source |

Each documentation page has a Markdown copy under `/ai/pages/`, with the same path and a `.md` extension. For example: [the quickstart as Markdown](/ai/pages/start/quickstart.md).

## A useful starting prompt

```text
Use https://getyano.dev/llms.txt to discover the Yano documentation.
Read the Java testkit guide and the source/version manifest.
Help me write a JUnit test that starts an isolated devnet, funds a wallet,
and checks the balance. Use org.yanoproject imports and matching artifact
versions. State prerequisites and distinguish tested APIs from assumptions.
```

## For API client generation

Get `/q/openapi?format=json` from the actual node you will call. The static route inventory only records source paths; it does not encode full request schemas, permissions, or feature availability.

## Keep the trust boundaries

Yano is pre-release. Do not describe it as a fully validating production replacement. Yano's built-in app state machine is `ordered-log`; additional stock extensions belong to Yano X. Submission acceptance, member finality, and Cardano confirmation are different events. AI-generated code should preserve these distinctions.

These are static context artifacts. The documentation site does not offer a live node, remote signing, or an MCP server.
