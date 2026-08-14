import type { AnchorCommitment, AppChainBlockDetail, AppChainBlocks, AppChainMessage, AppChainStatus,
  AuthenticatedSnapshotDescriptor, AuthenticatedSnapshotJob, AuthenticatedSnapshotStatus,
  AuthenticatedSnapshotPage, ChainSummary,
  CommittedQueryResult,
  EffectPage, EffectStats, MessageSubmitResult, NodeConfig, NodePeers, NodeStatus,
  PluginBundleDetail, PluginBundlePage,
  L1Transaction, L1TransactionUtxos, PluginOperationsSummary, ProofVerificationRequest,
  ProofVerificationResult, StateProofEnvelope,
  ProofSubjectDiscovery,
  StorageStatus } from './types';

const API_STORAGE_KEY = 'yano.console.api-base.v1';
const KEY_STORAGE_KEY = 'yano.console.api-key.v1';
const CREDENTIAL_STORAGE_KEY = 'yano.console.api-credential.v2';
export const PLUGIN_DISCOVERY_PATH = '/ui/plugins/api-prefix.json';
let memoryCredential: ApiCredential | null = null;

interface ApiCredential {
  apiBase: string;
  apiKey: string;
}

export function normalizeApiBase(value: string, origin = globalThis.location?.origin ?? 'http://localhost'): string {
  const trimmed = value.trim();
  if (!trimmed) return '/api/v1';
  if (trimmed.startsWith('/')) {
    const path = trimmed.length > 1 && trimmed.endsWith('/') && !trimmed.endsWith('//')
      ? trimmed.slice(0, -1) : trimmed;
    const canonicalPath = /^\/(?:[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*)?$/;
    if (path.length > 256 || !canonicalPath.test(path)
      || path.split('/').some((segment) => segment === '.' || segment === '..')) {
      throw new Error('API path must be a canonical absolute path');
    }
    return path;
  }
  const parsed = new URL(trimmed, origin);
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.hash || parsed.search) {
    throw new Error('API URL must be HTTP(S) without credentials, query, or fragment');
  }
  return parsed.href.replace(/\/+$/, '');
}

export async function resolveApiBase(): Promise<string> {
  const query = new URLSearchParams(location.search).get('api');
  if (query) return normalizeApiBase(query);
  const saved = localStorage.getItem(API_STORAGE_KEY);
  if (saved) {
    try { return normalizeApiBase(saved); } catch { localStorage.removeItem(API_STORAGE_KEY); }
  }
  try {
    const response = await fetch('/ui/api-prefix.json', {
      cache: 'no-store', redirect: 'error', credentials: 'same-origin'
    });
    if (response.ok) {
      const responseUrl = new URL(response.url);
      if (response.redirected || responseUrl.origin !== location.origin
        || responseUrl.pathname !== '/ui/api-prefix.json' || responseUrl.search || responseUrl.hash) {
        throw new Error('API discovery response is not the immutable same-origin asset');
      }
      const value = (await response.json()) as { apiPrefix?: unknown };
      if (typeof value.apiPrefix === 'string') return normalizeApiBase(value.apiPrefix);
    }
  } catch {
    // Standalone builds do not have the artifact-specific discovery document.
  }
  return '/api/v1';
}

export async function resolvePluginApiBase(): Promise<string> {
  const response = await fetch(PLUGIN_DISCOVERY_PATH, {
    cache: 'no-store', redirect: 'error', credentials: 'same-origin',
    headers: { Accept: 'application/json' }
  });
  const responseUrl = new URL(response.url);
  if (!response.ok || response.redirected || responseUrl.origin !== location.origin
    || responseUrl.pathname !== PLUGIN_DISCOVERY_PATH || responseUrl.search || responseUrl.hash) {
    throw new Error('The host plugin API prefix could not be verified');
  }
  const value = (await response.json()) as { apiPrefix?: unknown };
  if (typeof value.apiPrefix !== 'string' || !value.apiPrefix.startsWith('/')
    || value.apiPrefix.includes('%') || value.apiPrefix.includes('+')) {
    throw new Error('The host plugin API prefix could not be verified');
  }
  return normalizeApiBase(value.apiPrefix);
}

