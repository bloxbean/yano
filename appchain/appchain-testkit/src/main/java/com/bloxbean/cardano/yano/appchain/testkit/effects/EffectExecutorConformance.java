package com.bloxbean.cardano.yano.appchain.testkit.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Reusable black-box contract for ADR-010 connector executors. Provider test
 * suites expose it from one {@code @TestFactory}; no provider SDK enters this
 * module.
 */
public final class EffectExecutorConformance {
    private EffectExecutorConformance() {
    }

    /**
     * Builds the reusable dynamic test tree for one connector implementation.
     *
     * @param spec the connector-specific fixture and verification adapter
     * @return dynamic tests suitable for returning from a JUnit {@code @TestFactory}
     */
    public static Stream<DynamicNode> tests(ExecutorConformanceSpec spec) {
        validateSpec(spec);
        List<DynamicNode> groups = new ArrayList<>();
        groups.add(DynamicTest.dynamicTest("identity, routing and successful receipt",
                () -> success(spec)));
        groups.add(DynamicContainer.dynamicContainer("invalid payloads",
                spec.invalidPayloads().stream().map(payload -> DynamicTest.dynamicTest(
                        payload.name(), () -> invalid(spec, payload)))));
        groups.add(DynamicTest.dynamicTest("fresh products and idempotent close",
                () -> lifecycle(spec)));
        groups.add(DynamicTest.dynamicTest("failed construction closes partial resources",
                () -> failedConstruction(spec)));
        if (spec.supports(ExecutorScenario.TRANSIENT_THEN_SUCCESS)) {
            groups.add(DynamicTest.dynamicTest("transient retry preserves idempotency identity",
                    () -> transientThenSuccess(spec)));
        }
        if (spec.supports(ExecutorScenario.UNKNOWN_ACK_THEN_RECONCILE)) {
            groups.add(DynamicTest.dynamicTest("unknown acknowledgement follows declared model",
                    () -> unknownAcknowledgement(spec)));
        }
        if (spec.supports(ExecutorScenario.EXISTING_MATCH)) {
            groups.add(DynamicTest.dynamicTest("existing matching state confirms without mutation",
                    () -> existingMatch(spec)));
        }
        if (spec.supports(ExecutorScenario.EXISTING_CONFLICT)) {
            groups.add(DynamicTest.dynamicTest("existing conflict is definitive and non-mutating",
                    () -> existingConflict(spec)));
        }
        if (spec.supports(ExecutorScenario.BLOCKED_CALL)) {
            groups.add(DynamicTest.dynamicTest("blocked call and close remain bounded",
                    () -> blockedClose(spec)));
        }
        return groups.stream();
    }

    private static void success(ExecutorConformanceSpec spec) throws Exception {
        ExecutorFixture fixture = spec.open(ExecutorScenario.SUCCESS, spec.validPayload());
        useFixture(fixture, spec, spec.closeTimeout(), "success fixture", current -> {
            Assertions.assertNotNull(current.executor());
            Assertions.assertTrue(current.executor().id().matches("[a-z][a-z0-9.-]{0,63}"));
            Assertions.assertTrue(current.executor().supports(spec.actionType()));
            for (String other : routingNearMisses(spec.actionType())) {
                if (!other.equals(spec.actionType())) {
                    Assertions.assertFalse(current.executor().supports(other),
                            () -> "executor over-claimed effect type: " + other);
                }
            }
            Assertions.assertFalse(current.executor().supports(null),
                    "exact-match routing must safely reject null");
            EffectExecution outcome = current.executor().execute(current.context(1, new byte[0]),
                    current.effect());
            EffectExecution.Confirmed confirmed = Assertions.assertInstanceOf(
                    EffectExecution.Confirmed.class, outcome);
            assertConfirmedBounds(confirmed);
            spec.verifyReceipt(confirmed.externalRef());
            Assertions.assertTrue(current.probe().calls() >= 1,
                    "success scenario did not contact the provider");
            Assertions.assertEquals(1, current.probe().logicalMutations(),
                    "success scenario must represent one logical external mutation");
            assertStableKeys(current);
            assertSafe(spec, current, outcome);
        });
    }

