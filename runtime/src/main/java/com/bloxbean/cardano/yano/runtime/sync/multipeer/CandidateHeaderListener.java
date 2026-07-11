package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yano.consensus.selection.CandidateHeader;
import com.bloxbean.cardano.yano.consensus.selection.HeaderFanIn;
import com.bloxbean.cardano.yano.consensus.selection.HeaderValidationEvidence;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationResult;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Header-only listener used by non-selected upstream peers.
 */
public final class CandidateHeaderListener implements BlockChainDataListener {
    private static final Logger log = LoggerFactory.getLogger(CandidateHeaderListener.class);

    private final String peerId;
    private final boolean trusted;
    private final HeaderFanIn fanIn;
    private final Consumer<CandidateHeader> onCandidate;
    private final HeaderValidator headerValidator;

    private volatile long headersObserved;
    private volatile long lastObservedSlot = -1;
    private volatile long lastObservedBlockNumber = -1;
    private volatile String lastObservedHash;

    public CandidateHeaderListener(String peerId,
                                   boolean trusted,
                                   HeaderFanIn fanIn,
                                   Consumer<CandidateHeader> onCandidate) {
        this(peerId, trusted, fanIn, onCandidate, HeaderValidator.none());
    }

    public CandidateHeaderListener(String peerId,
                                   boolean trusted,
                                   HeaderFanIn fanIn,
                                   Consumer<CandidateHeader> onCandidate,
                                   HeaderValidator headerValidator) {
        this.peerId = requireText(peerId, "peerId");
        this.trusted = trusted;
        this.fanIn = Objects.requireNonNull(fanIn, "fanIn");
        this.onCandidate = onCandidate != null ? onCandidate : header -> { };
        this.headerValidator = headerValidator != null ? headerValidator : HeaderValidator.none();
    }

    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        if (blockHeader == null || blockHeader.getHeaderBody() == null) {
            return;
        }
        HeaderValidationResult validation = headerValidator.validateShelley(blockHeader, originalHeaderBytes);
        if (!validation.accepted()) {
            log.debug("Rejected observer Shelley+ header from peer {} at stage {}: {}",
                    peerId, validation.stage(), validation.reason());
            return;
        }
        var body = blockHeader.getHeaderBody();
        record(new CandidateHeader(
                peerId,
                body.getSlot(),
                body.getBlockNumber(),
                body.getBlockHash(),
                body.getPrevHash(),
                trusted,
                System.currentTimeMillis(),
                "shelley+",
                evidence(validation),
                null,
                false,
                false));
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        if (byronBlockHead == null || byronBlockHead.getConsensusData() == null
                || byronBlockHead.getConsensusData().getDifficulty() == null) {
            return;
        }
        record(new CandidateHeader(
                peerId,
                byronBlockHead.getConsensusData().getAbsoluteSlot(),
                byronBlockHead.getConsensusData().getDifficulty().longValue(),
                byronBlockHead.getBlockHash(),
                byronBlockHead.getPrevBlock(),
                trusted,
                System.currentTimeMillis(),
                "byron",
                HeaderValidationEvidence.none(),
                null,
                false,
                false));
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        if (byronEbHead == null || byronEbHead.getConsensusData() == null
                || byronEbHead.getConsensusData().getDifficulty() == null) {
            return;
        }
        record(new CandidateHeader(
                peerId,
                byronEbHead.getConsensusData().getAbsoluteSlot(),
                byronEbHead.getConsensusData().getDifficulty().longValue(),
                byronEbHead.getBlockHash(),
                byronEbHead.getPrevBlock(),
                trusted,
                System.currentTimeMillis(),
                "byron",
                HeaderValidationEvidence.none(),
                null,
                false,
                false));
    }

    @Override
    public void onDisconnect() {
        log.debug("Observer peer {} disconnected", peerId);
    }

    public long headersObserved() {
        return headersObserved;
    }

    public long lastObservedSlot() {
        return lastObservedSlot;
    }

    public long lastObservedBlockNumber() {
        return lastObservedBlockNumber;
    }

    public String lastObservedHash() {
        return lastObservedHash;
    }

    private void record(CandidateHeader header) {
        if (header.blockHash() == null || header.blockHash().isBlank()) {
            return;
        }
        headersObserved++;
        lastObservedSlot = header.slot();
        lastObservedBlockNumber = header.blockNumber();
        lastObservedHash = header.blockHash();
        fanIn.onCandidateHeader(header);
        try {
            onCandidate.accept(header);
        } catch (Exception e) {
            log.debug("Observer candidate callback failed for peer {} at slot {}: {}",
                    peerId, header.slot(), e.getMessage());
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static HeaderValidationEvidence evidence(HeaderValidationResult result) {
        if (result == null) {
            return HeaderValidationEvidence.none();
        }
        if (result.accepted()) {
            return HeaderValidationEvidence.accepted(result.level(), result.acceptedStages());
        }
        return HeaderValidationEvidence.rejected(
                result.level(),
                result.acceptedStages(),
                result.stage(),
                result.reason());
    }
}
