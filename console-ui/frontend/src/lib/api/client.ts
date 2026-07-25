import type { AnchorCommitment, AppChainBlocks, AppChainMessage, AppChainStatus, ChainSummary,
  CommittedQueryResult,
  EffectPage, EffectStats, NodeConfig, NodePeers, NodeStatus, PluginBundleDetail, PluginBundlePage,
  PluginOperationsSummary, ProofVerificationRequest, ProofVerificationResult, StateProofEnvelope,
  StorageStatus } from './types';

const API_STORAGE_KEY = 'yano.console.api-base.v1';
const KEY_STORAGE_KEY = 'yano.console.api-key.v1';
export const PLUGIN_DISCOVERY_PATH = '/ui/plugins/api-prefix.json';
let memoryKey = '';

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
  localStorage.setItem(API_STORAGE_KEY, normalizeApiBase(apiBase));
  memoryKey = apiKey;
  if (persistKey && apiKey) localStorage.setItem(KEY_STORAGE_KEY, apiKey);
  else localStorage.removeItem(KEY_STORAGE_KEY);
}

export function currentApiKey(): string {
  return memoryKey || localStorage.getItem(KEY_STORAGE_KEY) || '';
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
  constructor(public readonly base: string, private readonly apiKey = currentApiKey()) {}

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
    if (!response.ok) throw new ApiError(response.status, `Request failed (${response.status})`);
    return response.json() as Promise<T>;
  }

  config(signal?: AbortSignal) { return this.json<NodeConfig>('/node/config', signal); }
  status(signal?: AbortSignal) { return this.json<NodeStatus>('/node/status', signal); }
  peers(signal?: AbortSignal) { return this.json<NodePeers>('/node/peers', signal); }
  storage(signal?: AbortSignal) { return this.json<StorageStatus>('/status', signal); }
  chains(signal?: AbortSignal) { return this.json<ChainSummary[]>('/app-chain/chains', signal); }
  chainStatus(chainId: string, signal?: AbortSignal) {
    return this.json<AppChainStatus>(`${chainPath(chainId)}/status`, signal);
  }
  chainBlocks(chainId: string, signal?: AbortSignal) {
    return this.json<AppChainBlocks>(`${chainPath(chainId)}/blocks?limit=12`, signal);
  }
  chainMessage(chainId: string, messageId: string, signal?: AbortSignal) {
    return this.json<AppChainMessage>(`${chainPath(chainId)}/messages/${encodeURIComponent(messageId)}`, signal);
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
      `${chainPath(chainId)}/proof/${encodeURIComponent(keyHex)}${query}`, signal);
  }
  verifyChainProof(chainId: string, request: ProofVerificationRequest, signal?: AbortSignal) {
    return this.post<ProofVerificationResult>(
      `${chainPath(chainId)}/proof/verify`, request, signal);
  }
  chainAnchorCommitment(chainId: string, signal?: AbortSignal) {
    return this.json<AnchorCommitment>(`${chainPath(chainId)}/anchor/commitment`, signal);
  }
  domain(bundleId: string, path: string, parameters: Record<string, string>, signal?: AbortSignal) {
    const query = new URLSearchParams(parameters).toString();
    const safePath = path.split('/').map(encodeURIComponent).join('/');
    return this.json<Record<string, unknown>>(
      `/plugins/${encodeURIComponent(bundleId)}/${safePath}${query ? `?${query}` : ''}`, signal);
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