export function saveConnection(apiBase: string, apiKey: string, persistKey: boolean): void {
  const normalized = normalizeApiBase(apiBase);
  localStorage.setItem(API_STORAGE_KEY, normalized);
  memoryCredential = apiKey ? { apiBase: normalized, apiKey } : null;
  if (persistKey && apiKey) {
    localStorage.setItem(CREDENTIAL_STORAGE_KEY, JSON.stringify({ apiBase: normalized, apiKey }));
  } else {
    localStorage.removeItem(CREDENTIAL_STORAGE_KEY);
  }
  // v1 stored an unscoped key. It must never be inherited by a query-selected
  // or otherwise different API base.
  localStorage.removeItem(KEY_STORAGE_KEY);
}

export function currentApiKey(apiBase: string): string {
  const normalized = normalizeApiBase(apiBase);
  if (memoryCredential?.apiBase === normalized) return memoryCredential.apiKey;
  const persisted = persistedCredential();
  return persisted?.apiBase === normalized ? persisted.apiKey : '';
}

export function hasPersistedApiKey(apiBase: string): boolean {
  const persisted = persistedCredential();
  return persisted?.apiBase === normalizeApiBase(apiBase) && persisted.apiKey.length > 0;
}

function persistedCredential(): ApiCredential | null {
  // Fail closed during migration from the unscoped v1 credential.
  localStorage.removeItem(KEY_STORAGE_KEY);
  const encoded = localStorage.getItem(CREDENTIAL_STORAGE_KEY);
  if (!encoded || encoded.length > 16_384) return null;
  try {
    const value = JSON.parse(encoded) as Partial<ApiCredential>;
    if (typeof value.apiBase !== 'string' || typeof value.apiKey !== 'string'
      || value.apiKey.length === 0 || value.apiKey.length > 8_192
      || normalizeApiBase(value.apiBase) !== value.apiBase) {
      throw new Error('invalid credential');
    }
    return { apiBase: value.apiBase, apiKey: value.apiKey };
  } catch {
    localStorage.removeItem(CREDENTIAL_STORAGE_KEY);
    return null;
  }
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}

export function apiFailureMessage(cause: unknown, fallback: string): string {
  if (cause instanceof TypeError) {
    return 'Node request failed. If this console is hosted separately, verify the node URL and its exact CORS origin.';
  }
  return cause instanceof Error ? cause.message : fallback;
}

export class YanoApi {
  public readonly base: string;
  private readonly apiKey: string;

  constructor(base: string, apiKey?: string) {
    this.base = normalizeApiBase(base);
    this.apiKey = apiKey ?? currentApiKey(this.base);
  }

  async response(path: string, accept: string, signal?: AbortSignal): Promise<Response> {
    const headers = new Headers({ Accept: accept });
    if (this.apiKey) headers.set('X-API-Key', this.apiKey);
    const response = await fetch(`${this.base}${path}`, {
      headers, signal, cache: 'no-store', redirect: 'error'
    });
    if (!response.ok) throw new ApiError(response.status, `Request failed (${response.status})`);
    return response;
  }

  async json<T>(path: string, signal?: AbortSignal): Promise<T> {
    const response = await this.response(path, 'application/json', signal);
    return response.json() as Promise<T>;
  }

