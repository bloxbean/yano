import { describe, expect, it } from 'vitest';
import { archiveDatasets, archiveState, coverageLabel, failedWorker, failedWorkerStates } from './archive';

describe('archive status view model', () => {
  it('keeps disabled, catch-up, ready, and unhealthy states distinct', () => {
    expect(archiveState(undefined)).toBe('DISABLED');
    expect(archiveState({ enabled: true, available: false })).toBe('UNAVAILABLE');
    expect(archiveState({ enabled: true, available: true, health: { status: 'HEALTHY' },
      datasets: { address_transaction: { enabled: true, ready: false } } })).toBe('CATCHING_UP');
    expect(archiveState({ enabled: true, available: true, health: { status: 'HEALTHY' },
      datasets: { address_transaction: { enabled: true, ready: true } } })).toBe('READY');
    expect(archiveState({ enabled: true, available: true, health: { status: 'UNHEALTHY' } }))
      .toBe('UNHEALTHY');
  });

  it('never reports READY while an enabled dataset has a failed worker', () => {
    // A parked LIVE worker still reports ready=true and the backend can still be
    // healthy, so readiness must not be evaluated before worker state.
    const parked = {
      enabled: true,
      available: true,
      health: { status: 'HEALTHY' },
      datasets: {
        address_transaction: {
          enabled: true,
          ready: true,
          workers: { LIVE: { state: 'FAILED' } }
        }
      }
    };
    expect(archiveState(parked)).toBe('UNHEALTHY');

    // A retryable failure is also a failure, matching ArchiveWorkerStatus.healthyState().
    expect(archiveState({ enabled: true, available: true, health: { status: 'HEALTHY' },
      datasets: { transaction: { enabled: true, ready: true, workers: { BACKFILL: { state: 'DEGRADED' } } } } }))
      .toBe('UNHEALTHY');

    // A catch-up dataset with a failed worker is unhealthy, not merely catching up.
    expect(archiveState({ enabled: true, available: true, health: { status: 'HEALTHY' },
      datasets: { transaction: { enabled: true, ready: false, workers: { BACKFILL: { state: 'FAILED' } } } } }))
      .toBe('UNHEALTHY');

    // Contention and normal progress stay healthy.
    for (const state of ['IDLE', 'RUNNING', 'WAITING_FOR_WRITER', 'PAUSED_CORE_LAG', 'DISABLED']) {
      expect(archiveState({ enabled: true, available: true, health: { status: 'HEALTHY' },
        datasets: { transaction: { enabled: true, ready: true, workers: { BACKFILL: { state } } } } }))
        .toBe('READY');
    }

    // A disabled dataset's worker state must not drag the aggregate down.
    expect(archiveState({ enabled: true, available: true, health: { status: 'HEALTHY' },
      datasets: { reward: { enabled: false, workers: { BACKFILL: { state: 'FAILED' } } },
        transaction: { enabled: true, ready: true } } }))
      .toBe('READY');
  });

  it('reports which worker states failed, most severe first', () => {
    const dataset = { workers: { BACKFILL: { state: 'DEGRADED' }, LIVE: { state: 'FAILED' } } };
    expect(failedWorker(dataset)).toBe(true);
    expect(failedWorkerStates(dataset)).toEqual(['FAILED', 'DEGRADED']);
    expect(failedWorker({ workers: { LIVE: { state: 'WAITING_FOR_WRITER' } } })).toBe(false);
    expect(failedWorkerStates({})).toEqual([]);
  });

  it('sorts datasets and makes committed coverage readable', () => {
    expect(archiveDatasets({ datasets: { reward: {}, account_event: {} } }).map(([name]) => name))
      .toEqual(['account_event', 'reward']);
    expect(coverageLabel({ coverage: { completeRanges: [{ startInclusive: 1, endInclusive: 42 }] } }))
      .toBe('1–42');
    expect(coverageLabel({})).toBe('none committed');
  });
});
