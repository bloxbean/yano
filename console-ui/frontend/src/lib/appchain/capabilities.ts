import type { AppCapabilityManifest, AppChainStatus } from '$lib/api/types';

export interface ChainCapabilities {
  effects: boolean;
  eutxoExplorer: boolean;
  authenticatedMap: boolean;
  roleApprovals: boolean;
  roleDomainBundle: string | null;
  evidenceBundles: boolean;
  stateProofs: boolean;
  finalizedMessageIndex: boolean;
  commitmentTarget: string;
  sources: string[];
}

const ROLE_BUNDLE = 'com.bloxbean.cardano.yano.appchain.role-workflow';
const hasCapability = (manifest: AppCapabilityManifest | undefined, id: string) =>
  manifest?.crossCutting.some((capability) => capability.enabled && capability.capabilityId === id) === true;
const hasComponent = (manifest: AppCapabilityManifest | undefined, id: string) =>
  manifest?.components.some((component) => component.id === id) === true;

export function discoverChainCapabilities(status: AppChainStatus | null,
                                          _pluginBundleIds: readonly string[] = []): ChainCapabilities {
  const manifest = status?.capabilityManifest;
  const roleApprovals = hasCapability(manifest, 'approval:actor-role-v1');
  const mpf = hasCapability(manifest, 'state-commitment:mpf-blake2b256-v1');
  const jmt = hasCapability(manifest, 'state-commitment:jmt-blake2b256-v1');
  return {
    effects: hasCapability(manifest, 'effects:outbox-v1'),
    eutxoExplorer: hasComponent(manifest, 'eutxo-ledger'),
    authenticatedMap: hasComponent(manifest, 'authenticated-map'),
    roleApprovals,
    roleDomainBundle: roleApprovals ? ROLE_BUNDLE : null,
    evidenceBundles: !!status?.chainId,
    stateProofs: mpf || jmt,
    finalizedMessageIndex: hasCapability(manifest, 'state-index:finalized-message-v1'),
    commitmentTarget: mpf ? 'off-chain + on-chain' : jmt ? 'off-chain only' : 'not declared',
    sources: manifest ? [`capabilityManifest:${manifest.manifestDigest}`] : ['capability manifest unavailable']
  };
}