  async post<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
    const headers = new Headers({ Accept: 'application/json' });
    if (body !== undefined) headers.set('Content-Type', 'application/json');
    if (this.apiKey) headers.set('X-API-Key', this.apiKey);
    const response = await fetch(`${this.base}${path}`, {
      method: 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body),
      signal, cache: 'no-store', redirect: 'error'
    });
    if (!response.ok) {
      let detail = '';
      try {
        detail = ((await response.json()) as { error?: string }).error ?? '';
      } catch {
        // Non-JSON error body; the status alone must do.
      }
      throw new ApiError(response.status,
        detail || `Request failed (${response.status})`);
    }
    return response.json() as Promise<T>;
  }

  eutxoL2TransferBuild(
    chainId: string,
    body: { fromAddress: string; toAddress: string; lovelace: number },
    signal?: AbortSignal
  ) {
    return this.post<{ unsignedTxCborHex: string; transactionId: string; submitTopic: string }>(
      `${chainPath(chainId)}/eutxo/bridge/transfer/build`, body, signal);
  }
  eutxoL2ClaimBuild(
    chainId: string,
    body: { fromAddress: string; lovelace: number; payoutAddress?: string },
    signal?: AbortSignal
  ) {
    return this.post<{ unsignedTxCborHex: string; transactionId: string; submitTopic: string; payoutAddress: string }>(
      `${chainPath(chainId)}/eutxo/bridge/claim/build`, body, signal);
  }
  async submitTxHex(signedTxCborHex: string, signal?: AbortSignal): Promise<string> {
    const headers = new Headers({ Accept: 'application/json', 'Content-Type': 'text/plain' });
    if (this.apiKey) headers.set('X-API-Key', this.apiKey);
    const response = await fetch(`${this.base}/tx/submit`, {
      method: 'POST', headers, body: signedTxCborHex, signal, cache: 'no-store', redirect: 'error'
    });
    const text = await response.text();
    if (!response.ok) throw new ApiError(response.status, text || `Request failed (${response.status})`);
    try {
      return (JSON.parse(text) as { txHash?: string }).txHash ?? text.trim();
    } catch {
      return text.trim();
    }
  }

  config(signal?: AbortSignal) { return this.json<NodeConfig>('/node/config', signal); }
  status(signal?: AbortSignal) { return this.json<NodeStatus>('/node/status', signal); }
  peers(signal?: AbortSignal) { return this.json<NodePeers>('/node/peers', signal); }
  storage(signal?: AbortSignal) { return this.json<StorageStatus>('/status', signal); }
  chains(signal?: AbortSignal) { return this.json<ChainSummary[]>('/app-chain/chains', signal); }
  chainStatus(chainId: string, signal?: AbortSignal) {
    return this.json<AppChainStatus>(`${chainPath(chainId)}/status`, signal);
  }
  chainBlocks(chainId: string, signal?: AbortSignal, from?: number, limit = 25) {
    const start = from == null ? '' : `&from=${encodeURIComponent(String(from))}`;
    return this.json<AppChainBlocks>(`${chainPath(chainId)}/blocks?limit=${limit}${start}`, signal);
  }
  chainBlock(chainId: string, height: number, signal?: AbortSignal) {
    return this.json<AppChainBlockDetail>(
      `${chainPath(chainId)}/blocks/${encodeURIComponent(String(height))}`, signal);
  }
  chainMessage(chainId: string, messageId: string, signal?: AbortSignal) {
    return this.json<AppChainMessage>(`${chainPath(chainId)}/messages/${encodeURIComponent(messageId)}`, signal);
  }
  chainMessageProof(chainId: string, messageId: string, signal?: AbortSignal) {
    return this.json<import('./types').MessageInclusionProof>(
      `${chainPath(chainId)}/messages/${encodeURIComponent(messageId)}/proof`, signal);
  }
  chainMessageProofPackage(chainId: string, messageId: string, signal?: AbortSignal) {
    return this.json<Record<string, unknown>>(
      `${chainPath(chainId)}/messages/${encodeURIComponent(messageId)}/proof-package`, signal);
  }
  chainEffects(chainId: string, signal?: AbortSignal) {
    return this.json<EffectPage>(`${chainPath(chainId)}/effects?fromHeight=0&limit=100`, signal);
  }
  chainEffectStats(chainId: string, signal?: AbortSignal) {
    return this.json<EffectStats>(`${chainPath(chainId)}/effects/stats`, signal);
  }
  chainEffectProof(chainId: string, height: number, ordinal: number, signal?: AbortSignal) {
    return this.json<Record<string, unknown>>(
      `${chainPath(chainId)}/effects/${height}/${ordinal}/proof`, signal);
  }
  requeueEffect(chainId: string, height: number, ordinal: number, signal?: AbortSignal) {
    return this.post<Record<string, unknown>>(
      `${chainPath(chainId)}/effects/${height}/${ordinal}/requeue`, undefined, signal);
  }
  cancelEffect(chainId: string, height: number, ordinal: number, reason = 'operator-cancel', signal?: AbortSignal) {
    return this.post<Record<string, unknown>>(
      `${chainPath(chainId)}/effects/${height}/${ordinal}/cancel?reason=${encodeURIComponent(reason)}`, undefined, signal);
  }
  chainQuery(chainId: string, path: string, paramsHex = '', signal?: AbortSignal) {
    return this.post<CommittedQueryResult>(
      `${chainPath(chainId)}/query/${path.split('/').map(encodeURIComponent).join('/')}`, { paramsHex }, signal);
  }
  chainEvidence(chainId: string, messageId: string, signal?: AbortSignal) {
    return this.json<Record<string, unknown>>(`${chainPath(chainId)}/evidence/${encodeURIComponent(messageId)}`, signal);
  }
  chainProof(chainId: string, keyHex: string, height?: number, signal?: AbortSignal) {
    const query = height === undefined ? '' : `?height=${encodeURIComponent(height)}`;
    return this.json<StateProofEnvelope>(
      `${chainPath(chainId)}/state/proof/${encodeURIComponent(keyHex)}${query}`, signal);
  }
  verifyChainProof(chainId: string, request: ProofVerificationRequest, signal?: AbortSignal) {
    return this.post<ProofVerificationResult>(
      `${chainPath(chainId)}/proof/verify`, request, signal);
  }
  chainProofSubjects(chainId: string, signal?: AbortSignal) {
    return this.json<ProofSubjectDiscovery>(`${chainPath(chainId)}/proof-subjects`, signal);
  }
  chainTypedProof(chainId: string, subjectId: string, request: Record<string, unknown>,
    signal?: AbortSignal) {
    return this.post<Record<string, unknown>>(
      `${chainPath(chainId)}/proof-subjects/${encodeURIComponent(subjectId)}/proof`, request, signal);
  }
  chainTypedProofPackage(chainId: string, subjectId: string, request: Record<string, unknown>,
    signal?: AbortSignal) {
    return this.post<Record<string, unknown>>(
      `${chainPath(chainId)}/proof-subjects/${encodeURIComponent(subjectId)}/package`, request, signal);
  }
  chainOnChainProofExport(chainId: string, subjectId: string, request: Record<string, unknown>,
    signal?: AbortSignal) {
    return this.post<Record<string, unknown>>(
      `${chainPath(chainId)}/proof-subjects/${encodeURIComponent(subjectId)}/onchain-export`,
      request, signal);
  }
  chainAnchorCommitment(chainId: string, signal?: AbortSignal) {
    return this.json<AnchorCommitment>(`${chainPath(chainId)}/anchor/commitment`, signal);
  }
  chainStateIdentity(chainId: string, signal?: AbortSignal) {
    return this.json<Record<string, unknown>>(`${chainPath(chainId)}/state/identity`, signal);
  }
  chainSnapshotStatus(chainId: string, signal?: AbortSignal) {
    return this.json<AuthenticatedSnapshotStatus>(`${chainPath(chainId)}/snapshots/status`, signal);
  }
  chainSnapshots(chainId: string, series?: string, cursor?: string, limit = 20,
                 signal?: AbortSignal) {
    const parameters = new URLSearchParams({
      limit: String(Math.min(100, Math.max(1, limit)))
    });
    if (series) parameters.set('series', series);
    if (cursor) parameters.set('cursor', cursor);
    return this.json<AuthenticatedSnapshotPage>(
      `${chainPath(chainId)}/snapshots?${parameters.toString()}`, signal);
  }
  chainSnapshot(chainId: string, series: string, sequence: number, signal?: AbortSignal) {
    return this.json<AuthenticatedSnapshotDescriptor>(
      `${chainPath(chainId)}/snapshots/${encodeURIComponent(series)}/${encodeURIComponent(sequence)}`, signal);
  }
  chainSnapshotProof(chainId: string, series: string, sequence: number, keyHex: string,
                     signal?: AbortSignal) {
    return this.post<Record<string, unknown>>(
      `${chainPath(chainId)}/snapshots/${encodeURIComponent(series)}/${encodeURIComponent(sequence)}/proof`,
      { keyHex }, signal);
  }
  verifyChainSnapshotProof(chainId: string, request: Record<string, unknown>, signal?: AbortSignal) {
    return this.post<Record<string, unknown>>(`${chainPath(chainId)}/snapshots/proof/verify`, request, signal);
  }
  operateChainSnapshot(chainId: string, series: string, sequence: number,
                       operation: 'archive' | 'restore' | 'evict', idempotencyKey: string,
                       evictAfterArchive = false, signal?: AbortSignal) {
    return this.post<{ jobId: string; operation: string }>(
      `${chainPath(chainId)}/admin/snapshots/${encodeURIComponent(series)}/${encodeURIComponent(sequence)}/${operation}`,
      { idempotencyKey, evictAfterArchive }, signal);
  }
  chainSnapshotJobs(chainId: string, limit = 100, signal?: AbortSignal) {
    return this.json<AuthenticatedSnapshotJob[]>(
      `${chainPath(chainId)}/admin/snapshots/jobs?limit=${Math.min(1000, Math.max(1, limit))}`, signal);
  }
  chainSnapshotJob(chainId: string, jobId: string, signal?: AbortSignal) {
    return this.json<AuthenticatedSnapshotJob>(
      `${chainPath(chainId)}/admin/snapshots/jobs/${encodeURIComponent(jobId)}`, signal);
  }
  domain<T = Record<string, unknown>>(
    bundleId: string, path: string, parameters: Record<string, string>, signal?: AbortSignal
  ) {
    const query = new URLSearchParams(parameters).toString();
    const safePath = path.split('/').map(encodeURIComponent).join('/');
    return this.json<T>(
      `/plugins/${encodeURIComponent(bundleId)}/${safePath}${query ? `?${query}` : ''}`, signal);
  }
  eutxoIndex<T>(
    path: string, parameters: Record<string, string>, signal?: AbortSignal
  ) {
    return this.domain<T>(
      'com.bloxbean.cardano.yano.appchain.eutxo.indexer',
      `index/v1/${path}`, parameters, signal);
  }
  authenticatedMapDomain<T>(
    path: string, parameters: Record<string, string>, signal?: AbortSignal
  ) {
    return this.domain<T>(
      'com.bloxbean.cardano.yano.appchain.stdlib', path, parameters, signal);
  }
  chainSubmitMessage(chainId: string, topic: string, bodyHex: string, signal?: AbortSignal) {
    return this.post<MessageSubmitResult>(
      `${chainPath(chainId)}/messages`, { topic, bodyHex }, signal);
  }
  eutxoBridgeInfo(chainId: string, signal?: AbortSignal) {
    return this.json<import('$lib/eutxo/deposit').EutxoBridgeInfo>(
      `${chainPath(chainId)}/eutxo/bridge/info`, signal);
  }
  eutxoDepositBuild(
    chainId: string,
    body: { depositorAddress: string; lovelace: number; l2OwnerAddress?: string },
    signal?: AbortSignal
  ) {
    return this.post<import('$lib/eutxo/deposit').DepositBuildResponse>(
      `${chainPath(chainId)}/eutxo/bridge/deposit/build`, body, signal);
  }
  eutxoDepositAssemble(
    chainId: string,
    body: { unsignedTxCborHex: string; witnessSetCborHex: string },
    signal?: AbortSignal
  ) {
    return this.post<import('$lib/eutxo/deposit').DepositAssembleResponse>(
      `${chainPath(chainId)}/eutxo/bridge/deposit/assemble`, body, signal);
  }
  l1Transaction(transactionId: string, signal?: AbortSignal) {
    return this.json<L1Transaction>(`/txs/${encodeURIComponent(transactionId)}`, signal);
  }
  l1TransactionUtxos(transactionId: string, signal?: AbortSignal) {
    return this.json<L1TransactionUtxos>(
      `/txs/${encodeURIComponent(transactionId)}/utxos`, signal);
  }
  chainStream(chainId: string, fromHeight: number, signal?: AbortSignal) {
    return this.response(`${chainPath(chainId)}/stream?fromHeight=${Math.max(1, fromHeight)}`,
      'text/event-stream', signal);
  }
  pluginSummary(signal?: AbortSignal) {
    return this.json<PluginOperationsSummary>('/plugin-operations', signal);
  }
  pluginBundles(after: string | null, limit = 100, signal?: AbortSignal) {
    const cursor = after ? `&after=${encodeURIComponent(after)}` : '';
    return this.json<PluginBundlePage>(`/plugin-operations/bundles?limit=${limit}${cursor}`, signal);
  }
  pluginBundle(id: string, signal?: AbortSignal) {
    return this.json<PluginBundleDetail>(`/plugin-operations/bundles/${encodeURIComponent(id)}`, signal);
  }
}

function chainPath(chainId: string): string {
  return `/app-chain/chains/${encodeURIComponent(chainId)}`;
}
