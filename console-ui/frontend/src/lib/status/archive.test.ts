import { describe, expect, it } from 'vitest';
import { archiveDatasets, archiveState, coverageLabel } from './archive';

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

  it('sorts datasets and makes committed coverage readable', () => {
    expect(archiveDatasets({ datasets: { reward: {}, account_event: {} } }).map(([name]) => name))
      .toEqual(['account_event', 'reward']);
    expect(coverageLabel({ coverage: { completeRanges: [{ startInclusive: 1, endInclusive: 42 }] } }))
      .toBe('1–42');
    expect(coverageLabel({})).toBe('none committed');
  });
});
