package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.ByronAddress;
import com.bloxbean.cardano.client.address.PointerAddress;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.util.AddressKeyUtil;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.core.address.*;
import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.core.worker.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Resolver-independent-from-core, sequential address history projection. */
public final class AddressTransactionDataset implements LiveStatefulBlockArchiveDataset<Block> {
    private final HotHistoryStore state;
    private SequentialOutpointResolver durableResolver;
    private SequentialPointerResolver pointerResolver;
    private final String resolverNamespace;
    private final AddressKeyCodec addressKeys;
    private final ArchiveTrack track;
    private List<BlockSourceContext<Block>> blocks = List.of();
    private List<HotBlockUpdate> updates = List.of();
    private Map<Outpoint, ResolvedOutput> pendingPuts = Map.of();
    private Set<Outpoint> pendingDeletes = Set.of();
    private Map<SequentialPointerResolver.PointerCoordinate,
            SequentialPointerResolver.ResolvedStakeCredential> pendingPointers = Map.of();
    private Set<SequentialPointerResolver.PointerCoordinate> pendingDeletedPointers = Set.of();
    private int blockIndex;

    public AddressTransactionDataset(HotHistoryStore state, AddressKeyCodec addressKeys) {
        this(state, addressKeys, "backfill", ArchiveTrack.BACKFILL);
    }

    public AddressTransactionDataset(HotHistoryStore state, AddressKeyCodec addressKeys,
                                     String resolverNamespace, ArchiveTrack track) {
        this.state = Objects.requireNonNull(state, "state");
        this.resolverNamespace = resolverNamespace;
        this.durableResolver = new SequentialOutpointResolver(state, resolverNamespace);
        this.pointerResolver = new SequentialPointerResolver(state, ArchiveDatasetId.ADDRESS_TRANSACTION,
                resolverNamespace);
        this.addressKeys = Objects.requireNonNull(addressKeys, "addressKeys");
        this.track = Objects.requireNonNull(track, "track");
    }

    public void seedGenesis(Iterable<SequentialOutpointResolver.Entry> outputs) {
        durableResolver.seedGenesis(outputs);
    }

    public void seedResolver(Iterable<SequentialOutpointResolver.Entry> outputs, boolean complete) {
        durableResolver.seedEntries(outputs, complete);
    }

    public void completeResolverSeed(long baseBlock) {
        durableResolver.completeSeed(baseBlock);
    }

    public boolean resolverSeeded() {
        return durableResolver.isSeeded();
    }

    public OptionalLong resolverBaseBlock() {
        return durableResolver.seedBaseBlock();
    }

    /** Clears an invalid UTXO base before reseeding from an authoritative source. */
    public void resetResolver() {
        state.deleteDataPrefix(dataset(), ("resolver/" + resolverNamespace + "/")
                .getBytes(StandardCharsets.UTF_8));
        state.deleteDataPrefix(dataset(), ("pointer/" + resolverNamespace + "/")
                .getBytes(StandardCharsets.UTF_8));
        durableResolver = new SequentialOutpointResolver(state, resolverNamespace);
        pointerResolver = new SequentialPointerResolver(state, ArchiveDatasetId.ADDRESS_TRANSACTION,
                resolverNamespace);
        clear();
    }

    @Override public ArchiveDatasetId dataset() { return ArchiveDatasetId.ADDRESS_TRANSACTION; }
    @Override public int projectionVersion() { return ArchiveSchemas.schema(dataset()).projectionVersion(); }

    @Override
    public void beginBatch(ArchiveJob job, List<BlockSourceContext<Block>> blocks) {
        this.blocks = List.copyOf(blocks);
        this.updates = new ArrayList<>(blocks.size());
        this.pendingPuts = new HashMap<>();
        this.pendingDeletes = new HashSet<>();
        this.pendingPointers = new HashMap<>();
        this.pendingDeletedPointers = new HashSet<>();
        this.blockIndex = 0;
    }

