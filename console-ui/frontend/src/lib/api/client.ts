import type { NodeConfig, NodePeers, NodeStatus, StorageStatus } from './types';

const API_STORAGE_KEY = 'yano.console.api-base.v1';
const KEY_STORAGE_KEY = 'yano.console.api-key.v1';
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
      const value = (await response.json()) as { apiPrefix?: unknown };
      if (typeof value.apiPrefix === 'string') return normalizeApiBase(value.apiPrefix);
    }
  } catch {
    // Standalone builds do not have the artifact-specific discovery document.
  }
  return '/api/v1';
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

export class YanoApi {
  constructor(public readonly base: string, private readonly apiKey = currentApiKey()) {}

  async json<T>(path: string, signal?: AbortSignal): Promise<T> {
    const headers = new Headers({ Accept: 'application/json' });
    if (this.apiKey) headers.set('X-API-Key', this.apiKey);
    const response = await fetch(`${this.base}${path}`, {
      headers, signal, cache: 'no-store', redirect: 'error'
    });
    if (!response.ok) throw new ApiError(response.status, `Request failed (${response.status})`);
    return response.json() as Promise<T>;
  }

  config(signal?: AbortSignal) { return this.json<NodeConfig>('/node/config', signal); }
  status(signal?: AbortSignal) { return this.json<NodeStatus>('/node/status', signal); }
  peers(signal?: AbortSignal) { return this.json<NodePeers>('/node/peers', signal); }
  storage(signal?: AbortSignal) { return this.json<StorageStatus>('/status', signal); }
}
