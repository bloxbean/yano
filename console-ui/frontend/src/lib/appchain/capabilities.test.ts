import { describe, expect, it } from 'vitest';
import { discoverChainCapabilities } from './capabilities';

describe('app-chain capability discovery', () => {
  it('does not render optional panels from deployment assumptions', () => {
    const capabilities = discoverChainCapabilities({ chainId: 'orders', stateMachine: 'ordered-log' });
    expect(capabilities.effects).toBe(false);
    expect(capabilities.roleApprovals).toBe(false);
    expect(capabilities.evidenceBundles).toBe(true);
  });

  it('discovers effects and generic role approvals from chain status and confirms the bundle catalog', () => {
    const capabilities = discoverChainCapabilities({
      chainId: 'approvals', stateMachine: 'role-approvals', effects: { enabled: true }
    }, ['com.bloxbean.cardano.yano.appchain.role-workflow']);
    expect(capabilities.effects).toBe(true);
    expect(capabilities.roleDomainBundle).toContain('role-workflow');
    expect(capabilities.sources).toContain('plugin-catalog:com.bloxbean.cardano.yano.appchain.role-workflow');
  });
});
