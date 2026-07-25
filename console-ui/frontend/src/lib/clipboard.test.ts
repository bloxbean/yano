import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyText } from './clipboard';

describe('copyText', () => {
  afterEach(() => vi.restoreAllMocks());

  it('uses the asynchronous clipboard without changing the value', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    });

    await copyText('abcdef012345');

    expect(writeText).toHaveBeenCalledWith('abcdef012345');
  });

  it('rejects an empty copy request', async () => {
    await expect(copyText('')).rejects.toThrow('Nothing to copy');
  });
});
