/**
 * CIP-30 vault deposit helpers (ADR-UTXO-008 BR-M7). Pure logic only — the
 * unsigned transaction and the datum are built SERVER-side by
 * /eutxo/bridge/deposit/build; the page merely connects a wallet, signs,
 * assembles, and submits.
 */
export interface EutxoBridgeInfo {
  chainId: string;
  vaultAddress: string;
  vaultScriptHash: string;
  withdrawalAddress: string;
  bridgeEpoch: number;
  maxDepositLovelace: number;
  withdrawalsPaused: boolean;
  stabilityDepth: number;
}

export interface DepositBuildResponse {
  chainId: string;
  unsignedTxCborHex: string;
  transactionId: string;
  vaultAddress: string;
  depositorAddress: string;
  l2OwnerAddress: string;
  lovelace: number;
  fee: number;
  ttlSlot: number;
  datumHex: string;
}

export interface DepositAssembleResponse {
  signedTxCborHex: string;
  transactionId: string;
}

const ADA = /^([0-9]+)(?:\.([0-9]{1,6}))?$/;

/** "5" or "5.25" ADA -> lovelace; null when malformed. */
export function adaToLovelace(input: string): number | null {
  const match = ADA.exec(input.trim());
  if (!match) return null;
  const whole = Number(match[1]);
  const fraction = match[2] ? Number(match[2].padEnd(6, '0')) : 0;
  if (!Number.isSafeInteger(whole * 1_000_000 + fraction)) return null;
  return whole * 1_000_000 + fraction;
}

export function validateDeposit(
  lovelace: number | null,
  info: EutxoBridgeInfo | null
): string | null {
  if (lovelace === null) return 'Enter an ADA amount like 5 or 5.25';
  if (lovelace < 1_000_000) return 'Deposit at least 1 ADA';
  if (info && lovelace > info.maxDepositLovelace) {
    return `The chain caps deposits at ${info.maxDepositLovelace / 1_000_000} ADA`;
  }
  return null;
}