    @Override
    public void derive(ArchiveJob job, BlockSourceContext<Block> context, Consumer<ArchiveRow> sink) {
        if (blockIndex >= blocks.size() || blocks.get(blockIndex).blockNumber() != context.blockNumber()) {
            throw new IllegalStateException("address dataset batch order changed");
        }
        List<HotHistoryMutation> resolverMutations = new ArrayList<>();
        Block block = context.block();
        Set<Integer> invalid = block.getInvalidTransactions() == null
                ? Set.of() : Set.copyOf(block.getInvalidTransactions());
        List<TransactionBody> transactions = block.getTransactionBodies() == null
                ? List.of() : block.getTransactionBodies();
        int era = block.getEra() == null ? Era.Conway.getValue() : block.getEra().getValue();
        if (era < Era.Conway.getValue()) {
            for (int txIndex = 0; txIndex < transactions.size(); txIndex++) {
                if (invalid.contains(txIndex)) continue;
                TransactionBody tx = transactions.get(txIndex);
                if (tx.getCertificates() == null) continue;
                for (int certIndex = 0; certIndex < tx.getCertificates().size(); certIndex++) {
                    var certificate = tx.getCertificates().get(certIndex);
                    if (certificate instanceof StakeRegistration registration
                            && registration.getStakeCredential() != null) {
                        var model = registration.getStakeCredential();
                        var coordinate = new SequentialPointerResolver.PointerCoordinate(
                                context.slot(), txIndex, certIndex);
                        var credential = new SequentialPointerResolver.ResolvedStakeCredential(
                                model.getType().name().equals("ADDR_KEYHASH") ? "key" : "script",
                                HexUtil.decodeHexString(model.getHash()));
                        var previous = pendingPointers.putIfAbsent(coordinate, credential);
                        if (previous != null && (!previous.type().equals(credential.type())
                                || !Arrays.equals(previous.hash(), credential.hash()))) {
                            throw new ArchiveStoreException("conflicting pointer registration at " + coordinate);
                        }
                        pendingDeletedPointers.remove(coordinate);
                        resolverMutations.addAll(pointerResolver.putMutations(coordinate, credential));
                    } else if (certificate instanceof StakeDeregistration
                            || certificate instanceof UnregCert) {
                        var model = certificate instanceof StakeDeregistration deregistration
                                ? deregistration.getStakeCredential()
                                : ((UnregCert) certificate).getStakeCredential();
                        if (model == null) continue;
                        var credential = new SequentialPointerResolver.ResolvedStakeCredential(
                                model.getType().name().equals("ADDR_KEYHASH") ? "key" : "script",
                                HexUtil.decodeHexString(model.getHash()));
                        for (var pending : new ArrayList<>(pendingPointers.entrySet())) {
                            if (pending.getValue().type().equals(credential.type())
                                    && Arrays.equals(pending.getValue().hash(), credential.hash())) {
                                resolverMutations.addAll(pointerResolver.deleteMutations(
                                        pending.getKey(), pending.getValue()));
                                pendingDeletedPointers.add(pending.getKey());
                                pendingPointers.remove(pending.getKey());
                            }
                        }
                        var deletion = pointerResolver.deleteCredential(credential);
                        pendingDeletedPointers.addAll(deletion.coordinates());
                        resolverMutations.addAll(deletion.mutations());
                    }
                }
            }
        }
        for (int txIndex = 0; txIndex < transactions.size(); txIndex++) {
            TransactionBody tx = transactions.get(txIndex);
            boolean valid = !invalid.contains(txIndex);
            byte[] txHash = HexUtil.decodeHexString(tx.getTxHash());
            LinkedHashMap<SubjectKey, SubjectRoles> subjects = new LinkedHashMap<>();

            var consumed = valid ? tx.getInputs() : tx.getCollateralInputs();
            if (consumed != null) {
                for (var input : consumed) {
                    Outpoint outpoint = new Outpoint(HexUtil.decodeHexString(input.getTransactionId()), input.getIndex());
                    ResolvedOutput resolved = resolve(outpoint).orElseThrow(() -> new ArchiveStoreException(
                            "unresolved address-history input " + input.getTransactionId() + '#' + input.getIndex()));
                    addSubjects(subjects, resolved,
                            valid ? ParticipationRole.INPUT : ParticipationRole.COLLATERAL_INPUT);
                    pendingPuts.remove(outpoint);
                    pendingDeletes.add(outpoint);
                    resolverMutations.add(durableResolver.deleteMutation(outpoint));
                }
            }

            if (valid && tx.getOutputs() != null) {
                for (int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                    addOutput(txHash, outputIndex, tx.getOutputs().get(outputIndex), era, subjects,
                            resolverMutations, ParticipationRole.OUTPUT);
                }
            } else if (!valid && tx.getCollateralReturn() != null) {
                addOutput(txHash, tx.getOutputs() == null ? 0 : tx.getOutputs().size(), tx.getCollateralReturn(), era,
                        subjects, resolverMutations, ParticipationRole.COLLATERAL_RETURN);
            }
            for (SubjectRoles roles : subjects.values()) {
                AddressSubject subject = roles.subject;
                sink.accept(new ArchiveRow("address_transactions", List.of(subject.subjectType(),
                        subject.subjectKey(), txHash, context.blockHash(), context.blockNumber(), context.slot(),
                        context.epoch(), context.blockTime().getEpochSecond(), txIndex, roles.inputCount,
                        roles.outputCount, roles.collateralInputCount, roles.collateralReturnCount, job.jobId())));
            }
        }
        updates.add(new HotBlockUpdate(new HotBlockCheckpoint(context.blockNumber(), context.slot(),
                context.blockHash(), context.parentHash()), resolverMutations));
        blockIndex++;
    }

