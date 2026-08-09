import { describe, expect, it } from 'vitest';
import type { AppChainStatus, UiExtensionCatalogEntry } from '$lib/api/types';
import { isEligible, validateBridgeRequest } from './ui-extension';

const extension: UiExtensionCatalogEntry = {
  bundleId: 'com.example.history', extensionId: 'history', title: 'History',
  mountPoint: 'app-chain', uiApiVersion: 1, assetsDigest: 'ab'.repeat(32),
  entrypointUrl: '/ui-plugins/com.example.history/x/index.html',
  requiredCapabilities: ['l1-epoch-params-v1'],
  permissions: ['app-chain.status.read', 'app-chain.domain.read']
};
const status = {
  chainId: 'history-chain',
  capabilityManifest: {
    schemaVersion: 1, applicationId: 'history', applicationVersion: '1', manifestDigest: 'x',
    components: [{ id: 'l1-epoch-params-v1', version: '1', configurationId: 'x',
      stateNamespace: 'x', topics: [], querySubjects: [], origin: 'COMPOSED' }],
    workflows: [], crossCutting: [], proofSubjects: []
  }
} as AppChainStatus;

describe('plugin UI bridge policy', () => {
  it('gates navigation on the selected chain manifest', () => {
    expect(isEligible(extension, status)).toBe(true);
    expect(isEligible({ ...extension, requiredCapabilities: ['missing'] }, status)).toBe(false);
  });

  it('rejects forged nonces, wrong chains, undeclared permissions and methods', () => {
    const base = { uiApiVersion: 1, sessionNonce: 'nonce', chainId: 'history-chain',
      method: 'app-chain.status', requestId: 'r1', payload: {} };
    expect(validateBridgeRequest(base, extension, 'history-chain', 'nonce')).toEqual(base);
    expect(() => validateBridgeRequest({ ...base, sessionNonce: 'forged' }, extension,
      'history-chain', 'nonce')).toThrow();
    expect(() => validateBridgeRequest({ ...base, chainId: 'other' }, extension,
      'history-chain', 'nonce')).toThrow();
    expect(() => validateBridgeRequest({ ...base, method: 'app-chain.proof' }, extension,
      'history-chain', 'nonce')).toThrow('permission');
    expect(() => validateBridgeRequest({ ...base, method: 'fetch' }, extension,
      'history-chain', 'nonce')).toThrow('unsupported');
  });

  it('gates snapshot proofs and host-mediated downloads on explicit permissions', () => {
    const base = { uiApiVersion: 1, sessionNonce: 'nonce', chainId: 'history-chain',
      method: 'app-chain.snapshot-proof', requestId: 'r2', payload: {} };
    expect(() => validateBridgeRequest(base, extension, 'history-chain', 'nonce'))
      .toThrow('permission');
    expect(validateBridgeRequest(base, { ...extension,
      permissions: [...extension.permissions, 'app-chain.proof.read'] },
    'history-chain', 'nonce')).toEqual(base);
    expect(() => validateBridgeRequest({ ...base, method: 'file.export' }, extension,
      'history-chain', 'nonce')).toThrow('permission');
  });
});
