package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yano.runtime.peer.PeerHealth;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionCallbacks;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * PipelineDataListener is an adapter that implements BlockChainDataListener
 * and delegates events to the appropriate pipeline managers:
 * - HeaderSyncManager for ChainSync events (headers)
 * - BodyFetchManager for BlockFetch events (bodies)
 * - Yano for rollback coordination
 *
 * This allows the pipeline architecture to work with the existing
 * PeerClient.connect() method without modifications.
 */
@Slf4j
public class PipelineDataListener implements BlockChainDataListener {

    private final HeaderSyncManager headerSyncManager;
    private final BodyFetchManager bodyFetchManager;
    private final PeerSessionCallbacks callbacks;
    private final PeerHealth peerHealth;

    /**
     * Create a new PipelineDataListener
     *
     * @param headerSyncManager Manager for header synchronization
     * @param bodyFetchManager Manager for body fetching
     * @param callbacks Runtime callbacks for rollback and sync progress coordination
     */
    public PipelineDataListener(HeaderSyncManager headerSyncManager,
                               BodyFetchManager bodyFetchManager,
                               PeerSessionCallbacks callbacks) {
        this(headerSyncManager, bodyFetchManager, callbacks, null);
    }

    public PipelineDataListener(HeaderSyncManager headerSyncManager,
                               BodyFetchManager bodyFetchManager,
                               PeerSessionCallbacks callbacks,
                               PeerHealth peerHealth) {
        this.headerSyncManager = headerSyncManager;
        this.bodyFetchManager = bodyFetchManager;
        this.callbacks = callbacks;
        this.peerHealth = peerHealth;

        log.info("PipelineDataListener initialized for parallel header/body processing");
    }

    // ================================================================
    // ChainSync Events - Delegate to HeaderSyncManager
    // ================================================================

    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        // Delegate header processing to HeaderSyncManager
        headerSyncManager.rollforward(tip, blockHeader, originalHeaderBytes);
        if (peerHealth != null && blockHeader != null && blockHeader.getHeaderBody() != null) {
            peerHealth.recordHeaderProgress(
                    blockHeader.getHeaderBody().getSlot(),
                    blockHeader.getHeaderBody().getBlockNumber(),
                    System.currentTimeMillis());
        }

        //TODO remove this log
//        log.info("Rollforward to header: {} at slot: {}", blockHeader.getHeaderBody().getBlockNumber(), blockHeader.getHeaderBody().getSlot());

        // Resume BodyFetchManager if paused and headers are flowing after intersection
        callbacks.resumeBodyFetchOnHeaderFlow();
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        // Delegate Byron header processing to HeaderSyncManager
        headerSyncManager.rollforwardByronEra(tip, byronBlockHead, originalHeaderBytes);
        if (peerHealth != null && byronBlockHead != null && byronBlockHead.getConsensusData() != null) {
            peerHealth.recordHeaderProgress(
                    byronBlockHead.getConsensusData().getAbsoluteSlot(),
                    byronBlockHead.getConsensusData().getDifficulty().longValue(),
                    System.currentTimeMillis());
        }

        // Resume BodyFetchManager if paused and headers are flowing after intersection
        callbacks.resumeBodyFetchOnHeaderFlow();
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        // Delegate Byron EB header processing to HeaderSyncManager
        headerSyncManager.rollforwardByronEra(tip, byronEbHead, originalHeaderBytes);
        if (peerHealth != null && byronEbHead != null && byronEbHead.getConsensusData() != null) {
            peerHealth.recordHeaderProgress(
                    byronEbHead.getConsensusData().getAbsoluteSlot(),
                    byronEbHead.getConsensusData().getDifficulty().longValue(),
                    System.currentTimeMillis());
        }

