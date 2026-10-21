package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventContext;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.l1view.L1Observation;
import org.yanoproject.api.appchain.l1view.L1Observer;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.api.appchain.l1view.L1ObserverProvider;
import org.yanoproject.api.appchain.sequencer.SequencerContext;
import org.yanoproject.api.appchain.sequencer.SequencerMode;
import org.yanoproject.api.appchain.sequencer.SequencerModeProvider;
import org.yanoproject.api.events.AppChainAnchoredEvent;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.events.RollbackEvent;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.runtime.chain.BlockBodyRetentionRegistry;
import org.yanoproject.runtime.kernel.SubsystemHealth;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The app chain fed by cursor-driven L1 delivery (app-layer ADR-038), end to end through the subsystem. L1 blocks
 * live in a real in-memory chain state; node events only wake the loop.
 */
@Timeout(60)
class AppChainL1DeliveryTest {

    private static final String MODE_ID = "controlled-l1-mode";
    private static final String OBSERVER_TYPE = "controlled-l1-observer";
    private static final String OBSERVER_ID = "controlled";
    private static final byte[] ANCHOR_TX = L1TestChain.sampleTransaction(1);
    private static final String ANCHOR_TX_HASH = L1TestChain.transactionHash(ANCHOR_TX);
    private static final byte[] SIGNING_KEY = fill(32, 73);
    private static final String SIGNING_KEY_HEX = HexUtil.encodeHexString(SIGNING_KEY);
    private static final String PUBLIC_KEY = HexUtil.encodeHexString(
            KeyGenUtil.getPublicKeyFromPrivateKey(SIGNING_KEY));

    @TempDir
    Path tempDir;

