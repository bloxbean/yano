import type { PluginOperationsSummary } from '$lib/api/types';
import { objectValue, stringValue } from '../appchain/value';

export function normalizedList(value: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(value)) return value.filter((item): item is Record<string, unknown> =>
    item !== null && typeof item === 'object' && !Array.isArray(item));
  return Object.entries(objectValue(value)).map(([id, item]) => ({ id, ...objectValue(item) }));
}

export function first(object: unknown, names: string[], fallback: unknown = null): unknown {
  const source = objectValue(object);
  for (const name of names) if (source[name] !== undefined && source[name] !== null) return source[name];
  return fallback;
}

export function bundleId(bundle: unknown): string {
  return stringValue(first(bundle, ['id', 'bundleId']), 'unknown');
}

export function summaryCounts(summary: PluginOperationsSummary | null): Array<[string, number]> {
  const counts = { ...(summary?.totals ?? {}), ...(summary?.healthCounts ?? {}) };
  return Object.entries(counts).filter((entry): entry is [string, number] => Number.isFinite(entry[1]))
    .sort(([left], [right]) => left.localeCompare(right));
}

export function overallHealth(summary: PluginOperationsSummary | null): string {
  if (!summary) return 'UNKNOWN';
  const counts = Object.fromEntries(summaryCounts(summary));
  const failing = Number(counts.DOWN ?? counts.failedBundles ?? 0);
  const degraded = Number(counts.DEGRADED ?? counts.degradedBundles ?? counts.staleSources ?? 0);
  const unknown = Number(counts.UNKNOWN ?? 0);
  return failing > 0 ? 'DOWN' : degraded > 0 ? 'DEGRADED' : unknown > 0 ? 'UNKNOWN' : 'UP';
}

export function stateBadge(value: unknown): string {
  const state = stringValue(value, 'UNKNOWN').toUpperCase();
  if (state === 'UP' || state === 'ACTIVE') return 'badge-ok';
  if (['DEGRADED', 'STALE', 'ACTIVATING'].includes(state)) return 'badge-warn';
  if (state === 'DOWN' || state === 'FAILED') return 'badge-bad';
  return '';
}

export function contributionLabel(contribution: unknown): string {
  const lifecycleObserved = first(contribution, ['lifecycleObserved'], false) === true;
  const runtime = lifecycleObserved
    ? `${first(contribution, ['lifecycle', 'lifecycleState'], 'UNKNOWN')}/${first(contribution, ['health', 'healthState'], 'UNKNOWN')}`
    : 'CATALOG VALID · LIFECYCLE NOT OBSERVED';
  return `${first(contribution, ['kind'], 'unknown')} · ${first(contribution, ['name', 'id'], 'unnamed')} · ${runtime}`;
}

export function detailLabel(kind: string, item: unknown): string {
  if (kind === 'Health checks') return `${first(item, ['id'], 'check')}: ${first(item, ['status'], 'UNKNOWN')}`
    + `${first(item, ['description'], '') ? ` · ${first(item, ['description'], '')}` : ''}`;
  if (kind === 'Cached metrics') return `${first(item, ['id', 'metricId', 'name'], 'metric')}`
    + ` (${String(first(item, ['type'], '')).toUpperCase()}): ${first(item, ['value', 'total', 'count'], '-')}`;
  if (kind === 'Operations') return `${first(item, ['operation'], 'UNKNOWN')} · ${first(item, ['outcome'], 'UNKNOWN')}: ${first(item, ['total'], 0)}`;
  return contributionLabel(item);
}