    private void addOutput(byte[] txHash, int index, TransactionOutput output, int era,
                           Map<SubjectKey, SubjectRoles> subjects,
                           List<HotHistoryMutation> mutations, ParticipationRole role) {
        AddressParts parts = address(output.getAddress(), era);
        ResolvedOutput resolved = new ResolvedOutput(parts.addressKey(), parts.paymentCredential(),
                parts.stakeCredential());
        Outpoint outpoint = new Outpoint(txHash, index);
        if (pendingPuts.containsKey(outpoint)) {
            throw new ArchiveStoreException("duplicate address-history outpoint "
                    + HexUtil.encodeHexString(txHash) + '#' + index);
        }
        if (!pendingDeletes.contains(outpoint)) {
            Optional<ResolvedOutput> existing = durableResolver.resolve(outpoint);
            if (existing.isPresent()) {
                if (!sameOutput(existing.orElseThrow(), resolved)) {
                    throw new ArchiveStoreException("conflicting address-history outpoint "
                            + HexUtil.encodeHexString(txHash) + '#' + index);
                }
                // An interrupted rollback/replay can present the already-applied
                // resolver value. Identical content is idempotent; a different
                // value for the same outpoint remains fatal.
                addSubjects(subjects, resolved, role);
                return;
            }
        }
        pendingDeletes.remove(outpoint);
        pendingPuts.put(outpoint, resolved);
        mutations.add(durableResolver.putMutation(outpoint, resolved));
        addSubjects(subjects, resolved, role);
    }

    private static boolean sameOutput(ResolvedOutput left, ResolvedOutput right) {
        return Arrays.equals(left.addressKey(), right.addressKey())
                && Arrays.equals(left.paymentCredential(), right.paymentCredential())
                && Arrays.equals(left.stakeCredential(), right.stakeCredential());
    }

    private Optional<ResolvedOutput> resolve(Outpoint outpoint) {
        if (pendingDeletes.contains(outpoint)) return Optional.empty();
        ResolvedOutput pending = pendingPuts.get(outpoint);
        return pending != null ? Optional.of(pending) : durableResolver.resolve(outpoint);
    }

    private static void addSubjects(Map<SubjectKey, SubjectRoles> subjects, ResolvedOutput output,
                                    ParticipationRole role) {
        add(subjects, "address", output.addressKey(), role);
        add(subjects, "payment_credential", output.paymentCredential(), role);
        add(subjects, "stake_credential", output.stakeCredential(), role);
    }

    private static void add(Map<SubjectKey, SubjectRoles> subjects, String type, byte[] key,
                            ParticipationRole role) {
        if (key == null) return;
        subjects.computeIfAbsent(new SubjectKey(type, key), ignored ->
                new SubjectRoles(new AddressSubject(type, key))).increment(role);
    }

    public AddressParts address(String display) {
        return address(display, Era.Conway.getValue());
    }