    @Test
    void observerFailureNeverLosesTheAnchorConfirmation() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("observer-failure", controls)) {
            controls.failure.set(new IllegalStateException("transient observer failure"));
            harness.anchorBlock(50);

            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(harness.anchoredEvents()).as("the retried block confirms nothing twice").hasSize(1);
            assertThat(controls.observedSlots).contains(50L);
        }
    }

    @Test
    void phaseTwoInvalidTransactionCannotConfirmAnchor() throws Exception {
        try (StartedHarness harness = startHarness("invalid-anchor-tx", new Controls())) {
            harness.l1.appendWithTransactions(50, List.of(ANCHOR_TX), Set.of(0));
            harness.wake();
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(anchorHeight(harness.subsystem)).isZero();
            assertThat(harness.anchoredEvents()).isEmpty();

            harness.anchorBlock(60);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(harness.anchoredEvents()).hasSize(1);
        }
    }

    @Test
    void processFatalErrorEscapesThePassAndTheBlockIsRedelivered() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("fatal-phase", controls)) {
            controls.failure.set(new TestVirtualMachineError());
            harness.anchorBlock(50);
            assertThat(controls.failureThrown.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(anchorHeight(harness.subsystem)).as("the fatal pass committed nothing").isZero();

            // No event: the poll survives the escaped error and redelivers the block (I6).
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(harness.anchoredEvents()).hasSize(1);
        }
    }

    @Test
    void stopDuringAnInFlightPassDrainsAndRestartResumesFromTheDurableCursor() throws Exception {
        Controls controls = new Controls();
        StartedHarness harness = startHarness("drain", controls);
        try {
            controls.blockingSlot.set(50);
            harness.anchorBlock(50);
            assertThat(controls.observationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            harness.subsystem.stop();
            assertThatThrownBy(harness.subsystem::start)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("still draining");
            controls.releaseObservation.countDown();
            startAfterDrain(harness.subsystem);

            harness.wake();
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(harness.anchoredEvents()).hasSize(1);
        } finally {
            controls.releaseObservation.countDown();
            harness.close();
        }
    }

    @Test
    void failedObserverHoldsTheCursorAndNoLaterBlockIsSkipped() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("no-skip", controls)) {
            controls.failure.set(new IllegalStateException("transient observer failure"));
            harness.l1.append(50, 60);
            harness.wake();

            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(controls.observedSlots).as("ADR-038 P2: slot 60 is observed after 50 recovers")
                    .containsSubsequence(50L, 60L);
            assertThat(observerHealthy(harness.subsystem)).isTrue();
        }
    }

    @Test
    void restartRetriesTheFailedBlockFromTheDurableRecord() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("restart-retry", controls)) {
            controls.failure.set(new IllegalStateException("transient observer failure"));
            harness.l1.append(50);
            harness.subsystem.stop();
            startAfterDrain(harness.subsystem);
            harness.wake();

            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(controls.observedSlots).contains(50L);
            assertThat(observerHealthy(harness.subsystem)).isTrue();
        }
    }

    @Test
    void rollbackPastAFailedBlockClearsTheBarrierAndDeliversTheReplacement() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("rollback-barrier", controls)) {
            long base = harness.l1.tipNumber();
            controls.failure.set(new IllegalStateException("transient observer failure"));
            controls.failAgainOnRetry.set(true);
            harness.l1.append(50);
            harness.wake();
            awaitCondition(() -> !observerHealthy(harness.subsystem));

            controls.failAgainOnRetry.set(false);
            harness.fork(base, 55);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(observerHealthy(harness.subsystem)).isTrue();
            assertThat(controls.observedSlots).contains(55L).doesNotContain(50L);
        }
    }

    @Test
    void stableAnchorFrontierFollowsDeliveryAndForgetsARolledBackAnchor() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("stable-frontier", controls)) {
            // No observations: a finalized one below the fork would rightly quarantine the chain (ADR-036).
            controls.suppressObservations.set(true);
            long base = harness.l1.tipNumber();
            harness.anchorBlock(50);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(stableAnchorHeight(harness.subsystem)).as("first sighting only").isZero();

            harness.block(60);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(stableAnchorHeight(harness.subsystem)).as("one block deep").isEqualTo(1);

            harness.fork(base, 55, 65, 75);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(anchorHeight(harness.subsystem)).isZero();
            assertThat(stableAnchorHeight(harness.subsystem)).isZero();
        }
    }

    /** bloxbean/yano#166: a rollback published while the chain is stopped is still applied after restart. */
    @Test
    void rollbackMissedWhileStoppedIsAppliedAfterRestart() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("missed-rollback", controls)) {
            // No observations: a finalized one below the fork would rightly quarantine the chain (ADR-036).
            controls.suppressObservations.set(true);
            long base = harness.l1.tipNumber();
            harness.anchorBlock(50);
            harness.block(60);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(stableAnchorHeight(harness.subsystem)).isEqualTo(1);

            harness.subsystem.stop();
            harness.l1.fork(base, 55, 65, 75);   // no subscription is listening
            startAfterDrain(harness.subsystem);

            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(anchorHeight(harness.subsystem)).isZero();
            assertThat(stableAnchorHeight(harness.subsystem)).isZero();
        }
    }

    /** A node rollback event skips the retry backoff, so a fork that kills the retried block is handled at once. */
    @Test
    void rollbackEventSkipsTheRetryBackoff() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("rollback-skips-backoff", controls)) {
            long base = harness.l1.tipNumber();
            controls.failAgainOnRetry.set(true);
            controls.failure.set(new IllegalStateException("observer down"));
            harness.block(50);
            // Attempts at about 0 s, 1 s and 3 s; the next one waits until about 7 s.
            Thread.sleep(4_000);
            controls.failure.set(null);

            long forkedAt = System.nanoTime();
            harness.fork(base, 55);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(System.nanoTime() - forkedAt).as("handled before the backoff deadline")
                    .isLessThan(TimeUnit.SECONDS.toNanos(2));
        }
    }

    @Test
    void lostEventsStillDeliverEveryBlockOnThePoll() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("lost-events", controls)) {
            harness.l1.append(50, 60, 70);   // no event published at all
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(controls.observedSlots).containsSubsequence(50L, 60L, 70L);
        }
    }

    /** ADR-038 D8a/D8b: a ledger from before the delivery record is checked against chain state on upgrade. */
    @Test
    void upgradeExcludesAnAnchorWhoseL1BlockDiedWhileStopped() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("upgrade-dead-anchor", controls)) {
            controls.suppressObservations.set(true);
            long base = harness.l1.tipNumber();
            harness.anchorBlock(50);
            harness.block(60);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(stableAnchorHeight(harness.subsystem)).isEqualTo(1);

            harness.subsystem.stop();
            dropDeliveryRecordAfterDrain(harness, "upgrade-dead-anchor");
            harness.l1.fork(base, 55, 65, 75);
            startAfterDrain(harness.subsystem);
            awaitReconciled(harness);

            harness.block(85);
            harness.block(95);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(stableAnchorHeight(harness.subsystem)).isZero();
        }
    }

    @Test
    void upgradeKeepsAnAnchorWhoseL1BlockIsStillCanonical() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("upgrade-live-anchor", controls)) {
            controls.suppressObservations.set(true);
            harness.anchorBlock(50);
            harness.block(60);
            awaitDelivered(harness, harness.l1.tipNumber());

            harness.subsystem.stop();
            dropDeliveryRecordAfterDrain(harness, "upgrade-live-anchor");
            startAfterDrain(harness.subsystem);
            awaitReconciled(harness);
            assertThat(stableAnchorHeight(harness.subsystem)).as("no delivered window yet").isZero();

            harness.block(85);
            harness.block(95);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(stableAnchorHeight(harness.subsystem)).isEqualTo(1);
        }
    }

    @Test
    void upgradeQuarantinesWhenAFinalizedObservationsL1BlockDiedWhileStopped() throws Exception {
        try (StartedHarness harness = startHarness("upgrade-dead-observation", new Controls())) {
            long base = harness.l1.tipNumber();
            harness.block(50);
            harness.block(60);
            awaitCondition(() -> observationCommitted(harness.subsystem, 50));

            harness.subsystem.stop();
            dropDeliveryRecordAfterDrain(harness, "upgrade-dead-observation");
            harness.l1.fork(base, 55, 65);
            startAfterDrain(harness.subsystem);

            awaitCondition(() -> "QUARANTINED".equals(deliveryStatus(harness.subsystem).get("state")));
            assertThat(deliveryStatus(harness.subsystem))
                    .containsEntry("lastFailure", "DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION")
                    .containsEntry("deliveryHealthy", false);
        }
    }

    /** ADR-038 D8: a ledger whose only L1-derived state is journaled observations is still reconciled on upgrade. */
    @Test
    void upgradeReconcilesAJournalOnlyLedger() throws Exception {
        String testId = "upgrade-journal-only";
        L1TestChain l1 = new L1TestChain();
        l1.append(10, 20);
        Controls controls = new Controls();
        AppChainConfig config = AppChainConfig.builder("l1-delivery-" + testId)
                .signingKeyHex(SIGNING_KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .threshold(1)
                .blockIntervalMs(25)
                .l1StabilityDepth(3)
                .pluginSettings(Map.of(
                        "sequencer.mode", MODE_ID,
                        "observers." + OBSERVER_ID + ".type", OBSERVER_TYPE,
                        "observation.l1-network-genesis-id", "01".repeat(32)))
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, 42, new DirectEventBus(), null,
                tempDir.resolve(testId).toString(), null, new ControlledRegistry(controls), mock(Logger.class));
        subsystem.wireL1Chain(l1.reader(), null);
        StartedHarness harness = new StartedHarness(subsystem, new DirectEventBus(), l1, null);
        try {
            subsystem.start();
            awaitDelivered(harness, l1.tipNumber());
            l1.append(50);
            awaitDelivered(harness, l1.tipNumber());
            assertThat(journalStates(subsystem)).containsEntry("SEEN_UNSTABLE", 1L);
            assertThat(subsystem.tipHeight()).isZero();

            subsystem.stop();
            dropDeliveryRecordAfterDrain(harness, testId);
            l1.fork(1, 55);
            startAfterDrain(subsystem);
            awaitReconciled(harness);

            assertThat(journalStates(subsystem)).as("the dead-fork observation is invalidated")
                    .containsEntry("SEEN_UNSTABLE", 0L);
        } finally {
            subsystem.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> journalStates(AppChainSubsystem subsystem) {
        Map<?, ?> observers = (Map<?, ?>) subsystem.status().get("observers");
        return (Map<String, Object>) ((Map<?, ?>) observers.get("journal")).get("states");
    }

    /** ADR-038 §15 item 4: bodies stripped by app-block retention do not leave an upgrade without evidence. */
    @Test
    void upgradeReconcilesAChainWhosePrunedBodiesHeldObservations() throws Exception {
        try (StartedHarness harness = startHarness("upgrade-pruned", new Controls())) {
            harness.block(50);
            harness.block(60);
            awaitCondition(() -> observationCommitted(harness.subsystem, 50));
            long tip = harness.subsystem.tipHeight();

            harness.subsystem.stop();
            dropDeliveryRecordAfterDrain(harness, "upgrade-pruned",
                    ledger -> assertThat(ledger.pruneBodiesBelow(tip)).isPositive());
            startAfterDrain(harness.subsystem);

            awaitReconciled(harness);
        }
    }

    /** ADR-038 D10: readiness degrades while delivery lags or retries and recovers with it; safety is D9a's job. */
    @Test
    void readinessFollowsDeliveryLagAndRetries() throws Exception {
        Controls controls = new Controls();
        try (StartedHarness harness = startHarness("readiness", controls,
                Map.of("l1.delivery.readiness-max-lag-blocks", "2"))) {
            assertThat(harness.subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);

            controls.blockingSlot.set(50);
            harness.block(50);
            assertThat(controls.observationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            harness.l1.append(60, 70, 80);
            awaitCondition(() -> harness.subsystem.health().status() == SubsystemHealth.Status.DEGRADED);
            assertThat(harness.subsystem.health().message()).isEqualTo("L1 delivery lags 4 blocks");
            assertThat(deliveryStatus(harness.subsystem)).containsKey("notReady");
            controls.releaseObservation.countDown();
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(harness.subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);

            controls.failAgainOnRetry.set(true);
            controls.failure.set(new IllegalStateException("observer down"));
            harness.block(90);
            awaitCondition(() -> harness.subsystem.health().status() == SubsystemHealth.Status.DEGRADED);
            assertThat(harness.subsystem.health().message()).isEqualTo("L1 delivery RETRYING_BLOCK");
            controls.failure.set(null);
            awaitCondition(() -> harness.subsystem.health().status() == SubsystemHealth.Status.UP);
        }
    }

    /** ADR-038 F6: retention survives a stop, so the pruner cannot remove a body the chain has not delivered. */
    @Test
    void retentionHoldsTheNextUndeliveredBodyAcrossStopAndStart() throws Exception {
        try (StartedHarness harness = startHarness("retention", new Controls())) {
            harness.block(50);
            awaitDelivered(harness, harness.l1.tipNumber());
            long next = harness.l1.tipNumber() + 1;
            assertThat(harness.retention.oldestRequiredBlockNumber()).hasValue(next);

            harness.subsystem.stop();
            harness.l1.append(60, 70);
            assertThat(harness.retention.oldestRequiredBlockNumber()).hasValue(next);

            startAfterDrain(harness.subsystem);
            awaitDelivered(harness, harness.l1.tipNumber());
            assertThat(harness.retention.oldestRequiredBlockNumber()).hasValue(harness.l1.tipNumber() + 1);
            harness.subsystem.close();
            assertThat(harness.retention.oldestRequiredBlockNumber()).isEmpty();
        }
    }

    /** A chain that consumes no L1 runs no delivery loop, yet its rotating sequencer still clocks off the L1 tip. */
    @Test
    void rotatingSequencerClocksOffTheL1TipWithoutADeliveryLoop() throws Exception {
        L1TestChain l1 = new L1TestChain();
        l1.append(10, 130);
        AppChainConfig config = AppChainConfig.builder("l1-clock")
                .signingKeyHex(SIGNING_KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .threshold(1)
                .blockIntervalMs(60_000)
                .pluginSettings(Map.of("sequencer.mode", "rotating", "sequencer.window-slots", "60"))
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, 42, new DirectEventBus(), null,
                tempDir.resolve("l1-clock").toString(), null, mock(Logger.class));
        subsystem.wireL1Chain(l1.reader(), null);
        try {
            subsystem.start();
            assertThat(subsystem.status()).doesNotContainKey("l1Delivery");
            assertThat(subsystem.status().get("sequencer")).isInstanceOfSatisfying(Map.class,
                    sequencer -> assertThat(sequencer.get("currentWindow")).isEqualTo(2L));
        } finally {
            subsystem.close();
        }
    }

    /** ADR-038 F8: a depth-0 chain's consensus reads no L1, so delivery retries do not stop it finalizing. */
    @Test
    void depthZeroChainKeepsFinalizingWhileDeliveryRetries() throws Exception {
        L1TestChain l1 = new L1TestChain();
        l1.append(10, 20);
        AppChainConfig config = AppChainConfig.builder("l1-depth-zero")
                .signingKeyHex(SIGNING_KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .blockIntervalMs(25)
                .anchor(new AppChainConfig.AnchorConfig(true, SIGNING_KEY_HEX, 1, 60, 7014))
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, 42, new DirectEventBus(), null,
                tempDir.resolve("depth-zero").toString(), null, mock(Logger.class));
        subsystem.wireL1(ignored -> ANCHOR_TX_HASH, () -> new FixedUtxoState(List.of(anchorUtxo())));
        subsystem.wireL1Chain(l1.reader(), null);
        try {
            subsystem.start();
            awaitCondition(() -> deliveryStatus(subsystem).containsKey("cursorBlock"));
            l1.beforeBodyRead = () -> {
                throw new IllegalStateException("disk unavailable");
            };
            l1.append(30);
            awaitCondition(() -> "RETRYING_BLOCK".equals(deliveryStatus(subsystem).get("state")));

            subsystem.submit("test", new byte[]{1});
            awaitTip(subsystem, 1);
            assertThat(deliveryStatus(subsystem)).containsEntry("deliveryHealthy", false);
        } finally {
            subsystem.close();
        }
    }

    /** PR #177 F1: a member without local anchoring still follows script anchors, so the gate can open. */
    @Test
    void l1AnchoredGateIsReachableOnScriptAnchorFollower() throws Exception {
        for (AppChainConfig.AnchorConfig anchor : Arrays.asList(
                null, new AppChainConfig.AnchorConfig(false, SIGNING_KEY_HEX, 1, 60, 7014))) {
            String id = anchor == null ? "gate-follower-absent" : "gate-follower-disabled";
            assertThat(l1AnchoredGateStatus(id, anchor, true)).isEqualTo(Map.of("reachable", true));
        }
    }

    @Test
    void l1AnchoredGateIsUnreachableWithoutAnyAnchorPath() throws Exception {
        assertThat(l1AnchoredGateStatus("gate-no-l1-tx", null, false)).isEqualTo(Map.of(
                "reachable", false,
                "reason", "this node neither submits anchors nor follows script anchors (no L1 transaction access)"));
    }

    @SuppressWarnings("unchecked")
    private Object l1AnchoredGateStatus(String testId, AppChainConfig.AnchorConfig anchor, boolean wireL1Tx)
            throws Exception {
        L1TestChain l1 = new L1TestChain();
        l1.append(10, 20);
        AppChainConfig config = AppChainConfig.builder("l1-delivery-" + testId)
                .signingKeyHex(SIGNING_KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .blockIntervalMs(25)
                .l1StabilityDepth(1)
                .anchor(anchor)
                .pluginSettings(Map.of(
                        "effects.enabled", "true",
                        "effects.executor.enabled", "true",
                        "effects.executors.webhook.url", "http://127.0.0.1:9/unused"))
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, 42, new DirectEventBus(), null,
                tempDir.resolve(testId).toString(), null, mock(Logger.class));
        if (wireL1Tx) {
            subsystem.wireL1(ignored -> ANCHOR_TX_HASH, () -> new FixedUtxoState(List.of(anchorUtxo())));
        }
        subsystem.wireL1Chain(l1.reader(), null);
        try {
            subsystem.start();
            Map<String, Object> effects = (Map<String, Object>) subsystem.status().get("effects");
            Map<String, Object> executor = (Map<String, Object>) effects.get("executor");
            return executor.get("l1AnchoredGate");
        } finally {
            subsystem.close();
        }
    }

    private StartedHarness startHarness(String testId, Controls controls) throws Exception {
        return startHarness(testId, controls, Map.of());
    }

    private StartedHarness startHarness(String testId, Controls controls, Map<String, String> extraSettings)
            throws Exception {
        L1TestChain l1 = new L1TestChain();
        l1.append(10, 20);
        DirectEventBus eventBus = new DirectEventBus();
        AppChainConfig config = AppChainConfig.builder("l1-delivery-" + testId)
                .signingKeyHex(SIGNING_KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .threshold(1)
                .blockIntervalMs(25)
                .l1StabilityDepth(1)
                .anchor(new AppChainConfig.AnchorConfig(true, SIGNING_KEY_HEX, 1, 60, 7014))
                .pluginSettings(harnessSettings(extraSettings))
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(
                config, 42, eventBus, null, tempDir.resolve(testId).toString(),
                null, new ControlledRegistry(controls), mock(Logger.class));
        subsystem.wireL1(ignored -> ANCHOR_TX_HASH, () -> new FixedUtxoState(List.of(anchorUtxo())));
        BlockBodyRetentionRegistry retention = new BlockBodyRetentionRegistry();
        subsystem.wireL1Chain(l1.reader(), retention.register(OptionalLong.of(0)));
        StartedHarness harness = new StartedHarness(subsystem, eventBus, l1, retention);
        try {
            subsystem.start();
            // Blocks 0-1 become the baseline history; wait for it, then deliver two blocks so the proposer has a
            // stable L1 reference (depth 1).
            awaitDelivered(harness, l1.tipNumber());
            controls.suppressObservations.set(true);
            l1.append(30, 40);
            harness.wake();
            awaitDelivered(harness, l1.tipNumber());
            subsystem.submit("test", new byte[]{1});
            awaitTip(subsystem, 1);
            assertThat(subsystem.forceAnchor()).isTrue();
            controls.observedSlots.clear();
            controls.suppressObservations.set(false);
            return harness;
        } catch (Exception | Error failure) {
            subsystem.close();
            throw failure;
        }
    }

    private static Map<String, String> harnessSettings(Map<String, String> extraSettings) {
        Map<String, String> settings = new HashMap<>(Map.of(
                "sequencer.mode", MODE_ID,
                "observers." + OBSERVER_ID + ".type", OBSERVER_TYPE,
                "observation.l1-network-genesis-id", "01".repeat(32)));
        settings.putAll(extraSettings);
        return settings;
    }

    private static void awaitReconciled(StartedHarness harness) throws InterruptedException {
        awaitCondition(() -> "RECONCILED".equals(deliveryStatus(harness.subsystem).get("phase"))
                && "RUNNING".equals(deliveryStatus(harness.subsystem).get("state")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deliveryStatus(AppChainSubsystem subsystem) {
        return subsystem.status().get("l1Delivery") instanceof Map<?, ?> delivery
                ? (Map<String, Object>) delivery : Map.of();
    }

    /** Simulates a ledger written before ADR-038: its L1-derived state is there, the delivery record is not. */
    private void dropDeliveryRecordAfterDrain(StartedHarness harness, String testId) throws InterruptedException {
        dropDeliveryRecordAfterDrain(harness, testId, ledger -> { });
    }

    private void dropDeliveryRecordAfterDrain(StartedHarness harness, String testId,
                                              Consumer<AppLedgerStore> alsoWhileStopped) throws InterruptedException {
        String path = tempDir.resolve(testId) + "/l1-delivery-" + testId;
        var identity = harness.subsystem.stateCommitmentIdentity().orElseThrow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try (AppLedgerStore ledger = new AppLedgerStore(path, mock(Logger.class), identity)) {
                assertThat(ledger.metaBytes(L1DeliveryRecord.META_KEY)).isNotNull();
                ledger.metaDeleteSync(L1DeliveryRecord.META_KEY);
                alsoWhileStopped.accept(ledger);
                return;
            } catch (RuntimeException stillOpenByTheDrainingGeneration) {
                if (System.nanoTime() > deadline) {
                    throw stillOpenByTheDrainingGeneration;
                }
                Thread.sleep(10);
            }
        }
    }

    private static boolean observationCommitted(AppChainSubsystem subsystem, long slot) {
        for (long height = 1; height <= subsystem.tipHeight(); height++) {
            for (AppMessage message : subsystem.block(height).orElseThrow().messages()) {
                if (message.getTopic().startsWith(L1Observation.TOPIC_PREFIX)
                        && L1Observation.decode(message.getBody()).slot() == slot) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void awaitDelivered(StartedHarness harness, long blockNumber) throws InterruptedException {
        awaitCondition(() -> {
            Object raw = harness.subsystem.status().get("l1Delivery");
            if (!(raw instanceof Map<?, ?> delivery)) {
                return false;
            }
            Object cursor = delivery.get("cursorBlock");
            return cursor instanceof Number number && number.longValue() >= blockNumber
                    && "RUNNING".equals(delivery.get("state")) && !delivery.containsKey("pending");
        });
    }

    private static void awaitTip(AppChainSubsystem subsystem, long expected) throws InterruptedException {
        awaitCondition(() -> subsystem.tipHeight() >= expected);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("condition timed out");
    }

    private static void startAfterDrain(AppChainSubsystem subsystem) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IllegalStateException lastDraining = null;
        while (System.nanoTime() < deadline) {
            try {
                subsystem.start();
                return;
            } catch (IllegalStateException failure) {
                if (!failure.getMessage().contains("still draining")) {
                    throw failure;
                }
                lastDraining = failure;
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Old generation did not drain", lastDraining);
    }

    private static boolean observerHealthy(AppChainSubsystem subsystem) {
        Object rawObservers = subsystem.status().get("observers");
        return rawObservers instanceof Map<?, ?> observers && Boolean.TRUE.equals(observers.get("healthy"));
    }

    private static long anchorHeight(AppChainSubsystem subsystem) {
        return anchorStatusLong(subsystem, "lastAnchoredHeight");
    }

    private static long stableAnchorHeight(AppChainSubsystem subsystem) {
        return anchorStatusLong(subsystem, "stableAnchoredHeight");
    }

    private static long anchorStatusLong(AppChainSubsystem subsystem, String key) {
        Object rawAnchor = subsystem.status().get("anchor");
        assertThat(rawAnchor).isInstanceOf(Map.class);
        Object value = ((Map<?, ?>) rawAnchor).get(key);
        assertThat(value).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private static Utxo anchorUtxo() {
        return new Utxo(new Outpoint("cd".repeat(32), 0), "addr_test",
                BigInteger.valueOf(50_000_000), List.of(), null, null,
                null, null, false, 0, 0, null);
    }

    private static byte[] fill(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Controls {
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean failAgainOnRetry = new AtomicBoolean();
        private final CountDownLatch failureThrown = new CountDownLatch(1);
        private final AtomicLong blockingSlot = new AtomicLong(-1);
        private final CountDownLatch observationEntered = new CountDownLatch(1);
        private final CountDownLatch releaseObservation = new CountDownLatch(1);
        private final List<Long> observedSlots = new CopyOnWriteArrayList<>();
        /** Warm-up blocks yield no observations, so none becomes a mandatory proposal input. */
        private final AtomicBoolean suppressObservations = new AtomicBoolean();
    }

    private record StartedHarness(AppChainSubsystem subsystem, DirectEventBus eventBus, L1TestChain l1,
                                  BlockBodyRetentionRegistry retention) implements AutoCloseable {
        void block(long slot) {
            l1.append(slot);
            wake();
        }

        void anchorBlock(long slot) {
            l1.appendWithTransactions(slot, List.of(ANCHOR_TX), Set.of());
            wake();
        }

        void fork(long keep, long... slots) {
            l1.fork(keep, slots);
            var base = l1.block(keep);
            eventBus.publish(new RollbackEvent(new Point(base.slot(), HexUtil.encodeHexString(base.blockHash())),
                    true), EventMetadata.builder().build(), PublishOptions.builder().build());
        }

        /** A node event: it carries nothing the loop reads, it only wakes it. */
        void wake() {
            eventBus.publish(new BlockAppliedEvent(null, 0, 0, "00".repeat(32), null),
                    EventMetadata.builder().build(), PublishOptions.builder().build());
        }

        List<AppChainAnchoredEvent> anchoredEvents() {
            return eventBus.published.stream()
                    .filter(AppChainAnchoredEvent.class::isInstance)
                    .map(AppChainAnchoredEvent.class::cast)
                    .toList();
        }

        @Override
        public void close() {
            subsystem.close();
        }
    }

    private static final class ControlledRegistry implements PluginProviderRegistry {
        private final SequencerModeProvider modeProvider;
        private final L1ObserverProvider observerProvider;

        private ControlledRegistry(Controls controls) {
            this.modeProvider = new SequencerModeProvider() {
                @Override public String id() { return MODE_ID; }
                @Override public SequencerMode create(SequencerContext context) {
                    return new ControlledMode();
                }
            };
            this.observerProvider = new L1ObserverProvider() {
                @Override public String type() { return OBSERVER_TYPE; }
                @Override
                public L1ObserverConsensusIdentity consensusIdentity(
                        String observerId, Map<String, String> settings) {
                    return new L1ObserverConsensusIdentity(1, "controlled-test-claim-v1", 1, new byte[]{1});
                }
                @Override
                public L1Observer create(String observerId, Map<String, String> settings) {
                    return new ControlledObserver(observerId, controls);
                }
            };
        }

        @Override
        public <P> Optional<P> find(Class<P> providerType, String selector) {
            if (providerType == SequencerModeProvider.class && MODE_ID.equals(selector)) {
                return Optional.of(providerType.cast(modeProvider));
            }
            if (providerType == L1ObserverProvider.class && OBSERVER_TYPE.equals(selector)) {
                return Optional.of(providerType.cast(observerProvider));
            }
            return Optional.empty();
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            if (providerType == SequencerModeProvider.class) {
                return List.of(MODE_ID);
            }
            if (providerType == L1ObserverProvider.class) {
                return List.of(OBSERVER_TYPE);
            }
            return List.of();
        }
    }

    private static final class ControlledMode implements SequencerMode {
        private String selfKey;

        @Override public String id() { return MODE_ID; }

        @Override
        public void init(SequencerContext context) {
            selfKey = context.selfKeyHex();
        }

        @Override public boolean shouldProposeNow(long height) { return true; }

        @Override
        public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            return ProposalEligibility.ACCEPT;
        }

        @Override
        public Map<String, Object> status() {
            return Map.of("currentProposer", selfKey);
        }
    }

    private static final class ControlledObserver implements L1Observer {
        private final String observerId;
        private final Controls controls;

        private ControlledObserver(String observerId, Controls controls) {
            this.observerId = observerId;
            this.controls = controls;
        }

        @Override public String observerId() { return observerId; }

        @Override
        public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
            Throwable failure = controls.failAgainOnRetry.get()
                    ? controls.failure.get() : controls.failure.getAndSet(null);
            if (failure != null) {
                controls.failureThrown.countDown();
                if (failure instanceof Error error) {
                    throw error;
                }
                throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
            }
            if (controls.blockingSlot.get() == slot) {
                controls.observationEntered.countDown();
                awaitUninterruptibly(controls.releaseObservation);
            }
            controls.observedSlots.add(slot);
            if (controls.suppressObservations.get()) {
                return List.of();
            }
            return List.of(L1Observation.transaction(observerId, fill(32, Math.toIntExact(slot % 256)),
                    slot, blockHash.clone(), new byte[]{1}));
        }
    }

    private record FixedUtxoState(List<Utxo> utxos) implements UtxoState {
        @Override
        public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
            return utxos;
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
            return utxos;
        }

        @Override public Optional<Utxo> getUtxo(Outpoint outpoint) { return Optional.empty(); }

        @Override public boolean isEnabled() { return true; }
    }

    private static final class DirectEventBus implements EventBus {
        private final Map<Class<?>, CopyOnWriteArrayList<DirectSubscription<?>>> subscribers =
                new ConcurrentHashMap<>();
        private final List<Event> published = new CopyOnWriteArrayList<>();

        @Override
        public <E extends Event> SubscriptionHandle subscribe(Class<E> eventType, EventListener<E> listener,
                                                              SubscriptionOptions options) {
            CopyOnWriteArrayList<DirectSubscription<?>> typeSubscribers =
                    subscribers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>());
            DirectSubscription<E> subscription = new DirectSubscription<>(listener);
            typeSubscribers.add(subscription);
            return new SubscriptionHandle() {
                @Override
                public void close() {
                    subscription.active.set(false);
                    typeSubscribers.remove(subscription);
                }

                @Override public boolean isActive() { return subscription.active.get(); }
            };
        }

        @Override
        public <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options) {
            published.add(event);
            List<DirectSubscription<?>> current = subscribers.get(event.getClass());
            if (current == null) {
                return;
            }
            for (DirectSubscription<?> raw : current) {
                @SuppressWarnings("unchecked")
                DirectSubscription<E> subscription = (DirectSubscription<E>) raw;
                if (!subscription.active.get()) {
                    continue;
                }
                try {
                    subscription.listener.onEvent(new EventContext<>() {
                        @Override public E event() { return event; }
                        @Override public EventMetadata metadata() { return metadata; }
                    });
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException("Checked event-listener failure", failure);
                }
            }
        }

        @Override
        public void close() {
            subscribers.values().forEach(list -> list.forEach(subscription -> subscription.active.set(false)));
            subscribers.clear();
        }
    }

    private static final class DirectSubscription<E extends Event> {
        private final EventListener<E> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private DirectSubscription(EventListener<E> listener) {
            this.listener = listener;
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }
}
