package com.bloxbean.cardano.yano.runtime.handlers;

import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationException;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.PeerClass;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxBodyIngressResult;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxBodyServeResult;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxDiffusion;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxIdAndSize;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxRequestPlan;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implementation of TxSubmissionListener and TxSubmissionHandler that integrates
 * TxSubmission protocol with Yano's MemPool and transaction processing.
 * 
 * This implementation uses a blocking-only approach where transactions are
 * processed immediately and removed from the mempool to simulate processing.
 */
@Slf4j
public class YaciTxSubmissionHandler implements TxSubmissionListener, TxSubmissionHandler {

    private final TransactionAdmission transactionAdmission;
    private final boolean blockProducerMode;
    private final TxDiffusion txDiffusion;
    private final Set<String> knownTxIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> clientConnections = new ConcurrentHashMap<>();

    // Statistics
    private long txIdsReceived = 0;
    private long txsReceived = 0;
    private long txsAccepted = 0;
    private long txsRejected = 0;
    private long txsProcessed = 0;

    public YaciTxSubmissionHandler(TransactionAdmission transactionAdmission) {
        this(transactionAdmission, false);
    }

    public YaciTxSubmissionHandler(TransactionAdmission transactionAdmission, boolean blockProducerMode) {
        this(transactionAdmission, blockProducerMode, null);
    }

    public YaciTxSubmissionHandler(TransactionAdmission transactionAdmission,
                                   boolean blockProducerMode,
                                   TxDiffusion txDiffusion) {
        this.transactionAdmission = Objects.requireNonNull(transactionAdmission, "transactionAdmission");
        this.blockProducerMode = blockProducerMode;
        this.txDiffusion = txDiffusion;
    }

    // TxSubmissionListener implementation (for server-side handling)

    @Override
    public void handleRequestTxs(RequestTxs requestTxs) {
        if (!diffusionEnabled()) {
            log.debug("Received RequestTxs message (not applicable in server mode): {} tx IDs",
                    requestTxs.getTxIds().size());
            return;
        }
        List<String> txHashes = requestTxs.getTxIds().stream()
                .map(txId -> HexUtil.encodeHexString(txId.getTxId()))
                .toList();
        TxBodyServeResult result = txDiffusion.onPeerRequestedTxs(currentPeerId(), PeerClass.DOWNSTREAM, txHashes);
        log.debug("Received RequestTxs from {}; served={}, missing={}",
                currentPeerId(), result.transactions().size(), result.missing());
    }

    @Override
    public void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {
        // This is called when acting as a client - not used in server mode
        log.debug("Received RequestTxIds non-blocking message (not applicable in server mode): ack={}, req={}",
                requestTxIds.getAckTxIds(), requestTxIds.getReqTxIds());
    }

    @Override
    public void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {
        // This is called when acting as a client - not used in server mode
        log.debug("Received RequestTxIds blocking message (not applicable in server mode): ack={}, req={}",
                requestTxIds.getAckTxIds(), requestTxIds.getReqTxIds());
    }

    @Override
    public void handleReplyTxIds(ReplyTxIds replyTxIds) {
        txIdsReceived += replyTxIds.getTxIdAndSizeMap().size();

        log.info("Received {} transaction IDs from client",
                replyTxIds.getTxIdAndSizeMap().size());

        List<TxIdAndSize> announced = new ArrayList<>(replyTxIds.getTxIdAndSizeMap().size());
        replyTxIds.getTxIdAndSizeMap().forEach((txId, size) -> {
            String txIdStr = HexUtil.encodeHexString(txId.getTxId());
            knownTxIds.add(txIdStr);
            announced.add(new TxIdAndSize(txIdStr, size));
            if (log.isDebugEnabled()) {
                log.debug("TX ID: {} (size: {} bytes)", txIdStr, size);
            }
        });
        if (diffusionEnabled()) {
            TxRequestPlan plan = txDiffusion.onPeerTxIds(currentPeerId(), PeerClass.DOWNSTREAM, announced);
            log.debug("Planned tx body requests from {}: requested={}, ignored={}, rejected={}",
                    currentPeerId(), plan.requested().size(), plan.ignored(), plan.rejected());
        }
    }

