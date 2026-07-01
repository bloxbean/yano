package com.bloxbean.cardano.yano.p2p.tx.diffusion;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultTxDiffusion implements TxDiffusion {
    private final TxDiffusionMode mode;
    private final TxCatalog txCatalog;
    private final TxHashProvider txHashProvider;
    private final int maxInFlightTxsPerPeer;
    private final long maxInFlightBytesPerPeer;
    private final long peerCooldownMillis;
    private final Logger log;
    private final ConcurrentHashMap<String, PeerTxState> peers = new ConcurrentHashMap<>();
    private final AtomicLong acceptedMempoolEvents = new AtomicLong();
    private final AtomicLong inboundAccepted = new AtomicLong();
    private final AtomicLong inboundRejected = new AtomicLong();
    private final AtomicLong inboundIgnored = new AtomicLong();
    private final AtomicLong outboundForwarded = new AtomicLong();
    private final AtomicLong outboundSuppressed = new AtomicLong();
    private final AtomicLong servedTxs = new AtomicLong();
    private final AtomicLong servedBytes = new AtomicLong();

    public DefaultTxDiffusion(TxDiffusionMode mode,
                              TxCatalog txCatalog,
                              TxHashProvider txHashProvider,
                              int maxInFlightTxsPerPeer,
                              long maxInFlightBytesPerPeer,
                              long peerCooldownMillis,
                              Logger log) {
        this.mode = mode != null ? mode : TxDiffusionMode.DISABLED;
        this.txCatalog = Objects.requireNonNull(txCatalog, "txCatalog");
        this.txHashProvider = Objects.requireNonNull(txHashProvider, "txHashProvider");
        this.maxInFlightTxsPerPeer = Math.max(1, maxInFlightTxsPerPeer);
        this.maxInFlightBytesPerPeer = Math.max(0L, maxInFlightBytesPerPeer);
        this.peerCooldownMillis = Math.max(0L, peerCooldownMillis);
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public boolean isEnabled() {
        return mode.enabled();
    }

    @Override
    public TxDiffusionMode mode() {
        return mode;
    }

    @Override
    public void onPeerConnected(String peerId, PeerClass peerClass) {
        state(peerId, peerClass);
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        if (peerId != null) {
            peers.remove(peerId);
        }
    }

    @Override
    public void onTransactionAccepted(MemPoolTransaction transaction) {
        if (transaction != null) {
            acceptedMempoolEvents.incrementAndGet();
        }
    }

    @Override
    public boolean reserveLocalSubmitForward(String peerId, PeerClass peerClass, String txHash, int size) {
        if (!mode.localSubmitAllowed(peerClass)) {
            outboundSuppressed.incrementAndGet();
            return false;
        }
        boolean reserved = state(peerId, peerClass).reserveLocalSubmitForward(txHash);
        if (!reserved) {
            outboundSuppressed.incrementAndGet();
        }
        return reserved;
    }

    @Override
    public void onLocalSubmitForwarded(String peerId, PeerClass peerClass, String txHash, int size) {
        outboundForwarded.incrementAndGet();
    }

    @Override
    public TxRequestPlan onPeerTxIds(String peerId, PeerClass peerClass, List<TxIdAndSize> announced) {
        TxRequestPlan plan = state(peerId, peerClass).planRequests(
                mode,
                announced,
                txCatalog,
                maxInFlightTxsPerPeer,
                maxInFlightBytesPerPeer,
                System.currentTimeMillis());
        inboundIgnored.addAndGet(plan.ignored());
        inboundRejected.addAndGet(plan.rejected());
        return plan;
    }

    @Override
    public boolean shouldRequestTransaction(String peerId, PeerClass peerClass, String txHash) {
        if (!mode.networkIngressAllowed(peerClass)) {
            return false;
        }
        String normalized = TxIdAndSize.normalizeHash(txHash);
        return !txCatalog.contains(normalized);
    }

    @Override
    public TxBodyIngressResult onPeerTxBodies(String peerId,
                                             PeerClass peerClass,
                                             List<byte[]> txBodies,
                                             TxAdmissionPort txAdmission) {
        Objects.requireNonNull(txAdmission, "txAdmission");
        if (txBodies == null || txBodies.isEmpty()) {
            return TxBodyIngressResult.empty();
        }
        PeerTxState state = state(peerId, peerClass);
        int accepted = 0;
        int rejected = 0;
        int ignored = 0;
        long acceptedBytes = 0;
        for (byte[] txBody : txBodies) {
            if (txBody == null || txBody.length == 0 || !mode.networkIngressAllowed(peerClass)) {
                ignored++;
                continue;
            }
            String txHash;
            try {
                txHash = txHashProvider.txHash(txBody);
            } catch (Exception e) {
                rejected++;
                state.recordRejection(peerCooldownMillis, System.currentTimeMillis());
                continue;
            }
            if (!state.takeRequestedFromPeer(txHash)) {
                ignored++;
                continue;
            }
            try {
                txAdmission.admitTransaction(txBody, "tx-diffusion:" + peerId);
                accepted++;
                acceptedBytes += txBody.length;
            } catch (Exception e) {
                rejected++;
                state.recordRejection(peerCooldownMillis, System.currentTimeMillis());
                log.warn("Failed to admit diffused tx from {}: {}", peerId, summarizeFailure(e));
            }
        }
        inboundAccepted.addAndGet(accepted);
        inboundRejected.addAndGet(rejected);
        inboundIgnored.addAndGet(ignored);
        return new TxBodyIngressResult(accepted, rejected, ignored, acceptedBytes);
    }

    @Override
    public TxBodyServeResult onPeerRequestedTxs(String peerId, PeerClass peerClass, List<String> txHashes) {
        if (txHashes == null || txHashes.isEmpty()) {
            return TxBodyServeResult.empty();
        }
        if (!serveAllowed(peerClass)) {
            return new TxBodyServeResult(List.of(), txHashes.size(), 0);
        }
        List<MemPoolTransaction> transactions = new ArrayList<>();
        int notServed = 0;
        long bytes = 0;
        for (String txHash : txHashes) {
            if (transactions.size() >= maxInFlightTxsPerPeer) {
                notServed++;
                continue;
            }
            MemPoolTransaction transaction = txCatalog.getTransaction(TxIdAndSize.normalizeHash(txHash));
            if (transaction == null) {
                notServed++;
                continue;
            }
            int size = transaction.size();
            if (maxInFlightBytesPerPeer > 0 && bytes + size > maxInFlightBytesPerPeer) {
                notServed++;
                continue;
            }
            transactions.add(transaction);
            bytes += size;
        }
        servedTxs.addAndGet(transactions.size());
        servedBytes.addAndGet(bytes);
        return new TxBodyServeResult(transactions, notServed, bytes);
    }

    @Override
    public TxDiffusionStats stats() {
        long inFlightTxs = 0;
        long inFlightBytes = 0;
        for (PeerTxState state : peers.values()) {
            inFlightTxs += state.inFlightTxs();
            inFlightBytes += state.inFlightBytes();
        }
        return new TxDiffusionStats(
                mode.configValue(),
                mode.enabled(),
                peers.size(),
                acceptedMempoolEvents.get(),
                inboundAccepted.get(),
                inboundRejected.get(),
                inboundIgnored.get(),
                outboundForwarded.get(),
                outboundSuppressed.get(),
                servedTxs.get(),
                servedBytes.get(),
                inFlightTxs,
                inFlightBytes);
    }

    @Override
    public void close() {
        peers.clear();
    }

    private PeerTxState state(String peerId, PeerClass peerClass) {
        String normalizedPeerId = peerId != null && !peerId.isBlank() ? peerId : "unknown-peer";
        PeerTxState state = peers.computeIfAbsent(normalizedPeerId,
                id -> new PeerTxState(id, peerClass != null ? peerClass : PeerClass.DOWNSTREAM));
        state.updatePeerClass(peerClass);
        return state;
    }

    private boolean serveAllowed(PeerClass peerClass) {
        if (!mode.enabled() || peerClass == null) {
            return false;
        }
        if (mode == TxDiffusionMode.ALL_HOT) {
            return true;
        }
        return mode.localSubmitAllowed(peerClass);
    }

    private static String summarizeFailure(Exception e) {
        String message = e != null ? e.getMessage() : null;
        if (message == null || message.isBlank()) {
            return e != null ? e.getClass().getSimpleName() : "unknown";
        }
        return message;
    }

    static String txHash(MemPoolTransaction transaction) {
        return HexUtil.encodeHexString(transaction.txHash());
    }
}
