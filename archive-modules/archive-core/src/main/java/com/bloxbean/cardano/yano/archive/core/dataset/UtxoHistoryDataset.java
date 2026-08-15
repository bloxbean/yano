package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.core.address.SequentialPointerResolver;
import com.bloxbean.cardano.yano.archive.core.address.SequentialPointerResolver.PointerCoordinate;
import com.bloxbean.cardano.yano.archive.core.address.SequentialPointerResolver.ResolvedStakeCredential;
import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;

import java.util.*;
import java.util.function.Consumer;

/** Normalized UTXO history with an archive-private, rollback-aware pointer resolver. */
public final class UtxoHistoryDataset implements LiveStatefulBlockArchiveDataset<UtxoHistoryFact> {
    private final RocksDbHotHistoryStore state;
    private final SequentialPointerResolver pointers;
    private final ArchiveTrack track;
    private List<BlockSourceContext<UtxoHistoryFact>> blocks = List.of();
    private List<HotBlockUpdate> updates = List.of();
    private Map<PointerCoordinate, ResolvedStakeCredential> pendingPointers = Map.of();
    private Set<PointerCoordinate> pendingDeletedPointers = Set.of();
    private int blockIndex;

    /** Projection-only constructor used by isolated schema tests. */
    public UtxoHistoryDataset() {
        this.state = null;
        this.pointers = null;
        this.track = ArchiveTrack.BACKFILL;
    }

    public UtxoHistoryDataset(RocksDbHotHistoryStore state, String namespace, ArchiveTrack track) {
        this.state = Objects.requireNonNull(state, "state");
        this.pointers = new SequentialPointerResolver(state, ArchiveDatasetId.UTXO_HISTORY, namespace);
        this.track = Objects.requireNonNull(track, "track");
    }

    @Override public ArchiveDatasetId dataset() { return ArchiveDatasetId.UTXO_HISTORY; }
    @Override public int projectionVersion() { return ArchiveSchemas.schema(dataset()).projectionVersion(); }

    @Override
    public void beginBatch(ArchiveJob job, List<BlockSourceContext<UtxoHistoryFact>> blocks) {
        this.blocks = List.copyOf(blocks);
        this.updates = new ArrayList<>(blocks.size());
        this.pendingPointers = new HashMap<>();
        this.pendingDeletedPointers = new HashSet<>();
        this.blockIndex = 0;
    }

