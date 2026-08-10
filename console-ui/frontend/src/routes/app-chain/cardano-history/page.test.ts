import { describe, expect, it } from 'vitest';
import pageSource from './+page.svelte?raw';

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
