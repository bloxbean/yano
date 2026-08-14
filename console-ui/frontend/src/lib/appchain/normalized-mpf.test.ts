import { describe, expect, it } from 'vitest';
import { blake2b256 } from './message-proof';
import { verifyNormalizedMpf, type NormalizedMpfProof } from './normalized-mpf';

const b64 = (value: Uint8Array) => btoa(String.fromCharCode(...value));
const concat = (...values: Uint8Array[]) => {
  const result = new Uint8Array(values.reduce((sum, value) => sum + value.length, 0));
  let offset = 0;
  values.forEach((value) => { result.set(value, offset); offset += value.length; });
  return result;
};

describe('normalized MPF browser verifier', () => {
  it('accepts a released single-leaf vector and rejects root/value mutations', () => {
    const key = new Uint8Array([1, 2, 3]);
    const value = new Uint8Array([4, 5, 6]);
    const path = blake2b256(key);
    const suffix = concat(new Uint8Array([255]), path);
    const root = blake2b256(concat(suffix, blake2b256(value)));
    const proof: NormalizedMpfProof = {
      stateRoot: b64(root), key: b64(key), value: b64(value), leafSuffix: b64(suffix),
      folds: [], committedHeight: 9
    };

    expect(verifyNormalizedMpf(proof)).toBe(true);
    expect(verifyNormalizedMpf({ ...proof, stateRoot: b64(new Uint8Array(32).fill(9)) })).toBe(false);
    expect(verifyNormalizedMpf({ ...proof, value: b64(new Uint8Array([7])) })).toBe(false);
  });
});
