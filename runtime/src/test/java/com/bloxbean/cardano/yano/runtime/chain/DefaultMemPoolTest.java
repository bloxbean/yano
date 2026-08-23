package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultMemPoolTest {
    private static final String ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
    private static final MempoolAdmissionLimits LIMITS =
            new MempoolAdmissionLimits(100, 1_000_000, 1_000);

    @Test
    void admitsArbitraryDepthChainAgainstOrderedOverlay() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 1);

        byte[] parent = transaction(seed, 200_001, List.of(), List.of());
        Outpoint parentOutput = new Outpoint(TransactionUtil.getTxHash(parent), 0);
        byte[] child = transaction(parentOutput, 200_002, List.of(), List.of());
        Outpoint childOutput = new Outpoint(TransactionUtil.getTxHash(child), 0);
        byte[] grandchild = transaction(childOutput, 200_003, List.of(), List.of());

        assertThat(admit(pool, parent, canonical).status())
                .isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);
        assertThat(admit(pool, child, canonical).status())
                .isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);
        assertThat(admit(pool, grandchild, canonical).status())
                .isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);

        MempoolStats stats = pool.stats();
        assertThat(stats.transactions()).isEqualTo(3);
        assertThat(stats.producedOutputs()).isEqualTo(3);
        assertThat(stats.spentOutpoints()).isEqualTo(3);
        assertThat(stats.dependencyEdges()).isEqualTo(2);
        assertThat(stats.utxoIndexEntries()).isEqualTo(10);
    }

    @Test
    void resolvesBulkSnapshotFromOverlayAndCanonicalStateAndHidesClaims() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint parentSeed = seed(canonical, 30);
        Outpoint canonicalOnly = seed(canonical, 31);
        byte[] parent = transaction(parentSeed, 200_001, List.of(), List.of());
        Outpoint parentOutput = new Outpoint(TransactionUtil.getTxHash(parent), 0);

        assertThat(admit(pool, parent, canonical).accepted()).isTrue();

        Map<Outpoint, Utxo> resolved = pool.resolveUtxos(
                List.of(parentOutput, canonicalOnly), canonical::get);
        assertThat(resolved).containsOnlyKeys(parentOutput, canonicalOnly);
        assertThat(resolved.get(parentOutput).lovelace()).isEqualTo(BigInteger.valueOf(1_000_000));
        assertThatThrownBy(() -> resolved.clear())
                .isInstanceOf(UnsupportedOperationException.class);

        byte[] child = transaction(parentOutput, 200_002, List.of(), List.of());
        assertThat(admit(pool, child, canonical).accepted()).isTrue();

        assertThat(pool.resolveUtxos(List.of(parentOutput, canonicalOnly), canonical::get))
                .containsOnlyKeys(canonicalOnly);
    }

    @Test
    void referenceScriptHashIndexIsBoundedAndRemovedWithItsProducer() throws Exception {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint input = seed(canonical, 35);
        var script = com.bloxbean.cardano.client.plutus.spec.PlutusV2Script.builder()
                .cborHex("49480100002221200101")
                .build();
        var output = TransactionOutput.builder()
                .address(ADDRESS)
                .value(new Value(BigInteger.valueOf(1_000_000), null))
                .scriptRef(script)
                .build();
        byte[] transaction = transaction(input, 200_001, output);
        String transactionHash = TransactionUtil.getTxHash(transaction);
        String scriptHash = com.bloxbean.cardano.yaci.core.util.HexUtil
                .encodeHexString(script.getScriptHash());

        assertThat(admit(pool, transaction, canonical).accepted()).isTrue();
        assertThat(pool.stats().referenceScripts()).isEqualTo(1);
        assertThat(pool.stats().utxoIndexEntries()).isEqualTo(3);

        byte[] resolved = pool.getScriptRefBytesByHash(scriptHash).orElseThrow();
        assertThat(resolved).containsExactly(output.getScriptRef());
        resolved[0] ^= 1;
        assertThat(pool.getScriptRefBytesByHash(scriptHash).orElseThrow())
                .containsExactly(output.getScriptRef());

        assertThat(pool.removeInvalidated(java.util.Set.of(transactionHash))).isEqualTo(1);
        assertThat(pool.getScriptRefBytesByHash(scriptHash)).isEmpty();
        assertThat(pool.stats().utxoIndexEntries()).isZero();
    }

    @Test
    void canonicalSnapshotReadsDoNotHoldAdmissionLane() throws Exception {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint requested = seed(canonical, 32);
        Outpoint concurrentSeed = seed(canonical, 33);
        CountDownLatch storageReadStarted = new CountDownLatch(1);
        CountDownLatch releaseStorageRead = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var resolution = executor.submit(() -> pool.resolveUtxos(List.of(requested), outpoint -> {
                storageReadStarted.countDown();
                try {
                    if (!releaseStorageRead.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("timed out waiting to release storage read");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return canonical.get(outpoint);
            }));

            assertThat(storageReadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var admission = executor.submit(() -> admit(pool,
                    transaction(concurrentSeed, 200_001, List.of(), List.of()), canonical));
            assertThat(admission.get(1, TimeUnit.SECONDS).accepted()).isTrue();

            releaseStorageRead.countDown();
            assertThat(resolution.get(1, TimeUnit.SECONDS)).containsOnlyKeys(requested);
        } finally {
            releaseStorageRead.countDown();
        }
    }

    @Test
    void canonicalSnapshotRecheckHidesClaimCreatedDuringStorageRead() throws Exception {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint requested = seed(canonical, 34);
        CountDownLatch storageReadStarted = new CountDownLatch(1);
        CountDownLatch releaseStorageRead = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var resolution = executor.submit(() -> pool.resolveUtxos(List.of(requested), outpoint -> {
                storageReadStarted.countDown();
                try {
                    if (!releaseStorageRead.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("timed out waiting to release storage read");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return canonical.get(outpoint);
            }));

            assertThat(storageReadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var admission = executor.submit(() -> admit(pool,
                    transaction(requested, 200_001, List.of(), List.of()), canonical));
            assertThat(admission.get(1, TimeUnit.SECONDS).accepted()).isTrue();

            releaseStorageRead.countDown();
            assertThat(resolution.get(1, TimeUnit.SECONDS)).isEmpty();
        } finally {
            releaseStorageRead.countDown();
        }
    }

    @Test
    void canonicalSnapshotRecheckAlsoHidesOverlayHitClaimedDuringStorageRead() throws Exception {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint parentSeed = seed(canonical, 36);
        Outpoint canonicalRead = seed(canonical, 37);
        byte[] parent = transaction(parentSeed, 200_001, List.of(), List.of());
        Outpoint parentOutput = new Outpoint(TransactionUtil.getTxHash(parent), 0);
        assertThat(admit(pool, parent, canonical).accepted()).isTrue();
        CountDownLatch storageReadStarted = new CountDownLatch(1);
        CountDownLatch releaseStorageRead = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var resolution = executor.submit(() -> pool.resolveUtxos(
                    List.of(parentOutput, canonicalRead), outpoint -> {
                        storageReadStarted.countDown();
                        try {
                            if (!releaseStorageRead.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("timed out waiting to release storage read");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return canonical.get(outpoint);
                    }));

            assertThat(storageReadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var admission = executor.submit(() -> admit(pool,
                    transaction(parentOutput, 200_002, List.of(), List.of()), canonical));
            assertThat(admission.get(1, TimeUnit.SECONDS).accepted()).isTrue();

            releaseStorageRead.countDown();
            assertThat(resolution.get(1, TimeUnit.SECONDS)).containsOnlyKeys(canonicalRead);
        } finally {
            releaseStorageRead.countDown();
        }
    }

    @Test
    void duplicateIsIdempotentAndConflictingSpendIsRejected() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 2);
        byte[] first = transaction(seed, 200_001, List.of(), List.of());
        byte[] conflict = transaction(seed, 200_002, List.of(), List.of());
        AtomicInteger acceptedEvents = new AtomicInteger();

        MempoolAdmissionResult accepted = pool.tryAdmit(first, canonical::get,
                resolvingValidator(), LIMITS, ignored -> acceptedEvents.incrementAndGet());
        MempoolAdmissionResult duplicate = pool.tryAdmit(first, canonical::get,
                resolvingValidator(), LIMITS, ignored -> acceptedEvents.incrementAndGet());
        MempoolAdmissionResult rejected = admit(pool, conflict, canonical);

        assertThat(accepted.status()).isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);
        assertThat(duplicate.status()).isEqualTo(MempoolAdmissionResult.Status.DUPLICATE);
        assertThat(rejected.status()).isEqualTo(MempoolAdmissionResult.Status.CONFLICT);
        assertThat(acceptedEvents).hasValue(1);
        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.stats().conflictRejections()).isEqualTo(1);
    }

    @Test
    void confirmingParentKeepsChildAndConvertsDependencyToCanonical() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 3);
        byte[] parent = transaction(seed, 200_001, List.of(), List.of());
        String parentHash = TransactionUtil.getTxHash(parent);
        Outpoint parentOutput = new Outpoint(parentHash, 0);
        byte[] child = transaction(parentOutput, 200_002, List.of(), List.of());

        assertThat(admit(pool, parent, canonical).accepted()).isTrue();
        assertThat(admit(pool, child, canonical).accepted()).isTrue();
        canonical.put(parentOutput, utxo(parentOutput));

        assertThat(pool.removeByTxHashes(java.util.Set.of(parentHash))).isEqualTo(1);
        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.contains(TransactionUtil.getTxHash(child))).isTrue();
        assertThat(pool.stats().dependencyEdges()).isZero();
        assertThat(pool.revalidate(canonical::get)).isZero();
    }

    @Test
    void invalidatingParentCascadesThroughDescendants() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 4);
        byte[] parent = transaction(seed, 200_001, List.of(), List.of());
        String parentHash = TransactionUtil.getTxHash(parent);
        byte[] child = transaction(new Outpoint(parentHash, 0), 200_002, List.of(), List.of());

        assertThat(admit(pool, parent, canonical).accepted()).isTrue();
        assertThat(admit(pool, child, canonical).accepted()).isTrue();

        assertThat(pool.removeInvalidated(java.util.Set.of(parentHash))).isEqualTo(2);
        assertThat(pool.isEmpty()).isTrue();
        assertThat(pool.stats().utxoIndexEntries()).isZero();
        assertThat(pool.stats().cascadedRemovals()).isEqualTo(1);
    }

    @Test
    void referenceAndCollateralInputsCreateDependenciesWithoutSpendClaims() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint parentSeed = seed(canonical, 5);
        Outpoint referenceRegularSeed = seed(canonical, 6);
        Outpoint collateralRegularSeed = seed(canonical, 7);
        byte[] parent = transaction(parentSeed, 200_001, List.of(), List.of());
        Outpoint parentOutput = new Outpoint(TransactionUtil.getTxHash(parent), 0);
        byte[] referenceChild = transaction(
                referenceRegularSeed, 200_002, List.of(parentOutput), List.of());
        byte[] collateralChild = transaction(
                collateralRegularSeed, 200_003, List.of(), List.of(parentOutput));

        assertThat(admit(pool, parent, canonical).accepted()).isTrue();
        assertThat(admit(pool, referenceChild, canonical).accepted()).isTrue();
        assertThat(admit(pool, collateralChild, canonical).accepted()).isTrue();

        assertThat(pool.stats().dependencyEdges()).isEqualTo(2);
        assertThat(pool.stats().spentOutpoints()).isEqualTo(3);
    }

    @Test
    void indexCapacityRejectsNewcomerWithoutPartialState() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 8);
        byte[] transaction = transaction(seed, 200_001, List.of(), List.of());

        MempoolAdmissionResult result = pool.tryAdmit(transaction, canonical::get,
                resolvingValidator(), new MempoolAdmissionLimits(100, 1_000_000, 1), null);

        assertThat(result.status()).isEqualTo(MempoolAdmissionResult.Status.INDEX_CAPACITY);
        assertThat(pool.size()).isZero();
        assertThat(pool.stats().utxoIndexEntries()).isZero();
    }

    @Test
    void transactionAndByteCapacityRejectNewcomerWithoutEvictingExistingEntry() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint firstSeed = seed(canonical, 12);
        Outpoint secondSeed = seed(canonical, 13);
        byte[] first = transaction(firstSeed, 200_001, List.of(), List.of());
        byte[] second = transaction(secondSeed, 200_002, List.of(), List.of());

        assertThat(pool.tryAdmit(first, canonical::get, resolvingValidator(),
                new MempoolAdmissionLimits(1, 1_000_000, 100), null).accepted()).isTrue();
        MempoolAdmissionResult countRejected = pool.tryAdmit(second, canonical::get,
                resolvingValidator(), new MempoolAdmissionLimits(1, 1_000_000, 100), null);

        assertThat(countRejected.status())
                .isEqualTo(MempoolAdmissionResult.Status.TRANSACTION_CAPACITY);
        assertThat(pool.size()).isEqualTo(1);

        DefaultMemPool byteLimited = new DefaultMemPool();
        MempoolAdmissionResult byteRejected = byteLimited.tryAdmit(first, canonical::get,
                resolvingValidator(), new MempoolAdmissionLimits(10, first.length - 1L, 100), null);
        assertThat(byteRejected.status()).isEqualTo(MempoolAdmissionResult.Status.BYTE_CAPACITY);
        assertThat(byteLimited.isEmpty()).isTrue();
    }

    @Test
    void malformedAndLedgerRejectionAreTypedAndLeaveNoState() {
        DefaultMemPool pool = new DefaultMemPool();

        MempoolAdmissionResult malformed = pool.tryAdmit(new byte[] {1, 2, 3}, ignored -> null,
                resolvingValidator(), LIMITS, null);
        assertThat(malformed.status()).isEqualTo(MempoolAdmissionResult.Status.MALFORMED);

        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 14);
        byte[] transaction = transaction(seed, 200_001, List.of(), List.of());
        MempoolAdmissionResult rejected = pool.tryAdmit(transaction, canonical::get,
                (bytes, hash, resolver) -> List.of(
                        new VetoableEvent.Rejection("test", "policy rejected")), LIMITS, null);

        assertThat(rejected.status()).isEqualTo(MempoolAdmissionResult.Status.LEDGER_REJECTED);
        assertThat(rejected.rejections()).extracting(VetoableEvent.Rejection::reason)
                .containsExactly("policy rejected");
        assertThat(pool.isEmpty()).isTrue();
        assertThat(pool.stats().malformedRejections()).isEqualTo(1);
        assertThat(pool.stats().ledgerRejections()).isEqualTo(1);
    }

    @Test
    void ttlRemovalCascadesFromExpiredParent() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 15);
        byte[] parent = transaction(seed, 200_001, List.of(), List.of());
        byte[] child = transaction(
                new Outpoint(TransactionUtil.getTxHash(parent), 0), 200_002, List.of(), List.of());
        assertThat(admit(pool, parent, canonical).accepted()).isTrue();
        assertThat(admit(pool, child, canonical).accepted()).isTrue();

        assertThat(pool.removeOlderThan(Long.MAX_VALUE)).isEqualTo(2);
        assertThat(pool.isEmpty()).isTrue();
        assertThat(pool.stats().utxoIndexEntries()).isZero();
    }

    @Test
    void acceptedEventFailureRollsBackBodyAndEveryIndex() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 9);
        byte[] transaction = transaction(seed, 200_001, List.of(), List.of());

        assertThatThrownBy(() -> pool.tryAdmit(transaction, canonical::get,
                resolvingValidator(), LIMITS,
                ignored -> { throw new IllegalStateException("publish failed"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish failed");

        assertThat(pool.size()).isZero();
        assertThat(pool.byteSize()).isZero();
        assertThat(pool.stats().utxoIndexEntries()).isZero();
    }

    @Test
    void collateralReturnIsNotProjectedAsProducedOutput() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 10);
        byte[] transaction = transactionWithCollateralReturn(seed);

        assertThat(admit(pool, transaction, canonical).accepted()).isTrue();
        assertThat(pool.stats().producedOutputs()).isEqualTo(1);
    }

    @Test
    void callerArrayMutationCannotCorruptStoredBodyHashOrInternalKeys() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 11);
        byte[] transaction = transaction(seed, 200_001, List.of(), List.of());
        byte[] expectedBody = transaction.clone();
        String expectedHash = TransactionUtil.getTxHash(transaction);

        MempoolAdmissionResult result = admit(pool, transaction, canonical);
        transaction[0] ^= 0x01;
        result.transaction().txHash()[0] ^= 0x01;

        assertThat(pool.contains(expectedHash)).isTrue();
        assertThat(pool.getTransaction(expectedHash).txBytes()).containsExactly(expectedBody);
        assertThat(pool.snapshotTransactions(1, Long.MAX_VALUE).getFirst().txBytes())
                .containsExactly(expectedBody);
    }

    @Test
    void admissionResolverExpiresWhenSynchronousValidationReturns() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 16);
        byte[] transaction = transaction(seed, 200_001, List.of(), List.of());
        AtomicReference<Function<Outpoint, Utxo>> captured = new AtomicReference<>();

        MempoolAdmissionResult result = pool.tryAdmit(transaction, canonical::get,
                (bytes, hash, resolver) -> {
                    captured.set(resolver);
                    assertThat(resolver.apply(seed)).isNotNull();
                    return List.of();
                }, LIMITS, null);

        assertThat(result.accepted()).isTrue();
        assertThatThrownBy(() -> captured.get().apply(seed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer active");
    }

    @Test
    void canonicalCompetingSpendInvalidatesMempoolChain() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint seed = seed(canonical, 17);
        byte[] parent = transaction(seed, 200_001, List.of(), List.of());
        String parentHash = TransactionUtil.getTxHash(parent);
        byte[] child = transaction(new Outpoint(parentHash, 0), 200_002, List.of(), List.of());
        assertThat(admit(pool, parent, canonical).accepted()).isTrue();
        assertThat(admit(pool, child, canonical).accepted()).isTrue();

        var competingBody = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .txHash("ab".repeat(32))
                .inputs(java.util.Set.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionInput.builder()
                                .transactionId(seed.txHash()).index(seed.index()).build()))
                .outputs(List.of())
                .build();
        Block block = Block.builder().era(Era.Conway)
                .transactionBodies(List.of(competingBody))
                .invalidTransactions(List.of())
                .build();

        new DefaultMempoolEvictionPolicy(pool, 0, 100)
                .onBlockApplied(new BlockAppliedEvent(
                        Era.Conway, 1, 1, "cd".repeat(32), block));

        assertThat(pool.isEmpty()).isTrue();
        assertThat(pool.stats().utxoIndexEntries()).isZero();
        assertThat(pool.stats().cascadedRemovals()).isEqualTo(1);
    }

    @Test
    void phaseTwoInvalidCanonicalTransactionOnlyConflictsOnCollateral() {
        DefaultMemPool pool = new DefaultMemPool();
        Map<Outpoint, Utxo> canonical = new HashMap<>();
        Outpoint regular = seed(canonical, 18);
        Outpoint collateral = seed(canonical, 19);
        byte[] regularSpender = transaction(regular, 200_001, List.of(), List.of());
        byte[] collateralSpender = transaction(collateral, 200_002, List.of(), List.of());
        assertThat(admit(pool, regularSpender, canonical).accepted()).isTrue();
        assertThat(admit(pool, collateralSpender, canonical).accepted()).isTrue();

        var invalidBody = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .txHash("ef".repeat(32))
                .inputs(java.util.Set.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionInput.builder()
                                .transactionId(regular.txHash()).index(regular.index()).build()))
                .collateralInputs(java.util.Set.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionInput.builder()
                                .transactionId(collateral.txHash()).index(collateral.index()).build()))
                .outputs(List.of())
                .build();
        Block block = Block.builder().era(Era.Conway)
                .transactionBodies(List.of(invalidBody))
                .invalidTransactions(List.of(0))
                .build();

        new DefaultMempoolEvictionPolicy(pool, 0, 100)
                .onBlockApplied(new BlockAppliedEvent(
                        Era.Conway, 1, 1, "01".repeat(32), block));

        assertThat(pool.contains(TransactionUtil.getTxHash(regularSpender))).isTrue();
        assertThat(pool.contains(TransactionUtil.getTxHash(collateralSpender))).isFalse();
    }

    private static MempoolAdmissionResult admit(DefaultMemPool pool, byte[] tx,
                                                 Map<Outpoint, Utxo> canonical) {
        return pool.tryAdmit(tx, canonical::get, resolvingValidator(), LIMITS, null);
    }

    private static MemPool.AdmissionValidator resolvingValidator() {
        return (txBytes, ignoredHash, resolver) -> {
            try {
                Transaction transaction = Transaction.deserialize(txBytes);
                List<TransactionInput> inputs = new ArrayList<>();
                if (transaction.getBody().getInputs() != null)
                    inputs.addAll(transaction.getBody().getInputs());
                if (transaction.getBody().getReferenceInputs() != null)
                    inputs.addAll(transaction.getBody().getReferenceInputs());
                if (transaction.getBody().getCollateral() != null)
                    inputs.addAll(transaction.getBody().getCollateral());
                for (TransactionInput input : inputs) {
                    Outpoint outpoint = new Outpoint(input.getTransactionId(), input.getIndex());
                    if (resolver.apply(outpoint) == null) {
                        return List.of(new VetoableEvent.Rejection("test", "missing " + outpoint));
                    }
                }
                return List.of();
            } catch (Exception e) {
                return List.of(new VetoableEvent.Rejection("test", e.getMessage()));
            }
        };
    }

    private static byte[] transaction(Outpoint regularInput, long fee,
                                      List<Outpoint> referenceInputs,
                                      List<Outpoint> collateralInputs) {
        try {
            TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                    .inputs(List.of(input(regularInput)))
                    .outputs(List.of(TransactionOutput.builder()
                            .address(ADDRESS)
                            .value(new Value(BigInteger.valueOf(1_000_000), null))
                            .build()))
                    .fee(BigInteger.valueOf(fee));
            if (!referenceInputs.isEmpty())
                body.referenceInputs(referenceInputs.stream().map(DefaultMemPoolTest::input).toList());
            if (!collateralInputs.isEmpty())
                body.collateral(collateralInputs.stream().map(DefaultMemPoolTest::input).toList());
            return Transaction.builder().body(body.build()).build().serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] transaction(Outpoint regularInput, long fee,
                                      TransactionOutput output) {
        try {
            TransactionBody body = TransactionBody.builder()
                    .inputs(List.of(input(regularInput)))
                    .outputs(List.of(output))
                    .fee(BigInteger.valueOf(fee))
                    .build();
            return Transaction.builder().body(body).build().serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TransactionInput input(Outpoint outpoint) {
        return TransactionInput.builder()
                .transactionId(outpoint.txHash()).index(outpoint.index()).build();
    }

    private static byte[] transactionWithCollateralReturn(Outpoint regularInput) {
        try {
            TransactionOutput normal = TransactionOutput.builder()
                    .address(ADDRESS).value(new Value(BigInteger.valueOf(1_000_000), null)).build();
            TransactionOutput collateralReturn = TransactionOutput.builder()
                    .address(ADDRESS).value(new Value(BigInteger.valueOf(500_000), null)).build();
            TransactionBody body = TransactionBody.builder()
                    .inputs(List.of(input(regularInput)))
                    .outputs(List.of(normal))
                    .fee(BigInteger.valueOf(200_000))
                    .collateralReturn(collateralReturn)
                    .totalCollateral(BigInteger.valueOf(500_000))
                    .build();
            return Transaction.builder().body(body).build().serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Outpoint seed(Map<Outpoint, Utxo> canonical, int discriminator) {
        Outpoint outpoint = new Outpoint(String.format("%064x", discriminator), 0);
        canonical.put(outpoint, utxo(outpoint));
        return outpoint;
    }

    private static Utxo utxo(Outpoint outpoint) {
        return new Utxo(outpoint, ADDRESS, BigInteger.valueOf(2_000_000),
                List.of(), null, null, null, null, false, 1, 1, "block");
    }
}