    private static void invalid(ExecutorConformanceSpec spec, PayloadCase payload) throws Exception {
        ExecutorFixture fixture = spec.open(ExecutorScenario.SUCCESS, payload.payload());
        useFixture(fixture, spec, spec.closeTimeout(), "invalid-payload fixture", current -> {
            EffectExecution outcome = current.executor().execute(current.context(1, new byte[0]),
                    current.effect());
            EffectExecution.Failed failed = Assertions.assertInstanceOf(
                    EffectExecution.Failed.class, outcome);
            Assertions.assertFalse(failed.retryable(), "invalid payload must be definitive");
            Assertions.assertEquals(payload.expectedCode().wireCode(), failed.reason());
            Assertions.assertEquals(0, current.probe().calls(),
                    "invalid payload must not contact the provider");
            assertStableKeys(current);
            assertSafe(spec, current, outcome);
        });
    }

    private static void lifecycle(ExecutorConformanceSpec spec) throws Exception {
        ExecutorFixture first = spec.open(ExecutorScenario.SUCCESS, spec.validPayload());
        ExecutorFixture second = null;
        ExecutorService closeWorker = null;
        Throwable primaryFailure = null;
        try {
            second = spec.open(ExecutorScenario.SUCCESS, spec.validPayload());
            Assertions.assertNotSame(first.executor(), second.executor());
            Assertions.assertEquals(first.executor().id(), second.executor().id());
            closeWorker = daemonWorker("effect-conformance-lifecycle-close");
            Future<Void> closing = closeWorker.submit(() -> {
                try {
                    first.close();
                    first.close();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
                return null;
            });
            await(closing, spec.closeTimeout(), "executor close did not complete within the bound");
            Assertions.assertEquals(1, first.probe().closeCalls(),
                    "owned provider client must close exactly once");
            assertCapturedLogs(spec, first, "first lifecycle fixture after close");

            EffectExecution outcome = second.executor().execute(
                    second.context(1, new byte[0]), second.effect());
            EffectExecution.Confirmed confirmed = Assertions.assertInstanceOf(
                    EffectExecution.Confirmed.class, outcome,
                    "the second fresh executor product was not usable");
            assertConfirmedBounds(confirmed);
            spec.verifyReceipt(confirmed.externalRef());
            Assertions.assertTrue(second.probe().calls() >= 1,
                    "the second fresh executor product did not contact the provider");
            Assertions.assertEquals(1, second.probe().logicalMutations(),
                    "the second fresh executor product did not perform its one mutation");
            assertStableKeys(second);
            assertSafe(spec, second, outcome);
            closeBounded(second, spec.closeTimeout(), "second lifecycle fixture");
            closeBounded(second, spec.closeTimeout(), "second lifecycle fixture repeat");
            Assertions.assertEquals(1, second.probe().closeCalls(),
                    "the second fresh provider client was not closed exactly once");
            assertCapturedLogs(spec, second, "second lifecycle fixture after close");
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw propagate(failure);
        } finally {
            Throwable cleanupFailure = cleanupFixtureFailure(
                    spec, first, spec.closeTimeout(), "first lifecycle fixture");
            if (second != null) {
                cleanupFailure = combineFailures(cleanupFailure, cleanupFixtureFailure(
                        spec, second, spec.closeTimeout(), "second lifecycle fixture"));
            }
            stopWorker(closeWorker);
            reportCleanupFailure(primaryFailure, cleanupFailure);
        }
    }

    private static void failedConstruction(ExecutorConformanceSpec spec) throws Exception {
        FailedConstructionObservation observation = spec.failedConstruction();
        Assertions.assertTrue(observation.resourcesCreated() > 0,
                "failed-construction scenario must acquire at least one owned resource");
        Assertions.assertEquals(observation.resourcesCreated(), observation.resourcesClosed(),
                "partially constructed provider resources leaked");
        EffectRuntimeSnapshotAssertions.assertNoSentinels(
                observation.failure(), spec.forbiddenSentinels());
        EffectRuntimeSnapshotAssertions.assertNoSentinels(
                observation.diagnostics(), spec.forbiddenSentinels());
        CapturedLogObservation capturedLogs = observation.capturedLogs();
        Assertions.assertTrue(capturedLogs.captureActive(),
                "failed-construction log capture was not active through resource cleanup");
        EffectRuntimeSnapshotAssertions.assertNoSentinels(
                capturedLogs.entries(), spec.forbiddenSentinels());
    }

    private static void transientThenSuccess(ExecutorConformanceSpec spec) throws Exception {
        ExecutorFixture fixture = spec.open(ExecutorScenario.TRANSIENT_THEN_SUCCESS,
                spec.validPayload());
        useFixture(fixture, spec, spec.closeTimeout(), "transient-retry fixture", current -> {
            EffectExecution first = current.executor().execute(current.context(1, new byte[0]),
                    current.effect());
            EffectExecution.Failed failed = Assertions.assertInstanceOf(EffectExecution.Failed.class, first);
            Assertions.assertTrue(failed.retryable());
            assertNormalizedFailure(failed);
            Assertions.assertEquals(0, current.probe().logicalMutations(),
                    "a transient failure must not report an external mutation");
            int callsBeforeRetry = current.probe().calls();
            Assertions.assertTrue(callsBeforeRetry >= 1,
                    "transient scenario did not contact the provider");
            assertStableKeys(current);
            EffectExecution second = current.executor().execute(current.context(2, new byte[0]),
                    current.effect());
            EffectExecution.Confirmed confirmed = Assertions.assertInstanceOf(
                    EffectExecution.Confirmed.class, second);
            assertConfirmedBounds(confirmed);
            spec.verifyReceipt(confirmed.externalRef());
            Assertions.assertEquals(1, current.probe().logicalMutations(),
                    "transient-then-success must perform exactly one logical mutation");
            Assertions.assertTrue(current.probe().calls() > callsBeforeRetry,
                    "retry did not make a new provider interaction");
            assertStableKeys(current);
            assertSafe(spec, current, List.of(first, second));
        });
    }

    private static void unknownAcknowledgement(ExecutorConformanceSpec spec) throws Exception {
        ExecutorFixture firstFixture = spec.open(ExecutorScenario.UNKNOWN_ACK_THEN_RECONCILE,
                spec.validPayload());
        ExecutorFixture restarted = null;
        Throwable primaryFailure = null;
        try {
            EffectExecution first = firstFixture.executor().execute(
                    firstFixture.context(1, new byte[0]), firstFixture.effect());
            byte[] submittedRef = first instanceof EffectExecution.Submitted submitted
                    ? submitted.externalRef() : new byte[0];
            if (first instanceof EffectExecution.Failed failed) {
                Assertions.assertTrue(failed.retryable());
                Assertions.assertEquals(ConnectorErrorCode.ACK_UNKNOWN.wireCode(), failed.reason());
            } else {
                Assertions.assertInstanceOf(EffectExecution.Submitted.class, first);
                Assertions.assertTrue(submittedRef.length > 0 && submittedRef.length <= 128,
                        "Submitted must persist a non-empty bounded polling handle");
                spec.verifySubmittedRef(submittedRef);
            }
            assertStableKeys(firstFixture);
            assertSafe(spec, firstFixture, first);
            int mutationsBeforeRestart = firstFixture.probe().logicalMutations();
            int callsBeforeRestart = firstFixture.probe().calls();
            int keysBeforeRestart = firstFixture.probe().observedIdempotencyKeys().size();
            Assertions.assertTrue(callsBeforeRestart >= 1,
                    "unknown-ack scenario did not contact the provider before restart");
            closeBounded(firstFixture, spec.closeTimeout(), "pre-restart fixture");
            Assertions.assertEquals(1, firstFixture.probe().closeCalls(),
                    "the pre-restart provider client was not closed exactly once");
            assertCapturedLogs(spec, firstFixture, "pre-restart fixture after close");
            restarted = spec.restart(firstFixture, ExecutorScenario.UNKNOWN_ACK_THEN_RECONCILE,
                    spec.validPayload());
            Assertions.assertNotSame(firstFixture.executor(), restarted.executor());
            Assertions.assertSame(firstFixture.probe(), restarted.probe(),
                    "restart fixture must expose one cumulative external-state probe");
            Assertions.assertArrayEquals(firstFixture.effect().idHash(), restarted.effect().idHash(),
                    "restart must preserve the exact effect identity");
            Assertions.assertEquals(mutationsBeforeRestart, restarted.probe().logicalMutations(),
                    "restart reset cumulative mutation accounting");
            Assertions.assertEquals(callsBeforeRestart, restarted.probe().calls(),
                    "restart reset cumulative provider-call accounting");
            Assertions.assertEquals(keysBeforeRestart,
                    restarted.probe().observedIdempotencyKeys().size(),
                    "restart reset cumulative idempotency observations");
            EffectExecution second = restarted.executor().execute(restarted.context(2, submittedRef),
                    restarted.effect());
            EffectExecution.Confirmed confirmed = Assertions.assertInstanceOf(
                    EffectExecution.Confirmed.class, second);
            assertConfirmedBounds(confirmed);
            spec.verifyReceipt(confirmed.externalRef());
            Assertions.assertTrue(restarted.probe().calls() > callsBeforeRestart,
                    "restart reconciliation did not interact with the provider");
            assertStableKeys(restarted);
            if (spec.idempotencyModel() != IdempotencyModel.STABLE_DEDUPE_TOKEN) {
                Assertions.assertTrue(restarted.probe().logicalMutations() <= 1,
                        "probe-safe connector performed more than one logical mutation");
            }
            Assertions.assertTrue(restarted.probe().logicalMutations() >= mutationsBeforeRestart,
                    "restart probe counters must be cumulative");
            Assertions.assertTrue(restarted.probe().logicalMutations() >= 1,
                    "unknown-ack reconciliation did not preserve a logical mutation");
            if (spec.idempotencyModel() == IdempotencyModel.PROBE_SINGLE_MUTATION
                    || spec.idempotencyModel() == IdempotencyModel.IDEMPOTENT_SET) {
                Assertions.assertEquals(1, restarted.probe().logicalMutations(),
                        "probe-safe reconciliation must preserve exactly one logical mutation");
            }
            assertSafe(spec, restarted, List.of(first, second));
            int closeCallsBeforeRestartedClose = restarted.probe().closeCalls();
            closeBounded(restarted, spec.closeTimeout(), "restarted fixture");
            closeBounded(restarted, spec.closeTimeout(), "restarted fixture repeat");
            Assertions.assertEquals(closeCallsBeforeRestartedClose + 1,
                    restarted.probe().closeCalls(),
                    "the restarted provider client was not closed exactly once");
            assertCapturedLogs(spec, restarted, "restarted fixture after close");
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw propagate(failure);
        } finally {
            Throwable cleanupFailure = cleanupFixtureFailure(
                    spec, firstFixture, spec.closeTimeout(), "pre-restart fixture cleanup");
            if (restarted != null) {
                cleanupFailure = combineFailures(cleanupFailure, cleanupFixtureFailure(
                        spec, restarted, spec.closeTimeout(), "restarted fixture cleanup"));
            }
            reportCleanupFailure(primaryFailure, cleanupFailure);
        }
    }

    private static void existingMatch(ExecutorConformanceSpec spec) throws Exception {
        ExecutorFixture fixture = spec.open(ExecutorScenario.EXISTING_MATCH,
                spec.validPayload());
        useFixture(fixture, spec, spec.closeTimeout(), "existing-match fixture", current -> {
            EffectExecution outcome = current.executor().execute(current.context(1, new byte[0]),
                    current.effect());
            EffectExecution.Confirmed confirmed = Assertions.assertInstanceOf(
                    EffectExecution.Confirmed.class, outcome);
            assertConfirmedBounds(confirmed);
            spec.verifyReceipt(confirmed.externalRef());
            Assertions.assertTrue(current.probe().calls() >= 1,
                    "existing-match scenario did not probe provider state");
            Assertions.assertEquals(0, current.probe().logicalMutations());
            assertStableKeys(current);
            assertSafe(spec, current, outcome);
        });
    }

    private static void existingConflict(ExecutorConformanceSpec spec) throws Exception {
        ExecutorFixture fixture = spec.open(ExecutorScenario.EXISTING_CONFLICT,
                spec.validPayload());
        useFixture(fixture, spec, spec.closeTimeout(), "existing-conflict fixture", current -> {
            EffectExecution outcome = current.executor().execute(current.context(1, new byte[0]),
                    current.effect());
            EffectExecution.Failed failed = Assertions.assertInstanceOf(EffectExecution.Failed.class, outcome);
            Assertions.assertFalse(failed.retryable());
            Assertions.assertEquals(ConnectorErrorCode.DESTINATION_CONFLICT.wireCode(), failed.reason());
            Assertions.assertTrue(current.probe().calls() >= 1,
                    "existing-conflict scenario did not probe provider state");
            Assertions.assertEquals(0, current.probe().logicalMutations());
            assertStableKeys(current);
            assertSafe(spec, current, outcome);
        });
    }

    private static void blockedClose(ExecutorConformanceSpec spec) throws Exception {
        ExecutorFixture fixture = spec.open(ExecutorScenario.BLOCKED_CALL, spec.validPayload());
        ExecutorService executionWorker = daemonWorker("effect-conformance-blocked-execute");
        ExecutorService closeWorker = daemonWorker("effect-conformance-blocked-close");
        Future<EffectExecution> execution = executionWorker.submit(() ->
                fixture.executor().execute(fixture.context(1, new byte[0]), fixture.effect()));
        Throwable primaryFailure = null;
        try {
            Assertions.assertTrue(fixture.awaitBlocked(spec.closeTimeout()),
                    "fixture never reached its deterministic blocked seam");
            Assertions.assertTrue(fixture.probe().calls() >= 1,
                    "blocked scenario did not enter a provider interaction");
            int mutationsBeforeClose = fixture.probe().logicalMutations();
            Assertions.assertEquals(0, mutationsBeforeClose,
                    "blocked seam must be reached before an external mutation");
            Future<Void> close = closeWorker.submit(() -> {
                try {
                    fixture.close();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
                return null;
            });
            await(close, spec.closeTimeout(), "close blocked behind an in-flight provider call");
            Assertions.assertEquals(1, fixture.probe().closeCalls(),
                    "owned provider client must close exactly once");
            EffectExecution outcome = await(execution, spec.closeTimeout(),
                    "close did not terminate the blocked provider call");
            EffectExecution.Failed stopped = Assertions.assertInstanceOf(
                    EffectExecution.Failed.class, outcome);
            Assertions.assertEquals(ConnectorErrorCode.SHUTDOWN.wireCode(), stopped.reason());
            Assertions.assertTrue(stopped.retryable());
            assertNormalizedFailure(stopped);
            Assertions.assertEquals(mutationsBeforeClose, fixture.probe().logicalMutations(),
                    "close allowed a new mutation after the blocked seam");
            assertStableKeys(fixture);
            assertSafe(spec, fixture, outcome);
            assertCapturedLogs(spec, fixture, "blocked-call fixture after close");
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw propagate(failure);
        } finally {
            // Cleanup escape hatch only. The assertion above must observe completion
            // before this signal, otherwise close semantics are not conformant.
            execution.cancel(true);
            Throwable cleanupFailure = cleanupFixtureFailure(
                    spec, fixture, spec.closeTimeout(), "blocked-call fixture");
            stopWorker(closeWorker);
            stopWorker(executionWorker);
            reportCleanupFailure(primaryFailure, cleanupFailure);
        }
    }

    private static void assertStableKeys(ExecutorFixture fixture) {
        List<byte[]> keys = fixture.probe().observedIdempotencyKeys();
        for (byte[] key : keys) {
            Assertions.assertArrayEquals(fixture.effect().idHash(), key,
                    "every provider call must use the ADR-010 effect id hash");
        }
        Assertions.assertEquals(fixture.probe().calls(), keys.size(),
                "every provider call must expose exactly one idempotency identity");
        Assertions.assertTrue(fixture.probe().calls() >= fixture.probe().logicalMutations(),
                "logical mutation count exceeds provider-call count");
    }

    private static void assertSafe(ExecutorConformanceSpec spec,
                                   ExecutorFixture fixture,
                                   Object outcome) {
        inspectOutcomes(outcome, spec, fixture);
        EffectRuntimeSnapshotAssertions.assertNoSentinels(
                fixture.probe().diagnostics(), spec.forbiddenSentinels());
        assertCapturedLogs(spec, fixture, "active fixture");
        EffectRuntimeSnapshotAssertions.assertSafe(fixture.runtimeStats(), fixture.runtimeStatus(),
                java.util.Set.of(spec.actionType()), spec.forbiddenSentinels());
    }

    private static void inspectOutcomes(Object value,
                                        ExecutorConformanceSpec spec,
                                        ExecutorFixture fixture) {
        if (value instanceof Iterable<?> values) {
            values.forEach(child -> inspectOutcomes(child, spec, fixture));
            return;
        }
        if (value instanceof EffectExecution.Confirmed confirmed) {
            assertConfirmedBounds(confirmed);
            EffectRuntimeSnapshotAssertions.assertNoSentinels(
                    confirmed.externalRef(), spec.forbiddenSentinels());
            if (confirmed.detailHash() != null) {
                byte[] detail = fixture.archivedDetail(confirmed.detailHash()).orElseThrow(() ->
                        new AssertionError("detailHash returned without retrievable durable bytes"));
                Assertions.assertArrayEquals(confirmed.detailHash(),
                        ConnectorDetailHash.compute(detail).bytes(),
                        "retrieved detail bytes do not match detailHash");
                ConnectorDetailDocumentV1 document = ConnectorDetailDocumentV1.decode(detail);
                Assertions.assertArrayEquals(fixture.effect().idHash(), document.effectIdHash(),
                        "detail document is not bound to this effect");
                Assertions.assertEquals(spec.actionType(), document.action().type(),
                        "detail document is bound to the wrong connector action");
                spec.verifyReceipt(confirmed.externalRef());
                spec.verifyReceiptDetailConsistency(
                        fixture.effect().payload(), confirmed.externalRef(), document);
                EffectRuntimeSnapshotAssertions.assertNoSentinels(detail, spec.forbiddenSentinels());
            }
            return;
        }
        if (value instanceof EffectExecution.Submitted submitted) {
            EffectRuntimeSnapshotAssertions.assertNoSentinels(
                    submitted.externalRef(), spec.forbiddenSentinels());
            return;
        }
        if (value instanceof EffectExecution.Failed failed) {
            assertNormalizedFailure(failed);
            EffectRuntimeSnapshotAssertions.assertNoSentinels(
                    failed.reason(), spec.forbiddenSentinels());
        }
    }

    private static void assertConfirmedBounds(EffectExecution.Confirmed confirmed) {
        Assertions.assertTrue(confirmed.externalRef().length > 0,
                "Confirmed must carry a non-empty canonical receipt");
        Assertions.assertTrue(confirmed.externalRef().length <= 128,
                "Confirmed receipt exceeds the consensus bound");
        Assertions.assertTrue(confirmed.detailHash() == null || confirmed.detailHash().length == 32,
                "Confirmed detail hash must be absent or exactly 32 bytes");
    }

    private static ConnectorErrorCode assertNormalizedFailure(EffectExecution.Failed failed) {
        ConnectorErrorCode code;
        try {
            code = ConnectorErrorCode.fromWireCode(failed.reason());
        } catch (IllegalArgumentException invalid) {
            throw new AssertionError("executor returned a non-normalized failure code", invalid);
        }
        Assertions.assertEquals(code.disposition().retryable(), failed.retryable(),
                "failure retryability differs from the frozen default classification");
        return code;
    }

    private static Set<String> routingNearMisses(String actionType) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add("");
        candidates.add(" " + actionType);
        candidates.add(actionType + " ");
        candidates.add(actionType.toUpperCase(java.util.Locale.ROOT));
        candidates.add(actionType.replace('.', '_'));
        candidates.add(actionType + ".unexpected");
        candidates.add("unexpected." + actionType);
        candidates.add(ConnectorTypes.KAFKA_PUBLISH);
        candidates.add(ConnectorTypes.OBJECT_PUT);
        candidates.add(ConnectorTypes.IPFS_PIN);
        return candidates;
    }

    private static ExecutorService daemonWorker(String name) {
        return Executors.newSingleThreadExecutor(task ->
                Thread.ofPlatform().daemon().name(name).unstarted(task));
    }

    private static <T> T await(Future<T> future, Duration timeout, String timeoutMessage)
            throws Exception {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw new AssertionError(timeoutMessage, timeoutFailure);
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("bounded worker failed", cause);
        }
    }

