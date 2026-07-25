import { numberValue, objectList, objectValue, stringValue } from './value';

export interface NamedCount {
  name: string;
  count: number;
}

export interface EffectLatency {
  type: string;
  count: number;
  averageMillis: number | null;
}

export interface EffectExecutor {
  id: string;
  bundleId: string;
  readiness: string;
  sampleState: string;
  types: string[];
  attempts: number;
  successes: number;
  failures: number;
  inFlight: number;
  lastActivity: string;
  failureCode: string;
}

export interface EffectStatsView {
  enabled: boolean;
  queueDepth: number;
  inFlight: number;
  openOnChain: number;
  resultBacklog: number;
  executed: number;
  parked: number;
  expired: number;
  oldestPendingAgeSeconds: number;
  oldestPendingAgeBlocks: number;
  statuses: NamedCount[];
  backlogByType: NamedCount[];
  executionTotals: NamedCount[];
  latency: EffectLatency[];
  executors: EffectExecutor[];
}

function namedCounts(value: unknown): NamedCount[] {
  return Object.entries(objectValue(value))
    .map(([name, count]) => ({ name, count: numberValue(count) }))
    .sort((left, right) => right.count - left.count || left.name.localeCompare(right.name));
}

function executorView(value: Record<string, unknown>): EffectExecutor {
  const failures = numberValue(value.retryableFailures) + numberValue(value.terminalFailures);
  const lastSuccess = stringValue(value.lastSuccessAge, 'NEVER');
  const lastFailure = stringValue(value.lastFailureAge, 'NEVER');
  return {
    id: stringValue(value.id, 'unknown'),
    bundleId: stringValue(value.bundleId, '-'),
    readiness: stringValue(value.readiness, 'UNKNOWN'),
    sampleState: stringValue(value.sampleState, 'UNKNOWN'),
    types: Array.isArray(value.types) ? value.types.map(String) : [],
    attempts: numberValue(value.attempts),
    successes: numberValue(value.successes),
    failures,
    inFlight: numberValue(value.inFlight),
    lastActivity: lastFailure !== 'NEVER' ? `failure ${lastFailure}`
      : lastSuccess !== 'NEVER' ? `success ${lastSuccess}` : 'never',
    failureCode: stringValue(value.failureCode, 'NONE')
  };
}

export function effectStatsView(stats: Record<string, unknown>): EffectStatsView {
  const oldest = objectValue(stats.oldestPending);
  const latency = Object.entries(objectValue(stats.latencyByType))
    .map(([type, raw]) => {
      const values = objectValue(raw);
      const count = numberValue(values.count);
      const total = numberValue(values.totalMillis);
      return {
        type,
        count,
        averageMillis: count > 0 ? total / count : null
      };
    })
    .sort((left, right) => left.type.localeCompare(right.type));

  return {
    enabled: stats.enabled === true,
    queueDepth: numberValue(stats.queueDepth),
    inFlight: numberValue(stats.inFlight),
    openOnChain: numberValue(stats.openOnChain),
    resultBacklog: numberValue(stats.resultBacklog),
    executed: numberValue(stats.executed),
    parked: numberValue(stats.parked),
    expired: numberValue(stats.expiredTotal),
    oldestPendingAgeSeconds: numberValue(oldest.ageSeconds),
    oldestPendingAgeBlocks: numberValue(oldest.ageBlocks),
    statuses: namedCounts(stats.statusCounts),
    backlogByType: namedCounts(stats.resultBacklogByType),
    executionTotals: namedCounts(stats.executionTotals),
    latency,
    executors: objectList(stats.executorOperations).map(executorView)
  };
}
