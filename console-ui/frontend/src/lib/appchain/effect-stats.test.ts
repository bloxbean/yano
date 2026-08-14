import { describe, expect, it } from 'vitest';
import { effectStatsView } from './effect-stats';

describe('effectStatsView', () => {
  it('maps nested runtime data into stable operator summaries', () => {
    const view = effectStatsView({
      enabled: true,
      queueDepth: 3,
      inFlight: 1,
      statusCounts: { PENDING: 3, DONE: 7 },
      resultBacklogByType: { 'kafka.publish': 2 },
      oldestPending: { ageSeconds: 12.5, ageBlocks: 4 },
      executionTotals: { confirmed: 7, failed: 1 },
      latencyByType: { 'kafka.publish': { count: 4, totalMillis: 50 } },
      executorOperations: [{
        id: 'kafka',
        bundleId: 'bundle.kafka',
        readiness: 'READY',
        sampleState: 'FRESH',
        types: ['kafka.publish'],
        attempts: 8,
        successes: 7,
        retryableFailures: 1,
        terminalFailures: 0,
        inFlight: 1,
        lastSuccessAge: '2s',
        lastFailureAge: 'NEVER',
        failureCode: 'NONE'
      }]
    });

    expect(view.statuses).toEqual([
      { name: 'DONE', count: 7 },
      { name: 'PENDING', count: 3 }
    ]);
    expect(view.latency[0]).toMatchObject({ count: 4, averageMillis: 12.5 });
    expect(view.executors[0]).toMatchObject({
      id: 'kafka',
      failures: 1,
      lastActivity: 'success 2s'
    });
  });

  it('keeps a truthful zero state for absent optional fields', () => {
    const view = effectStatsView({});

    expect(view.enabled).toBe(false);
    expect(view.queueDepth).toBe(0);
    expect(view.latency).toEqual([]);
    expect(view.executors).toEqual([]);
  });
});
