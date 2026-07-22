import { describe, expect, it } from 'vitest';
import { contributionLabel, normalizedList, overallHealth, summaryCounts } from './model';

describe('plugin operations model', () => {
  it('combines deterministic summary counters and derives health', () => {
    const summary = { totals: { selectedBundles: 2, failedBundles: 0 }, healthCounts: { DOWN: 1 } };
    expect(summaryCounts(summary)).toContainEqual(['selectedBundles', 2]);
    expect(overallHealth(summary)).toBe('DOWN');
  });

  it('normalizes maps and distinguishes catalog validity from lifecycle', () => {
    expect(normalizedList({ alpha: { state: 'ACTIVE' } })).toEqual([{ id: 'alpha', state: 'ACTIVE' }]);
    expect(contributionLabel({ kind: 'state', name: '<unsafe>' }))
      .toContain('CATALOG VALID · LIFECYCLE NOT OBSERVED');
  });
});
