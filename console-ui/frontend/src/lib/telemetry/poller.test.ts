import { afterEach, describe, expect, it, vi } from 'vitest';
import { createPoller } from './poller';

describe('createPoller', () => {
  afterEach(() => vi.useRealTimers());

  it('never overlaps work and refreshes immediately on start', async () => {
    vi.useFakeTimers();
    let resolve!: () => void;
    const work = vi.fn(() => new Promise<void>((done) => { resolve = done; }));
    const poller = createPoller(work, 1000, 5000);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(work).toHaveBeenCalledTimes(1);
    void poller.refresh();
    expect(work).toHaveBeenCalledTimes(1);
    resolve();
    await vi.advanceTimersByTimeAsync(1000);
    expect(work).toHaveBeenCalledTimes(2);
    poller.stop();
  });
});
