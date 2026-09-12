# State machines & extensions

Extend Yano through deterministic state machines and public plugin contracts.

Canonical URL: https://getyano.dev/app-chains/extensions/

Use `ordered-log` when applications interpret a shared event history. Use an `AppStateMachine` plugin when members must enforce application state transitions together.

## Deterministic application rules

Every voting member must compute the same state from the same inputs. Keep network requests, filesystem operations, randomness, and local wall-clock reads out of deterministic application logic. Admission validation gives callers early feedback; deterministic application remains the authority for finalized commands.

Expose your machine through `AppStateMachineProvider`, ServiceLoader metadata, and a Yano plugin manifest. Install the same compatible bundle on all voting members of a JVM cluster. Select its ID with the chain's `state-machine` setting.

Start from the [Yano X plugin template](https://github.com/bloxbean/yano-x/tree/main/scaffolds/plugin-template). Read [plugin operations](/operate/plugins/) and the [query/domain API contract](/reference/plugin-contract/) before deploying.

## Effects, observations, and queries

- **Effects** separate deterministic intent from external work and its reported outcome. Executors must support bounded execution, recovery, and the documented idempotency contract.
- **Observations** bring certified source reports into application state. They are preview functionality, disabled by default, and do not establish objective truth merely by reaching a reporter threshold.
- **Committed queries** read against a fixed committed root; domain APIs may provide convenient decoded projections with a different trust boundary.

[Yano X](https://github.com/bloxbean/yano-x) owns additional stock implementations and integrations. Yano retains the host and extension contracts.

## Native boundary

Dynamic plugin JAR loading is a JVM feature. Native distributions retain the core providers; copying a JVM plugin beside a native executable does not install it.

## Test your plugin

Use [the app-chain testkit](/develop/app-chain-testkit/) for embedded clusters, plus the conformance suites for the SPI you implement. Test actual failure/recovery behavior, not just a successful submit.
