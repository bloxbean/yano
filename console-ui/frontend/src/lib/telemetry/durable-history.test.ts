import { describe, expect, it } from 'vitest';
import { alignedSamples, mergeSamples } from './durable-history';

describe('durable history', () => {
  it('aligns series and lets authoritative session samples replace matching timestamps', () => {
    const durable = alignedSamples([
      { labels: {}, points: [[1000, 1], [2000, 2]] },
      { labels: {}, points: [[2000, 20]] }
    ], 2);
    expect(durable).toEqual([[1000, 1, null], [2000, 2, 20]]);
    expect(mergeSamples(durable, [[2000, 3, 30], [3000, 4, 40]])).toEqual([
      [1000, 1, null], [2000, 3, 30], [3000, 4, 40]
    ]);
  });
});
