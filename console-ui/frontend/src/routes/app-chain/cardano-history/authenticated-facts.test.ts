import { describe, expect, it } from 'vitest';
import { authenticatedParameter, authenticatedStake } from './authenticated-facts';

describe('authenticated Cardano History proof values', () => {
  it('derives stake semantics from the proof value rather than editable summary JSON', () => {
    const result = authenticatedStake({ coin: '99999999', poolHash: 'ff'.repeat(28) },
      `821a00335a9b581c${'aa'.repeat(28)}`);
    expect(result).toMatchObject({ found: true, coin: '3365531', poolHash: 'aa'.repeat(28) });
  });

  it('derives the named lovelace parameter from its proof leaf', () => {
    // [1, 305, "key-deposit", 2, 2(h'1e8480')]. The production codec uses
    // canonical CBOR bignums for Java BigInteger-backed parameter amounts.
    const result = authenticatedParameter({ epoch: 305, value: '999' },
      '85011901316b6b65792d6465706f73697402c2431e8480');
    expect(result).toMatchObject({ found: true, fieldId: 'key-deposit',
      type: 'lovelace', value: '2000000' });
  });

  it('decodes canonical negative bignums for signed parameter fields', () => {
    // [1, 305, "signed-field", 1, 3(h'01')] where tag 3 encodes -1 - 1.
    const result = authenticatedParameter({ epoch: 305 },
      '85011901316c7369676e65642d6669656c6401c34101');
    expect(result).toMatchObject({ found: true, fieldId: 'signed-field',
      type: 'signed-integer', value: '-2' });
  });
});
