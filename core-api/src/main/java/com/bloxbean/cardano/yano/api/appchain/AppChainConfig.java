package com.bloxbean.cardano.yano.api.appchain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration of one app chain this node participates in.
 * See adr/app-layer/005-yano-app-chain-framework.md.
 *
 * @param chainId           app-chain identity (group-scoped namespace)
 * @param signingKeyHex     this member's Ed25519 private key (hex, 32-byte seed)
 * @param memberKeysHex     Ed25519 public keys (hex) of all group members; senders
 *                          must be in this set for a message to be admitted
 * @param peers             app-group peers this node connects to for diffusion
 * @param maxMessageBytes   max opaque body size accepted/offered
 * @param maxTtlSeconds     upper bound on message time-to-live
 * @param defaultTtlSeconds TTL applied to locally submitted messages
 * @param proposerKeyHex    fixed sequencer's public key (hex); empty = sequencing
 *                          disabled (diffusion-only mode)
 * @param threshold         finality-cert signature threshold (n of members)
 * @param blockIntervalMs   proposer tick interval
 * @param maxBlockMessages  max messages per app block
 * @param stateMachineId    built-in state machine id ("ordered-log") — custom
 *                          state machines are supplied programmatically or via
 *                          plugins (AppStateMachineProvider)
 * @param ledgerPath        RocksDB directory for the app ledger; null = derive
 *                          from the node storage path
 * @param anchor            L1 anchoring policy; null = anchoring disabled
 * @param l1StabilityDepth  minimum depth (blocks) of the L1 reference carried in
 *                          app blocks; 0 = no L1 reference
 * @param webhookUrls       webhook sinks receiving finalized blocks
 *                          (at-least-once, ordered, per-sink persisted cursor)
 * @param retentionEnabled  prune message bodies below the last L1_FINAL anchor
 *                          (headers/roots/certs/ids kept — proofs stay valid)
 * @param retentionKeepBlocks keep bodies of at least this many most-recent
 *                          blocks regardless of the anchor horizon
 */
