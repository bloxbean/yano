import { describe, expect, it } from 'vitest';
import { isLocalProducer, syncProgress } from './view-model';

describe('status view model', () => {
  it('presents client-disabled devnet nodes as producers rather than syncing', () => {
    const status = { upstreamMode: 'disabled (local producer)', localTipBlockNumber: 42 };
    expect(isLocalProducer(status)).toBe(true);
    expect(syncProgress(status)).toBe(100);
  });

  it('bounds remote sync progress', () => {
    expect(syncProgress({ localTipBlockNumber: 50, remoteTipBlockNumber: 100 })).toBe(50);
    expect(syncProgress({ localTipBlockNumber: 120, remoteTipBlockNumber: 100 })).toBe(100);
  });
});
