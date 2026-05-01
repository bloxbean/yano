/// <reference types="vite/client" />

interface Window {
  cardano?: Record<string, Cip30InjectedWallet | undefined>;
  Buffer?: unknown;
}

interface Cip30InjectedWallet {
  name?: string;
  icon?: string;
  apiVersion?: string;
  supportedExtensions?: Array<{ cip: number }>;
  enable?: (extensions?: Array<{ cip: number }>) => Promise<Cip30WalletApi>;
  isEnabled?: () => Promise<boolean>;
}

interface Cip30WalletApi {
  getNetworkId: () => Promise<number>;
  getBalance: () => Promise<string>;
  getUtxos: () => Promise<string[] | null>;
  getChangeAddress: () => Promise<string>;
  getRewardAddresses: () => Promise<string[]>;
  signTx: (txCborHex: string, partialSign?: boolean) => Promise<string>;
  submitTx: (txCborHex: string) => Promise<string>;
}
