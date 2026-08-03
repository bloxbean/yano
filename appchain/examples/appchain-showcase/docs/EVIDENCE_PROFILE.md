# Evidence Profile

This profile delegates to the maintained `appchain-effects-demo` harness and
ships its release-built runner and connector bundles. It does not copy or
reimplement the evidence state machine, Kafka, S3-compatible object storage,
or IPFS connectors.

```bash
./showcase.sh doctor --profile evidence
./showcase.sh quickstart --profile evidence --variant composite --instance evidence
./showcase.sh quickstart --profile evidence --variant role --instance role-evidence
```

The evidence product is the reusable contracts/profile/registry assembly. The
effects-demo and this facade are deployment/demo harnesses around that product.
Docker Compose is the default and Docker is required. Use the role variant to
demonstrate governed organizations, actors, policies, distinct role decisions,
key rotation, and revocation. The composite quickstart runs the ordinary
evidence scenario; the role quickstart invokes the maintained
`role-lifecycle` scenario. `verify` is read-only for composite and runs the
maintained readiness/proof probe for the role variant. All JARs, connector
bundles, network files, and the role-profile digest calculator come from the
ZIP; neither path reaches back into a source checkout or runs Gradle.

The maintained evidence topology is exactly three nodes with a 2-of-3 member
threshold. The facade rejects `--nodes` or `--threshold` values that would
otherwise be ignored. Preprod anchor key and confirmation options are passed
through to the maintained harness; key material is referenced from an
owner-only file and is never copied into the showcase YAML.
