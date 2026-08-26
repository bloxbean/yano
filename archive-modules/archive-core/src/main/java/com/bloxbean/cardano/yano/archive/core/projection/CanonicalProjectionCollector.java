package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.source.ByronBlockNormalizer;
import com.bloxbean.cardano.yano.archive.core.source.YaciBlockArchiveDecoder;
import com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxoProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongUnaryOperator;

/**
 * Builds one canonical block's projection sections during apply (ADR-039 Phase 3).
 *
 * <p>The block arrives already decoded, so this performs no CBOR parsing on the hot
 * path. Both existing decoders expose a {@code project(BlockSourceContext&lt;Block&gt;)}
 * entry point that derives facts from a parsed block, and those are reused verbatim —
 * this class contributes the transport, not a second interpretation of the chain.
 *
 * <p>What runs here is deterministic fact derivation, deterministic encoding, and a
 * bounded staging write. No archive session is opened, no writer is awaited, and no
 * artifact is scanned.
 */
public final class CanonicalProjectionCollector implements CanonicalProjectionContributor {

    private final ProjectionOutboxStore outbox;
    private final ProjectionIdentity identity;
    private final ArchiveNetworkIdentity network;
    private final YaciBlockArchiveDecoder blockFacts;
    private final YaciUtxoHistoryDecoder utxoFacts;
    private final LongUnaryOperator slotToEpoch;
    private final LongUnaryOperator slotToUnixTime;
    private final int chunkBytes;
    private final boolean enabled;
    /** Authoritative pointer mapping, owned by the account-state contributor. */
    private final PointerCredentialSource pointerSource;
    /** The genesis distribution the Byron outpoint resolver is seeded from. */
    private final GenesisUtxoProvider genesisUtxos;

    public CanonicalProjectionCollector(ProjectionOutboxStore outbox, ProjectionIdentity identity,
                                        LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime) {
        this(outbox, identity, slotToEpoch, slotToUnixTime, ProjectionChunking.DEFAULT_CHUNK_BYTES, true,
                PointerCredentialSource.NONE);
    }

    public CanonicalProjectionCollector(ProjectionOutboxStore outbox, ProjectionIdentity identity,
                                        LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                        int chunkBytes, boolean enabled) {
        this(outbox, identity, slotToEpoch, slotToUnixTime, chunkBytes, enabled, PointerCredentialSource.NONE);
    }

    public CanonicalProjectionCollector(ProjectionOutboxStore outbox, ProjectionIdentity identity,
                                        LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                        int chunkBytes, boolean enabled, PointerCredentialSource pointerSource) {
        this(outbox, identity, slotToEpoch, slotToUnixTime, chunkBytes, enabled, pointerSource,
                GenesisUtxoProvider.EMPTY);
    }

    public CanonicalProjectionCollector(ProjectionOutboxStore outbox, ProjectionIdentity identity,
                                        LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                        int chunkBytes, boolean enabled, PointerCredentialSource pointerSource,
                                        GenesisUtxoProvider genesisUtxos) {
        this(outbox, identity, slotToEpoch, slotToUnixTime, chunkBytes, enabled, pointerSource,
                genesisUtxos, BUILDABLE_SECTIONS);
    }

    /**
     * @param buildableSections which sections this collector can produce; overridable so the
     *                          fail-closed guard remains testable once every shipped section is
     *                          buildable and the guard would otherwise be unreachable
     */
    CanonicalProjectionCollector(ProjectionOutboxStore outbox, ProjectionIdentity identity,
                                 LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                 int chunkBytes, boolean enabled, PointerCredentialSource pointerSource,
                                 GenesisUtxoProvider genesisUtxos,
                                 java.util.Set<ProjectionSectionType> buildableSections) {
        this.pointerSource = Objects.requireNonNull(pointerSource, "pointerSource");
        this.genesisUtxos = Objects.requireNonNull(genesisUtxos, "genesisUtxos");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.network = identity.networkIdentity();
        this.slotToEpoch = Objects.requireNonNull(slotToEpoch, "slotToEpoch");
        this.slotToUnixTime = Objects.requireNonNull(slotToUnixTime, "slotToUnixTime");
        this.blockFacts = new YaciBlockArchiveDecoder(slotToEpoch, slotToUnixTime);
        this.utxoFacts = new YaciUtxoHistoryDecoder(slotToEpoch, slotToUnixTime);
        this.chunkBytes = chunkBytes;
        this.enabled = enabled;

        // Fail closed on an identity demanding a section this collector cannot build.
        // Without this the contributor would advance its cursor while never writing a
        // section, so the envelope could never complete and the archive would stall
        // silently at the first block — the worst possible shape for the failure.
        var unbuildable = identity.requiredSections().stream()
                .filter(section -> !buildableSections.contains(section))
                .map(ProjectionSectionType::wireName)
                .sorted()
                .toList();
        if (!unbuildable.isEmpty()) {
            throw new IllegalArgumentException("projection identity requires section(s) this collector cannot"
                    + " produce yet: " + unbuildable + ". Remove them from the required set or implement their"
                    + " contributor before enabling them.");
        }
    }

