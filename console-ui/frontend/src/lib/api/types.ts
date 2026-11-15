export interface NodeConfig {
  protocolMagic: number;
  network?: string;
  version?: string;
  clientEnabled?: boolean;
  serverEnabled?: boolean;
}

export interface NodeStatus {
  running?: boolean;
  syncing?: boolean;
  serverRunning?: boolean;
  blocksProcessed?: number;
  localTipSlot?: number;
  localTipBlockNumber?: number;
  remoteTipSlot?: number;
  remoteTipBlockNumber?: number;
  initialSyncComplete?: boolean;
  syncMode?: string;
  statusMessage?: string;
  runtimeDegraded?: boolean;
  upstreamMode?: string;
  upstreamActivePeer?: string;
  upstreamTxForwarding?: string;
  relayAdvertisedHost?: string;
  relayAdvertisedPort?: number;
  relayInboundConnectionCount?: number;
  relayOutboundConnectionCount?: number;
  relayEstablishedConnectionCount?: number;
  relayRejectedInboundConnections?: number;
  relayFailedOutboundConnections?: number;
  relayKnownPeerCount?: number;
  relayColdPeerCount?: number;
  relayWarmPeerCount?: number;
  relayHotPeerCount?: number;
  relayBackoffPeerCount?: number;
  relayQuarantinedPeerCount?: number;
  relaySharablePeerCount?: number;
  relayInboundPeerCount?: number;
  relayGossipPeerCount?: number;
  relayLedgerPeerCount?: number;
  relayBootstrapPeerCount?: number;
  relayGovernorTargetHotPeers?: number;
  relayGovernorTargetWarmPeers?: number;
  upstreamValidationLevel?: string;
  upstreamValidationAcceptedHeaders?: number;
  upstreamValidationRejectedHeaders?: number;
  mempoolSize?: number;
  mempoolBytes?: number;
  mempoolMaxTxs?: number;
  mempoolMaxBytes?: number;
  mempoolTtlSeconds?: number;
  mempoolAccepting?: boolean;
  txDiffusionMode?: string;
  txDiffusionOutboundForwarded?: number;
  txDiffusionOutboundSuppressed?: number;
  txDiffusionInboundTxIdsRequested?: number;
  txDiffusionInboundTxIdsIgnored?: number;
  txDiffusionInboundTxIdsRejected?: number;
  txDiffusionInboundTxBodiesAccepted?: number;
  txDiffusionInboundTxBodiesRejected?: number;
  txDiffusionInboundTxBodiesIgnored?: number;
  txDiffusionServedTxs?: number;
  txDiffusionServedBytes?: number;
  txDiffusionInFlightTxs?: number;
  txDiffusionInFlightBytes?: number;
}

export interface StorageStatus {
  chain?: { blockNumber?: number; slot?: number; blockHash?: string };
  utxo?: {
    enabled?: boolean;
    store?: string;
    lastAppliedBlock?: number;
    lastAppliedSlot?: number;
    lagBlocks?: number;
    prune?: Record<string, unknown>;
    metrics?: Record<string, number>;
    cfEstimates?: Record<string, number>;
  };
  cfEstimates?: Record<string, number>;
  history?: ArchiveHistoryStatus;
}

/** Read-only projection archive status published by GET /status. */
export interface ArchiveHistoryStatus {
  enabled?: boolean;
  available?: boolean;
  /** 'projection' for an ADR-039 archive; absent when reads are not projection-backed. */
  source?: string;
  /** Logical dataset names this archive projects, e.g. 'transaction', 'utxo-history'. */
  datasets?: string[];
  /** Highest block committed to the sink, or -1 when nothing has committed yet. */
  committedThroughBlock?: number;
}

/**
 * GET /history/coverage - what the projection archive can answer for right now.
 *
 * Block-number fields carry -1 for "unknown", never 0. Blocks above
 * queryableThroughBlock are not yet committed: treat them as unknown, not absent.
 */
export interface ProjectionCoverage {
  enabled?: boolean;
  /**
   * Why the archive could not initialise, when it could not.
   *
   * Present instead of the fields below, not alongside them: a node whose projection failed to
   * start keeps serving, so this endpoint reports the reason rather than throwing.
   */
  error?: string;
  /** Projection identity fingerprint: network, sink engine, version, section wire names. */
  identity?: string;
  sections?: string[];
  /** Pipe-separated artifact contract wire forms; parse with parseArtifactContracts(). */
  artifactContracts?: string;
  sinkHealth?: 'READY' | 'DEGRADED' | 'UNAVAILABLE' | string;
  /** A fresh archive cannot claim coverage from block 0 until genesis is durable. */
  genesisCaptured?: boolean;
  queryableFromBlock?: number;
  queryableThroughBlock?: number;
  tipBlock?: number;
  blocksBehindTip?: number;
  /** ISO-8601 duration, e.g. 'PT2S': upper bound for a final block to become queryable. */
  maxCommitLatency?: string;
  transactionHashLookup?: { mode?: string; correct?: boolean; note?: string };
  note?: string;
}

/** GET /history/watermark - cross-dataset consistency point. */
export interface ProjectionWatermark {
  source?: string;
  available?: boolean;
  /** Why no consistency point is available; present only when available is false. */
  reason?: string;
  /** The projection identity fingerprint, not a numeric generation. */
  generation?: string;
  fromBlock?: number;
  toBlock?: number;
  /** Omitted when the chain cannot resolve the coordinate canonically. */
  asOf?: { blockNumber?: number; slot?: number; blockHash?: string };
  projectionVersions?: Record<string, number>;
}

export interface Peer {
  id?: string;
  endpoint?: string;
  active?: boolean;
  governorState?: string;
  connectionState?: string;
  direction?: string;
  source?: string;
  trusted?: boolean;
  chainSync?: boolean;
  blockFetch?: boolean;
  txSubmission?: boolean;
  peerSharing?: boolean;
  query?: boolean;
  lastSeenMillis?: number;
  connectionReason?: string;
}

export interface NodePeers {
  peers?: Peer[];
  establishedConnectionCount?: number;
  knownPeerCount?: number;
}

export interface PluginOperationsSummary {
  catalogFingerprint?: string;
  generation?: number;
  capturedAtEpochMillis?: number;
  totals?: Record<string, number>;
  healthCounts?: Record<string, number>;
}

export interface PluginBundlePage {
  items?: Array<Record<string, unknown>>;
  nextAfter?: string | null;
}

export interface PluginBundleDetail {
  bundle?: Record<string, unknown>;
}
