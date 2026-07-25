import type { AppChainStatus, EutxoTransactionSummary } from '$lib/api/types';

export const EUTXO_BUNDLE_ID = 'com.bloxbean.cardano.yano.appchain.eutxo';
export const EUTXO_STATE_MACHINE_ID = 'eutxo-ledger';
const IDENTIFIER = /^[0-9a-f]{64}$/;

export function isEutxoChain(status: AppChainStatus | null): boolean {
  return status?.stateMachine === EUTXO_STATE_MACHINE_ID;
}

export function canonicalEutxoIdentifier(value: string): string {
  const normalized = value.trim();
  if (!IDENTIFIER.test(normalized)) {
    throw new Error('Enter a lowercase 64-character transaction or message ID');
  }
  return normalized;
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
