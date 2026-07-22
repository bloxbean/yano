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