    public AddressParts address(String display, int era) {
        try {
            byte[] raw;
            try {
                raw = AddressUtil.addressToBytes(display);
            } catch (Exception notTextEncoded) {
                raw = HexUtil.decodeHexString(display);
            }
            if (raw.length == 0) throw new IllegalArgumentException("empty address");
            Address parsed;
            try {
                parsed = new Address(raw);
            } catch (Exception notShelleyAddress) {
                // Bootstrap addresses are valid canonical archive subjects but do
                // not expose payment or staking credentials. Validate their CBOR
                // envelope/checksum with the dedicated Byron codec rather than
                // treating every value rejected by the Shelley parser as Byron.
                ByronAddress byron = new ByronAddress(raw);
                return new AddressParts(byron.getBytes(), addressKeys.key(byron.getBytes()), null, null);
            }
            byte[] stake = AddressKeyUtil.stakeCred28(display);
            if (parsed.getAddressType().name().equalsIgnoreCase("ptr")) {
                if (era >= Era.Conway.getValue()) {
                    stake = null;
                } else {
                    var pointer = new PointerAddress(raw).getPointer();
                    var coordinate = new SequentialPointerResolver.PointerCoordinate(pointer.getSlot(),
                            pointer.getTxIndex(), pointer.getCertIndex());
                    var credential = pendingDeletedPointers.contains(coordinate) ? null
                            : Optional.ofNullable(pendingPointers.get(coordinate))
                                    .or(() -> pointerResolver.resolve(coordinate)).orElse(null);
                    stake = credential == null ? null : credential.hash();
                }
            }
            return new AddressParts(raw, addressKeys.key(raw), AddressKeyUtil.paymentCred28(display), stake);
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot decode canonical output address", e);
        }
    }

    @Override
    public void commitBatch(ArchiveReceipt receipt) {
        BlockSourceContext<Block> last = blocks.getLast();
        state.applyBlocks(dataset(), updates, new ArchiveProgress(dataset(), track,
                last.blockNumber(), last.slot(), last.blockHash(), receipt.backendGeneration()), receipt);
        clear();
    }

    @Override
    public void commitCoveredBatch(long backendGeneration) {
        BlockSourceContext<Block> last = blocks.getLast();
        state.applyBlocks(dataset(), updates, new ArchiveProgress(dataset(), track,
                last.blockNumber(), last.slot(), last.blockHash(), backendGeneration), null);
        clear();
    }

    @Override
    public void commitLiveBatch(List<List<HotHistoryMutation>> rowMutations) {
        if (track != ArchiveTrack.LIVE || rowMutations.size() != updates.size()) {
            throw new IllegalStateException("invalid address live batch");
        }
        List<HotBlockUpdate> combined = new ArrayList<>(updates.size());
        for (int i = 0; i < updates.size(); i++) {
            List<HotHistoryMutation> mutations = new ArrayList<>(updates.get(i).mutations());
            mutations.addAll(rowMutations.get(i));
            combined.add(new HotBlockUpdate(updates.get(i).checkpoint(), mutations));
        }
        BlockSourceContext<Block> last = blocks.getLast();
        state.applyBlocks(dataset(), combined, new ArchiveProgress(dataset(), ArchiveTrack.LIVE,
                last.blockNumber(), last.slot(), last.blockHash(), 0), null);
        clear();
    }

    @Override public void abortBatch() { clear(); }

    private void clear() {
        blocks = List.of(); updates = List.of(); pendingPuts = Map.of(); pendingDeletes = Set.of();
        pendingPointers = Map.of(); pendingDeletedPointers = Set.of(); blockIndex = 0;
    }
    public record AddressParts(byte[] raw, byte[] addressKey, byte[] paymentCredential, byte[] stakeCredential) { }

    private enum ParticipationRole { INPUT, OUTPUT, COLLATERAL_INPUT, COLLATERAL_RETURN }

    private static final class SubjectRoles {
        private final AddressSubject subject;
        private int inputCount;
        private int outputCount;
        private int collateralInputCount;
        private int collateralReturnCount;

        private SubjectRoles(AddressSubject subject) { this.subject = subject; }

        private void increment(ParticipationRole role) {
            switch (role) {
                case INPUT -> inputCount++;
                case OUTPUT -> outputCount++;
                case COLLATERAL_INPUT -> collateralInputCount++;
                case COLLATERAL_RETURN -> collateralReturnCount++;
            }
        }
    }

    private record SubjectKey(String type, byte[] key) {
        private SubjectKey { key = key.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof SubjectKey that && type.equals(that.type) && Arrays.equals(key, that.key);
        }
        @Override public int hashCode() { return 31 * type.hashCode() + Arrays.hashCode(key); }
    }
}
