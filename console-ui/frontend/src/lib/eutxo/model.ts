import type { AppChainStatus, EutxoTransactionSummary } from '$lib/api/types';

export const EUTXO_BUNDLE_ID = 'com.bloxbean.cardano.yano.appchain.eutxo';
export const EUTXO_STATE_MACHINE_ID = 'eutxo-ledger';
const IDENTIFIER = /^[0-9a-f]{64}$/;
const OUTPOINT = /^([0-9a-f]{64})#([0-9]|[1-9][0-9]{0,4})$/;

export function isEutxoChain(status: AppChainStatus | null): boolean {
  return status?.capabilityManifest?.components.some(
    (component) => component.id === EUTXO_STATE_MACHINE_ID) === true;
}

export function canonicalEutxoIdentifier(value: string): string {
  const normalized = value.trim();
  if (!IDENTIFIER.test(normalized)) {
    throw new Error('Enter a lowercase 64-character transaction or message ID');
  }
  return normalized;
}

export function canonicalEutxoOutpoint(value: string): {
  transactionId: string; outputIndex: number
} {
  const normalized = value.trim();
  const match = OUTPOINT.exec(normalized);
  if (!match) {
    throw new Error('Enter a lowercase transaction ID followed by #output-index');
  }
  const outputIndex = Number(match[2]);
  if (outputIndex > 65_535) throw new Error('Output index must be at most 65535');
  return { transactionId: match[1], outputIndex };
}

export function transactionIdFromOutpoint(value: string): string {
  return canonicalEutxoOutpoint(value).transactionId;
}

export function indexStatusLabel(status: string): string {
  if (status.startsWith('READY_')) return 'Ready';
  if (status.startsWith('REBUILDING_')) return 'Rebuilding';
  if (status.startsWith('CATCHING_UP_')) return 'Catching up';
  if (status.includes('IDENTITY')) return 'Identity mismatch';
  return 'Unavailable';
}

export function isCompleteProjection(
  status: string, fullHistory: boolean, lagBlocks: number
): boolean {
  return status.startsWith('READY_') && fullHistory && lagBlocks === 0;
}

export function formatLovelace(value: string): string {
  try {
    const lovelace = BigInt(value);
    if (lovelace < 0n) return value;
    const ada = lovelace / 1_000_000n;
    const fraction = (lovelace % 1_000_000n).toString().padStart(6, '0')
      .replace(/0+$/, '');
    return `${ada.toLocaleString()}${fraction ? `.${fraction}` : ''} ADA`;
  } catch {
    return value;
  }
}

export function transactionTitle(value: EutxoTransactionSummary): string {
  return value.transactionId || `Rejected message ${value.messageId}`;
}
