package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ADR app-layer/008.1 I1.5: anchor tx construction — size-based linear fee from
 * protocol params (fallback fee only without them), multi-input selection with
 * a min-UTxO guard on change, validity interval, and precise rollback rewind
 * to the rolled-back anchor's range start.
 */
@Timeout(60)
class AnchorServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AnchorServiceTest.class);
    private static final long MIN_FEE_A = 44;
    private static final long MIN_FEE_B = 155_381;

    @TempDir
    Path tempDir;

    private AppLedgerStore ledger;
    private final List<byte[]> submitted = new ArrayList<>();
    private final long[] tip = {3};

    @BeforeEach
    void setUp() {
        ledger = new AppLedgerStore(tempDir.resolve("anchor-ledger").toString(), log);
    }

    @AfterEach
    void tearDown() {
        ledger.close();
    }

    private AnchorService service(List<Utxo> utxos, boolean withFeeParams, long currentSlot) {
        AppChainConfig.AnchorConfig anchorConfig = new AppChainConfig.AnchorConfig(
                true, "aa".repeat(32), 10, 60, 7014);
        UtxoState utxoState = new FixedUtxoState(utxos);
        AnchorService service = new AnchorService("test-chain", anchorConfig, ledger,
                cbor -> {
                    submitted.add(cbor);
                    return "txhash-" + submitted.size();
                },
                () -> utxoState,
                this::blockAt,
                () -> tip[0],
                42,
                log);
        service.wireFees(
                withFeeParams ? () -> new AnchorService.FeeParams(MIN_FEE_A, MIN_FEE_B) : () -> null,
                () -> currentSlot);
        return service;
    }

    private AppBlock blockAt(long height) {
        return new AppBlock(AppBlock.BLOCK_VERSION, "test-chain", height, new byte[32],
                0, new byte[0], 1_000_000 + height, new byte[32], new byte[32],
                List.of(), new byte[32], FinalityCert.empty());
    }

    private Utxo utxo(int index, long lovelace) {
        return new Utxo(new Outpoint("cc".repeat(32), index), "addr_test", BigInteger.valueOf(lovelace),
                List.of(), null, null, null, null, false, 0, 0, null);
    }

    @Test
    void paramFee_ttl_andMultiInputSelection() throws Exception {
        // First utxo alone cannot cover fee + min-change → two inputs expected
        AnchorService service = service(List.of(utxo(0, 1_100_000), utxo(1, 5_000_000)), true, 500);

        assertThat(service.forceAnchorNow()).isTrue();
        Transaction tx = Transaction.deserialize(submitted.get(0));

        assertThat(tx.getBody().getInputs()).hasSize(2);
        long size = submitted.get(0).length;
        long expectedFee = MIN_FEE_A * size + MIN_FEE_B;
        assertThat(tx.getBody().getFee().longValue()).isEqualTo(expectedFee);
        long sum = 1_100_000 + 5_000_000;
        assertThat(tx.getBody().getOutputs().get(0).getValue().getCoin().longValue())
                .isEqualTo(sum - expectedFee);
        assertThat(tx.getBody().getTtl()).isEqualTo(500 + 7_200);
    }

    @Test
    void fallbackFee_whenParamsUnavailable() throws Exception {
        AnchorService service = service(List.of(utxo(0, 5_000_000)), false, 0);

        assertThat(service.forceAnchorNow()).isTrue();
        Transaction tx = Transaction.deserialize(submitted.get(0));

        assertThat(tx.getBody().getInputs()).hasSize(1);
        assertThat(tx.getBody().getFee().longValue()).isEqualTo(300_000);
        assertThat(tx.getBody().getOutputs().get(0).getValue().getCoin().longValue())
                .isEqualTo(5_000_000 - 300_000);
        assertThat(tx.getBody().getTtl()).isEqualTo(0); // no L1 slot observed → no TTL
    }

    @Test
    void dustWallet_failsWithClearError_neverBuildsInvalidChange() {
        // 1.05M covers the fee but the change would fall below min-UTxO
        AnchorService service = service(List.of(utxo(0, 1_050_000)), true, 500);

        assertThat(service.forceAnchorNow()).isFalse();
        assertThat(submitted).isEmpty();
        assertThat(service.status().get("lastError").toString())
                .isEqualTo("Anchor build/submit failed (errorType="
                        + IllegalStateException.class.getName() + ")");
    }

    @Test
    void submitterAssertionIsRedactedAndDoesNotCancelLaterTicks() {
        String secret = "submit-api-token-must-not-reach-health-or-logs";
        AssertionError submitFailure = new AssertionError(secret);
        AtomicInteger submitCalls = new AtomicInteger();
        Logger safeLog = mock(Logger.class);
        AppChainConfig.AnchorConfig config = new AppChainConfig.AnchorConfig(
                true, "aa".repeat(32), 1, 1, 7014);
        AnchorService service = new AnchorService(
                "test-chain", config, ledger,
                ignored -> {
                    submitCalls.incrementAndGet();
                    throw submitFailure;
                },
                () -> new FixedUtxoState(List.of(utxo(0, 5_000_000))),
                this::blockAt, () -> tip[0], 42, safeLog);
        service.wireFees(() -> new AnchorService.FeeParams(MIN_FEE_A, MIN_FEE_B),
                () -> 500L);

        service.tick();
        service.tick();

        assertThat(submitCalls).hasValue(2);
        assertThat(service.status().get("lastError"))
                .isEqualTo("Anchor build/submit failed (errorType="
                        + AssertionError.class.getName() + ")");
        assertThat(service.status().toString()).doesNotContain(secret);
        verify(safeLog, times(2)).warn("Anchor {} failed (errorType={})",
                "build/submit", AssertionError.class.getName());
    }

    @Test
    void forceAnchorContainsAndRedactsTipAndBlockProviderErrors() {
        String secret = "force-provider-secret-must-not-reach-health-or-logs";
        Logger safeLog = mock(Logger.class);
        AppChainConfig.AnchorConfig config = new AppChainConfig.AnchorConfig(
                true, "aa".repeat(32), 1, 1, 7014);
        AnchorService tipFailure = new AnchorService(
                "test-chain", config, ledger, ignored -> "unused",
                () -> new FixedUtxoState(List.of(utxo(0, 5_000_000))),
                this::blockAt, () -> {
                    throw new AssertionError(secret);
                }, 42, safeLog);

        assertThat(tipFailure.forceAnchorNow()).isFalse();
        assertThat(tipFailure.status().get("lastError"))
                .isEqualTo("Anchor force-anchor failed (errorType="
                        + AssertionError.class.getName() + ")");

        AnchorService blockFailure = new AnchorService(
                "test-chain", config, ledger, ignored -> "unused",
                () -> new FixedUtxoState(List.of(utxo(0, 5_000_000))),
                ignored -> {
                    throw new AssertionError(secret);
                }, () -> tip[0], 42, safeLog);
        assertThat(blockFailure.forceAnchorNow()).isFalse();
        assertThat(blockFailure.status().get("lastError"))
                .isEqualTo("Anchor build/submit failed (errorType="
                        + AssertionError.class.getName() + ")");
        assertThat(tipFailure.status().toString()).doesNotContain(secret);
        assertThat(blockFailure.status().toString()).doesNotContain(secret);
    }

    @Test
    void diagnosticLoggerFailureCannotMaskRecoverableCallbackFailure() {
        Logger hostileLog = mock(Logger.class);
        doThrow(new AssertionError("diagnostic-secret"))
                .when(hostileLog)
                .warn("Anchor {} failed (errorType={})",
                        "force-anchor", AssertionError.class.getName());
        AppChainConfig.AnchorConfig config = new AppChainConfig.AnchorConfig(
                true, "aa".repeat(32), 1, 1, 7014);
        AnchorService service = new AnchorService(
                "test-chain", config, ledger, ignored -> "unused",
                () -> new FixedUtxoState(List.of(utxo(0, 5_000_000))),
                this::blockAt, () -> {
                    throw new AssertionError("callback-secret");
                }, 42, hostileLog);

        assertThat(service.forceAnchorNow()).isFalse();
        assertThat(service.status().get("lastError"))
                .isEqualTo("Anchor force-anchor failed (errorType="
                        + AssertionError.class.getName() + ")");
        assertThat(service.status().toString())
                .doesNotContain("callback-secret", "diagnostic-secret");
    }

    @Test
    void observedConfirmationRetriesOnTickWithoutReplayOrResubmit() {
        AtomicInteger blockLookups = new AtomicInteger();
        LongFunction<AppBlock> transientLookup = height -> {
            int call = blockLookups.incrementAndGet();
            // Submission is lookup 1. The one and only L1 observation is
            // lookup 2 and sees a transiently missing local block.
            return call == 2 ? null : blockAt(height);
        };
        AppChainConfig.AnchorConfig config = new AppChainConfig.AnchorConfig(
                true, "aa".repeat(32), 1, 1, 7014);
        AnchorService service = new AnchorService(
                "test-chain", config, ledger,
                cbor -> {
                    submitted.add(cbor);
                    return "txhash-1";
                },
                () -> new FixedUtxoState(List.of(utxo(0, 5_000_000))),
                transientLookup, () -> tip[0], 42, log);
        service.wireFees(() -> new AnchorService.FeeParams(MIN_FEE_A, MIN_FEE_B), () -> 500L);

        assertThat(service.forceAnchorNow()).isTrue();
        assertThat(service.onL1Block(100, List.of("txhash-1"))).isNull();
        assertThat(service.lastAnchoredHeight()).isZero();
        assertThat(service.status()).containsEntry("confirmationObservedAtL1Slot", 100L);

        service.tick();

        assertThat(service.lastAnchoredHeight()).isEqualTo(3);
        assertThat(service.status()).doesNotContainKey("confirmationObservedAtL1Slot");
        assertThat(submitted).hasSize(1);
        assertThat(blockLookups).hasValue(3);
    }

    @Test
    void l1Rollback_rewindsToRolledBackRangeStart() {
        AnchorService service = service(List.of(utxo(0, 50_000_000)), true, 500);

        // Anchor 1..3, confirm at slot 100
        tip[0] = 3;
        assertThat(service.forceAnchorNow()).isTrue();
        assertThat(service.onL1Block(100, List.of("txhash-1"))).isNotNull();
        assertThat(service.lastAnchoredHeight()).isEqualTo(3);

        // Anchor 4..8 (spans what would be multiple every-blocks intervals), confirm at slot 200
        tip[0] = 8;
        assertThat(service.forceAnchorNow()).isTrue();
        assertThat(service.onL1Block(200, List.of("txhash-2"))).isNotNull();
        assertThat(service.lastAnchoredHeight()).isEqualTo(8);

        // A third confirmation means a rollback to 150 must unwind TWO
        // anchors, not merely rewind the latest range start.
        tip[0] = 10;
        assertThat(service.forceAnchorNow()).isTrue();
        assertThat(service.onL1Block(300, List.of("txhash-3"))).isNotNull();
        assertThat(service.lastAnchoredHeight()).isEqualTo(10);

        // Simulate a service restart: rollback correctness comes from the
        // persisted bounded confirmation history, not in-memory last-slot.
        AnchorService restarted = service(List.of(utxo(0, 50_000_000)), true, 500);
        restarted.onL1Rollback(150);
        assertThat(restarted.lastAnchoredHeight()).isEqualTo(3);
        assertThat(ledger.metaLong("anchor_last_slot", 0)).isEqualTo(100);
        assertThat(ledger.metaString("anchor_last_tx")).isEqualTo("txhash-1");
        assertThat(ledger.metaBytes("anchor_last_block_hash")).hasSize(32);
    }

    @Test
    void rollbackDropsPendingRangeDerivedFromInvalidatedFrontier() {
        AnchorService service = service(List.of(utxo(0, 50_000_000)), true, 500);
        tip[0] = 3;
        assertThat(service.forceAnchorNow()).isTrue();
        assertThat(service.onL1Block(100, List.of("txhash-1"))).isNotNull();
        tip[0] = 8;
        assertThat(service.forceAnchorNow()).isTrue();
        assertThat(service.onL1Block(200, List.of("txhash-2"))).isNotNull();

        tip[0] = 12;
        assertThat(service.forceAnchorNow()).isTrue();
        assertThat(service.status()).containsEntry("pendingRange", "9..12");

        service.onL1Rollback(150);

        assertThat(service.lastAnchoredHeight()).isEqualTo(3);
        assertThat(service.status()).doesNotContainKey("pendingTx");
        assertThat(service.forceAnchorNow()).isTrue();
        assertThat(service.status()).containsEntry("pendingRange", "4..12");
    }

    @Test
    void rollbackOlderThanBoundedHistoryConservativelyResetsToGenesis() {
        List<AnchorService.Confirmation> retained = new ArrayList<>();
        for (int i = 0; i < AnchorService.ConfirmationHistory.MAX_ENTRIES; i++) {
            long height = i + 1L;
            retained.add(new AnchorService.Confirmation(
                    height, height, "tx-" + height, 100L + i, new byte[32]));
        }
        AnchorService.Confirmation latest = retained.getLast();
        ledger.metaPutAll(
                java.util.Map.of(
                        "anchor_last_height", latest.toHeight(),
                        "anchor_last_from_height", latest.fromHeight(),
                        "anchor_last_slot", latest.l1Slot()),
                java.util.Map.of(
                        "anchor_last_block_hash", latest.blockHash(),
                        "anchor_last_tx", latest.txHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "anchor_confirmation_history_v1",
                        AnchorService.ConfirmationHistory.encode(retained)));
        AnchorService service = service(List.of(utxo(0, 50_000_000)), true, 500);

        service.onL1Rollback(50);

        assertThat(service.lastAnchoredHeight()).isZero();
        assertThat(ledger.metaLong("anchor_last_slot", -1)).isZero();
        assertThat(ledger.metaLong("anchor_last_from_height", -1)).isZero();
        assertThat(ledger.metaString("anchor_last_tx")).isEmpty();
        assertThat(ledger.metaBytes("anchor_last_block_hash")).isEmpty();
        assertThat(AnchorService.ConfirmationHistory.decode(
                ledger.metaBytes("anchor_confirmation_history_v1"))).isEmpty();
    }

    private record FixedUtxoState(List<Utxo> utxos) implements UtxoState {
        @Override
        public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
            return utxos;
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
            return utxos;
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return Optional.empty();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
