import { describe, expect, it } from 'vitest';
import {
  canonicalEutxoIdentifier, canonicalEutxoOutpoint, formatLovelace,
  indexStatusLabel, isCompleteProjection, isEutxoChain, transactionIdFromOutpoint
} from './model';

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

  it('parses only canonical bounded outpoints', () => {
    const id = 'ab'.repeat(32);
    expect(canonicalEutxoOutpoint(`${id}#12`)).toEqual({
      transactionId: id, outputIndex: 12
    });
    expect(transactionIdFromOutpoint(`${id}#0`)).toBe(id);
    expect(() => canonicalEutxoOutpoint(`${id.toUpperCase()}#0`)).toThrow();
    expect(() => canonicalEutxoOutpoint(`${id}#65536`)).toThrow('65535');
  });

  it('keeps readiness, history coverage, and lag distinct', () => {
    expect(indexStatusLabel('READY_FULL')).toBe('Ready');
    expect(indexStatusLabel('REBUILDING_PARTIAL')).toBe('Rebuilding');
    expect(isCompleteProjection('READY_FULL', true, 0)).toBe(true);
    expect(isCompleteProjection('READY_PARTIAL', false, 0)).toBe(false);
    expect(isCompleteProjection('READY_FULL', true, 2)).toBe(false);
  });
});
