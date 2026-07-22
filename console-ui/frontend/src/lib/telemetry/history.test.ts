import { beforeEach, describe, expect, it } from 'vitest';
import { MAX_SAMPLES, MAX_SERIALIZED_BYTES, SessionHistory } from './history';

describe('SessionHistory', () => {
  beforeEach(() => sessionStorage.clear());

  it('restores only matching, valid, bounded samples', () => {
    const history = new SessionHistory('node-a');
    for (let index = 0; index < MAX_SAMPLES + 5; index++) {
      history.append([index + 1, index], 20_000 + index * 10_001);
    }
    history.persist();

    const restored = new SessionHistory('node-a').values();
    expect(restored).toHaveLength(MAX_SAMPLES);
    expect(restored[0][0]).toBe(6);
    expect(new SessionHistory('node-b').values()).toEqual([]);
  });

  it('keeps serialized data under the hard cap and accepts discontinuities', () => {
    const history = new SessionHistory('large');
    for (let index = 0; index < MAX_SAMPLES; index++) {
      history.append([Date.now() + index, null, ...Array(80).fill(index)], index * 10_001);
    }
    history.persist();
    const stored = Object.values(sessionStorage).join('');
    expect(stored.length).toBeLessThanOrEqual(MAX_SERIALIZED_BYTES);
    expect(new SessionHistory('large').values().at(-1)?.[1]).toBeNull();
  });

  it('drops malformed persisted data', () => {
    sessionStorage.setItem('yano.console.history.v1.invalid', '{bad json');
    expect(new SessionHistory('invalid').values()).toEqual([]);
  });
});
