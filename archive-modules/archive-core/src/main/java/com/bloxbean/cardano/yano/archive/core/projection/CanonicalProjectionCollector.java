package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    @Override
    public void reinitializeAfterSnapshotRestore() {
        outbox.reinitializeAfterSnapshotRestore();
    }

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
                BUILDABLE_SECTIONS);
    }

    /**
     * @param buildableSections which sections this collector can produce; overridable so the
     *                          fail-closed guard remains testable once every shipped section is
     *                          buildable and the guard would otherwise be unreachable
     */
    CanonicalProjectionCollector(ProjectionOutboxStore outbox, ProjectionIdentity identity,
                                 LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                                 int chunkBytes, boolean enabled, PointerCredentialSource pointerSource,
                                 java.util.Set<ProjectionSectionType> buildableSections) {
        this.pointerSource = Objects.requireNonNull(pointerSource, "pointerSource");
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
        var existing = outbox.blockIdentity(event.blockNumber());
        if (existing.isPresent()) {
            ProjectionEnvelopeHeader claimed = existing.get();
            if (claimed.blockKind() == ProjectionBlockKind.BYRON_EBB
                    && !Arrays.equals(claimed.blockHash(), blockHash)) {
                throw new ProjectionOutboxException("Byron EBB hash mismatch at projection block "
                        + event.blockNumber());
            }
            if (claimed.blockKind() != ProjectionBlockKind.BYRON_EBB
                    && claimed.blockKind() != ProjectionBlockKind.BYRON_MAIN) {
                throw new ProjectionOutboxException("Byron EBB coordinate " + event.blockNumber()
                        + " is occupied by " + claimed.blockKind());
            }
            return;
        }

        Block normalized = ByronBlockNormalizer.normalizeEpochBoundary(
                event.epochBoundaryBlock(), event.blockNumber(), blockHash);
        var context = context(event.blockNumber(), event.slot(), event.blockHash(), normalized);
        outbox.putBlockIdentity(writer, header(context, ProjectionBlockKind.BYRON_EBB));
        for (ProjectionSectionType type : identity.requiredSections()) {
            outbox.advanceContributor(writer, type, event.blockNumber());
        }
    }

    @Override
    public void contributeByronMainBlock(ByronMainBlockAppliedEvent event,
                                         ConsumedOutputAddresses consumed,
                                         ProjectionStagingWriter writer) {
        if (!enabled) return;
        this.consumedAddresses = consumed == null ? ConsumedOutputAddresses.NONE : consumed;
        try {
            Block normalized = ByronBlockNormalizer.normalizeMain(event.block(), event.blockNumber(),
                    HexUtil.decodeHexString(event.blockHash()));
            contribute(context(event.blockNumber(), event.slot(), event.blockHash(), normalized),
                    ProjectionBlockKind.BYRON_MAIN, writer);
        } finally {
            this.consumedAddresses = ConsumedOutputAddresses.NONE;
        }
    }

    private ConsumedOutputAddresses addressResolution() {
        return consumedAddresses;
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

    /** Roll back pending projection state to the exact canonical point. */
    public long rollbackToPoint(long rollbackSlot, byte[] rollbackHash, boolean origin) {
        return outbox.rollbackToPoint(
                rollbackSlot, rollbackHash, origin, identity.requiredSections());
    }

    // ---------------------------------------------------------------- internals

    private void contribute(BlockSourceContext<Block> context, ProjectionBlockKind kind,
                            ProjectionStagingWriter writer) {
        ContributionPlan plan = contributionPlan(context);
        if (plan.skip()) return;
        if (plan.writeIdentity()) outbox.putBlockIdentity(writer, header(context, kind));

        for (ProjectionSectionType type : plan.sections()) {
            // Every required section is buildable: the constructor rejected any that is not.
            outbox.putSection(writer, context.blockNumber(), buildSection(type, context));
        }
    }

    private ContributionPlan contributionPlan(BlockSourceContext<Block> context) {
        long blockNumber = context.blockNumber();
        if (blockNumber <= outbox.acknowledgedThrough()) return ContributionPlan.SKIP;

        var existing = outbox.blockIdentity(blockNumber);
        if (existing.isPresent()) {
            if (!Arrays.equals(existing.get().blockHash(), context.blockHash())) {
                throw new ProjectionOutboxException("projection replay hash mismatch at block "
                        + blockNumber);
            }
            Set<ProjectionSectionType> missing =
                    outbox.missingSections(blockNumber, identity.requiredSections());
            return missing.isEmpty() ? ContributionPlan.SKIP
                    : new ContributionPlan(false, false, missing);
        }
        if (blockNumber <= outbox.identityCursor()) {
            throw new ProjectionOutboxException("projection identity is missing at block " + blockNumber
                    + " inside the durable identity cursor " + outbox.identityCursor());
        }
        return new ContributionPlan(false, true, identity.requiredSections());
    }

    private record ContributionPlan(boolean skip, boolean writeIdentity,
                                    Set<ProjectionSectionType> sections) {
        private static final ContributionPlan SKIP = new ContributionPlan(true, false, Set.of());
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
            // the address it belonged to.
            case ADDRESS_TRANSACTION -> {
                // Consumed inputs were resolved during apply, while the outputs they spend were
                // still current. The shared capture covers Byron and Shelley-family blocks;
                // outputs are parsed here with the same parser the live path uses. The sink
                // therefore needs neither the block nor the UTXO set.
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
