import { describe, expect, it } from 'vitest';
import { assessProofBinding, parseProofEnvelope } from './proof-verification';

const proof = {
  key: '00',
  chainId: 'orders',
  committedHeight: 7,
  stateRoot: '11'.repeat(32),
  proofWireHex: '8200',
  valueHex: ''
};

describe('proof envelope verification model', () => {
  it('accepts bounded inclusion and exclusion envelopes', () => {
    expect(parseProofEnvelope(JSON.stringify(proof))).toEqual(proof);
    const { valueHex: ignored, ...exclusion } = proof;
    expect(ignored).toBe('');
    expect(parseProofEnvelope(JSON.stringify(exclusion)).valueHex).toBeUndefined();
  });

  it('rejects malformed identity and unbounded wire before verification', () => {
    expect(() => parseProofEnvelope(JSON.stringify({ ...proof, key: 'AA' }))).toThrow('key');
    expect(() => parseProofEnvelope(JSON.stringify({ ...proof, stateRoot: '00' }))).toThrow('root');
    expect(() => parseProofEnvelope(JSON.stringify({
      ...proof, proofWireHex: '00'.repeat(1024 * 1024 + 1)
    }))).toThrow('bounded');
  });

  it('keeps mathematical verification separate from root and height binding', () => {
    expect(assessProofBinding(proof, proof.stateRoot, 7))
      .toEqual({ rootMatches: true, heightMatches: true });
    expect(assessProofBinding(proof, '22'.repeat(32), 8))
      .toEqual({ rootMatches: false, heightMatches: false });
    expect(assessProofBinding(proof, proof.stateRoot))
      .toEqual({ rootMatches: true, heightMatches: undefined });
  });
});
