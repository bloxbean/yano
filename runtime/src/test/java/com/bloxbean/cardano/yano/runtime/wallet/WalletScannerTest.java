package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.api.chain.ChainPoint;
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
    private static final ChainPoint P1 = new ChainPoint(1, 10, "11".repeat(32));
    private static final ChainPoint P2 = new ChainPoint(2, 20, "22".repeat(32));

    @Test void historicalExtendedAddressScansAndResumesByStakeCredential() {
        Backend backend = new Backend();
        WalletCredential stake = new WalletCredential("stake", "key", WalletCredentialsTest.STAKE);
        byte[] filter = CredentialFilter.encode(new byte[16], List.of(stake.filterElement()));
        backend.records.set(0, new WalletIndexStore.FilterRecord(P1, filter));
        backend.records.set(1, new WalletIndexStore.FilterRecord(P2, filter));
        TransactionOutput output = TransactionOutput.builder().address(WalletCredentialsTest.HISTORICAL_ADDRESS)
                .amounts(List.of(Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(2_000_000)).build())).build();
        backend.blocks.set(0, Block.builder().transactionBodies(List.of(TransactionBody.builder()
                .txHash(hash(1)).inputs(Set.of()).outputs(List.of(output)).build())).build());
        var coverage = new WalletIndexCoverage(true, true, ChainPoint.ORIGIN, P2, "test", null);
        List<WalletScanEvent> events = drain(new WalletScanner(backend,
                new WalletScanRequest(1, List.of(stake), ChainPoint.ORIGIN, P2, List.of()), coverage, P2));
        var transactions = events.stream().filter(e -> e.type().equals("transaction")).toList();
        assertThat(transactions).extracting(WalletScanEvent::txHash).containsExactly(hash(1), hash(2));
        Utxo known = transactions.getFirst().outputs().getFirst();
        assertThat(known.address()).isEqualTo(WalletCredentialsTest.HISTORICAL_ADDRESS);
        var resumed = drain(new WalletScanner(backend,
                new WalletScanRequest(1, List.of(stake), P1, P2, List.of(known)), coverage, P2));
        assertThat(resumed.stream().filter(e -> e.type().equals("transaction")).toList())
                .containsExactly(transactions.getLast());
        assertThat(events.getLast().type()).isEqualTo("done");
        assertThat(resumed.getLast().type()).isEqualTo("done");
    }

    @Test void fullAndResumedScansFindOutgoingOnlyTransactionWithAssets() {
        Backend backend = new Backend();
        WalletScanner full = scanner(backend, ChainPoint.ORIGIN, null, P2);
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

    @Test void assetNamesUseCanonicalHexIncludingEmptyAndNonUtf8Bytes() {
        Backend backend = new Backend();
        TransactionOutput created = TransactionOutput.builder().address(transaction(1, false, 1).getOutputs().getFirst().getAddress()).amounts(List.of(
                Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(2_000_000)).build(),
                Amount.builder().unit("aa".repeat(28) + "57616c6c6574313139").policyId("aa".repeat(28)).assetNameBytes(HexUtil.decodeHexString("57616c6c6574313139")).assetName("Wallet119").quantity(BigInteger.TEN).build(),
                Amount.builder().unit("bb".repeat(28) + "ff00").policyId("bb".repeat(28)).assetNameBytes(new byte[]{(byte) 255, 0}).assetName("lossy display").quantity(BigInteger.ONE).build(),
                Amount.builder().unit("cc".repeat(28)).policyId("cc".repeat(28)).assetNameBytes(new byte[0]).assetName("").quantity(BigInteger.TWO).build())).build();
        TransactionBody tx = TransactionBody.builder().txHash(hash(1)).outputs(List.of(created)).inputs(Set.of()).build();
        backend.blocks.set(0, Block.builder().transactionBodies(List.of(tx)).build());
        var output = drain(scanner(backend, ChainPoint.ORIGIN, null, P1)).stream()
                .filter(e -> e.type().equals("transaction")).findFirst().orElseThrow().outputs().getFirst();
        assertThat(output.assets()).extracting(a -> a.policyId() + a.assetName()).containsExactly(
                "aa".repeat(28) + "57616c6c6574313139", "bb".repeat(28) + "ff00", "cc".repeat(28));
        assertThat(output.assets()).extracting(a -> a.quantity()).containsExactly(BigInteger.TEN, BigInteger.ONE, BigInteger.TWO);
    }

    @Test void falsePositiveDoesNotEmitAnUnrelatedTransaction() {
        Backend backend = new Backend();
        backend.blocks.set(0, Block.builder().transactionBodies(List.of(transaction(1, false, 2))).build());
        assertThat(drain(scanner(backend, ChainPoint.ORIGIN, null, P1)))
                .noneMatch(e -> e.type().equals("transaction"));
        assertThat(backend.bodyReads).isEqualTo(1);
    }

    @Test void filterMissSkipsBodyRead() {
        Backend backend = new Backend();
        backend.records.set(0, new WalletIndexStore.FilterRecord(P1, CredentialFilter.encode(new byte[16], List.of())));
        drain(scanner(backend, ChainPoint.ORIGIN, null, P1));
        assertThat(backend.bodyReads).isZero();
    }

    @Test void partialBlockReturnsGoodOutputsAndIncompleteTerminalEvenWhenFilterMisses() {
        Backend backend = new Backend();
        var good = transaction(1, false, 1).getOutputs().getFirst();
        var bad = TransactionOutput.builder().address("not-an-address").amounts(good.getAmounts()).build();
        backend.blocks.set(0, Block.builder().transactionBodies(List.of(TransactionBody.builder()
                .txHash(hash(1)).inputs(Set.of()).outputs(List.of(bad, good)).build())).build());
        backend.records.set(0, new WalletIndexStore.FilterRecord(P1,
                CredentialFilter.encode(new byte[16], List.of()), "bad output"));
        var events = drain(scanner(backend, ChainPoint.ORIGIN, null, P2));
        assertThat(events).anyMatch(e -> e.type().equals("transaction") && e.txHash().equals(hash(1)));
        assertThat(events.stream().filter(e -> e.type().equals("warning")).toList()).singleElement()
                .satisfies(e -> assertThat(e.point()).isEqualTo(P1));
        assertThat(events).noneMatch(e -> e.type().equals("done"));
        assertThat(events.getLast().type()).isEqualTo("incomplete");
        assertThat(events.getLast().complete()).isFalse();
        // Starting after the gap is complete when the caller supplies the boundary state.
        var after = drain(scanner(backend, P1, List.of(), P2));
        assertThat(after.getLast().type()).isEqualTo("done");
        assertThat(after.getLast().complete()).isTrue();
    }

    @Test void scanBeforeGapRemainsCompleteButUnmarkedRuntimeParsingFailureIsPartial() {
        Backend backend = new Backend();
        backend.records.set(1, new WalletIndexStore.FilterRecord(P2,
                CredentialFilter.encode(new byte[16], List.of()), "bad output"));
        assertThat(drain(scanner(backend, ChainPoint.ORIGIN, null, P1)).getLast().complete()).isTrue();
        var good = transaction(1, false, 1).getOutputs().getFirst();
        var bad = TransactionOutput.builder().address("not-an-address").amounts(good.getAmounts()).build();
        backend.blocks.set(0, Block.builder().transactionBodies(List.of(TransactionBody.builder()
                .txHash(hash(1)).inputs(Set.of()).outputs(List.of(bad, good)).build())).build());
        var events = drain(scanner(backend, ChainPoint.ORIGIN, null, P1));
        assertThat(events.getLast().complete()).isFalse();
        assertThat(events).anyMatch(e -> e.type().equals("transaction"));
    }

    @Test void rollbackPreventsSuccessfulCompletion() {
        Backend backend = new Backend();
        WalletScanner scanner = scanner(backend, ChainPoint.ORIGIN, null, P2);
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
        WalletScanner scanner = scanner(backend, ChainPoint.ORIGIN, null, P2);
        scanner.next();
        assertThatThrownBy(scanner::next).isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing filter");
        assertThat(scanner.finished()).isFalse();
    }

    @Test void resumedRequestMustExplicitlyProvideState() {
        assertThatThrownBy(() -> new WalletScanRequest(1, List.of(MINE), P1, P2, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("knownOutputs");
    }

    private static WalletScanner scanner(Backend backend, ChainPoint after, List<Utxo> known, ChainPoint to) {
        return new WalletScanner(backend, new WalletScanRequest(1, List.of(MINE), after, to, known),
                new WalletIndexCoverage(true, true, ChainPoint.ORIGIN, P2, "test", null), to);
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
        @Override public Block block(ChainPoint point) { bodyReads++; return blocks.get((int) point.blockNumber() - 1); }
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
