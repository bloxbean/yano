export const METRICS_BASE_KEY = 'yano.console.metrics-base.v1';
const METRICS_CREDENTIAL_KEY = 'yano.console.metrics-credential.v1';
const MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
const MAX_SERIES = 64;
const MAX_POINTS = 4_096;

export type QueryId =
  | 'health'
  | 'node.sync-gap'
  | 'node.peers'
  | 'node.mempool-transactions'
  | 'node.mempool-kib'
  | 'node.utxo-lag'
  | 'node.tx-rate';

export interface PrometheusSeries {
  labels: Readonly<Record<string, string>>;
  points: ReadonlyArray<readonly [number, number | null]>;
}

export interface QueryRange {
  start: number;
  end: number;
  step: number;
}

interface CredentialEnvelope { origin: string; bearer: string; }

const queries: Record<QueryId, string> = {
  health: 'up',
  'node.sync-gap': 'max(yano_node_sync_gap_blocks)',
  'node.peers': 'sum by (type) (yano_node_peers_connections)',
  'node.mempool-transactions': 'sum(yano_node_mempool_transactions)',
  'node.mempool-kib': 'sum(yano_node_mempool_bytes) / 1024',
  'node.utxo-lag': 'max(yano_node_utxo_lag_blocks)',
  'node.tx-rate': 'sum by (outcome) (rate(yano_node_tx_diffusion_total[5m]))'
};

export function normalizeMetricsBase(value: string,
                                     currentOrigin = globalThis.location?.origin ?? 'http://localhost'): string {
  const raw = value.trim();
  if (!raw) return '';
  let parsed: URL;
  try { parsed = new URL(raw, currentOrigin); } catch { throw new Error('Metrics URL must be a valid HTTP(S) origin'); }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password
    || parsed.search || parsed.hash || (parsed.pathname !== '/' && parsed.pathname !== '')) {
    throw new Error('Metrics URL must be an HTTP(S) origin without credentials, path, query, or fragment');
  }
  return parsed.origin;
}

export function queryExpression(id: QueryId): string {
  return queries[id];
}

export function resolveMetricsBase(storage: Storage = localStorage): string {
  const query = new URLSearchParams(globalThis.location?.search ?? '').get('metrics');
  if (query) {
    const normalized = normalizeMetricsBase(query);
    storage.setItem(METRICS_BASE_KEY, normalized);
    return normalized;
  }
  const saved = storage.getItem(METRICS_BASE_KEY);
  if (!saved) return '';
  try { return normalizeMetricsBase(saved); } catch { storage.removeItem(METRICS_BASE_KEY); return ''; }
}

export function saveMetricsConnection(base: string, bearer: string,
                                      persistent: Storage = localStorage,
                                      session: Storage = sessionStorage): string {
  const normalized = normalizeMetricsBase(base);
  const previous = persistent.getItem(METRICS_BASE_KEY);
  if (normalized) persistent.setItem(METRICS_BASE_KEY, normalized);
  else persistent.removeItem(METRICS_BASE_KEY);
  let changed = !!previous;
  try { changed = !!previous && normalizeMetricsBase(previous) !== normalized; } catch { changed = true; }
  if (!normalized || changed) {
    session.removeItem(METRICS_CREDENTIAL_KEY);
  }
  if (normalized && bearer) {
    const envelope: CredentialEnvelope = { origin: normalized, bearer };
    session.setItem(METRICS_CREDENTIAL_KEY, JSON.stringify(envelope));
  } else {
    session.removeItem(METRICS_CREDENTIAL_KEY);
  }
  return normalized;
}

export function metricsCredential(base: string, session: Storage = sessionStorage): string {
  if (!base) return '';
  try {
    const parsed = JSON.parse(session.getItem(METRICS_CREDENTIAL_KEY) ?? 'null') as Partial<CredentialEnvelope> | null;
    return parsed?.origin === normalizeMetricsBase(base) && typeof parsed.bearer === 'string' ? parsed.bearer : '';
  } catch {
    session.removeItem(METRICS_CREDENTIAL_KEY);
    return '';
  }
}

export class PrometheusHistoryProvider {
  readonly base: string;

