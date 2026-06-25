package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YanoWorkflowHelpersTest {
    private static final NodeStatus HEALTHY = TestkitFakes.status(true, false);

    @Test
    void withSnapshotRestoresAfterSuccessfulAction() throws Exception {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(HEALTHY);
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.from(node)) {
            String result = kit.snapshots().withSnapshot("baseline", () -> "done");

            assertEquals("done", result);
            assertEquals(List.of("baseline"), node.devnet.snapshots.stream().map(snapshot -> snapshot.name()).toList());
            assertEquals(List.of("baseline"), node.devnet.restoredSnapshots);
        }
    }

    @Test
    void withSnapshotRestoresAfterFailingAction() {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(HEALTHY);
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.from(node)) {
            assertThrows(IllegalStateException.class, () -> kit.snapshots().withSnapshot("baseline", () -> {
                throw new IllegalStateException("boom");
            }));

            assertEquals(List.of("baseline"), node.devnet.restoredSnapshots);
        }
    }

    @Test
    void advanceToEpochUsesConfiguredEpochLength() {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(HEALTHY, List.of(), tip(150, 12));
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.from(node)) {
            kit.time().advanceToEpoch(3);

            assertEquals(List.of(300L), node.devnet.targetSlots);
        }
    }

    @Test
    void crossEpochBoundaryAdvancesToNextEpoch() {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(HEALTHY, List.of(), tip(150, 12));
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.from(node)) {
            kit.time().crossEpochBoundary();

            assertEquals(List.of(200L), node.devnet.targetSlots);
        }
    }

    @Test
    void epochAssertionsUseCurrentEpoch() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY, List.of(), tip(250, 20))) {
            kit.assertions().epochAtLeast(2);
            assertThrows(AssertionError.class, () -> kit.assertions().epochAtLeast(3));
        }
    }

    @Test
    void realDevnetDefaultConfigCanCrossEpochBoundary() {
        YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
                .epochLength(3)
                .build();
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(config)) {
            kit.start();

            kit.time().crossEpochBoundary();
            kit.assertions().epochAtLeast(1);
        }
    }

    @Test
    void submitCopiesCborAndReturnsTxHash() {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(HEALTHY);
        node.txGateway.txHash = "submitted";
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.from(node)) {
            byte[] txCbor = new byte[]{1, 2, 3};

            String txHash = kit.transactions().submit(txCbor);
            txCbor[0] = 9;

            assertEquals("submitted", txHash);
            assertArrayEquals(new byte[]{1, 2, 3}, node.txGateway.submittedCbor.getFirst());
        }
    }

    @Test
    void submitAndAwaitWaitsForVisibleOutput() {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(HEALTHY,
                List.of(utxo("submitted", 0, "addr_test1", 1_000_000L)));
        node.txGateway.txHash = "submitted";

        try (YanoDevnetTestKit kit = YanoDevnetTestKit.from(node)) {
            assertEquals("submitted", kit.transactions().submitAndAwait(new byte[]{1}));
        }
    }

    @Test
    void evaluateUsesGatewayWhenAvailable() throws Exception {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(HEALTHY);
        node.txEvaluationGateway.available = true;
        node.txEvaluationGateway.results = new java.util.ArrayList<>(
                List.of(new TxEvaluationResult("spend", 0, 10, 20)));

        try (YanoDevnetTestKit kit = YanoDevnetTestKit.from(node)) {
            List<TxEvaluationResult> result = kit.transactions().evaluate(new byte[]{1});

            assertEquals(node.txEvaluationGateway.results, result);
            assertThrows(UnsupportedOperationException.class, () -> result.add(new TxEvaluationResult("mint", 1, 1, 1)));
        }
    }

    @Test
    void transactionsRejectInvalidOrUnavailableEvaluation() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY)) {
            assertThrows(IllegalArgumentException.class, () -> kit.transactions().submit(new byte[0]));
            assertThrows(UnsupportedOperationException.class, () -> kit.transactions().evaluate(new byte[]{1}));
        }
    }

    private static ChainTip tip(long slot, long blockNumber) {
        return new ChainTip(slot, new byte[]{1, 2, 3}, blockNumber);
    }

    private static Utxo utxo(String txHash, int index, String address, long lovelace) {
        return new Utxo(
                new Outpoint(txHash, index),
                address,
                BigInteger.valueOf(lovelace),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                0,
                0,
                null);
    }
}
