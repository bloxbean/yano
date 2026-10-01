# Implementation style

- Prefer imports and simple class names in implementation code. Avoid fully qualified class names such as `java.util.ArrayList` when `ArrayList` can be imported without ambiguity. Use a fully qualified name only when needed to resolve a name collision or another genuine ambiguity.

# Simplicity and reuse (avoid tech debt)

- **Keep it simple (KISS).** Choose the simplest design that is correct. Do not add abstractions, extension points, modules, configuration keys or options for needs that do not exist yet.
- **Fix at the source.** When behaviour is wrong, fix the single place that produces it rather than patching each consumer. For example, keep original bytes where an object is built instead of repairing them field by field downstream.
- **Reuse, don't copy.** Before writing new logic, look for existing code that already does it and use or extend it. When two implementations need the same logic (for example two engines or evaluators behind one interface), extract it once into a small shared helper in the module that owns the contract, and keep each implementation to what is actually specific to it.
- **Refactor with a safety net.** When extracting shared code, keep behaviour identical and keep the existing tests passing unchanged.
- **Leave nothing behind.** No dead code, commented-out code, compatibility shims, scratch or triage files, or TODOs without an issue. Workarounds for third-party bugs stay small and isolated, reference the upstream issue, and carry a test that fails once the upstream fix lands.
- **Prefer existing harnesses and conventions.** Extend existing test fixtures, QA harnesses and configuration patterns instead of adding parallel ones.

# Package namespace

Yano publishes under the Maven group `org.yanoproject` and owns the matching
package root `org.yanoproject.*`. Sibling products in the Yano project nest
beneath the same root but are owned by their own repositories:

| Namespace                | Owner                |
|--------------------------|----------------------|
| `org.yanoproject.*`      | this repository      |
| `org.yanoproject.x.*`    | `bloxbean/yano-x`    |

Because Yano owns the root, a new top-level subpackage here can collide with a
sibling product. **Never create an `org.yanoproject.<product-name>` package.**
Product names are reserved for sibling repositories, so node-side code for a
sibling belongs under a domain name, not the product's name — node-side wallet
APIs live under a domain package, never `org.yanoproject.wallet`, which is
reserved for `bloxbean/yano-wallet`. Yano's own top-level subpackages are
domain names (`api`, `runtime`, `consensus`, `p2p`, `ledgerstate`, …); keep it
that way.

Do not introduce `org.yanoproject.yano.*` or a `com.bloxbean.cardano.yano.*`
package. The `com.bloxbean.cardano` group still owns Yano's *dependencies*
(yaci, cardano-client-lib, julc, zeroj), which are unrelated projects and are
not moving.
