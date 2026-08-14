import { beforeEach, describe, expect, it, vi } from 'vitest';
import { normalizeMetricsBase, PrometheusHistoryProvider, queryExpression,
  resolveMetricsBase, saveMetricsConnection } from './prometheus';

const matrix = (values: Array<[number, string]>, labels: Record<string, string> = {}) => ({
  status: 'success', data: { resultType: 'matrix', result: [{ metric: labels, values }] }
});

describe('Prometheus history provider', () => {
  beforeEach(() => { localStorage.clear(); sessionStorage.clear(); history.replaceState({}, '', '/'); });

  it('accepts explicit origins and rejects authority or path ambiguity', () => {
    expect(normalizeMetricsBase('https://metrics.example:9443/')).toBe('https://metrics.example:9443');
    for (const value of ['ftp://host', 'https://user:secret@host', 'https://host/prometheus',
      'https://host/?query=up', 'https://host/#fragment']) {
      expect(() => normalizeMetricsBase(value)).toThrow();
    }
  });

  it('uses only fixed queries and validates chain parameters', () => {
    expect(queryExpression('node.sync-gap')).toBe('max(yano_node_sync_gap_blocks)');
    expect(queryExpression('appchain.pool', 'orders-chain')).toContain('{chain="orders-chain"}');
    expect(() => queryExpression('appchain.pool', 'orders"} or up')).toThrow('Invalid app-chain id');
  });

  it('prefers the explicit handoff and stores credentials only in the session for the exact origin', () => {
    history.replaceState({}, '', '/?metrics=https%3A%2F%2Fmetrics.example');
    expect(resolveMetricsBase()).toBe('https://metrics.example');
    expect(localStorage.getItem('yano.console.metrics-base.v1')).toBe('https://metrics.example');
    saveMetricsConnection('https://metrics.example', 'read-only-token');
    expect(JSON.stringify(localStorage)).not.toContain('read-only-token');
    saveMetricsConnection('https://other.example', '');
    expect(JSON.stringify(sessionStorage)).not.toContain('read-only-token');
  });

  it('never sends the Yano API key and parses bounded range results', async () => {
    localStorage.setItem('yano.console.api-key.v1', 'yano-secret');
    const fetcher = vi.fn(async (_url: URL | RequestInfo, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      expect(headers.get('X-API-Key')).toBeNull();
      expect(headers.get('Authorization')).toBeNull();
      return new Response(JSON.stringify(matrix([[1, '2.5'], [2, '3']])));
    });
    const result = await new PrometheusHistoryProvider('http://localhost:9090', '', fetcher as typeof fetch)
      .range('node.sync-gap', { start: 1, end: 3, step: 1 });
    expect(result[0].points).toEqual([[1000, 2.5], [2000, 3]]);
  });

  it('rejects cardinality, point and byte bounds', async () => {
    const tooMany = { status: 'success', data: { result: Array.from({ length: 65 }, () => ({ metric: {}, values: [] })) } };
    const fetcher = vi.fn(async () => new Response(JSON.stringify(tooMany)));
    await expect(new PrometheusHistoryProvider('http://localhost:9090', '', fetcher as typeof fetch)
      .instant('health')).rejects.toThrow('too many series');
    await expect(new PrometheusHistoryProvider('http://localhost:9090', '', fetcher as typeof fetch)
      .range('health', { start: 1, end: 5000, step: 1 })).rejects.toThrow('range');
    const points = matrix(Array.from({ length: 4_097 }, (_, index) => [index + 1, '1'] as [number, string]));
    const pointFetcher = vi.fn(async () => new Response(JSON.stringify(points)));
    await expect(new PrometheusHistoryProvider('http://localhost:9090', '', pointFetcher as typeof fetch)
      .instant('health')).rejects.toThrow('too many points');
  });
});
