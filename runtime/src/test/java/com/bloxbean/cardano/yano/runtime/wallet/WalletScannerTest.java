package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.api.wallet.WalletChainPoint;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.api.wallet.WalletIndexCoverage;
import com.bloxbean.cardano.yano.api.wallet.WalletScanEvent;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRequest;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRollbackException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletScannerTest {
    private static final WalletCredential MINE = new WalletCredential("payment", "key", "01".repeat(28));
    private static final WalletChainPoint P1 = new WalletChainPoint(1, 10, "11".repeat(32));
    private static final WalletChainPoint P2 = new WalletChainPoint(2, 20, "22".repeat(32));

    @Test void fullAndResumedScansFindOutgoingOnlyTransactionWithAssets() {
        Backend backend = new Backend();
        WalletScanner full = scanner(backend, WalletChainPoint.ORIGIN, null, P2);
        List<WalletScanEvent> events = drain(full);
        List<WalletScanEvent> transactions = events.stream().filter(e -> e.type().equals("transaction")).toList();
        assertThat(transactions).extracting(WalletScanEvent::txHash).containsExactly(hash(1), hash(2));
        assertThat(transactions.getFirst().outputs().getFirst().assets()).singleElement()
                .satisfies(asset -> assertThat(asset.quantity()).isEqualTo(BigInteger.TEN));
        Utxo known = transactions.getFirst().outputs().getFirst();
        List<WalletScanEvent> resumed = drain(scanner(new Backend(), P1, List.of(known), P2));
        assertThat(resumed.stream().filter(e -> e.type().equals("transaction")).toList())
                .containsExactly(transactions.getLast());
        assertThat(events.getLast().type()).isEqualTo("done");
        assertThat(events.getLast().point()).isEqualTo(P2);
    }

    @Test void falsePositiveDoesNotEmitAnUnrelatedTransaction() {
        Backend backend = new Backend();
        backend.blocks.set(0, Block.builder().transactionBodies(List.of(transaction(1, false, 2))).build());
        assertThat(drain(scanner(backend, WalletChainPoint.ORIGIN, null, P1)))
                .noneMatch(e -> e.type().equals("transaction"));
        assertThat(backend.bodyReads).isEqualTo(1);
    }

    @Test void filterMissSkipsBodyRead() {
        Backend backend = new Backend();
        backend.records.set(0, new WalletIndexStore.FilterRecord(P1, CredentialFilter.encode(new byte[16], List.of())));
        drain(scanner(backend, WalletChainPoint.ORIGIN, null, P1));
        assertThat(backend.bodyReads).isZero();
    }

    @Test void rollbackPreventsSuccessfulCompletion() {
        Backend backend = new Backend();
        WalletScanner scanner = scanner(backend, WalletChainPoint.ORIGIN, null, P2);
        assertThat(scanner.next()).extracting(WalletScanEvent::type).containsExactly("ready");
        backend.rolledBack = true;
        assertThatThrownBy(scanner::next).isInstanceOf(WalletScanRollbackException.class);
        assertThat(scanner.finished()).isFalse();
        scanner.close();
        assertThat(scanner.finished()).isTrue();
    }

    @Test void missingFilterCannotProduceDone() {
        Backend backend = new Backend();
        backend.records.removeFirst();
        WalletScanner scanner = scanner(backend, WalletChainPoint.ORIGIN, null, P2);
        scanner.next();
        assertThatThrownBy(scanner::next).isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing filter");
        assertThat(scanner.finished()).isFalse();
    }

    @Test void resumedRequestMustExplicitlyProvideState() {
        assertThatThrownBy(() -> new WalletScanRequest(1, List.of(MINE), P1, P2, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("knownOutputs");
    }

    private static WalletScanner scanner(Backend backend, WalletChainPoint after, List<Utxo> known, WalletChainPoint to) {
        return new WalletScanner(backend, new WalletScanRequest(1, List.of(MINE), after, to, known),
                new WalletIndexCoverage(true, true, WalletChainPoint.ORIGIN, P2, "test", null), to);
    }

    private static List<WalletScanEvent> drain(WalletScanner scanner) {
        try (scanner) {
            List<WalletScanEvent> events = new ArrayList<>();
            while (!scanner.finished()) events.addAll(scanner.next());
            return events;
        }
    }

    private static final class Backend implements WalletScanner.Backend {
        private final List<WalletIndexStore.FilterRecord> records = new ArrayList<>();
        private final List<Block> blocks = new ArrayList<>();
        private boolean rolledBack;
        private int bodyReads;

        private Backend() {
            byte[] filter = CredentialFilter.encode(new byte[16], List.of(MINE.filterElement()));
            records.add(new WalletIndexStore.FilterRecord(P1, filter));
            records.add(new WalletIndexStore.FilterRecord(P2, filter));
            blocks.add(Block.builder().transactionBodies(List.of(transaction(1, false, 1))).build());
            blocks.add(Block.builder().transactionBodies(List.of(transaction(2, true, 2))).build());
        }
        @Override public void validate() {
            if (rolledBack) throw new WalletScanRollbackException("Test rollback");
        }
        @Override public List<WalletIndexStore.FilterRecord> filters(long after, long to, int limit) {
            return records.stream().filter(r -> r.point().blockNumber() > after && r.point().blockNumber() <= to).limit(limit).toList();
        }
        @Override public Block block(WalletChainPoint point) { bodyReads++; return blocks.get((int) point.blockNumber() - 1); }
        @Override public List<Utxo> genesis() { return List.of(); }
    }

    private static TransactionBody transaction(int id, boolean spend, int owner) {
        byte[] raw = new byte[57];
        Arrays.fill(raw, 1, 29, (byte) owner);
        TransactionOutput output = TransactionOutput.builder().address(new Address(raw).toBech32())
                .amounts(List.of(Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(2_000_000)).build(),
                        Amount.builder().unit("aa".repeat(28) + "01").policyId("aa".repeat(28)).assetName("01").quantity(BigInteger.TEN).build())).build();
        return TransactionBody.builder().txHash(hash(id)).outputs(List.of(output))
                .inputs(spend ? Set.of(TransactionInput.builder().transactionId(hash(1)).index(0).build()) : Set.of()).build();
    }
    private static String hash(int value) { return "%064x".formatted(value); }
}