public record AppChainConfig(String chainId,
                             String signingKeyHex,
                             Set<String> memberKeysHex,
                             List<AppPeer> peers,
                             int maxMessageBytes,
                             long maxTtlSeconds,
                             long defaultTtlSeconds,
                             String proposerKeyHex,
                             int threshold,
                             long blockIntervalMs,
                             int maxBlockMessages,
                             String stateMachineId,
                             String ledgerPath,
                             AnchorConfig anchor,
                             int l1StabilityDepth,
                             List<String> webhookUrls,
                             boolean retentionEnabled,
                             int retentionKeepBlocks,
                             Map<String, String> pluginSettings) {

    public static final int DEFAULT_MAX_MESSAGE_BYTES = 65536;
    public static final long DEFAULT_MAX_TTL_SECONDS = 3600;
    public static final long DEFAULT_DEFAULT_TTL_SECONDS = 600;
    public static final long DEFAULT_BLOCK_INTERVAL_MS = 2000;
    public static final int DEFAULT_MAX_BLOCK_MESSAGES = 500;
    public static final String DEFAULT_STATE_MACHINE = "ordered-log";

    public AppChainConfig {
        Objects.requireNonNull(chainId, "chainId");
        if (chainId.isBlank())
            throw new IllegalArgumentException("chainId must not be blank");
        Objects.requireNonNull(signingKeyHex, "signingKeyHex (yano.app-chain.signing-key) is required");
        memberKeysHex = memberKeysHex != null ? Set.copyOf(memberKeysHex) : Set.of();
        peers = peers != null ? List.copyOf(peers) : List.of();
        if (maxMessageBytes <= 0)
            maxMessageBytes = DEFAULT_MAX_MESSAGE_BYTES;
        if (maxTtlSeconds <= 0)
            maxTtlSeconds = DEFAULT_MAX_TTL_SECONDS;
        if (defaultTtlSeconds <= 0)
            defaultTtlSeconds = DEFAULT_DEFAULT_TTL_SECONDS;
        proposerKeyHex = proposerKeyHex != null ? proposerKeyHex.trim() : "";
        if (threshold <= 0)
            threshold = 1;
        if (blockIntervalMs <= 0)
            blockIntervalMs = DEFAULT_BLOCK_INTERVAL_MS;
        if (maxBlockMessages <= 0)
            maxBlockMessages = DEFAULT_MAX_BLOCK_MESSAGES;
        if (stateMachineId == null || stateMachineId.isBlank())
            stateMachineId = DEFAULT_STATE_MACHINE;
        webhookUrls = webhookUrls != null ? List.copyOf(webhookUrls) : List.of();
        if (retentionKeepBlocks < 0)
            retentionKeepBlocks = 0;
        pluginSettings = pluginSettings != null ? Map.copyOf(pluginSettings) : Map.of();
    }

    public static Builder builder(String chainId) {
        return new Builder(chainId);
    }

    /** Fluent builder — preferred over the canonical constructor for source stability. */
    public static final class Builder {
        private final String chainId;
        private String signingKeyHex;
        private Set<String> memberKeysHex = Set.of();
        private List<AppPeer> peers = List.of();
        private int maxMessageBytes = DEFAULT_MAX_MESSAGE_BYTES;
        private long maxTtlSeconds = DEFAULT_MAX_TTL_SECONDS;
        private long defaultTtlSeconds = DEFAULT_DEFAULT_TTL_SECONDS;
        private String proposerKeyHex = "";
        private int threshold = 1;
        private long blockIntervalMs = DEFAULT_BLOCK_INTERVAL_MS;
        private int maxBlockMessages = DEFAULT_MAX_BLOCK_MESSAGES;
        private String stateMachineId = DEFAULT_STATE_MACHINE;
        private String ledgerPath;
        private AnchorConfig anchor;
        private int l1StabilityDepth;
        private List<String> webhookUrls = List.of();
        private boolean retentionEnabled;
        private int retentionKeepBlocks;
        private Map<String, String> pluginSettings = Map.of();

        private Builder(String chainId) {
            this.chainId = chainId;
        }

        public Builder signingKeyHex(String value) { this.signingKeyHex = value; return this; }
        public Builder memberKeysHex(Set<String> value) { this.memberKeysHex = value; return this; }
        public Builder peers(List<AppPeer> value) { this.peers = value; return this; }
        public Builder maxMessageBytes(int value) { this.maxMessageBytes = value; return this; }
        public Builder maxTtlSeconds(long value) { this.maxTtlSeconds = value; return this; }
        public Builder defaultTtlSeconds(long value) { this.defaultTtlSeconds = value; return this; }
        public Builder proposerKeyHex(String value) { this.proposerKeyHex = value; return this; }
        public Builder threshold(int value) { this.threshold = value; return this; }
        public Builder blockIntervalMs(long value) { this.blockIntervalMs = value; return this; }
        public Builder maxBlockMessages(int value) { this.maxBlockMessages = value; return this; }
        public Builder stateMachineId(String value) { this.stateMachineId = value; return this; }
        public Builder ledgerPath(String value) { this.ledgerPath = value; return this; }
        public Builder anchor(AnchorConfig value) { this.anchor = value; return this; }
        public Builder l1StabilityDepth(int value) { this.l1StabilityDepth = value; return this; }
        public Builder webhookUrls(List<String> value) { this.webhookUrls = value; return this; }
        public Builder retentionEnabled(boolean value) { this.retentionEnabled = value; return this; }
        public Builder retentionKeepBlocks(int value) { this.retentionKeepBlocks = value; return this; }
        public Builder pluginSettings(Map<String, String> value) { this.pluginSettings = value; return this; }

        public AppChainConfig build() {
            return new AppChainConfig(chainId, signingKeyHex, memberKeysHex, peers,
                    maxMessageBytes, maxTtlSeconds, defaultTtlSeconds, proposerKeyHex,
                    threshold, blockIntervalMs, maxBlockMessages, stateMachineId,
                    ledgerPath, anchor, l1StabilityDepth, webhookUrls,
                    retentionEnabled, retentionKeepBlocks, pluginSettings);
        }
    }

    /** Sequencing enabled when a proposer is configured. */
    public boolean sequencingEnabled() {
        return !proposerKeyHex.isEmpty();
    }

    public boolean anchoringEnabled() {
        return anchor != null && anchor.enabled();
    }

    /**
     * L1 anchoring policy (metadata mode, ADR app-layer/005 D4).
     *
     * @param enabled            anchor at all
     * @param signingKeyHex      anchor wallet Ed25519 payment key (hex 32-byte seed)
     * @param everyBlocks        anchor when this many new app blocks finalized
     * @param maxIntervalMinutes anchor at least this often while blocks are pending
     * @param metadataLabel      tx metadata label (default 7014)
     */
    public record AnchorConfig(boolean enabled,
                               String signingKeyHex,
                               long everyBlocks,
                               long maxIntervalMinutes,
                               long metadataLabel) {
        public AnchorConfig {
            if (enabled && (signingKeyHex == null || signingKeyHex.isBlank()))
                throw new IllegalArgumentException(
                        "yano.app-chain.anchor.signing-key is required when anchoring is enabled");
            if (everyBlocks <= 0)
                everyBlocks = 10;
            if (maxIntervalMinutes <= 0)
                maxIntervalMinutes = 60;
            if (metadataLabel <= 0)
                metadataLabel = 7014;
        }
    }

    /** One app-group peer endpoint. */
    public record AppPeer(String host, int port) {
        public AppPeer {
            Objects.requireNonNull(host, "host");
            if (port <= 0 || port > 65535)
                throw new IllegalArgumentException("Invalid app peer port: " + port);
        }

        /** Parse "host:port". */
        public static AppPeer parse(String value) {
            int idx = value.lastIndexOf(':');
            if (idx <= 0)
                throw new IllegalArgumentException("App peer must be host:port, got: " + value);
            return new AppPeer(value.substring(0, idx).trim(),
                    Integer.parseInt(value.substring(idx + 1).trim()));
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
