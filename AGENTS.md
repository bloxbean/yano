# Implementation style

- Prefer imports and simple class names in implementation code. Avoid fully qualified class names such as `java.util.ArrayList` when `ArrayList` can be imported without ambiguity. Use a fully qualified name only when needed to resolve a name collision or another genuine ambiguity.

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