    @Override
    public void derive(ArchiveJob job, BlockSourceContext<UtxoHistoryFact> block, Consumer<ArchiveRow> sink) {
        if (state != null && (blockIndex >= blocks.size()
                || blocks.get(blockIndex).blockNumber() != block.blockNumber())) {
            throw new IllegalStateException("UTXO history dataset batch order changed");
        }
        var facts = block.block();
        List<HotHistoryMutation> pointerMutations = new ArrayList<>();
        if (state != null) {
            List<PointerLifecycle> lifecycle = new ArrayList<>();
            facts.pointerRegistrations().forEach(value -> lifecycle.add(new PointerLifecycle(
                    value.txIndex(), value.certIndex(), value, null)));
            facts.pointerDeregistrations().forEach(value -> lifecycle.add(new PointerLifecycle(
                    value.txIndex(), value.certIndex(), null, value)));
            lifecycle.sort(Comparator.comparingInt(PointerLifecycle::txIndex)
                    .thenComparingInt(PointerLifecycle::certIndex));
            for (PointerLifecycle event : lifecycle) {
                if (event.registration() != null) {
                    var registration = event.registration();
                    PointerCoordinate coordinate = new PointerCoordinate(registration.slot(), registration.txIndex(),
                            registration.certIndex());
                    ResolvedStakeCredential credential = new ResolvedStakeCredential(
                            registration.credentialType(), registration.credential());
                    ResolvedStakeCredential previous = pendingPointers.putIfAbsent(coordinate, credential);
                    if (previous != null && (!previous.type().equals(credential.type())
                            || !Arrays.equals(previous.hash(), credential.hash()))) {
                        throw new ArchiveStoreException("conflicting pointer registration at " + coordinate);
                    }
                    pendingDeletedPointers.remove(coordinate);
                    pointerMutations.addAll(pointers.putMutations(coordinate, credential));
                } else {
                    var deregistration = event.deregistration();
                    var credential = new ResolvedStakeCredential(
                            deregistration.credentialType(), deregistration.credential());
                    for (var pending : new ArrayList<>(pendingPointers.entrySet())) {
                        if (sameCredential(pending.getValue(), credential)) {
                            pendingDeletedPointers.add(pending.getKey());
                            pointerMutations.addAll(pointers.deleteMutations(pending.getKey(), pending.getValue()));
                            pendingPointers.remove(pending.getKey());
                        }
                    }
                    var deletion = pointers.deleteCredential(credential);
                    pendingDeletedPointers.addAll(deletion.coordinates());
                    pointerMutations.addAll(deletion.mutations());
                }
            }
        }

        Map<String, ResolvedAddress> resolvedAddresses = new HashMap<>();
        for (var address : facts.newAddresses()) {
            ResolvedAddress resolved = resolveAddress(facts.era(), address);
            resolvedAddresses.put(HexUtil.encodeHexString(address.addressKey()), resolved);
            // The address dimension describes only structure encoded in the
            // address bytes. Pointer resolution is ledger-time dependent: the
            // same raw pointer address can resolve before deregistration and
            // be unresolved afterwards. Keep that temporal result on each
            // transaction output instead of turning it into a false dimension
            // conflict.
            var value = address;
            sink.accept(new ArchiveRow("addresses", Arrays.asList(
                    value.addressKey(), value.rawAddress(), value.displayAddress(), value.networkId(),
                    value.addressType(), value.paymentCredentialType(), value.paymentCredential(),
                    value.stakeReferenceType(), value.stakeCredentialType(), value.stakeCredential(),
                    value.pointerSlot(), value.pointerTxIndex(), value.pointerCertIndex(),
                    block.blockNumber(), block.slot(), block.epoch())));
        }
        for (var output : facts.outputs()) {
            ResolvedAddress address = resolvedAddresses.get(HexUtil.encodeHexString(output.addressKey()));
            byte[] stakeCredential = address == null ? output.stakeCredential() : address.stakeCredential();
            sink.accept(new ArchiveRow("transaction_outputs", Arrays.asList(
                    output.txHash(), output.outputIndex(), output.txIndex(), output.originType(), output.addressKey(),
                    output.paymentCredential(), stakeCredential, output.lovelace(), output.datumKind(),
                    output.datumHash(), output.inlineDatumCbor(), output.referenceScriptHash(),
                    output.referenceScriptType(), output.referenceScriptCbor(), output.collateralReturn(),
                    block.blockHash(), block.blockNumber(), block.slot(), block.epoch(),
                    block.blockTime().getEpochSecond(), job.jobId())));
        }
        for (var asset : facts.assets()) sink.accept(new ArchiveRow("transaction_output_assets", Arrays.asList(
                asset.txHash(), asset.outputIndex(), asset.policyId(), asset.assetName(), asset.quantity(),
                block.blockNumber(), block.slot(), block.epoch(), job.jobId())));
        for (var input : facts.inputs()) sink.accept(new ArchiveRow("transaction_inputs", Arrays.asList(
                input.spendingTxHash(), input.spendingTxIndex(), input.inputIndex(), input.inputRole(),
                input.referencedTxHash(), input.referencedOutputIndex(), input.consumesOutput(), block.blockHash(),
                block.blockNumber(), block.slot(), block.epoch(), block.blockTime().getEpochSecond(), job.jobId())));
        for (var datum : facts.transactionDatums()) sink.accept(new ArchiveRow("transaction_datums", List.of(
                datum.txHash(), datum.txIndex(), datum.datumHash(), datum.datumCbor(), block.blockHash(),
                block.blockNumber(), block.slot(), block.epoch(), block.blockTime().getEpochSecond(), job.jobId())));
        for (var redeemer : facts.transactionRedeemers()) sink.accept(new ArchiveRow("transaction_redeemers", Arrays.asList(
                redeemer.txHash(), redeemer.txIndex(), redeemer.purpose(), redeemer.redeemerIndex(),
                redeemer.redeemerCbor(), redeemer.redeemerDataHash(), redeemer.executionMem(),
                redeemer.executionSteps(), block.blockHash(), block.blockNumber(), block.slot(), block.epoch(),
                block.blockTime().getEpochSecond(), job.jobId())));

        if (state != null) {
            updates.add(new HotBlockUpdate(new HotBlockCheckpoint(block.blockNumber(), block.slot(),
                    block.blockHash(), block.parentHash()), pointerMutations));
            blockIndex++;
        }
    }