        // Resume BodyFetchManager if paused and headers are flowing after intersection
        callbacks.resumeBodyFetchOnHeaderFlow();
    }

    // ================================================================
    // BlockFetch Events - Delegate to BodyFetchManager
    // ================================================================

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        recordShelleyBodyReceived(block);

        // Delegate block body processing to BodyFetchManager
        bodyFetchManager.onBlock(era, block, transactions);

        // Update sync progress tracking in Yano
        callbacks.updateSyncProgress();

        // Notify server about new block availability (only during STEADY_STATE)
        callbacks.notifyServerNewBlockStored();
    }

    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        recordByronBodyReceived(byronBlock);

        // Delegate Byron block processing to BodyFetchManager
        bodyFetchManager.onByronBlock(byronBlock);

        // Update sync progress tracking in Yano
        callbacks.updateSyncProgress();

        // Notify server about new block availability (only during STEADY_STATE)
        callbacks.notifyServerNewBlockStored();
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        recordByronEbBodyReceived(byronEbBlock);

        // Delegate Byron EB block processing to BodyFetchManager
        bodyFetchManager.onByronEbBlock(byronEbBlock);

        // Update sync progress tracking in Yano
        callbacks.updateSyncProgress();

        // Notify server about new block availability (only during STEADY_STATE)
        callbacks.notifyServerNewBlockStored();
    }

    @Override
    public void batchStarted() {
        if (peerHealth != null) {
            peerHealth.markBodyFetchStarted(System.currentTimeMillis());
        }
        // Delegate batch start to BodyFetchManager
        bodyFetchManager.batchStarted();
    }

    @Override
    public void batchDone() {
        if (peerHealth != null) {
            peerHealth.markBodyFetchCompleted();
        }
        // Delegate batch completion to BodyFetchManager
        bodyFetchManager.batchDone();
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        if (peerHealth != null) {
            peerHealth.markBodyFetchCompleted();
        }
        // Delegate no block found event to BodyFetchManager
        bodyFetchManager.noBlockFound(from, to);
    }

    // ================================================================
    // Control Events - Coordinate Between Components
    // ================================================================

    @Override
    public void intersactFound(Tip tip, Point point) {
        // Notify HeaderSyncManager about intersection
        headerSyncManager.intersactFound(tip, point);

        // Update sync phase in Yano for rollback classification
        callbacks.onIntersectionFound();

        // If we're already near the remote tip, transition to STEADY_STATE immediately
        callbacks.maybeFastTransitionToSteadyState(tip);

        log.info("Intersection found at point: {} - notified both header manager and Yano", point);
    }

    @Override
    public void intersactNotFound(Tip tip) {
        // Notify HeaderSyncManager about intersection not found
        headerSyncManager.intersactNotFound(tip);

        log.warn("Intersection not found for tip: {} - notified header manager", tip);
    }

    @Override
    public void onRollback(Point point) {
        if (peerHealth != null) {
            peerHealth.markBodyFetchCompleted();
        }
        bodyFetchManager.onRollback(point);
        // Delegate rollback handling to Yano for classification and coordination
        // Yano will pause/resume BodyFetchManager and handle server notifications
        callbacks.handleChainSyncRollback(point);

        log.info("Rollback to point: {} - delegated to Yano for coordination", point);
    }

    @Override
    public void onDisconnect() {
        // Notify both managers about disconnection
        headerSyncManager.onDisconnect();
        bodyFetchManager.onDisconnect();
        if (peerHealth != null) {
            peerHealth.recordDisconnect(System.currentTimeMillis());
            peerHealth.markBodyFetchCompleted();
        }
        try {
            callbacks.onPeerDisconnected();
        } catch (Exception e) {
            log.warn("Peer disconnect callback failed", e);
        }

        log.info("Disconnection event - notified both header and body managers");
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        // Delegate parsing errors to BodyFetchManager
        bodyFetchManager.onParsingError(e);

        log.error("Block parsing error delegated to BodyFetchManager", e);
    }

    private void recordShelleyBodyReceived(Block block) {
        if (peerHealth == null || block == null || block.getHeader() == null || block.getHeader().getHeaderBody() == null) {
            return;
        }

        peerHealth.recordBodyReceived(
                block.getHeader().getHeaderBody().getSlot(),
                block.getHeader().getHeaderBody().getBlockNumber(),
                System.currentTimeMillis());
    }

    private void recordByronBodyReceived(ByronMainBlock byronBlock) {
        if (peerHealth == null || byronBlock == null || byronBlock.getHeader() == null
                || byronBlock.getHeader().getConsensusData() == null) {
            return;
        }

        peerHealth.recordBodyReceived(
                byronBlock.getHeader().getConsensusData().getAbsoluteSlot(),
                byronBlock.getHeader().getConsensusData().getDifficulty().longValue(),
                System.currentTimeMillis());
    }

    private void recordByronEbBodyReceived(ByronEbBlock byronEbBlock) {
        if (peerHealth == null || byronEbBlock == null || byronEbBlock.getHeader() == null
                || byronEbBlock.getHeader().getConsensusData() == null) {
            return;
        }

        peerHealth.recordBodyReceived(
                byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot(),
                byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue(),
                System.currentTimeMillis());
    }

}
