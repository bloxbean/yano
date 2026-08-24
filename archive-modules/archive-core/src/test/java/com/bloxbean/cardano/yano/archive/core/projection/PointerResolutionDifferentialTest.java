package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.PointerCoordinate;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.PointerCredential;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential and semantic tests for ADR-039 as-of pointer resolution.
 *
 * <p>The oracle is the **real** shipped sequential archive resolver, backed by a live
 * {@code RocksDbHotHistoryStore}. Comparing two resolver-less paths would be tautological for
 * pointer addresses, which is how the original crash-on-pointer defect survived a full parity
 * suite.
 *
 * <p>The subject is capture-time resolution against the authoritative registration mapping and
 * the derived deregistration index, evaluated as of the block being projected.
 */
class PointerResolutionDifferentialTest {
    @TempDir Path temp;

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "fixture-genesis");
    private static final String CRED_A = "77".repeat(28);
    private static final String CRED_B = "88".repeat(28);

    /**
     * Models the account-state store: an append-only registration map plus a coordinate-keyed
     * deregistration index. Deliberately mirrors the real key semantics rather than the real
     * storage, so the interval logic is what is under test.
     */
    static final class FakeAuthoritativeSource implements PointerCredentialSource {
        private final TreeMap<PointerCoordinate, PointerCredential> registrations = new TreeMap<>();
        private final List<Object[]> deregistrations = new ArrayList<>(); // [credential, coordinate]
        private IndexCompleteness completeness = IndexCompleteness.COMPLETE;

        FakeAuthoritativeSource register(long slot, int tx, int cert, String hash) {
            registrations.put(new PointerCoordinate(slot, tx, cert), new PointerCredential(0, hash));
            return this;
        }

        FakeAuthoritativeSource deregister(long slot, int tx, int cert, String hash) {
            deregistrations.add(new Object[]{new PointerCredential(0, hash),
                    new PointerCoordinate(slot, tx, cert)});
            return this;
        }

        FakeAuthoritativeSource completeness(IndexCompleteness value) {
            this.completeness = value;
            return this;
        }

        @Override public Optional<PointerCredential> registrationAt(PointerCoordinate coordinate) {
            return Optional.ofNullable(registrations.get(coordinate));
        }

        @Override public boolean deregisteredWithin(PointerCredential credential, PointerCoordinate after,
                                                    PointerCoordinate through) {
            return deregistrations.stream().anyMatch(entry ->
                    entry[0].equals(credential)
                            && ((PointerCoordinate) entry[1]).compareTo(after) > 0
                            && ((PointerCoordinate) entry[1]).compareTo(through) <= 0);
        }

        @Override public IndexCompleteness completeness() {
            return completeness;
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static UtxoHistoryFact.Address pointerAddress(long slot, int tx, int cert) {
        return new UtxoHistoryFact.Address(new byte[]{1, 2, 3}, new byte[]{4}, "addr_test_pointer",
                0, "ptr", "key", new byte[]{9}, "pointer", null, null, slot, tx, cert);
    }

    private static UtxoHistoryFact.Output output() {
        return new UtxoHistoryFact.Output(new byte[]{0x11}, 0, 0, "output", new byte[]{1, 2, 3},
                new byte[]{9}, null, 1_000_000L, "none", null, null, null, null, null, false);
    }

    private static UtxoHistoryFact.PointerRegistration registration(long slot, int tx, int cert, String hash) {
        return new UtxoHistoryFact.PointerRegistration(slot, tx, cert, "key", HexUtil.decodeHexString(hash));
    }

    private static UtxoHistoryFact.PointerDeregistration deregistration(int tx, int cert, String hash) {
        return new UtxoHistoryFact.PointerDeregistration(tx, cert, "key", HexUtil.decodeHexString(hash));
    }

    private static UtxoHistoryFact fact(int era, List<UtxoHistoryFact.PointerRegistration> regs,
                                        List<UtxoHistoryFact.PointerDeregistration> deregs,
                                        UtxoHistoryFact.Address address) {
        return new UtxoHistoryFact(era, regs, deregs, List.of(address), List.of(output()),
                List.of(), List.of(), List.of(), List.of());
    }

    private static ArchiveJob job() {
        return ArchiveJob.deterministic(NETWORK, ArchiveDatasetId.UTXO_HISTORY, 5, new BlockRange(1, 1),
                new ArchiveRangeAnchor(100, new byte[]{1}, 100, new byte[]{1}), "v1");
    }

    private static BlockSourceContext<UtxoHistoryFact> ctx(UtxoHistoryFact fact, long block, long slot) {
        return new BlockSourceContext<>(block, slot, 0, Instant.EPOCH, new byte[]{(byte) block},
                new byte[]{(byte) (block - 1)}, fact);
    }

    private static List<String> render(List<ArchiveRow> rows) {
        return rows.stream().filter(r -> r.table().equals("transaction_outputs"))
                .map(r -> r.values().stream()
                        .map(v -> v instanceof byte[] b ? HexUtil.encodeHexString(b) : String.valueOf(v))
                        .collect(Collectors.joining(",")))
                .toList();
    }

    /** Subject: resolve as of the block, then derive rows with no resolver at all. */
    private static List<String> subject(UtxoHistoryFact fact, long block, long slot,
                                        PointerCredentialSource source) {
        var resolved = ProjectionPointerResolution.resolve(fact, slot, source);
        List<ArchiveRow> rows = new ArrayList<>();
        new UtxoHistoryDataset().derive(job(), ctx(resolved, block, slot), rows::add);
        return render(rows);
    }

    /** Oracle: the shipped sequential resolver over an ordered sequence of blocks. */
    private List<String> oracleLastBlock(String store, List<BlockSourceContext<UtxoHistoryFact>> blocks) {
        try (var state = new RocksDbHotHistoryStore(temp.resolve(store))) {
            var dataset = new UtxoHistoryDataset(state, ArchiveTrack.BACKFILL);
            dataset.beginBatch(job(), blocks);
            List<ArchiveRow> last = null;
            for (BlockSourceContext<UtxoHistoryFact> block : blocks) {
                List<ArchiveRow> rows = new ArrayList<>();
                dataset.derive(job(), block, rows::add);
                last = rows;
            }
            return render(last);
        }
    }

    private static boolean resolvedTo(List<String> rows, String hash) {
        return rows.size() == 1 && rows.get(0).contains(hash);
    }

    // ------------------------------------------------- core as-of semantics

    @Test
    void registrationThenLaterDeregistrationThenUseIsUnresolved() {
        // The divergence that motivated this work: the ledger drops the pointer on
        // deregistration, so a later use must not resolve.
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A).deregister(75, 0, 0, CRED_A);
        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));

        var oracle = oracleLastBlock("dereg-then-use", List.of(
                ctx(fact(Era.Babbage.getValue(), List.of(registration(50, 0, 0, CRED_A)), List.of(),
                        pointerAddress(50, 0, 0)), 1, 50),
                ctx(new UtxoHistoryFact(Era.Babbage.getValue(), List.of(),
                        List.of(deregistration(0, 0, CRED_A)), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of()), 2, 75),
                ctx(using, 3, 100)));

        assertThat(subject(using, 3, 100, source)).isEqualTo(oracle);
        assertThat(resolvedTo(subject(using, 3, 100, source), CRED_A)).isFalse();
    }

    @Test
    void useBeforeDeregistrationIsResolved() {
        // Same chain, but projected at a block before the deregistration coordinate.
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A).deregister(75, 0, 0, CRED_A);
        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));
        assertThat(resolvedTo(subject(using, 2, 60, source), CRED_A)).isTrue();
    }

    @Test
    void aPreviousBlockCoordinateDeregisteredInThisBlockDoesNotFallThrough() {
        // The registration comes from the authoritative source; the deregistration is only in
        // this block's overlay. Resolving the former without consulting the latter would
        // wrongly resolve.
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A);
        var thisBlock = fact(Era.Babbage.getValue(),
                List.of(registration(100, 9, 9, CRED_B)),   // supplies this block's slot
                List.of(deregistration(0, 0, CRED_A)),
                pointerAddress(50, 0, 0));
        assertThat(resolvedTo(subject(thisBlock, 3, 100, source), CRED_A)).isFalse();
    }

    @Test
    void registrationThenDeregistrationInOneBlock() {
        var source = new FakeAuthoritativeSource();
        var block = fact(Era.Babbage.getValue(),
                List.of(registration(100, 0, 0, CRED_A)),
                List.of(deregistration(1, 0, CRED_A)),
                pointerAddress(100, 0, 0));
        var oracle = oracleLastBlock("reg-dereg-one-block", List.of(ctx(block, 1, 100)));
        assertThat(subject(block, 1, 100, source)).isEqualTo(oracle);
        assertThat(resolvedTo(subject(block, 1, 100, source), CRED_A)).isFalse();
    }

    @Test
    void deregistrationThenReRegistrationInOneBlockLeavesTheOldCoordinateDead() {
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A);
        var block = fact(Era.Babbage.getValue(),
                List.of(registration(100, 2, 0, CRED_A)),      // re-registration, new coordinate
                List.of(deregistration(1, 0, CRED_A)),
                pointerAddress(50, 0, 0));                      // the OLD coordinate
        assertThat(resolvedTo(subject(block, 3, 100, source), CRED_A))
                .as("re-registration must never reactivate an older coordinate")
                .isFalse();
    }

    @Test
    void theNewCoordinateFromAReRegistrationResolves() {
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A);
        var block = fact(Era.Babbage.getValue(),
                List.of(registration(100, 2, 0, CRED_A)),
                List.of(deregistration(1, 0, CRED_A)),
                pointerAddress(100, 2, 0));                     // the NEW coordinate
        assertThat(resolvedTo(subject(block, 3, 100, source), CRED_A)).isTrue();
    }

    @Test
    void anOldCoordinateStaysUnresolvedAfterCrossBlockReRegistration() {
        var source = new FakeAuthoritativeSource()
                .register(50, 0, 0, CRED_A)
                .deregister(75, 0, 0, CRED_A)
                .register(90, 0, 0, CRED_A);
        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));
        assertThat(resolvedTo(subject(using, 5, 200, source), CRED_A)).isFalse();

        var usingNew = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(90, 0, 0));
        assertThat(resolvedTo(subject(usingNew, 5, 200, source), CRED_A)).isTrue();
    }

    @Test
    void multipleEventsForOneCredentialInTheSameSlotDoNotCollide() {
        // Two deregistrations in one slot, different transactions. A slot-only key would keep
        // one and lose the other; the full coordinate distinguishes them.
        var source = new FakeAuthoritativeSource()
                .register(50, 0, 0, CRED_A)
                .deregister(75, 0, 0, CRED_A)
                .deregister(75, 4, 0, CRED_A);
        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));
        assertThat(resolvedTo(subject(using, 5, 100, source), CRED_A)).isFalse();

        // A coordinate registered between the two same-slot deregistrations is killed by the
        // later one only.
        var between = new FakeAuthoritativeSource()
                .register(75, 2, 0, CRED_A)
                .deregister(75, 0, 0, CRED_A)
                .deregister(75, 4, 0, CRED_A);
        var usingBetween = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(75, 2, 0));
        assertThat(resolvedTo(subject(usingBetween, 5, 100, between), CRED_A)).isFalse();
    }

    @Test
    void anUnrelatedCredentialsDeregistrationDoesNotInvalidate() {
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A).deregister(75, 0, 0, CRED_B);
        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));
        assertThat(resolvedTo(subject(using, 5, 100, source), CRED_A)).isTrue();
    }

    @Test
    void aMissingMappingIsUnresolvedRatherThanThrowing() {
        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(10, 0, 0));
        var rows = subject(using, 3, 100, new FakeAuthoritativeSource());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("null");
    }

    @Test
    void conwayPointerReferencesAreNotEffective() {
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A);
        var using = fact(Era.Conway.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));
        var oracle = oracleLastBlock("conway", List.of(ctx(using, 1, 100)));
        assertThat(subject(using, 1, 100, source)).isEqualTo(oracle);
        assertThat(resolvedTo(subject(using, 1, 100, source), CRED_A)).isFalse();
    }

    // ------------------------------------------------------ replay determinism

    @Test
    void aFutureCoordinateStaysUnresolved() {
        var source = new FakeAuthoritativeSource().register(9_000, 0, 0, CRED_A);
        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(9_000, 0, 0));
        assertThat(resolvedTo(subject(using, 3, 100, source), CRED_A)).isFalse();
    }

    @Test
    void replayAgainstAdvancedCoreStateReproducesTheFirstPass() {
        // At capture, only the registration existed. Later the chain deregistered and
        // re-registered. Replaying the same block must give the capture-time answer.
        var atCapture = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A);
        var advanced = new FakeAuthoritativeSource()
                .register(50, 0, 0, CRED_A)
                .deregister(5_000, 0, 0, CRED_A)
                .register(6_000, 0, 0, CRED_A)
                .deregister(7_000, 1, 1, CRED_B);

        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));
        assertThat(subject(using, 3, 100, advanced)).isEqualTo(subject(using, 3, 100, atCapture));
        assertThat(resolvedTo(subject(using, 3, 100, advanced), CRED_A)).isTrue();
    }

    @Test
    void replayIsDeterministicAcrossRepeatedResolution() {
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A).deregister(75, 0, 0, CRED_A);
        var using = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));
        var first = subject(using, 3, 100, source);
        for (int i = 0; i < 10; i++) {
            assertThat(subject(using, 3, 100, source)).isEqualTo(first);
        }
    }

    @Test
    void resolutionIsIdempotent() {
        var source = new FakeAuthoritativeSource().register(50, 0, 0, CRED_A);
        var f = fact(Era.Babbage.getValue(), List.of(), List.of(), pointerAddress(50, 0, 0));
        var once = ProjectionPointerResolution.resolve(f, 100, source);
        var twice = ProjectionPointerResolution.resolve(once, 100, source);

        List<ArchiveRow> a = new ArrayList<>();
        List<ArchiveRow> b = new ArrayList<>();
        new UtxoHistoryDataset().derive(job(), ctx(once, 3, 100), a::add);
        new UtxoHistoryDataset().derive(job(), ctx(twice, 3, 100), b::add);
        assertThat(render(b)).isEqualTo(render(a));
    }

    @Test
    void nonPointerAddressesAreUntouched() {
        var plain = new UtxoHistoryFact.Address(new byte[]{1, 2, 3}, new byte[]{4}, "addr_test_plain",
                0, "base", "key", new byte[]{9}, "credential", "key",
                HexUtil.decodeHexString(CRED_A), null, null, null);
        var f = new UtxoHistoryFact(Era.Babbage.getValue(), List.of(), List.of(), List.of(plain),
                List.of(output()), List.of(), List.of(), List.of(), List.of());
        assertThat(ProjectionPointerResolution.resolve(f, 100, new FakeAuthoritativeSource())).isSameAs(f);
    }
}
