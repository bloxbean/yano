import { describe, expect, it } from 'vitest';
import { canonicalEutxoIdentifier, formatLovelace, isEutxoChain } from './model';

describe('EUTxO console model', () => {
  it('recognizes only the bundled EUTxO state machine', () => {
    expect(isEutxoChain({ stateMachine: 'eutxo-ledger' })).toBe(true);
    expect(isEutxoChain({ stateMachine: 'ordered-log' })).toBe(false);
    expect(isEutxoChain(null)).toBe(false);
  });

  it('accepts canonical identifiers and rejects ambiguous input', () => {
    const id = 'ab'.repeat(32);
    expect(canonicalEutxoIdentifier(` ${id} `)).toBe(id);
    expect(() => canonicalEutxoIdentifier(id.toUpperCase())).toThrow('lowercase');
    expect(() => canonicalEutxoIdentifier('../admin')).toThrow('lowercase');
  });

  it('formats lovelace without losing integer precision', () => {
    expect(formatLovelace('1250000')).toBe('1.25 ADA');
    expect(formatLovelace('9007199254740993000000')).toBe('9,007,199,254,740,993 ADA');
    expect(formatLovelace('unknown')).toBe('unknown');
  });
});
