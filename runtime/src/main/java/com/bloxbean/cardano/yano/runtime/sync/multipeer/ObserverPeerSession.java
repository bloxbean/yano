package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.p2p.peer.PeerEndpoint;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Header-only upstream observer. It never writes canonical chain state.
 */
public final class ObserverPeerSession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ObserverPeerSession.class);

    private final String peerId;
    private final PeerEndpoint endpoint;
    private final boolean trusted;
    private final HeaderFanIn fanIn;
    private final PeerClientFactory peerClientFactory;
    private final Consumer<CandidateHeader> onCandidate;
    private final HeaderValidator headerValidator;

    private PeerClient peerClient;
    private CandidateHeaderListener listener;

    public ObserverPeerSession(String peerId,
                               PeerEndpoint endpoint,
                               boolean trusted,
                               HeaderFanIn fanIn,
                               PeerClientFactory peerClientFactory,
                               Consumer<CandidateHeader> onCandidate) {
        this(peerId, endpoint, trusted, fanIn, peerClientFactory, onCandidate, HeaderValidator.none());
    }

    public ObserverPeerSession(String peerId,
                               PeerEndpoint endpoint,
                               boolean trusted,
                               HeaderFanIn fanIn,
                               PeerClientFactory peerClientFactory,
                               Consumer<CandidateHeader> onCandidate,
                               HeaderValidator headerValidator) {
        this.peerId = requireText(peerId, "peerId");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.trusted = trusted;
        this.fanIn = Objects.requireNonNull(fanIn, "fanIn");
        this.peerClientFactory = Objects.requireNonNull(peerClientFactory, "peerClientFactory");
        this.onCandidate = onCandidate != null ? onCandidate : header -> { };
        this.headerValidator = headerValidator != null ? headerValidator : HeaderValidator.none();
    }

    public void start(Point startPoint) {
        Objects.requireNonNull(startPoint, "startPoint");
        stop();
        listener = new CandidateHeaderListener(peerId, trusted, fanIn, onCandidate, headerValidator);
        peerClient = peerClientFactory.create(endpoint, startPoint);
        peerClient.connect(listener, null);
        peerClient.enableTxSubmission();
        peerClient.startHeaderSync(startPoint, true);
        log.info("Started observer upstream peer {} at {} from {}", peerId, endpoint.displayName(), startPoint);
    }

    public String peerId() {
        return peerId;
    }

    public PeerEndpoint endpoint() {
        return endpoint;
    }

    public boolean trusted() {
        return trusted;
    }

    public boolean isRunning() {
        return peerClient != null && peerClient.isRunning();
    }

    public long headersObserved() {
        return listener != null ? listener.headersObserved() : 0;
    }

    public long lastObservedSlot() {
        return listener != null ? listener.lastObservedSlot() : -1;
    }

    public long lastObservedBlockNumber() {
        return listener != null ? listener.lastObservedBlockNumber() : -1;
    }

    public void submitTxBytes(String txHash, byte[] txCbor, TxBodyType txBodyType) {
        if (peerClient != null && peerClient.isRunning()) {
            peerClient.submitTxBytes(txHash, txCbor, txBodyType);
        }
    }

    @Override
    public void close() {
        stop();
    }

    public void stop() {
        if (peerClient != null) {
            try {
                peerClient.stop();
            } catch (Exception e) {
                log.debug("Error stopping observer peer {}", peerId, e);
            }
            peerClient = null;
        }
        listener = null;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
