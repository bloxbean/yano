import { describe, expect, it } from 'vitest';
import type { AppCapabilityManifest } from '$lib/api/types';
import { discoverChainCapabilities } from './capabilities';

const manifest = (components: string[], capabilities: string[]): AppCapabilityManifest => ({
  schemaVersion: 1,
  applicationId: 'custom-application',
  applicationVersion: '1.0.0',
  manifestDigest: 'ab'.repeat(32),
  components: components.map((id) => ({ id, version: '1.0.0', configurationId: 'test',
    stateNamespace: `${id}/`, topics: [], querySubjects: [], origin: 'COMPOSED' })),
  workflows: [],
  crossCutting: capabilities.map((capabilityId) => ({ capabilityId, version: '1.0.0',
    enabled: true, configurationDigest: 'test', attributes: {}, origin: 'COMPOSED' })),
  proofSubjects: []
});

describe('app-chain capability discovery', () => {
  it('does not infer reusable capabilities from state-machine names', () => {
    const capabilities = discoverChainCapabilities({ chainId: 'orders', stateMachine: 'ordered-log' });
    expect(capabilities.effects).toBe(false);
    expect(capabilities.eutxoExplorer).toBe(false);
    expect(capabilities.roleApprovals).toBe(false);
    expect(capabilities.sources).toContain('capability manifest unavailable');
  });

  it('discovers effects, role approval and proof target from the manifest', () => {
    const capabilities = discoverChainCapabilities({
      chainId: 'custom', stateMachine: 'unknown', capabilityManifest: manifest(
        ['domain-actors', 'role-approvals'],
        ['approval:actor-role-v1', 'effects:outbox-v1', 'state-commitment:mpf-blake2b256-v1'])
    });
    expect(capabilities.effects).toBe(true);
    expect(capabilities.roleDomainBundle).toContain('role-workflow');
    expect(capabilities.commitmentTarget).toBe('off-chain + on-chain');
  });

  it('gates specialized explorers on declared components', () => {
    expect(discoverChainCapabilities({ chainId: 'custom', stateMachine: 'unknown',
      capabilityManifest: manifest(['eutxo-ledger', 'authenticated-map'], [])
    }).eutxoExplorer).toBe(true);
    expect(discoverChainCapabilities({ chainId: 'named-only', stateMachine: 'eutxo-ledger',
      capabilityManifest: manifest([], [])
    }).eutxoExplorer).toBe(false);
  });
});
