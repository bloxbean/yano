import type { AppChainStatus } from '$lib/api/types';

export interface ChainCapabilities {
  effects: boolean;
  roleApprovals: boolean;
  roleDomainBundle: string | null;
  evidenceBundles: boolean;
  stateProofs: boolean;
  sources: string[];
}

const ROLE_BUNDLE = 'com.bloxbean.cardano.yano.appchain.role-workflow';
const EVIDENCE_BUNDLE = 'com.bloxbean.cardano.yano.appchain.evidence-profile';

export function discoverChainCapabilities(status: AppChainStatus | null,
                                          pluginBundleIds: readonly string[] = []): ChainCapabilities {
  const machine = status?.stateMachine ?? '';
  const effects = isObject(status?.effects) && status?.effects?.enabled === true;
  const roleDomainBundle = machine === 'role-approvals' ? ROLE_BUNDLE
    : machine === 'role-evidence' ? EVIDENCE_BUNDLE : null;
  const catalogConfirmed = roleDomainBundle !== null && pluginBundleIds.includes(roleDomainBundle);
  const sources = ['app-chain core'];
  if (effects) sources.push('status:effects.enabled');
  if (roleDomainBundle) sources.push(`status:stateMachine=${machine}`);
  if (catalogConfirmed) sources.push(`plugin-catalog:${roleDomainBundle}`);
  return {
    effects,
    roleApprovals: roleDomainBundle !== null,
    roleDomainBundle,
    evidenceBundles: !!status?.chainId,
    stateProofs: !!status?.chainId,
    sources
  };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
