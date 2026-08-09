import type { AppChainStatus, UiExtensionCatalogEntry } from '$lib/api/types';

export const UI_API_VERSION = 1;
export const MAX_BRIDGE_REQUEST_BYTES = 256 * 1024;
export const MAX_BRIDGE_RESPONSE_BYTES = 2 * 1024 * 1024;

const methodPermission: Record<string, string | null> = {
  'host.context': null,
  'app-chain.status': 'app-chain.status.read',
  'app-chain.domain': 'app-chain.domain.read',
  'app-chain.query': 'app-chain.query.read',
  'app-chain.proof': 'app-chain.proof.read',
  'app-chain.anchor': 'app-chain.anchor.read'
};

export interface UiBridgeRequest {
  uiApiVersion: number;
  sessionNonce: string;
  chainId: string;
  method: string;
  requestId: string;
  payload: unknown;
}

export function capabilityIds(status: AppChainStatus | null): Set<string> {
  const result = new Set<string>();
  const manifest = status?.capabilityManifest;
  for (const component of manifest?.components ?? []) result.add(component.id);
  for (const capability of manifest?.crossCutting ?? []) {
    if (capability.enabled) result.add(capability.capabilityId);
  }
  for (const subject of manifest?.proofSubjects ?? []) result.add(subject.subjectId);
  return result;
}

export function isEligible(
  extension: UiExtensionCatalogEntry, status: AppChainStatus | null
): boolean {
  if (extension.uiApiVersion !== UI_API_VERSION || extension.mountPoint !== 'app-chain') return false;
  const available = capabilityIds(status);
  return extension.requiredCapabilities.every((capability) => available.has(capability));
}

export function validateBridgeRequest(
  value: unknown,
  extension: UiExtensionCatalogEntry,
  chainId: string,
  sessionNonce: string
): UiBridgeRequest {
  const encoded = JSON.stringify(value);
  if (encoded.length > MAX_BRIDGE_REQUEST_BYTES) throw new Error('bridge request is too large');
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid bridge request');
  const request = value as Partial<UiBridgeRequest>;
  if (request.uiApiVersion !== UI_API_VERSION || request.sessionNonce !== sessionNonce
    || request.chainId !== chainId || typeof request.method !== 'string'
    || typeof request.requestId !== 'string' || !/^[A-Za-z0-9._-]{1,80}$/.test(request.requestId)) {
    throw new Error('invalid bridge binding');
  }
  if (!Object.hasOwn(methodPermission, request.method)) throw new Error('unsupported bridge method');
  const permission = methodPermission[request.method];
  if (permission && !extension.permissions.includes(permission)) throw new Error('bridge permission denied');
  return request as UiBridgeRequest;
}

export function checkedBridgeResponse(value: unknown): unknown {
  if (JSON.stringify(value).length > MAX_BRIDGE_RESPONSE_BYTES) {
    throw new Error('bridge response is too large');
  }
  return value;
}
