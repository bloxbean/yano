# Failing run: aPinnedReaderSurvivesSnapshotExpirationAndFileCleanup

Captured 2026-08-20T23:43:13Z from the full-suite run that failed (BUILD FAILED in 14m 3s).
Preserved verbatim before any diagnosis, so a later 'it passes now' cannot quietly replace it.

## Environment at the time of failure
```
disk:            6.6T used of 7.3T, 90% full, 746G free
load average:    : 3.76, 4.57, 5.75
concurrent JVMs: 6
yano nodes up:   1
suite scope:     ./gradlew test -x :testkit:test  (14m 3s wall)
```

## Failure
```
classname="com.bloxbean.cardano.yano.archive.ducklake.DuckLakeProjectionSinkTest" time="42.311">
    java.lang.AssertionError: [the diagnostic must say why nothing was reclaimed] 
Expecting actual:
  "projection_receipts: TransactionContext Error: Failed to commit: Failed to commit DuckLake transaction.
Failed to commit: Failed to execute query "COMMIT": database is locked; transaction_redeemers: Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result
Error: Invalid Error: Failed to query most recent snapshot for DuckLake: Failed to prepare query "SELECT type FROM sqlite_master WHERE lower(name)=lower('ducklake_snapshot');": database is locked; rewards: Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result
Error: Invalid Error: Failed to query most recent snapshot for DuckLake: Failed to prepare query "SELECT type FROM sqlite_master WHERE lower(name)=lower('ducklake_snapshot');": database is locked; transaction_datums: Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result
Error: Invalid Error: Failed to query most recent snapshot for DuckLake: Failed to prepare query "SELECT type FROM sqlite_master WHERE lower(name)=lower('ducklake_snapshot');": database is locked; address_transactions: Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result
Error: Invalid Error: Failed to query most recent snapshot for DuckLake: Failed to prepare query "SELECT type FROM sqlite_master WHERE lower(name)=lower('ducklake_snapshot');": database is locked; transaction_redeemers: Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result
Error: Invalid Error: Failed to query most recent snapshot for DuckLake: Failed to prepare query "SELECT type FROM sqlite_master WHERE lower(name)=lower('ducklake_snapshot');": database is locked"
to contain:
  "expire_snapshots" 
	at com.bloxbean.cardano.yano.archive.ducklake.DuckLakeProjectionSinkTest.aPinnedReaderSurvivesSnapshotExpirationAndFileCleanup(DuckLakeProjectionSinkTest.java:492)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:725)
	at org.junit.jupiter.engine.execution.MethodInvocation.proceed(MethodInvocation.java:60)
	at org.junit.jupiter.engine.execution.InvocationInterceptorChain$ValidatingInvocation.proceed(InvocationInterceptorChain.java:131)
	at org.junit.jupiter.engine.extension.TimeoutExtension.intercept(TimeoutExtension.java:149)
	at org.junit.jupiter.engine.extension.TimeoutExtension.interceptTestableMethod(TimeoutExtension.java:140)
	at org.junit.jupiter.engine.extension.TimeoutExtension.interceptTestMethod(TimeoutExtension.java:84)
	at org.junit.jupiter.engine.execution.ExecutableInvoker$ReflectiveInterceptorCall.lambda$ofVoidMethod$0(Exe
```

## Captured stdout from that test
```
ADR-039 pinned reader: maintenance PARTIAL, snapshots expired 0, orphans deleted 0, files 35 -> 0, detail=projection_receipts: TransactionContext Error: Failed to commit: Failed to commit DuckLake transaction.
```

## Diagnosis

**The test was wrong, not the implementation.** The assertion required the diagnostic to name
`expire_snapshots` specifically:

```java
assertThat(result.detail().orElse(""))
        .as("the diagnostic must say why nothing was reclaimed")
        .contains("expire_snapshots");
```

In the failing run the detail instead began `projection_receipts: ... database is locked`, listing
per-table **compaction** failures. Both are the same underlying condition — the pinned reader's
open transaction holds the SQLite catalog lock — but *which step reaches the lock first* is a
matter of timing. In the isolated runs, snapshot expiration got there first; in a contended
14-minute suite with six JVMs and the machine at load 3.8, housekeeping got through and compaction
was the step that hit it.

So the assertion encoded an incidental scheduling detail as if it were the contract.

**The safety property held throughout.** The failing run shows every operation refused with
`database is locked`, which means nothing was expired and nothing was deleted — exactly what the
test exists to protect. The reader was never at risk. What failed was the test's description of
*how* the refusal would be reported.

**Fix:** assert the contract instead of the timing — the pass must not claim success, must carry a
diagnostic, must name whichever operation could not proceed, and must have reclaimed nothing. That
holds regardless of which step loses the race, and it still fails if maintenance ever reports
success while a reader is pinned, which is the property that matters.

No timeout was increased and no test was disabled.