    @Override
    public void handleReplyTxs(ReplyTxs replyTxs) {
        txsReceived += replyTxs.getTxns().size();

        log.info("Received {} transactions from client", replyTxs.getTxns().size());

        if (diffusionEnabled()) {
            List<byte[]> txBodies = replyTxs.getTxns().stream()
                    .map(Tx::getTx)
                    .toList();
            TxBodyIngressResult result = txDiffusion.onPeerTxBodies(
                    currentPeerId(), PeerClass.DOWNSTREAM, txBodies, transactionAdmission);
            txsAccepted += result.accepted();
            txsRejected += result.rejected();
            txsProcessed += result.accepted();
            log.debug("Processed diffused tx bodies from {}: accepted={}, rejected={}, ignored={}",
                    currentPeerId(), result.accepted(), result.rejected(), result.ignored());
            return;
        }

        for (Tx tx : replyTxs.getTxns()) {
            try {
                String txHash = transactionAdmission.admitTransaction(tx.getTx(), "txsubmission");
                txsAccepted++;

                log.info("Transaction added to mempool: {} ({} bytes)", txHash, tx.getTx().length);

            } catch (TransactionValidationException e) {
                txsRejected++;
                String errorMsg = e.getErrors().stream()
                        .map(TransactionValidationException.Error::message)
                        .collect(Collectors.joining("; "));
                log.warn("Rejecting invalid N2N tx: {}", !errorMsg.isBlank() ? errorMsg : e.getMessage());
            } catch (Exception e) {
                txsRejected++;
                log.warn("Failed to process received transaction: {}", e.getMessage());
                if (log.isDebugEnabled()) {
                    log.debug("Transaction processing error details", e);
                }
            }
        }
    }

    // TxSubmissionHandler implementation

    @Override
    public void handleTransaction(TxId txId, byte[] txBytes) {
        // This method is not used in the blocking-only approach
        // Transactions are handled directly in handleReplyTxs
        log.debug("handleTransaction called (not used in blocking mode)");
    }

    @Override
    public void handleTransactionIds(Map<TxId, Integer> txIdAndSizes) {
        // Not used in blocking-only approach
        log.debug("handleTransactionIds called (not used in blocking mode)");
    }

    @Override
    public boolean shouldRequestTransaction(TxId txId) {
        if (!diffusionEnabled()) {
            return true;
        }
        String txHash = HexUtil.encodeHexString(txId.getTxId());
        return txDiffusion.shouldRequestTransaction(currentPeerId(), PeerClass.DOWNSTREAM, txHash);
    }

    @Override
    public void onClientConnected(String clientId) {
        clientConnections.put(clientId, clientId);
        if (diffusionEnabled()) {
            txDiffusion.onPeerConnected(clientId, PeerClass.DOWNSTREAM);
        }
        log.info("TxSubmission client connected: {}", clientId);
        log.debug("Client connected - will request transactions once handshake completes");
    }

    @Override
    public void onClientDisconnected(String clientId) {
        clientConnections.remove(clientId);
        if (diffusionEnabled()) {
            txDiffusion.onPeerDisconnected(clientId);
        }
        log.info("TxSubmission client disconnected: {}", clientId);
    }

    @Override
    public short getAcknowledgeCount() {
        // Not used in the new blocking-only implementation
        // Acknowledgments are handled directly in the agent
        return 0;
    }

    @Override
    public short getRequestCount() {
        // Not used in the new blocking-only implementation
        return 5;
    }

    @Override
    public boolean useBlockingMode() {
        // Always use blocking mode in this implementation
        return true;
    }

    // Note: Agent now manages its own periodic requests automatically


    // Statistics and monitoring

    public long getTxIdsReceived() {
        return txIdsReceived;
    }

    public long getTxsReceived() {
        return txsReceived;
    }

    public long getTxsAccepted() {
        return txsAccepted;
    }

    public long getTxsRejected() {
        return txsRejected;
    }

    public long getTxsProcessed() {
        return txsProcessed;
    }

    public int getConnectedClients() {
        return clientConnections.size();
    }

    public int getKnownTxIds() {
        return knownTxIds.size();
    }

    public int getMempoolSize() {
        return transactionAdmission.mempoolSize();
    }

    private boolean diffusionEnabled() {
        return txDiffusion != null && txDiffusion.isEnabled();
    }

    /**
     * Get summary statistics for monitoring
     */
    public String getStatsMessage() {
        return String.format("TxSubmission Stats - Clients: %d, TxIDs: %d, TxReceived: %d, TxAccepted: %d, TxProcessed: %d, TxRejected: %d, MemPool: %d",
                getConnectedClients(), getTxIdsReceived(), getTxsReceived(), getTxsAccepted(), getTxsProcessed(), getTxsRejected(), getMempoolSize());
    }

    /**
     * Reset statistics (useful for testing)
     */
    public void resetStats() {
        txIdsReceived = 0;
        txsReceived = 0;
        txsAccepted = 0;
        txsRejected = 0;
        txsProcessed = 0;
        knownTxIds.clear();
        clientConnections.clear();
    }

    private String currentPeerId() {
        if (clientConnections.size() == 1) {
            return clientConnections.keySet().iterator().next();
        }
        return "txsubmission";
    }
}
