import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFailureMessage, currentApiKey, hasPersistedApiKey, normalizeApiBase, resolveApiBase,
  resolvePluginApiBase, saveConnection, YanoApi } from './client';

describe('Yano API client', () => {
  beforeEach(() => {
    localStorage.clear();
    history.replaceState({}, '', '/ui/status/');
    vi.restoreAllMocks();
  });

  it('normalizes safe API bases and rejects credential-bearing URLs', () => {
    expect(normalizeApiBase('/api/v1/')).toBe('/api/v1');
    expect(normalizeApiBase('https://node.example/api/v1/')).toBe('https://node.example/api/v1');
    expect(() => normalizeApiBase('https://user:secret@node.example/api')).toThrow();
    expect(() => normalizeApiBase('/api/v1?next=evil')).toThrow();
    expect(() => normalizeApiBase('/api//v1')).toThrow();
    expect(() => normalizeApiBase('/api/../admin')).toThrow();
  });

  it('uses explicit query, persisted choice, discovery, then fallback', async () => {
    history.replaceState({}, '', '/ui/status/?api=/custom');
    expect(await resolveApiBase()).toBe('/custom');
    history.replaceState({}, '', '/ui/status/');
    saveConnection('/saved', '', false);
    expect(await resolveApiBase()).toBe('/saved');
    localStorage.clear();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      redirected: false,
      url: `${location.origin}/ui/api-prefix.json`,
      json: async () => ({ apiPrefix: '/baked' })
    }));
    expect(await resolveApiBase()).toBe('/baked');
  });

  it('binds persisted and in-memory credentials to the exact normalized API base', async () => {
    saveConnection('https://node.example/api/v1/', 'secret', true);
    expect(currentApiKey('https://node.example/api/v1')).toBe('secret');
    expect(hasPersistedApiKey('https://node.example/api/v1')).toBe(true);
    expect(currentApiKey('https://other.example/api/v1')).toBe('');
    expect(currentApiKey('/api/v1')).toBe('');

    history.replaceState({}, '', '/ui/status/?api=https://other.example/api/v1');
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ running: true }) });
    vi.stubGlobal('fetch', fetchMock);
    const selectedBase = await resolveApiBase();
    await new YanoApi(selectedBase).status();
    expect((fetchMock.mock.calls[0][1].headers as Headers).has('X-API-Key')).toBe(false);
  });

  it('fails closed instead of migrating an unscoped v1 API key', () => {
    localStorage.setItem('yano.console.api-key.v1', 'legacy-secret');
    localStorage.setItem('yano.console.api-base.v1', '/api/v1');
    expect(currentApiKey('/api/v1')).toBe('');
    expect(localStorage.getItem('yano.console.api-key.v1')).toBeNull();
  });

  it('binds plugin discovery to its exact same-origin immutable asset', async () => {
    history.replaceState({}, '', '/ui/plugins/?api=https://attacker.example/api');
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, redirected: false,
      url: `${location.origin}/ui/plugins/api-prefix.json`, json: async () => ({ apiPrefix: '/custom' }) });
    vi.stubGlobal('fetch', fetchMock);
    expect(await resolvePluginApiBase()).toBe('/custom');
    expect(fetchMock).toHaveBeenCalledWith('/ui/plugins/api-prefix.json', expect.objectContaining({
      redirect: 'error', credentials: 'same-origin'
    }));
    fetchMock.mockResolvedValueOnce({ ok: true, redirected: false,
      url: 'https://attacker.example/ui/plugins/api-prefix.json', json: async () => ({ apiPrefix: '/api/v1' }) });
    await expect(resolvePluginApiBase()).rejects.toThrow('could not be verified');
  });

  it('sends keys only as headers and rejects redirects', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ running: true }) });
    vi.stubGlobal('fetch', fetchMock);
    await new YanoApi('/api/v1', 'secret').status();
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/node/status', expect.objectContaining({ redirect: 'error' }));
    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get('X-API-Key')).toBe('secret');
  });

  it('turns browser network failures into an actionable standalone diagnostic', () => {
    expect(apiFailureMessage(new TypeError('Failed to fetch'), 'fallback')).toContain('CORS origin');
  });
});
