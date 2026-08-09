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
  stateCommitment?: StateCommitmentStatus;
}

export interface StateCommitmentStatus {
  schemaVersion: number;
  profile: string;
  backend: string;
  commitmentFormatId: string;
  proofEncodingId: string;
  nativeVersioning: boolean;
  physicalDelete: boolean;
  formatFingerprint: string;
  genesisId: string;
  legacy: boolean;
  version: number;
  stateRoot: string;
  oldestProvableHeight: number;
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

export interface MessageInclusionProof {
  schemaVersion: number;
  treeId: string;
  chainId: string;
  blockHeight: number;
  blockHash: string;
  messagesRoot: string;
  messageId: string;
  messageIndex: number;
  leafCount: number;
  siblings: string[];
}

export interface AppChainBlockDetail {
  chainId: string;
  height: number;
  prevHash: string;
  timestamp: number;
  messagesRoot: string;
  stateRoot: string;
  proposer: string;
  certSignatures: number;
  messages: AppChainMessage[];
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
  stateCommitment?: StateCommitmentStatus;
  capabilityManifest?: AppCapabilityManifest;
}

export interface UiExtensionCatalogEntry {
  bundleId: string;
  extensionId: string;
  title: string;
  mountPoint: 'app-chain';
  uiApiVersion: 1;
  assetsDigest: string;
  entrypointUrl: string;
  requiredCapabilities: string[];
  permissions: string[];
}

export interface AppCapabilityComponent {
  id: string;
  version: string;
  configurationId: string;
  stateNamespace: string;
  topics: string[];
  querySubjects: string[];
  origin: 'INTRINSIC' | 'COMPOSED' | 'LAUNCHER_ENABLED' | 'RUNTIME_CONFIGURED';
}

export interface AppCapabilityWorkflow {
  id: string;
  version: string;
  participantComponentIds: string[];
  topic: string;
  effectTypes: string[];
  origin: AppCapabilityComponent['origin'];
}

export interface AppCrossCuttingCapability {
  capabilityId: string;
  version: string;
  enabled: boolean;
  configurationDigest: string;
  attributes: Record<string, string>;
  origin: AppCapabilityComponent['origin'];
}

export interface AppProofSubject {
  subjectId: string;
  componentId: string;
  keyNamespace: string;
  verificationTarget: string;
}

export interface AppCapabilityManifest {
  schemaVersion: number;
  applicationId: string;
  applicationVersion: string;
  manifestDigest: string;
  components: AppCapabilityComponent[];
  workflows: AppCapabilityWorkflow[];
  crossCutting: AppCrossCuttingCapability[];
  proofSubjects: AppProofSubject[];
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
  proofSchemaVersion?: number;
  profile?: string;
  backend?: string;
  commitmentFormatId?: string;
  formatFingerprint?: string;
  genesisId?: string;
  legacy?: boolean;
  proofEncodingId?: string;
  presence?: 'PRESENT' | 'ABSENT' | 'TOMBSTONED';
  version?: number;
  blockHash?: string;
  block?: Record<string, unknown>;
  finalityCertificate?: Record<string, unknown>;
}

export interface ProofVerificationRequest {
  mode: 'inclusion' | 'exclusion';
  profile?: string;
  presence?: 'PRESENT' | 'ABSENT' | 'TOMBSTONED';
  expectedRootHex: string;
  keyHex: string;
  valueHex?: string;
  proofWireHex: string;
}

export interface ProofVerificationResult {
  valid: boolean;
  mode: 'inclusion' | 'exclusion';
  profile: string;
  presence: 'PRESENT' | 'ABSENT' | 'TOMBSTONED';
  expectedRoot: string;
  key: string;
  verifier: string;
}

export interface AuthenticatedSnapshotSummary {
  seriesId: string;
  sequence: number;
  snapshotId: string;
  entryCount: number;
  completedAppChainHeight: number;
  profile: string;
  lifecycle: string;
}

export interface AuthenticatedSnapshotPage {
  items: AuthenticatedSnapshotSummary[];
  nextCursor: string | null;
  viewHeight: number;
  viewRootHex: string;
}

export interface AuthenticatedSnapshotStatus {
  enabled: boolean;
  series?: string[];
  tipHeight?: number;
  storage?: string;
  seriesDetails?: Array<{
    seriesId?: string;
    schemaId?: string;
    profile?: string;
    trigger?: string;
    proofWireVersion?: string;
    verificationTarget?: string;
    recoveryMode?: string;
    latestSequence?: number;
    latestLifecycle?: string;
  }>;
  proofMaxConcurrency?: number;
  proofAvailablePermits?: number;
  disputed?: boolean;
  disputeReason?: string;
  retentionEnabled?: boolean;
  keepOnlineCount?: number;
  mpfPruningEnabled?: boolean;
}

export interface AuthenticatedSnapshotDescriptor {
  seriesId: string;
  sequence: number;
  snapshotId: string;
  snapshotProfile: string;
  snapshotFormatFingerprintHex: string;
  snapshotProofWireVersion: string;
  snapshotRootHex: string;
  sourceDatasetRootHex: string;
  sourceCommitmentAlgorithm: string;
  sourceCommitmentWireVersion: string;
  schemaId: string;
  entryCount: number;
  baseAppChainHeight: number;
  completedAppChainHeight: number;
  coveredFromHeight: number;
  coveredThroughHeight: number;
  previousSnapshotCommitmentHex: string;
  recoveryCoverage: string;
  complete: boolean;
  descriptorCborHex?: string;
  descriptorCommitmentHex?: string;
}

export interface AuthenticatedSnapshotJob {
  id: string;
  operation: string;
  seriesId: string;
  sequence: number;
  state: string;
  result?: string;
  errorType?: string;
  startedAt: number;
  completedAt?: number;
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
  trustWarning?: string;
  stateCommitment?: StateCommitmentStatus;
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

export interface MessageSubmitResult {
  messageId: string;
  chainId: string;
  topic: string;
}

// ADR-025.2 authenticated-map domain API. Exact-record routes carry proofKey/
// recordValue with verificationLevel "authenticated-record"; pending pages are
// deterministic projections and carry sourceIndexProofKey/queryValue with
// verificationLevel "DERIVED_FROM_PENDING_INDEX" instead. Composed
// role-workflow routes omit apiVersion/verificationLevel.
export interface AuthMapEnvelope<T> {
  apiVersion?: string;
  chainId: string;
  stateMachineId: string;
  committedHeight: number;
  stateRoot: string;
  proofKey?: string;
  recordValue?: string;
  verificationLevel?: string;
  currentPointerProofKey?: string;
  currentPointerValue?: string;
  sourceIndexProofKey?: string;
  queryValue?: string;
  record: T;
}

export interface AuthMapCollection {
  id: string;
  authorization: number;
  authorizationPolicy: string;
  restoreAllowed: boolean;
  maxKeyBytes: number;
  maxValueBytes: number;
  valueEncoding: number;
  validator: string;
}

export interface AuthMapMetadataRecord {
  type: 'metadata';
  apiVersion: string;
  profile: string;
  genesisId: string;
  governed: boolean;
  collections: AuthMapCollection[];
}

export interface AuthMapEntryRecord {
  type: 'entry';
  collectionId: string;
  applicationKey: string;
  presence: number;
  revision?: number;
  status?: number;
  logicalValueHash?: string;
}

export interface AuthMapReceiptRecord {
  type: 'receipt';
  messageId: string;
  presence: number;
  status?: number;
  errorCode?: number;
  actionCommitment?: string;
}

export interface AuthMapDirectConsumptionRecord {
  type: 'direct-consumption';
  actorId: string;
  authorizationId: string;
  actionCommitment: string;
  appliedHeight: number;
  policyId: string;
  policyRevision: number;
}

export interface AuthMapApprovalConsumptionRecord {
  type: 'approval-consumption';
  proposalId: string;
  actionCommitment: string;
  appliedHeight: number;
  policyId: string;
  policyRevision: number;
}

export interface AuthMapDirectPolicyRecord {
  type: 'direct-policy';
  policyId: string;
  revision: number;
  status: string;
  requiredRole: string;
  maximumAuthorizationLifetimeBlocks: number;
}

export interface AuthMapAuthorityRecord {
  type: 'administrator-authority';
  authorityId: string;
  revision: number;
  administratorActors: string[];
  threshold: number;
}

export interface AuthMapGovernanceMutationRecord {
  type: 'governance-mutation';
  mutationId: string;
  status: string;
  authorityId: string;
  authorityRevision: number;
  expiryHeight: number;
}

export interface AuthMapCommandResultRecord {
  type: 'command-result';
  commandKind: number;
  subjectId: string;
  resultCode: string;
  appliedHeight: number;
}

export interface AuthMapPendingRecord {
  type: 'pending-approvals' | 'pending-governance';
  ids: string[];
  nextAfterId: string;
}

export interface RoleOrganizationRecord {
  type: 'organization';
  organizationId: string;
  revision: number;
  status: string;
  metadataCommitment: string;
}

export interface RoleActorKey {
  keyId: string;
  publicKey: string;
  validFromHeight: number;
  validUntilHeight: number;
  status: string;
}

export interface RoleActorRecord {
  type: 'actor';
  actorId: string;
  organizationId: string;
  revision: number;
  status: string;
  roles: string[];
  keys: RoleActorKey[];
}

export interface RolePolicyClause {
  clauseId: string;
  role: string;
  minimumCount: number;
  distinctBy: string;
}

export interface RolePolicyRecord {
  type: 'policy';
  policyId: string;
  revision: number;
  proposerRoles: string[];
  clauses: RolePolicyClause[];
  rejectionMode: string;
  maximumLifetimeBlocks: number;
}

export interface RoleProposalDecision {
  decision: string;
  actorId: string;
  organizationId: string;
  organizationRevision: number;
  role: string;
  clauseId: string;
  actorRevision: number;
  keyId: string;
  acceptedHeight: number;
}

export interface RoleProposalRecord {
  type: 'proposal';
  proposalId: string;
  policyId: string;
  policyRevision: number;
  payloadDomain: string;
  payloadHash: string;
  deadlineHeight: number;
  status: string;
  proposerActorId: string;
  proposerOrganizationId: string;
  proposerRole: string;
  decisions: RoleProposalDecision[];
}

export interface RoleApprovalStatsRecord {
  type: 'approval-stats';
  created: number;
  pending: number;
  approved: number;
  rejected: number;
  cancelled: number;
  expired: number;
}
