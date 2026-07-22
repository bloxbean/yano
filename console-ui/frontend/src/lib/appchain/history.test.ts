import { describe, expect, it } from 'vitest';
import { appChainSample } from './history';

describe('app-chain history', () => {
  it('computes rates and anchor lag', () => {
    const first = appChainSample({ tipHeight: 10, submitted: 2 }, null, 1_000);
    const second = appChainSample({ tipHeight: 14, submitted: 3, poolSize: 2,
      blockIntervalMs: 500, anchor: { lagBlocks: 4 } }, first.state, 3_000);
    expect(second.sample).toEqual([3_000, 2, 2, 500, 4]);
    expect(second.discontinuity).toBe(false);
  });

  it('marks restart and long-gap discontinuities', () => {
    const previous = { at: 1_000, tip: 10, submitted: 5, received: 4, relayed: 3 };
    expect(appChainSample({ tipHeight: 9 }, previous, 2_000).discontinuity).toBe(true);
    expect(appChainSample({ tipHeight: 11, submitted: 6, received: 5, relayed: 4 },
      previous, 40_000).discontinuity).toBe(true);
  });
});
