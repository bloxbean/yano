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

export interface ChainSummary {
  chainId: string;
  tipHeight: number;
  stateRoot: string;
}

export interface AppChainBlock {
  height: number;
  timestamp: number;
  stateRoot: string;
  messageCount: number;
  certSignatures: number;
}

export interface AppChainBlocks {
  chainId: string;
  tipHeight: number;
  from: number;
  blocks: AppChainBlock[];
}

export interface AppChainMessage {
  chainId?: string;
  height: number;
  index: number;
  messageId?: string;
  topic?: string;
  sender?: string;
  senderSeq?: number;
  bodyHex?: string;
}

export interface AppChainStatus {
  chainId?: string;
  running?: boolean;
  role?: string;
  sequencing?: boolean;
  stalled?: boolean;
  submissionsPaused?: boolean;
  tipHeight?: number;
  stateRoot?: string;
  stateMachine?: string;
  members?: number;
  threshold?: number;
  configuredBlockIntervalMs?: number;
  blockIntervalMs?: number;
  lastBlockAtMillis?: number;
  l1RefDeferrals?: number;
  poolSize?: number;
  poolCapacity?: number;
  submitted?: number;
  received?: number;
  relayed?: number;
  duplicates?: number;
  storedMessages?: number;
  drops?: Record<string, number>;
  peers?: Record<string, boolean>;
  peerTransports?: Record<string, string>;
  anchor?: Record<string, unknown>;
  sinks?: Record<string, Record<string, unknown>>;
  effects?: Record<string, unknown>;
  stateMachineStatus?: Record<string, unknown>;
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

export interface EffectPage {
  chainId?: string;
  effects?: Array<Record<string, unknown>>;
}

export interface EffectStats {
  chainId?: string;
  stats?: Record<string, unknown>;
}

export interface CommittedQueryResult {
  chainId?: string;
  stateMachineId?: string;
  committedHeight?: number;
  stateRoot?: string;
  payloadHex?: string;
}

export interface StateProofEnvelope {
  key: string;
  chainId: string;
  committedHeight: number;
  stateRoot: string;
  proofWireHex: string;
  valueHex?: string;
  finalizedAtHeight?: number;
}

export interface ProofVerificationRequest {
  mode: 'inclusion' | 'exclusion';
  expectedRootHex: string;
  keyHex: string;
  valueHex?: string;
  proofWireHex: string;
}

export interface ProofVerificationResult {
  valid: boolean;
  mode: 'inclusion' | 'exclusion';
  expectedRoot: string;
  key: string;
  verifier: string;
}

export interface AnchorCommitment {
  chainId: string;
  mode: 'metadata' | 'script' | string;
  anchoredHeight: number;
  stateRoot: string;
  blockHash: string;
  transactionHash: string;
  l1Slot: number;
  provenance: string;
}

export interface EutxoTransactionEntry {
  outpoint: string;
  address: string;
  lovelace: string;
}

export interface EutxoTransactionSummary {
  transactionId: string;
  messageId: string;
  sequence: number;
  appHeight: number;
  ordinal: number;
  l1Slot: number;
  status: 'ACCEPTED' | 'REJECTED';
  authorizationProfile: string;
  inputs: EutxoTransactionEntry[];
  outputs: EutxoTransactionEntry[];
  code: string;
}

export interface EutxoTransactionPage {
  chainId: string;
  stateMachineId: string;
  committedHeight: number;
  stateRoot: string;
  data: EutxoTransactionSummary[];
  nextBefore: number;
}

export interface EutxoTransactionDetail {
  chainId: string;
  stateMachineId: string;
  committedHeight: number;
  stateRoot: string;
  data: EutxoTransactionSummary;
}

export interface EutxoIndexProjection {
  kind: 'DERIVED';
  status: string;
  indexedHeight: number;
  finalizedHeight: number;
  lagBlocks: number;
  historyFromHeight: number;
  fullHistory: boolean;
}

export interface EutxoIndexEnvelope<T> {
  apiVersion: 'eutxo-index/v1';
  chainId: string;
  stateMachineId: 'eutxo-ledger';
  projection: EutxoIndexProjection;
  data: T;
}

export interface EutxoIndexPage<T> {
  items: T[];
  cursor: string;
  scanTruncated?: boolean;
}

export interface EutxoIndexStatus {
  storeType: string;
  checkpointHeight: number;
  finalizedHeight: number;
  lagBlocks: number;
  coverage: 'NONE' | 'PARTIAL' | 'FULL';
  normalizedDigest: string;
  validityAvailable?: boolean;
  diagnosticCode?: string;
}

export interface EutxoValidityBatch {
  batchId: string;
  provider: string;
  proofSystem: string;
  profileId: string;
  profileDigest: string;
  transactionIds: string[];
  previousRoot: string;
  nextRoot: string;
  dataCommitment: string;
  dataStatus: string;
  proofDigest: string;
  verificationKeyDigest: string;
  proofStatus: string;
  settlementStatus: string;
  settlementTransactionId: string;
  settlementSlot: number;
  settlementBlockHash: string;
}

export interface EutxoIndexedAccount {
  address: string;
  lovelace: string;
  utxos: EutxoTransactionEntry[];
  activityTransactionIds: string[];
}

export interface EutxoDeposit {
  acceptedOutpoint: string;
  stagingOutpoint: string;
  mirroredOutpoint: string;
  l2Address: string;
  l1Slot: number;
  l1BlockHash: string;
  creditedHeight: number;
}

export interface EutxoWithdrawal {
  claimId: string;
  status: string;
  withdrawalOutpoint: string;
  destinationAddress: string;
  lovelace: string;
  requestedHeight: number;
  settlementTransactionId: string;
  confirmedSlot: number;
  confirmedBlockHash: string;
  updatedHeight: number;
}

export interface EutxoLineageNode {
  kind: string;
  id: string;
  status: string;
}

export interface EutxoLineage {
  nodes: EutxoLineageNode[];
  edges: Array<{ from: string; to: string; relation: string }>;
  truncated: boolean;
}

export interface L1Transaction {
  hash?: string;
  block?: string;
  block_height?: number;
  block_time?: number;
  slot?: number;
  index?: number;
  output_amount?: Array<{ unit?: string; quantity?: string }>;
  fees?: string;
  invalid_before?: string | null;
  invalid_hereafter?: string | null;
  utxo_count?: number;
}

export interface L1TransactionUtxo {
  tx_hash?: string;
  output_index?: number;
  address?: string;
  amount?: Array<{ unit?: string; quantity?: string }>;
  data_hash?: string | null;
  inline_datum?: string | null;
  reference_script_hash?: string | null;
}

export interface L1TransactionUtxos {
  hash?: string;
  inputs?: L1TransactionUtxo[];
  outputs?: L1TransactionUtxo[];
}
