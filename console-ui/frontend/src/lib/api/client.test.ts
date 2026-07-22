import { beforeEach, describe, expect, it, vi } from 'vitest';
import { normalizeApiBase, resolveApiBase, saveConnection, YanoApi } from './client';

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
      json: async () => ({ apiPrefix: '/baked' })
    }));
    expect(await resolveApiBase()).toBe('/baked');
  });

  it('sends keys only as headers and rejects redirects', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ running: true }) });
    vi.stubGlobal('fetch', fetchMock);
    await new YanoApi('/api/v1', 'secret').status();
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/node/status', expect.objectContaining({ redirect: 'error' }));
    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get('X-API-Key')).toBe('secret');
  });
});
