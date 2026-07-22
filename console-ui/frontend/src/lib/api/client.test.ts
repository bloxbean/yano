import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFailureMessage, normalizeApiBase, resolveApiBase, resolvePluginApiBase, saveConnection, YanoApi } from './client';

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

  it('uses the authenticated fetch client for encoded app-chain SSE', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, body: {} });
    vi.stubGlobal('fetch', fetchMock);
    await new YanoApi('/api/v1', 'secret').chainStream('orders / east', 0);
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/app-chain/chains/orders%20%2F%20east/stream?fromHeight=1');
    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get('Accept')).toBe('text/event-stream');
    expect(headers.get('X-API-Key')).toBe('secret');
  });

  it('keeps capability queries bounded to encoded routes and privileged actions authenticated', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    vi.stubGlobal('fetch', fetchMock);
    const api = new YanoApi('/api/v1', 'operator-key');
    await api.chainQuery('orders/east', 'components/role-approvals/proposal', '6162');
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/app-chain/chains/orders%2Feast/query/components/role-approvals/proposal');
    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({
      method: 'POST', body: '{"paramsHex":"6162"}'
    }));
    await api.cancelEffect('orders/east', 7, 2, 'operator review');
    expect(fetchMock.mock.calls[1][0]).toBe(
      '/api/v1/app-chain/chains/orders%2Feast/effects/7/2/cancel?reason=operator%20review');
    expect((fetchMock.mock.calls[1][1].headers as Headers).get('X-API-Key')).toBe('operator-key');
  });

  it('turns browser network failures into an actionable standalone diagnostic', () => {
    expect(apiFailureMessage(new TypeError('Failed to fetch'), 'fallback')).toContain('CORS origin');
  });
});