    private static void stopWorker(ExecutorService worker) {
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    private static void useFixture(ExecutorFixture fixture,
                                   ExecutorConformanceSpec spec,
                                   Duration timeout,
                                   String description,
                                   FixtureAction action) throws Exception {
        Throwable primaryFailure = null;
        try {
            action.run(fixture);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw propagate(failure);
        } finally {
            Throwable cleanupFailure = cleanupFixtureFailure(
                    spec, fixture, timeout, description);
            reportCleanupFailure(primaryFailure, cleanupFailure);
        }
    }

    private static Throwable cleanupFixtureFailure(ExecutorConformanceSpec spec,
                                                   ExecutorFixture fixture,
                                                   Duration timeout,
                                                   String description) {
        Throwable cleanupFailure = null;
        try {
            fixture.unblock();
        } catch (Throwable failure) {
            cleanupFailure = failure;
        }
        try {
            closeBounded(fixture, timeout, description);
        } catch (Throwable failure) {
            cleanupFailure = combineFailures(cleanupFailure, failure);
        }
        try {
            assertCapturedLogs(spec, fixture, description + " after close");
        } catch (Throwable failure) {
            cleanupFailure = combineFailures(cleanupFailure, failure);
        }
        try {
            closeLogCaptureBounded(fixture, timeout, description);
        } catch (Throwable failure) {
            cleanupFailure = combineFailures(cleanupFailure, failure);
        }
        return cleanupFailure;
    }

    private static void assertCapturedLogs(ExecutorConformanceSpec spec,
                                           ExecutorFixture fixture,
                                           String description) {
        CapturedLogObservation capturedLogs = fixture.capturedLogs();
        Assertions.assertNotNull(capturedLogs,
                description + " must expose captured logs");
        Assertions.assertTrue(capturedLogs.captureActive(),
                description + " must retain an active test log appender through cleanup");
        EffectRuntimeSnapshotAssertions.assertNoSentinels(
                capturedLogs.entries(), spec.forbiddenSentinels());
    }

    private static Throwable combineFailures(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (next != null && next != first) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void reportCleanupFailure(Throwable primaryFailure,
                                             Throwable cleanupFailure) throws Exception {
        if (cleanupFailure == null) {
            return;
        }
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(cleanupFailure);
        } else {
            throw propagate(cleanupFailure);
        }
    }

    private static RuntimeException propagate(Throwable failure) throws Exception {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        return new RuntimeException(failure);
    }

    private static void closeBounded(ExecutorFixture fixture,
                                     Duration timeout,
                                     String description) throws Exception {
        ExecutorService cleanupWorker = daemonWorker("effect-conformance-cleanup");
        try {
            Future<Void> cleanup = cleanupWorker.submit(() -> {
                fixture.close();
                return null;
            });
            await(cleanup, timeout, description + " close did not complete within the bound");
        } finally {
            stopWorker(cleanupWorker);
        }
    }

    private static void closeLogCaptureBounded(ExecutorFixture fixture,
                                               Duration timeout,
                                               String description) throws Exception {
        ExecutorService cleanupWorker = daemonWorker("effect-conformance-log-cleanup");
        try {
            Future<Void> cleanup = cleanupWorker.submit(() -> {
                fixture.closeLogCapture();
                return null;
            });
            await(cleanup, timeout,
                    description + " log capture close did not complete within the bound");
        } finally {
            stopWorker(cleanupWorker);
        }
    }

    private static void validateSpec(ExecutorConformanceSpec spec) {
        Assertions.assertNotNull(spec, "spec");
        Assertions.assertTrue(spec.actionType() != null && !spec.actionType().isBlank(),
                "actionType is required");
        Assertions.assertNotNull(spec.validPayload(), "validPayload");
        Assertions.assertFalse(spec.invalidPayloads() == null || spec.invalidPayloads().isEmpty(),
                "at least one invalid payload is required");
        Assertions.assertFalse(spec.forbiddenSentinels() == null
                        || spec.forbiddenSentinels().isEmpty(),
                "at least one secret/endpoint sentinel is required");
        EffectRuntimeSnapshotAssertions.assertNoSentinels("", spec.forbiddenSentinels());
        Assertions.assertTrue(spec.forbiddenSentinels().stream()
                        .anyMatch(sentinel -> sentinel != null && !sentinel.isEmpty()),
                "at least one non-empty secret/endpoint sentinel is required");
        Assertions.assertNotNull(spec.idempotencyModel(), "idempotencyModel");
        for (ExecutorScenario required : List.of(
                ExecutorScenario.SUCCESS,
                ExecutorScenario.TRANSIENT_THEN_SUCCESS,
                ExecutorScenario.UNKNOWN_ACK_THEN_RECONCILE,
                ExecutorScenario.BLOCKED_CALL)) {
            Assertions.assertTrue(spec.supports(required),
                    () -> "mandatory conformance scenario is missing: " + required);
        }
        if (spec.idempotencyModel() == IdempotencyModel.PROBE_SINGLE_MUTATION) {
            Assertions.assertTrue(spec.supports(ExecutorScenario.EXISTING_MATCH));
            Assertions.assertTrue(spec.supports(ExecutorScenario.EXISTING_CONFLICT));
        } else if (spec.idempotencyModel() == IdempotencyModel.IDEMPOTENT_SET) {
            Assertions.assertTrue(spec.supports(ExecutorScenario.EXISTING_MATCH));
        }
    }

    @FunctionalInterface
    private interface FixtureAction {
        void run(ExecutorFixture fixture) throws Exception;
    }
}
