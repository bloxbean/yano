import type { NodeConfig, NodePeers, NodeStatus, PluginBundleDetail, PluginBundlePage,
  PluginOperationsSummary, StorageStatus, ProjectionCoverage, ProjectionWatermark } from './types';

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

  config(signal?: AbortSignal) { return this.json<NodeConfig>('/node/config', signal); }
  status(signal?: AbortSignal) { return this.json<NodeStatus>('/node/status', signal); }
  peers(signal?: AbortSignal) { return this.json<NodePeers>('/node/peers', signal); }
  storage(signal?: AbortSignal) { return this.json<StorageStatus>('/status', signal); }
  /** What the projection archive can answer for right now; see ProjectionCoverage. */
  historyCoverage(signal?: AbortSignal) { return this.json<ProjectionCoverage>('/history/coverage', signal); }
  /** Cross-dataset consistency point over the archive's committed range. */
  historyWatermark(signal?: AbortSignal) { return this.json<ProjectionWatermark>('/history/watermark', signal); }
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
