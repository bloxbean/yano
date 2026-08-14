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

  it('uses the canonical state proof endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ presence: 'PRESENT' })
    });
    vi.stubGlobal('fetch', fetchMock);

    await new YanoApi('/api/v1').chainProof('orders', '01', 42);

    expect(fetchMock.mock.calls[0][0])
      .toBe('/api/v1/app-chain/chains/orders/state/proof/01?height=42');
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

  it('uses bounded block pages and encoded block detail routes', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ blocks: [] }) });
    vi.stubGlobal('fetch', fetchMock);
    const api = new YanoApi('/api/v1', 'reader-key');
    await api.chainBlocks('orders/east', undefined, 51, 25);
    await api.chainBlock('orders/east', 75);
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/app-chain/chains/orders%2Feast/blocks?limit=25&from=51');
    expect(fetchMock.mock.calls[1][0]).toBe(
      '/api/v1/app-chain/chains/orders%2Feast/blocks/75');
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

  it('uses bounded authenticated-snapshot discovery and privileged lifecycle routes', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    vi.stubGlobal('fetch', fetchMock);
    const api = new YanoApi('/api/v1', 'operator-key');
    await api.chainSnapshotStatus('history/east');
    await api.chainSnapshots('history/east', 'epoch-stake', 'cursor_4', 500);
    await api.chainSnapshot('history/east', 'epoch-stake', 7);
    await api.chainSnapshotProof('history/east', 'epoch-stake', 7, '01');
    await api.operateChainSnapshot('history/east', 'epoch-stake', 7,
      'archive', 'demo-7', true);
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/app-chain/chains/history%2Feast/snapshots/status');
    expect(fetchMock.mock.calls[1][0]).toBe(
      '/api/v1/app-chain/chains/history%2Feast/snapshots?limit=100&series=epoch-stake&cursor=cursor_4');
    expect(fetchMock.mock.calls[2][0]).toBe(
      '/api/v1/app-chain/chains/history%2Feast/snapshots/epoch-stake/7');
    expect(fetchMock.mock.calls[3][1]).toEqual(expect.objectContaining({
      method: 'POST', body: '{"keyHex":"01"}'
    }));
    expect(fetchMock.mock.calls[4][0]).toBe(
      '/api/v1/app-chain/chains/history%2Feast/admin/snapshots/epoch-stake/7/archive');
    expect((fetchMock.mock.calls[4][1].headers as Headers).get('X-API-Key')).toBe('operator-key');
  });

  it('uses the same authenticated client for bounded plugin domain routes', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ data: [] }) });
    vi.stubGlobal('fetch', fetchMock);
    await new YanoApi('/api/v1', 'reader-key').domain(
      'com.bloxbean.cardano.yano.appchain.eutxo',
      `transactions/${'ab'.repeat(32)}`,
      { chain: 'payments/east', limit: '20' }
    );
    expect(fetchMock.mock.calls[0][0]).toBe(
      `/api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo/transactions/${'ab'.repeat(32)}?chain=payments%2Feast&limit=20`);
    expect((fetchMock.mock.calls[0][1].headers as Headers).get('X-API-Key')).toBe('reader-key');
  });

  it('routes lifecycle-index and lazy L1 enrichment through the reviewed client', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, json: async () => ({ apiVersion: 'eutxo-index/v1' })
    });
    vi.stubGlobal('fetch', fetchMock);
    const api = new YanoApi('/api/v1', 'reader-key');
    await api.eutxoIndex('transactions', {
      chain: 'payments/east', limit: '25', cursor: 'c1_safe'
    });
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo.indexer/index/v1/transactions'
      + '?chain=payments%2Feast&limit=25&cursor=c1_safe');
    const id = 'ab'.repeat(32);
    await api.l1Transaction(id);
    await api.l1TransactionUtxos(id);
    expect(fetchMock.mock.calls[1][0]).toBe(`/api/v1/txs/${id}`);
    expect(fetchMock.mock.calls[2][0]).toBe(`/api/v1/txs/${id}/utxos`);
    expect((fetchMock.mock.calls[2][1].headers as Headers).get('X-API-Key')).toBe('reader-key');
  });

  it('routes authenticated-map domain reads and message submission through the reviewed client', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    vi.stubGlobal('fetch', fetchMock);
    const api = new YanoApi('/api/v1', 'reader-key');
    await api.authenticatedMapDomain(
      `authenticated-map/entries/products/${'6b'.repeat(3)}`, { chain: 'map/east' });
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/plugins/com.bloxbean.cardano.yano.appchain.stdlib/authenticated-map/entries/'
      + `products/${'6b'.repeat(3)}?chain=map%2Feast`);
    expect((fetchMock.mock.calls[0][1].headers as Headers).get('X-API-Key')).toBe('reader-key');
    await api.chainSubmitMessage('map/east', 'authenticated-map.command.v1', '830100');
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/app-chain/chains/map%2Feast/messages');
    expect(fetchMock.mock.calls[1][1]).toEqual(expect.objectContaining({
      method: 'POST', body: '{"topic":"authenticated-map.command.v1","bodyHex":"830100"}'
    }));
  });

  it('turns browser network failures into an actionable standalone diagnostic', () => {
    expect(apiFailureMessage(new TypeError('Failed to fetch'), 'fallback')).toContain('CORS origin');
  });
});