  constructor(base: string, private readonly bearer = metricsCredential(base),
              private readonly fetcher: typeof fetch = fetch) {
    this.base = normalizeMetricsBase(base);
    if (!this.base) throw new Error('Metrics endpoint is not configured');
  }

  async health(signal?: AbortSignal): Promise<boolean> {
    const series = await this.instant('health', signal);
    return series.some((entry) => entry.points.some((point) => point[1] === 1));
  }

  async instant(id: QueryId, signal?: AbortSignal): Promise<PrometheusSeries[]> {
    return this.request('/api/v1/query', { query: queryExpression(id) }, signal);
  }

  async range(id: QueryId, range: QueryRange, signal?: AbortSignal): Promise<PrometheusSeries[]> {
    if (!Number.isFinite(range.start) || !Number.isFinite(range.end) || !Number.isFinite(range.step)
      || range.start <= 0 || range.end <= range.start || range.end - range.start > 90 * 86_400
      || range.step < 1 || Math.ceil((range.end - range.start) / range.step) > MAX_POINTS) {
      throw new Error('Metrics range is outside the supported bounds');
    }
    return this.request('/api/v1/query_range', {
      query: queryExpression(id), start: String(range.start), end: String(range.end), step: String(range.step)
    }, signal);
  }

  private async request(path: string, parameters: Record<string, string>, signal?: AbortSignal): Promise<PrometheusSeries[]> {
    const url = new URL(path, this.base);
    Object.entries(parameters).forEach(([name, value]) => url.searchParams.set(name, value));
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10_000);
    const forwardAbort = () => controller.abort();
    signal?.addEventListener('abort', forwardAbort, { once: true });
    try {
      const headers = new Headers({ Accept: 'application/json' });
      if (this.bearer) headers.set('Authorization', `Bearer ${this.bearer}`);
      const response = await this.fetcher(url, { headers, signal: controller.signal, cache: 'no-store', redirect: 'error' });
      if (!response.ok) throw new Error(`Metrics request failed (${response.status})`);
      return parseResponse(await boundedBody(response));
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', forwardAbort);
    }
  }
}

async function boundedBody(response: Response): Promise<unknown> {
  const declared = Number(response.headers.get('Content-Length'));
  if (Number.isFinite(declared) && declared > MAX_RESPONSE_BYTES) throw new Error('Metrics response is too large');
  if (!response.body) throw new Error('Metrics response has no body');
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    bytes += value.byteLength;
    if (bytes > MAX_RESPONSE_BYTES) { await reader.cancel(); throw new Error('Metrics response is too large'); }
    chunks.push(value);
  }
  const combined = new Uint8Array(bytes);
  let offset = 0;
  for (const chunk of chunks) { combined.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder().decode(combined)); } catch { throw new Error('Metrics response is not valid JSON'); }
}

function parseResponse(value: unknown): PrometheusSeries[] {
  const root = object(value);
  if (root.status !== 'success') throw new Error('Metrics query did not succeed');
  const result = object(root.data).result;
  if (!Array.isArray(result)) throw new Error('Metrics query returned an invalid result');
  if (result.length > MAX_SERIES) throw new Error('Metrics query returned too many series');
  return result.map((raw): PrometheusSeries => {
    const entry = object(raw);
    const metric = object(entry.metric);
    const labels: Record<string, string> = {};
    for (const [key, item] of Object.entries(metric)) {
      if (typeof item === 'string' && key.length <= 128 && item.length <= 512) labels[key] = item;
    }
    const rawPoints = Array.isArray(entry.values) ? entry.values : entry.value ? [entry.value] : [];
    if (rawPoints.length > MAX_POINTS) throw new Error('Metrics series contains too many points');
    const points = rawPoints.map((point): readonly [number, number | null] => {
      if (!Array.isArray(point) || point.length < 2 || !Number.isFinite(Number(point[0]))) {
        throw new Error('Metrics series contains an invalid point');
      }
      const number = Number(point[1]);
      return [Number(point[0]) * 1_000, Number.isFinite(number) ? number : null];
    });
    return { labels, points };
  });
}

function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
}
