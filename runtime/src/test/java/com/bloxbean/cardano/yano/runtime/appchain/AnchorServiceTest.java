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

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(service.status().get("lastError").toString()).contains("fund the anchor wallet");
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

        // Rollback below slot 200 un-confirms the 4..8 anchor → rewind to exactly 3
        // (the fixed every-blocks step would have wrongly rewound to 0)
        service.onL1Rollback(150);
        assertThat(service.lastAnchoredHeight()).isEqualTo(3);
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
