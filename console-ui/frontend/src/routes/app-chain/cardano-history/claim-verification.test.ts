import { describe, expect, it } from 'vitest';
import { combineProofAndClaim, evaluateParameterClaim, evaluateStakeClaim,
  packageVerification } from './claim-verification';

describe('Cardano History claim verification', () => {
  it('keeps a valid stake proof separate from a false minimum claim', () => {
    const result = evaluateStakeClaim(
      { found: true, coin: '3365531', poolHash: 'aa'.repeat(28), complete: true },
      { mode: 'minimum', expectedCoinLovelace: '19365533', expectedPoolHash: 'aa'.repeat(28) });
    expect(result.valid).toBe(false);
    expect(result.checks.find((check) => check.id === 'amount')).toMatchObject({
      passed: false, actual: '3365531', expected: '19365533'
    });
  });

  it('requires every condition for a combined stake claim', () => {
    expect(evaluateStakeClaim(
      { found: true, coin: '20000000', poolHash: 'aa'.repeat(28) },
      { mode: 'minimum-and-pool', expectedCoinLovelace: '19000000',
        expectedPoolHash: 'bb'.repeat(28) }).valid).toBe(false);
  });

  it('evaluates named lovelace parameter predicates', () => {
    const history = { found: true, complete: true, fieldId: 'key-deposit',
      type: 'lovelace', value: '2000000' };
    expect(evaluateParameterClaim(history,
      { mode: 'exact', fieldId: 'key-deposit', expectedValue: '2000000' }).valid).toBe(true);
    expect(evaluateParameterClaim(history,
      { mode: 'minimum', fieldId: 'key-deposit', expectedValue: '3000000' }).valid).toBe(false);
    expect(evaluateParameterClaim(history,
      { mode: 'range', fieldId: 'key-deposit', expectedValue: '1000000',
        maximumValue: '2500000' }).valid).toBe(true);
  });

  it('normalizes rational claims and requires completeness for absence', () => {
    expect(evaluateParameterClaim(
      { found: true, fieldId: 'pool-influence', type: 'rational', value: ['1', '2'] },
      { mode: 'exact', fieldId: 'pool-influence', expectedValue: '2/4' }).valid).toBe(true);
    expect(evaluateParameterClaim(
      { found: false, complete: false }, { mode: 'absence' }).valid).toBe(false);
  });

  it('rejects a claim relabeled as a different authenticated parameter', () => {
    expect(evaluateParameterClaim(
      { found: true, fieldId: 'pool-deposit', type: 'lovelace', value: '2000000' },
      { mode: 'exact', fieldId: 'key-deposit', expectedValue: '2000000' }).valid).toBe(false);
  });

  it('exports the authenticated actual value and an explicit false-claim verdict', () => {
    const claim = evaluateParameterClaim(
      { found: true, complete: true, fieldId: 'key-deposit',
        type: 'lovelace', value: '2000000' },
      { mode: 'exact', fieldId: 'key-deposit', expectedValue: '2000001' });
    const combined = combineProofAndClaim({ valid: true }, claim);
    expect(packageVerification(combined,
      { fieldId: 'key-deposit', fieldType: 'lovelace', value: '2000000' },
      { mode: 'exact', fieldId: 'key-deposit', expectedValue: '2000001' }))
      .toMatchObject({
        status: 'rejected', proofAuthentic: true, claimEvaluated: true,
        claimSatisfied: false, accepted: false, verifierMustRecompute: true,
        actual: { value: '2000000' }, requested: { expectedValue: '2000001' }
      });
  });
});