    private ResolvedAddress resolveAddress(int era, UtxoHistoryFact.Address address) {
        if (!"pointer".equals(address.stakeReferenceType())) {
            return new ResolvedAddress(address, address.stakeCredential());
        }
        if (era >= Era.Conway.getValue()) {
            return new ResolvedAddress(copyAddress(address, "pointer_not_effective", null, null), null);
        }
        if (pointers == null) {
            throw new ArchiveStoreException("pre-Conway pointer address requires a sequential archive resolver");
        }
        PointerCoordinate coordinate = new PointerCoordinate(address.pointerSlot(), address.pointerTxIndex(),
                address.pointerCertIndex());
        ResolvedStakeCredential credential = pendingDeletedPointers.contains(coordinate) ? null
                : Optional.ofNullable(pendingPointers.get(coordinate))
                        .or(() -> pointers.resolve(coordinate)).orElse(null);
        if (credential == null) {
            return new ResolvedAddress(copyAddress(address, "pointer_unresolved", null, null), null);
        }
        return new ResolvedAddress(copyAddress(address, "pointer_resolved", credential.type(), credential.hash()),
                credential.hash());
    }

    private static UtxoHistoryFact.Address copyAddress(UtxoHistoryFact.Address source, String referenceType,
                                                       String credentialType, byte[] credential) {
        return new UtxoHistoryFact.Address(source.addressKey(), source.rawAddress(), source.displayAddress(),
                source.networkId(), source.addressType(), source.paymentCredentialType(),
                source.paymentCredential(), referenceType, credentialType, credential,
                source.pointerSlot(), source.pointerTxIndex(), source.pointerCertIndex());
    }

    @Override
    public void commitBatch(ArchiveReceipt receipt) {
        if (state == null) return;
        BlockSourceContext<UtxoHistoryFact> last = blocks.getLast();
        state.applyBlocks(dataset(), updates, new ArchiveProgress(dataset(), track, last.blockNumber(),
                last.slot(), last.blockHash(), receipt.backendGeneration()), receipt);
        clear();
    }

    @Override
    public void commitCoveredBatch(long backendGeneration) {
        if (state == null) return;
        BlockSourceContext<UtxoHistoryFact> last = blocks.getLast();
        state.applyBlocks(dataset(), updates, new ArchiveProgress(dataset(), track, last.blockNumber(),
                last.slot(), last.blockHash(), backendGeneration), null);
        clear();
    }

    @Override
    public void commitLiveBatch(List<List<HotHistoryMutation>> rowMutations) {
        if (state == null || track != ArchiveTrack.LIVE || rowMutations.size() != updates.size()) {
            throw new IllegalStateException("invalid UTXO history live batch");
        }
        List<HotBlockUpdate> combined = new ArrayList<>(updates.size());
        for (int i = 0; i < updates.size(); i++) {
            List<HotHistoryMutation> mutations = new ArrayList<>(updates.get(i).mutations());
            mutations.addAll(rowMutations.get(i));
            combined.add(new HotBlockUpdate(updates.get(i).checkpoint(), mutations));
        }
        BlockSourceContext<UtxoHistoryFact> last = blocks.getLast();
        state.applyBlocks(dataset(), combined, new ArchiveProgress(dataset(), ArchiveTrack.LIVE,
                last.blockNumber(), last.slot(), last.blockHash(), 0), null);
        clear();
    }

    @Override public void abortBatch() { clear(); }

    private void clear() {
        blocks = List.of();
        updates = List.of();
        pendingPointers = Map.of();
        pendingDeletedPointers = Set.of();
        blockIndex = 0;
    }

    private record ResolvedAddress(UtxoHistoryFact.Address address, byte[] stakeCredential) { }
    private record PointerLifecycle(int txIndex, int certIndex,
                                    UtxoHistoryFact.PointerRegistration registration,
                                    UtxoHistoryFact.PointerDeregistration deregistration) { }

    private static boolean sameCredential(ResolvedStakeCredential left, ResolvedStakeCredential right) {
        return left.type().equals(right.type()) && Arrays.equals(left.hash(), right.hash());
    }
}
