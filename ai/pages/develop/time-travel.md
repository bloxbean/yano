# Snapshots, rollback & time

Make local Cardano history reproducible in development and tests.

Canonical URL: https://getyano.dev/develop/time-travel/

Yano's devnet controls let tests create history, rewind it, and explore epoch boundaries. They require a devnet-capable recipe and are not general public-network administration tools.

## Snapshots in a Java test

```java
import org.yanoproject.testkit.devnet.YanoDevnetTestConfig;
import org.yanoproject.testkit.devnet.YanoDevnetTestKit;

try (YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
        .temporaryRocksDbStorage().blockTimeMillis(200).build();
     YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(config)) {
    kit.start();
    var baseline = kit.snapshots().create("baseline");
    kit.time().advanceSlots(10);
    kit.snapshots().restore(baseline.name());
}
```

## Start in the past

From your extracted [JVM release](/start/installation/) directory:

```bash
java -Dquarkus.profile=devnet \
  -Dyano.block-producer.past-time-travel-mode=true \
  -jar yano.jar
```

Production is deferred until you shift the genesis:

```bash
curl -fsS -X POST http://localhost:7070/api/v1/devnet/epochs/shift \
  -H 'Content-Type: application/json' -d '{"epochs":4}'
curl -fsS -X POST http://localhost:7070/api/v1/devnet/epochs/catch-up
```

The shift starts the chain in the past; catch-up moves toward wall-clock and live production. Use fresh isolated storage for a new scenario. Timing derives from genesis; sparse backfill can be configured with `yano.block-producer.backfill-block-interval-slots`. Consult source configuration and compatibility tests before changing that interval.

## Trigger a rollback

```bash
curl -fsS -X POST http://localhost:7070/api/v1/devnet/rollback \
  -H 'Content-Type: application/json' -d '{"count":3}'
```

Specify exactly one of `count`, `slot`, or `blockNumber`. The operation uses the normal state rollback path and notifies downstream N2N clients. Use it to test whether an indexer or application reverses observations correctly.
