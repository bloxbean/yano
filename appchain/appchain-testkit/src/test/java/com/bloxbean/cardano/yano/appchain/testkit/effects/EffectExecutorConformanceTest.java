package com.bloxbean.cardano.yano.appchain.testkit.effects;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import com.bloxbean.cardano.yano.appchain.integration.detail.KafkaPublishDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectExecutorConformanceTest {

    @TestFactory
    Stream<DynamicNode> scriptedProviderPassesReusableSuite() {
        return EffectExecutorConformance.tests(new ScriptedSpec());
    }

    @Test
    void snapshotAssertionsDetectSecretCanaries() {
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertSafe(
                safeStats(Map.of("lastError", "token=super-secret")),
                Map.of("status", "DONE", "attempts", 1),
                Set.of(ConnectorTypes.KAFKA_PUBLISH), Set.of("super-secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void redactionTraversesNestedRecordsAndArraysWithoutEchoingSecret() {
        byte[] secret = "prefix-super-secret-suffix".getBytes(StandardCharsets.UTF_8);
        NestedSecret nested = new NestedSecret(new Object[]{new int[]{1, 2}, secret},
                Map.of("safe", List.of("still-safe")));

        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                nested, Set.of("super-secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void redactionTraversalIsCycleSafe() {
        Map<String, Object> cycle = new HashMap<>();
        cycle.put("self", cycle);
        EffectRuntimeSnapshotAssertions.assertNoSentinels(cycle, Set.of("super-secret"));
    }

    @Test
    void redactionTraversalAppliesOneGlobalSchedulingBound() {
        List<List<String>> broadGraph = IntStream.range(0, 6_000)
                .mapToObj(index -> List.of("safe-" + index))
                .toList();

        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                broadGraph, Set.of("super-secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("scheduling budget");
    }

    @Test
    void redactionRejectsHostileLeafAndCumulativeInspectionSizes() {
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                new byte[65_537], Set.of("secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("byte leaf");
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                new char[65_537], Set.of("secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("text leaf");
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                "x".repeat(65_537), Set.of("secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("text leaf");
        String boundedLeaf = "x".repeat(65_536);
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                java.util.Collections.nCopies(17, boundedLeaf), Set.of("secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cumulative redaction inspection budget");
    }

    @Test
    void redactionBoundsForbiddenSentinelCountAndSizeBeforeEncoding() {
        Set<String> tooMany = IntStream.range(0, 65)
                .mapToObj(index -> "secret-" + index)
                .collect(java.util.stream.Collectors.toSet());
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                "safe", tooMany))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("too many forbidden sentinels");
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                "safe", Set.of("x".repeat(257))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("sentinel exceeded the character bound");
        Set<String> excessiveTotal = IntStream.range(0, 17)
                .mapToObj(index -> ("x".repeat(252) + String.format("%04d", index)))
                .collect(java.util.stream.Collectors.toSet());
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertNoSentinels(
                "safe", excessiveTotal))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cumulative character bound");
    }

    @Test
    void conformanceRequiresANonEmptySentinel() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override public Set<String> forbiddenSentinels() {
                return Set.of("");
            }
        };

        assertThatThrownBy(() -> EffectExecutorConformance.tests(spec))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("non-empty secret/endpoint sentinel");
    }

    @Test
    void redactionScansCharSequenceWithoutCallingPotentiallyHostileToString() {
        AtomicInteger conversions = new AtomicInteger();
        CharSequence text = new CharSequence() {
            private final String value = "safe";
            @Override public int length() { return value.length(); }
            @Override public char charAt(int index) { return value.charAt(index); }
            @Override public CharSequence subSequence(int start, int end) {
                return value.subSequence(start, end);
            }
            @Override public String toString() {
                conversions.incrementAndGet();
                throw new AssertionError("toString must not be called");
            }
        };

        EffectRuntimeSnapshotAssertions.assertNoSentinels(text, Set.of("secret"));
        org.assertj.core.api.Assertions.assertThat(conversions).hasValue(0);
    }

    @Test
    void snapshotAssertionsRequirePositiveAttempts() {
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertSafe(
                safeStats(Map.of()), Map.of("status", "DONE", "attempts", 0),
                Set.of(ConnectorTypes.KAFKA_PUBLISH), Set.of("super-secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("attempts must be positive");
    }

    @Test
    void snapshotAssertionsRejectUnboundedNestedMetricLabels() {
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertSafe(
                safeStats(Map.of("resultBacklogByType", Map.of("customer-42", 1L))),
                Map.of("status", "DONE", "attempts", 1),
                Set.of(ConnectorTypes.KAFKA_PUBLISH), Set.of("super-secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("unbounded label");
    }

    @Test
    void snapshotAssertionsRejectMalformedLatencyShape() {
        assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertSafe(
                safeStats(Map.of("latencyByType", Map.of(
                        ConnectorTypes.KAFKA_PUBLISH, Map.of("count", 1L, "max", 2L)))),
                Map.of("status", "DONE", "attempts", 1),
                Set.of(ConnectorTypes.KAFKA_PUBLISH), Set.of("super-secret")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exactly count and totalMillis");
    }

    @Test
    void snapshotAssertionsRejectNonIntegralNonFiniteAndOutOfRangeCounters() {
        List<Number> invalid = List.of(
                -0.5d,
                0.5d,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        for (Number number : invalid) {
            assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertSafe(
                    safeStats(Map.of("statusCounts", Map.of("DONE", number))),
                    Map.of("status", "DONE", "attempts", 1),
                    Set.of(ConnectorTypes.KAFKA_PUBLISH), Set.of("super-secret")))
                    .isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> EffectRuntimeSnapshotAssertions.assertSafe(
                    safeStats(Map.of("latencyByType", Map.of(
                            ConnectorTypes.KAFKA_PUBLISH,
                            Map.of("count", number, "totalMillis", 1L)))),
                    Map.of("status", "DONE", "attempts", 1),
                    Set.of(ConnectorTypes.KAFKA_PUBLISH), Set.of("super-secret")))
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void conformanceRejectsSecretInCapturedLogs() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return new ScriptedFixture(scenario, payload) {
                    @Override
                    public CapturedLogObservation capturedLogs() {
                        return CapturedLogObservation.active(List.of(
                                Map.of("message", "token=super-secret")));
                    }
                };
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "identity, routing and successful receipt").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void conformanceRejectsInactiveLogCapture() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return new ScriptedFixture(scenario, payload) {
                    @Override
                    public CapturedLogObservation capturedLogs() {
                        return new CapturedLogObservation(false, List.of());
                    }
                };
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "identity, routing and successful receipt").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("active test log appender");
    }

    @Test
    void conformanceScansLogsProducedOnlyByExecutorClose() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return secretAfterClose(scenario, payload, new Probe());
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "identity, routing and successful receipt").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void conformanceScansSecondLifecycleFixtureAfterClose() {
        AtomicInteger opens = new AtomicInteger();
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return opens.incrementAndGet() == 2
                        ? secretAfterClose(scenario, payload, new Probe())
                        : new ScriptedFixture(scenario, payload);
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "fresh products and idempotent close").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void conformanceScansFirstLifecycleFixtureAfterClose() {
        AtomicInteger opens = new AtomicInteger();
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return opens.incrementAndGet() == 1
                        ? secretAfterClose(scenario, payload, new Probe())
                        : new ScriptedFixture(scenario, payload);
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "fresh products and idempotent close").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void conformanceScansRestartedFixtureAfterClose() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture restart(ExecutorFixture previous,
                                             ExecutorScenario scenario,
                                             byte[] payload) {
                return secretAfterClose(scenario, payload, (Probe) previous.probe());
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "unknown acknowledgement follows declared model").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void unknownAcknowledgementRequiresAtLeastOneLogicalMutation() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return new ScriptedFixture(scenario, payload) {
                    @Override protected void recordUnknownAcknowledgementMutation() {
                    }
                };
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "unknown acknowledgement follows declared model").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("logical mutation");
    }

    @Test
    void probeSafeUnknownAcknowledgementRejectsSecondLogicalMutation() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture restart(ExecutorFixture previous,
                                             ExecutorScenario scenario,
                                             byte[] payload) {
                return new ScriptedFixture(scenario, payload, (Probe) previous.probe()) {
                    @Override protected void recordReconciliationMutation() {
                        ((Probe) probe()).mutations.incrementAndGet();
                    }
                };
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "unknown acknowledgement follows declared model").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("more than one logical mutation");
    }

    @Test
    void failedConstructionScansLogsCapturedThroughCleanup() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public FailedConstructionObservation failedConstruction() {
                return new FailedConstructionObservation(
                        new IllegalArgumentException("configuration rejected"), 1, 1,
                        Map.of("phase", "construction"),
                        CapturedLogObservation.active(List.of("close token=super-secret")));
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "failed construction closes partial resources").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void capturedLogObservationRejectsUnboundedEventLists() {
        assertThatThrownBy(() -> CapturedLogObservation.active(
                java.util.Collections.nCopies(10_001, "safe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many captured log entries");
    }

    @Test
    void conformanceInvokesReceiptDetailSemanticHook() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public void verifyReceiptDetailConsistency(byte[] validPayload,
                                                       byte[] canonicalExternalRef,
                                                       ConnectorDetailDocumentV1 detailDocument) {
                throw new AssertionError("semantic mismatch detected");
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "identity, routing and successful receipt").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("semantic mismatch detected");
    }

    @Test
    void conformanceRejectsEmptyConfirmedReceipt() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return new ScriptedFixture(scenario, payload, new Probe(), true);
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "identity, routing and successful receipt").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("non-empty canonical receipt");
    }

    @Test
    @Timeout(5)
    void conformanceBoundsOrdinaryScenarioClose() {
        CountDownLatch never = new CountDownLatch(1);
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public Duration closeTimeout() {
                return Duration.ofMillis(50);
            }

            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return new ScriptedFixture(scenario, payload) {
                    @Override
                    public void close() throws InterruptedException {
                        never.await();
                    }
                };
            }
        };

        assertThatThrownBy(() -> dynamicTest(spec,
                "identity, routing and successful receipt").getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("close did not complete within the bound");
    }

    @Test
    void cleanupFailureIsSuppressedBehindPrimaryAssertion() {
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                return new ScriptedFixture(scenario, payload, new Probe(), true) {
                    @Override
                    public void close() {
                        throw new IllegalStateException("cleanup failed");
                    }
                };
            }
        };

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> dynamicTest(spec,
                "identity, routing and successful receipt").getExecutable().execute());

        org.assertj.core.api.Assertions.assertThat(failure)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("non-empty canonical receipt");
        org.assertj.core.api.Assertions.assertThat(failure.getSuppressed())
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(failure.getSuppressed()[0])
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cleanup failed");
    }

    @Test
    void lifecycleClosesFirstFixtureWhenSecondConstructionFails() {
        AtomicInteger opens = new AtomicInteger();
        List<ScriptedFixture> created = new ArrayList<>();
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                if (opens.incrementAndGet() == 2) {
                    throw new IllegalStateException("second construction failed");
                }
                ScriptedFixture fixture = new ScriptedFixture(scenario, payload);
                created.add(fixture);
                return fixture;
            }
        };
        DynamicTest lifecycle = EffectExecutorConformance.tests(spec)
                .filter(node -> node.getDisplayName().equals("fresh products and idempotent close"))
                .map(DynamicTest.class::cast)
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> lifecycle.getExecutable().execute())
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThat(created).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(created.getFirst().probe().closeCalls()).isEqualTo(1);
    }

    @Test
    void lifecycleExecutesAndClosesSecondFreshFixture() throws Throwable {
        List<ScriptedFixture> created = new ArrayList<>();
        ScriptedSpec spec = new ScriptedSpec() {
            @Override
            public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
                ScriptedFixture fixture = new ScriptedFixture(scenario, payload);
                created.add(fixture);
                return fixture;
            }
        };

        dynamicTest(spec, "fresh products and idempotent close")
                .getExecutable().execute();

        org.assertj.core.api.Assertions.assertThat(created).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(created.get(0).probe().closeCalls()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(created.get(0).logCaptureCloseCalls()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(created.get(1).probe().calls()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(created.get(1).probe().logicalMutations()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(created.get(1).probe().closeCalls()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(created.get(1).logCaptureCloseCalls()).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void runtimeHarnessUsesRealFinalizationDispatchStatusAndMetrics(@TempDir Path directory)
            throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger schemeCalls = new AtomicInteger();
        ConcurrentLinkedQueue<byte[]> effectIds = new ConcurrentLinkedQueue<>();
        AppEffectExecutorFactory factory = new AppEffectExecutorFactory() {
            @Override public String scheme() {
                schemeCalls.incrementAndGet();
                return "runtime-scripted";
            }

            @Override
            public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
                return List.of(new AppEffectExecutor() {
                    @Override public String id() { return "runtime-scripted"; }
                    @Override public boolean supports(String effectType) {
                        return ConnectorTypes.KAFKA_PUBLISH.equals(effectType);
                    }
                    @Override
                    public EffectExecution execute(EffectExecutionContext context,
                                                   PendingEffect effect) {
                        effectIds.add(effect.idHash().clone());
                        return EffectExecution.confirmed(
                                new KafkaPublishReceiptV1(new byte[32], 0, 0).encode());
                    }
                    @Override public void close() { closes.incrementAndGet(); }
                });
            }
        };

        EffectRuntimeHarness.RuntimeObservation observation;
        try (EffectRuntimeHarness harness = EffectRuntimeHarness.start(
                ConnectorTypes.KAFKA_PUBLISH,
                factory,
                Map.of("enabled", "true", "api-key", "super-secret"),
                directory)) {
            assertThatThrownBy(() -> harness.submit(
                    new byte[16 * 1024 + 1], Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("payload exceeds");
            observation = harness.submitAndAwaitDone(new byte[]{1}, Duration.ofSeconds(10));
            EffectRuntimeSnapshotAssertions.assertSafe(
                    observation.stats(), observation.status(),
                    Set.of(ConnectorTypes.KAFKA_PUBLISH), Set.of("super-secret"));
        }

        org.assertj.core.api.Assertions.assertThat(observation.effect().type())
                .isEqualTo(ConnectorTypes.KAFKA_PUBLISH);
        org.assertj.core.api.Assertions.assertThat(observation.status().get("status"))
                .isEqualTo("DONE");
        org.assertj.core.api.Assertions.assertThat(effectIds).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(HexUtil.encodeHexString(effectIds.element()))
                .isEqualTo(observation.effect().effectIdHashHex());
        org.assertj.core.api.Assertions.assertThat(schemeCalls).hasValue(1);
        org.assertj.core.api.Assertions.assertThat(closes).hasValue(1);
    }

    @Test
    void runtimeHarnessTimeoutArithmeticSurvivesNanoTimeWrap() {
        long started = Long.MAX_VALUE - 5;
        org.assertj.core.api.Assertions.assertThat(EffectRuntimeHarness.hasTimeRemaining(
                started, Long.MIN_VALUE + 3, 10)).isTrue();
        org.assertj.core.api.Assertions.assertThat(EffectRuntimeHarness.hasTimeRemaining(
                started, Long.MIN_VALUE + 4, 10)).isFalse();
    }

    @Test
    void runtimeHarnessRejectsSettingCountBeforeCopyingEntries() {
        Map<String, String> hostile = new AbstractMap<>() {
            @Override public int size() { return 65; }
            @Override public Set<Entry<String, String>> entrySet() {
                throw new AssertionError("entry copy must not be attempted");
            }
        };
        AppEffectExecutorFactory factory = new AppEffectExecutorFactory() {
            @Override public String scheme() { return "unused"; }
            @Override public List<AppEffectExecutor> create(String chainId,
                                                             Map<String, String> config) {
                return List.of();
            }
        };

        assertThatThrownBy(() -> EffectRuntimeHarness.start(
                ConnectorTypes.KAFKA_PUBLISH, factory, hostile, Path.of("unused")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many executor settings");
    }

    private static Map<String, Object> safeStats(Map<String, Object> extra) {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("queueDepth", 0L);
        stats.put("inFlight", 0L);
        stats.put("resultBacklog", 0L);
        stats.put("statusCounts", Map.of("DONE", 1L));
        stats.put("executionTotals", Map.of("confirmed", 1L, "failed", 0L, "parked", 0L));
        stats.put("resultBacklogByType", Map.of(ConnectorTypes.KAFKA_PUBLISH, 0L));
        stats.put("latencyByType", Map.of(ConnectorTypes.KAFKA_PUBLISH,
                Map.of("count", 1L, "totalMillis", 1L)));
        stats.putAll(extra);
        return stats;
    }

    private static DynamicTest dynamicTest(ExecutorConformanceSpec spec, String displayName) {
        return EffectExecutorConformance.tests(spec)
                .filter(node -> node.getDisplayName().equals(displayName))
                .map(DynamicTest.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static ScriptedFixture secretAfterClose(ExecutorScenario scenario,
                                                    byte[] payload,
                                                    Probe probe) {
        return new ScriptedFixture(scenario, payload, probe) {
            @Override public CapturedLogObservation capturedLogs() {
                String message = probe().closeCalls() > 0
                        ? "close token=super-secret" : "request completed";
                return CapturedLogObservation.active(List.of(Map.of("message", message)));
            }
        };
    }

    record NestedSecret(Object array, Object child) {
    }

    private static class ScriptedSpec implements ExecutorConformanceSpec {
        @Override public String actionType() { return ConnectorTypes.KAFKA_PUBLISH; }
        @Override public byte[] validPayload() {
            return new KafkaPublishCommandV1(
                    "broker-v1", "evidence-ready", new byte[]{1},
                    "application/octet-stream", new byte[]{2}, List.of()).encode();
        }
        @Override public List<PayloadCase> invalidPayloads() {
            return List.of(
                    new PayloadCase("malformed", new byte[]{0},
                            ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("unsupported-version", new byte[]{2},
                            ConnectorErrorCode.UNSUPPORTED_VERSION));
        }
        @Override public Set<String> forbiddenSentinels() {
            return Set.of("super-secret", "https://private.example");
        }
        @Override public IdempotencyModel idempotencyModel() {
            return IdempotencyModel.PROBE_SINGLE_MUTATION;
        }
        @Override public boolean supports(ExecutorScenario scenario) { return true; }
        @Override public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
            return new ScriptedFixture(scenario, payload);
        }
        @Override public ExecutorFixture restart(ExecutorFixture previous,
                                                 ExecutorScenario scenario,
                                                 byte[] payload) {
            return new ScriptedFixture(scenario, payload, (Probe) previous.probe());
        }
        @Override public FailedConstructionObservation failedConstruction() {
            return new FailedConstructionObservation(
                    new IllegalArgumentException("configuration rejected"), 1, 1,
                    Map.of("phase", "construction"),
                    CapturedLogObservation.active(List.of(
                            Map.of("message", "partial resource closed"))));
        }
        @Override public void verifyReceipt(byte[] canonicalExternalRef) {
            KafkaPublishReceiptV1.decode(canonicalExternalRef);
        }
        @Override public void verifySubmittedRef(byte[] canonicalSubmittedRef) {
            KafkaPublishReceiptV1.decode(canonicalSubmittedRef);
        }
        @Override
        public void verifyReceiptDetailConsistency(byte[] validPayload,
                                                   byte[] canonicalExternalRef,
                                                   ConnectorDetailDocumentV1 detailDocument) {
            KafkaPublishCommandV1 command = KafkaPublishCommandV1.decode(validPayload);
            KafkaPublishReceiptV1 receipt = KafkaPublishReceiptV1.decode(canonicalExternalRef);
            KafkaPublishDetailV1 detail = Assertions.assertInstanceOf(
                    KafkaPublishDetailV1.class, detailDocument.data());
            byte[] expectedDestination = KafkaDestinationFingerprint.compute(
                    command.target(), command.topic()).bytes();
            Assertions.assertArrayEquals(expectedDestination, receipt.destinationFingerprint());
            Assertions.assertArrayEquals(expectedDestination, detail.destinationFingerprint());
            Assertions.assertEquals(receipt.partition(), detail.partition());
            Assertions.assertEquals(receipt.offset(), detail.offset());
            Assertions.assertEquals(command.key().length, detail.serializedKeySize());
            Assertions.assertEquals(command.body().length, detail.serializedValueSize());
        }
    }

    private static class ScriptedFixture implements ExecutorFixture {
        private final ExecutorScenario scenario;
        private final PendingEffect effect;
        private final Probe probe;
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean logCaptureActive = new AtomicBoolean(true);
        private final AtomicInteger logCaptureCloseCalls = new AtomicInteger();
        private final List<Object> logs = java.util.Collections.synchronizedList(new ArrayList<>());
        private final byte[] detailBytes;
        private final byte[] detailHash;
        private final boolean emptyConfirmedReceipt;
        private final AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "scripted-connector"; }
            @Override public boolean supports(String effectType) {
                return ConnectorTypes.KAFKA_PUBLISH.equals(effectType);
            }
            @Override public EffectExecution execute(EffectExecutionContext context,
                                                     PendingEffect pending) throws Exception {
                if (pending.payload().length == 0 || pending.payload()[0] == 0) {
                    return EffectExecution.failed(ConnectorErrorCode.INVALID_PAYLOAD.wireCode(), false);
                }
                if (pending.payload()[0] == 2) {
                    return EffectExecution.failed(
                            ConnectorErrorCode.UNSUPPORTED_VERSION.wireCode(), false);
                }
                probe.calls.incrementAndGet();
                probe.keys.add(pending.idHash().clone());
                return switch (scenario) {
                    case SUCCESS -> {
                        probe.mutations.incrementAndGet();
                        yield confirmed();
                    }
                    case TRANSIENT_THEN_SUCCESS -> {
                        if (context.attempt() == 1) {
                            yield EffectExecution.failed(
                                    ConnectorErrorCode.SERVICE_UNAVAILABLE.wireCode(), true);
                        }
                        probe.mutations.incrementAndGet();
                        yield confirmed();
                    }
                    case UNKNOWN_ACK_THEN_RECONCILE -> {
                        if (context.attempt() == 1) {
                            recordUnknownAcknowledgementMutation();
                            yield EffectExecution.submitted(receipt());
                        }
                        recordReconciliationMutation();
                        yield confirmed();
                    }
                    case EXISTING_MATCH -> confirmed();
                    case EXISTING_CONFLICT -> EffectExecution.failed(
                            ConnectorErrorCode.DESTINATION_CONFLICT.wireCode(), false);
                    case BLOCKED_CALL -> {
                        blocked.countDown();
                        release.await();
                        yield closed.get()
                                ? EffectExecution.failed(ConnectorErrorCode.SHUTDOWN.wireCode(), true)
                                : confirmed();
                    }
                };
            }
            @Override public void close() {
                if (closed.compareAndSet(false, true)) {
                    logs.add(Map.of("logger", "scripted-provider", "message", "executor closed"));
                    probe.closeCalls.incrementAndGet();
                    release.countDown();
                }
            }
        };

        private ScriptedFixture(ExecutorScenario scenario, byte[] payload) {
            this(scenario, payload, new Probe(), false);
        }

        private ScriptedFixture(ExecutorScenario scenario, byte[] payload, Probe probe) {
            this(scenario, payload, probe, false);
        }

        private ScriptedFixture(ExecutorScenario scenario,
                                byte[] payload,
                                Probe probe,
                                boolean emptyConfirmedReceipt) {
            this.scenario = scenario;
            this.probe = probe;
            this.emptyConfirmedReceipt = emptyConfirmedReceipt;
            this.logs.add(Map.of("logger", "scripted-provider", "message", "fixture opened"));
            EffectRecord record = new EffectRecord(1, "test-chain", 7, 2,
                    ConnectorTypes.KAFKA_PUBLISH, payload.clone(), "demo",
                    FinalityGate.APP_FINAL, ResultPolicy.NONE, 100, null);
            this.effect = PendingEffect.of(record);
            this.detailBytes = ConnectorDetailDocumentV1.of(effect.idHash(),
                    new KafkaPublishDetailV1(destinationFingerprint(), 0, 0, 1, 1)).encode();
            this.detailHash = ConnectorDetailHash.compute(detailBytes).bytes();
        }

        @Override public AppEffectExecutor executor() { return executor; }
        @Override public PendingEffect effect() { return effect; }
        @Override public ExternalProbe probe() { return probe; }

        @Override
        public EffectExecutionContext context(int attempt, byte[] submittedRef) {
            byte[] snapshot = submittedRef != null ? submittedRef.clone() : new byte[0];
            return new EffectExecutionContext() {
                @Override public String chainId() { return "test-chain"; }
                @Override public long tipHeight() { return 9; }
                @Override public long anchoredHeight() { return 8; }
                @Override public int attempt() { return attempt; }
                @Override public byte[] submittedRef() { return snapshot.clone(); }
                @Override public Map<String, String> settings() { return Map.of(); }
            };
        }

        @Override public Map<String, Object> runtimeStats() {
            return Map.of(
                    "queueDepth", 0L,
                    "inFlight", 0L,
                    "resultBacklog", 0L,
                    "statusCounts", Map.of("DONE", 1L),
                    "executionTotals", Map.of("confirmed", 1L, "failed", 0L, "parked", 0L),
                    "resultBacklogByType", Map.of(ConnectorTypes.KAFKA_PUBLISH, 0L),
                    "latencyByType", Map.of(ConnectorTypes.KAFKA_PUBLISH,
                            Map.of("count", 1L, "totalMillis", 1L)),
                    "executors", List.of("scripted-connector"));
        }

        @Override public Map<String, Object> runtimeStatus() {
            return Map.of("status", "DONE", "attempts", 1);
        }

        @Override public CapturedLogObservation capturedLogs() {
            synchronized (logs) {
                return new CapturedLogObservation(logCaptureActive.get(), List.copyOf(logs));
            }
        }

        @Override public void closeLogCapture() {
            if (logCaptureActive.compareAndSet(true, false)) {
                logCaptureCloseCalls.incrementAndGet();
            }
        }

        @Override public boolean awaitBlocked(Duration timeout) throws InterruptedException {
            return blocked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override public void unblock() { release.countDown(); }

        @Override
        public Optional<byte[]> archivedDetail(byte[] requestedHash) {
            return Arrays.equals(detailHash, requestedHash)
                    ? Optional.of(detailBytes.clone()) : Optional.empty();
        }

        private EffectExecution confirmed() {
            return emptyConfirmedReceipt
                    ? EffectExecution.confirmed(new byte[0])
                    : EffectExecution.confirmed(receipt(), detailHash);
        }

        private byte[] receipt() {
            return new KafkaPublishReceiptV1(destinationFingerprint(), 0, 0).encode();
        }

        private static byte[] destinationFingerprint() {
            return KafkaDestinationFingerprint.compute("broker-v1", "evidence-ready").bytes();
        }

        protected void recordUnknownAcknowledgementMutation() {
            probe.mutations.incrementAndGet();
        }

        protected void recordReconciliationMutation() {
        }

        private int logCaptureCloseCalls() {
            return logCaptureCloseCalls.get();
        }
    }

    private static final class Probe implements ExternalProbe {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger mutations = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final List<byte[]> keys = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override public int calls() { return calls.get(); }
        @Override public int logicalMutations() { return mutations.get(); }
        @Override public int closeCalls() { return closeCalls.get(); }
        @Override public List<byte[]> observedIdempotencyKeys() {
            synchronized (keys) {
                return keys.stream().map(byte[]::clone).toList();
            }
        }
        @Override public Map<String, Object> diagnostics() {
            return Map.of("provider", "scripted", "calls", calls());
        }
    }
}
