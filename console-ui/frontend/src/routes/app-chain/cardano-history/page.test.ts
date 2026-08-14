import { describe, expect, it } from 'vitest';
import pageSource from './+page.svelte?raw';
import claimVerificationSource from './claim-verification.ts?raw';

describe('Cardano History page contract', () => {
  it('requests no more epochs than the domain API permits', () => {
    expect(pageSource).toContain("BUNDLE, 'epochs', { ...parameters, limit: '15' }");
    expect(pageSource).not.toContain("BUNDLE, 'epochs', { ...parameters, limit: '100' }");
  });

  it('keeps proof actions with their visible subject query', () => {
    expect(pageSource).not.toContain("view: 'overview' | 'parameters' | 'stake' | 'governance' | 'proofs'");
    expect(pageSource).toContain('Generate proof');
    expect(pageSource).toContain('Verify off-chain');
    expect(pageSource).toContain('Verify an exported proof');
    expect(pageSource).toContain('Verify proof and claim');
    expect(pageSource).toContain('Claim not accepted');
    expect(claimVerificationSource).toContain('proofValid');
    expect(claimVerificationSource).toContain('claimValid');
    expect(claimVerificationSource).toContain('accepted');
    expect(pageSource).toContain("status: 'pending-offchain-verification'");
    expect(pageSource).toContain("status: 'evaluated-offchain'");
    expect(pageSource).toContain('claimSatisfied');
    expect(pageSource).toContain('authenticatedClaimFact');
    expect(pageSource).toContain('packageVerification');
    expect(pageSource).not.toContain('Claim intent is exported as metadata');
    expect(pageSource).not.toContain('offchain-evaluated-onchain-redeemer-export-not-yet-implemented');
  });

  it('supports named parameter claims without exposing positional indexes', () => {
    expect(pageSource).toContain('Prove a parameter claim');
    expect(pageSource).toContain('parameters/fields/${parameterFieldId}');
    expect(pageSource).toContain('key-deposit');
    expect(pageSource).toContain('1 ADA = 1,000,000 lovelace');
    expect(pageSource).not.toContain('fieldIndex');
    expect(pageSource).toContain("kind === 'primary-pair' ? asRecord(bundle.fact)");
    expect(pageSource).toContain("asRecord(asRecord(bundle.proof).secondaryProof)");
    expect(pageSource).toContain('value != null && !parameterExpectedValue');
  });

  it('supports stake addresses without conflating advanced credentials', () => {
    expect(pageSource).toContain('Stake address');
    expect(pageSource).toContain('Advanced credential');
    expect(pageSource).toContain('/stake-address/${stakeAddress}');
    expect(pageSource).toContain("stakeInputMode === 'address'");
    expect(pageSource).toContain("stakeInputMode === 'credential'");
  });

  it('loads authenticated snapshot epochs independently of parameter epochs', () => {
    expect(pageSource).toContain('snapshotEpochCatalog');
    expect(pageSource).toContain("'l1-epoch-stake-v1.distribution'");
    expect(pageSource).toContain("'l1-epoch-governance-v1.drep-distribution'");
  });
});