    /** Sections this collector can produce. */
    private static final java.util.Set<ProjectionSectionType> BUILDABLE_SECTIONS =
            java.util.Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY,
                    ProjectionSectionType.ACCOUNT_EVENT, ProjectionSectionType.ADDRESS_TRANSACTION);

    private static final com.bloxbean.cardano.yano.archive.core.address.AddressKeyCodec ADDRESS_KEYS =
            new com.bloxbean.cardano.yano.archive.core.address.AddressKeyCodec();

    /**
     * Coordinate the seed asks the genesis provider for.
     *
     * <p>Immaterial: the resolver keeps outpoint and address, and drops the block, slot and hash
     * a genesis output is attributed to. Those belong to the genesis bootstrap, which records
     * them against the real canonical first block.
     */
    private static final String GENESIS_COORDINATE = "00".repeat(32);

    /**
     * Consumed-output addresses for the block being contributed.
     *
     * <p>Passed through a field rather than threaded through {@code buildSection} because only
     * one of four sections needs it and the alternative is a parameter every other case ignores.
     * Safe because contribution happens on the apply thread, inside one block's write batch.
     */
    private ConsumedOutputAddresses consumedAddresses = ConsumedOutputAddresses.NONE;

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean needsConsumedOutputAddresses() {
        return identity.requiredSections().contains(ProjectionSectionType.ADDRESS_TRANSACTION);
    }

    @Override
    public void contributeBlock(BlockAppliedEvent event, ConsumedOutputAddresses consumed,
                                ProjectionStagingWriter writer) {
        this.consumedAddresses = consumed == null ? ConsumedOutputAddresses.NONE : consumed;
        try {
            contributeBlock(event, writer);
        } finally {
            this.consumedAddresses = ConsumedOutputAddresses.NONE;
        }
    }

    @Override
    public void contributeBlock(BlockAppliedEvent event, ProjectionStagingWriter writer) {
        if (!enabled || event.block() == null) return;
        contribute(context(event.blockNumber(), event.slot(), event.blockHash(), event.block()),
                ProjectionBlockKind.SHELLEY_PLUS, writer);
    }

    @Override
    public void contributeByronBlock(ByronBlockProjectionEvent event, ProjectionStagingWriter writer) {
        if (!enabled) return;
        byte[] blockHash = HexUtil.decodeHexString(event.blockHash());

        if (event.isEpochBoundary()) {
            // An EBB's block number is its chain difficulty, and an EBB does not advance
            // difficulty - so every EBB but the chain's first reports the number of the main
            // block before it. That block owns the coordinate and has already written its
            // sections there; letting the EBB claim it replaced a real block's identity with an
            // empty one and left its sections orphaned, which readEnvelope then rejects as "an
            // epoch-boundary block must produce an empty envelope". Observed on mainnet at slot
            // 21,600, where the epoch-1 EBB reports block 21,586. The chain state refuses EBBs a
            // number mapping for exactly this reason; the projection follows it.
            //
            // The genesis EBB is the exception that must NOT be skipped: it is block 0, no main
            // block claims that number, and the drain begins at block 0 - so without its
            // envelope the first batch fails its contiguity check and the archive never starts.
            if (outbox.hasBlockIdentity(event.blockNumber())) return;

            // Otherwise the EBB contributes an empty envelope and advances every required
            // contributor, so the sink's greatest contiguous coordinate can move past it
            // instead of stopping there forever.
            Block normalized = ByronBlockNormalizer.normalizeEpochBoundary(
                    event.epochBoundaryBlock(), event.blockNumber(), blockHash);
            var context = context(event.blockNumber(), event.slot(), event.blockHash(), normalized);
            outbox.putBlockIdentity(writer, header(context, ProjectionBlockKind.BYRON_EBB));
            for (ProjectionSectionType type : identity.requiredSections()) {
                outbox.advanceContributor(writer, type, event.blockNumber());
            }
            return;
        }

        Block normalized = ByronBlockNormalizer.normalizeMain(
                event.mainBlock(), event.blockNumber(), blockHash);
        // Byron gets its consumed addresses from the archive's own resolver rather than from
        // apply: the live UTXO path never applied this block, so nothing was captured. Advancing
        // the resolver and building the section happen in one batch, so an output can never be
        // durable without the section that recorded it, or the reverse.
        if (needsConsumedOutputAddresses()) {
            this.consumedAddresses = recordByronOutputs(normalized, writer);
        }
        try {
            contribute(context(event.blockNumber(), event.slot(), event.blockHash(), normalized),
                    ProjectionBlockKind.BYRON_MAIN, writer);
        } finally {
            this.consumedAddresses = ConsumedOutputAddresses.NONE;
        }
    }

    /**
     * Stage this Byron block's outputs into the resolver, and answer for the block being built.
     *
     * <p>The returned resolution consults this block before the durable index because a RocksDB
     * point read cannot observe the batch these entries are staged in, and Byron chains
     * transactions inside one block. The durable index is consulted through
     * {@link #addressResolution()} like every other era's.
     *
     * <p>The genesis distribution is seeded here, on the first Byron main block, rather than at
     * startup. Seeding needs the node's genesis configuration, which is loaded after projection
     * history initialises, and the genesis-capture trigger can be deferred or replayed — so
     * ordering is established by construction instead of by reasoning about event sequence.
     */
    private ConsumedOutputAddresses recordByronOutputs(Block block, ProjectionStagingWriter writer) {
        ByronOutputAddressIndex index = outbox.byronOutputIndex();
        Map<String, String> thisBlock = new HashMap<>();

        if (!index.genesisSeeded()) {
            for (GenesisUtxo utxo : genesisUtxos.genesisUtxos(0, 0, GENESIS_COORDINATE)) {
                // Shelley initial funds live in the UTXO set and are captured during apply;
                // only the Byron half is unreachable to that path.
                if (!utxo.isByron()) continue;
                index.put(writer, utxo.txHash(), utxo.outputIndex(), utxo.address());
                thisBlock.put(outpoint(utxo.txHash(), utxo.outputIndex()), utxo.address());
            }
            index.markGenesisSeeded(writer);
        }

        for (TransactionBody tx : block.getTransactionBodies()) {
            List<TransactionOutput> outputs = tx.getOutputs();
            if (outputs == null) continue;
            for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
                String address = outputs.get(outputIndex).getAddress();
                index.put(writer, tx.getTxHash(), outputIndex, address);
                thisBlock.put(outpoint(tx.getTxHash(), outputIndex), address);
            }
        }
        return (txHash, outputIndex) -> thisBlock.get(outpoint(txHash, outputIndex));
    }

    /**
     * Consumed-output addresses for the block being built.
     *
     * <p>What apply captured comes first; the Byron resolver answers for what it could not hold.
     * That fallback is not Byron-only in effect: a Shelley-era transaction spending an output a
     * Byron block created finds nothing in {@code cfUnspent} either, because that output was
     * never applied there. The resolver is therefore consulted in every era, for as long as
     * Byron-era outputs remain unspent.
     */
    private ConsumedOutputAddresses addressResolution() {
        ConsumedOutputAddresses captured = consumedAddresses;
        ByronOutputAddressIndex byronOutputs = outbox.byronOutputIndex();
        return (txHash, outputIndex) -> {
            String address = captured.addressOf(txHash, outputIndex);
            return address != null ? address : byronOutputs.addressOf(txHash, outputIndex);
        };
    }

    private static String outpoint(String txHash, int outputIndex) {
        return txHash + '#' + outputIndex;
    }

    @Override
    public void rollbackFrom(long fromBlockNumber) {
        outbox.rollbackFrom(fromBlockNumber, identity.requiredSections());
    }

    /**
     * Roll back by canonical slot. Preferred at an event boundary, where the surviving
     * block number is not reliably readable at listener time.
     *
     * @return the number of envelopes removed
     */
    public long rollbackToSlot(long rollbackSlot) {
        return outbox.rollbackToSlot(rollbackSlot, identity.requiredSections());
    }

    // ---------------------------------------------------------------- internals

    private void contribute(BlockSourceContext<Block> context, ProjectionBlockKind kind,
                            ProjectionStagingWriter writer) {
        outbox.putBlockIdentity(writer, header(context, kind));

        for (ProjectionSectionType type : identity.requiredSections()) {
            // Every required section is buildable: the constructor rejected any that is not.
            outbox.putSection(writer, context.blockNumber(), buildSection(type, context));
        }
    }

    private ProjectionSection buildSection(ProjectionSectionType type, BlockSourceContext<Block> context) {
        return switch (type) {
            case TRANSACTION -> {
                var facts = blockFacts.project(context).block().transactions();
                yield section(type, ProjectionFactCodec.encodeTransactions(facts), facts.size());
            }
            case UTXO_HISTORY -> {
                // Resolve pointer stake references here, while the authoritative mapping is
                // current for this block. The sink then needs no resolver state at all.
                var fact = ProjectionPointerResolution.resolve(
                        utxoFacts.project(context).block(), context.slot(), pointerSource);
                long rows = (long) fact.outputs().size() + fact.assets().size() + fact.inputs().size()
                        + fact.transactionDatums().size() + fact.transactionRedeemers().size();
                yield section(type, ProjectionFactCodec.encodeUtxoHistory(fact), rows);
            }
            case ACCOUNT_EVENT -> {
                // Certificates are a pure function of the block body: no ledger state, no
                // resolution, nothing to capture that is not already in front of us.
                var facts = blockFacts.project(context).block().accountEvents();
                yield section(type, ProjectionFactCodec.encodeAccountEvents(facts), facts.size());
            }
            // ADDRESS_TRANSACTION still needs input-address resolution at capture time, the way
            // pointer addresses did: UtxoHistoryFact.Input carries the consumed outpoint but not
            // the address it belonged to. Unreachable: the constructor rejects an identity that
            // requires a section this collector cannot build.
            case ADDRESS_TRANSACTION -> {
                // Consumed inputs were resolved during apply, while the outputs they spend were
                // still current, or - for outputs apply never held, which is all of Byron's -
                // from the archive's own resolver. Outputs are parsed here with the same parser
                // the live path uses. The sink therefore needs neither the block nor the UTXO set.
                var fact = ProjectionAddressParticipation.resolve(context.block(), context.slot(),
                        addressResolution(), ADDRESS_KEYS, pointerSource);
                long rows = fact.transactions().stream()
                        .mapToLong(tx -> tx.participations().size()).sum();
                yield section(type, ProjectionFactCodec.encodeAddressParticipations(fact), rows);
            }
        };
    }

    private ProjectionSection section(ProjectionSectionType type, byte[] payload, long rowCount) {
        return new ProjectionSection(type, type.version(), ProjectionChunking.split(payload, chunkBytes), rowCount);
    }

    private ProjectionEnvelopeHeader header(BlockSourceContext<Block> context, ProjectionBlockKind kind) {
        return new ProjectionEnvelopeHeader(network, kind, context.blockNumber(), context.blockHash(),
                context.parentHash(), context.slot(), (int) context.epoch(),
                context.blockTime().getEpochSecond(), identity.canonicalProjectionVersion(), List.of(), List.of());
    }

    private BlockSourceContext<Block> context(long blockNumber, long slot, String blockHash, Block block) {
        var header = block.getHeader() == null ? null : block.getHeader().getHeaderBody();
        String prevHash = header == null ? null : header.getPrevHash();
        byte[] parent = prevHash == null || prevHash.isBlank() ? new byte[0] : HexUtil.decodeHexString(prevHash);
        return new BlockSourceContext<>(blockNumber, slot, slotToEpoch.applyAsLong(slot),
                Instant.ofEpochSecond(slotToUnixTime.applyAsLong(slot)), HexUtil.decodeHexString(blockHash),
                parent, block);
    }
}
