import type { ChildProcess } from "node:child_process";

export type YanoStorageMode = "temp-rocksdb" | "persistent-rocksdb";
export type YanoLovelaceAmount = number | string | bigint;
export type YanoCborBody = BodyInit;

export interface StartYanoDevnetOptions {
  binaryPath?: string;
  cwd?: string;
  httpPort?: number;
  n2nPort?: number;
  blockTimeMillis?: number;
  networkMagic?: number;
  storage?: YanoStorageMode;
  storagePath?: string;
  workDir?: string;
  preserveWorkDir?: boolean;
  timeoutMs?: number;
  env?: Record<string, string | undefined>;
  extraArgs?: string[];
  onStdout?: (line: string) => void;
  onStderr?: (line: string) => void;
}

export interface YanoReadyInfo {
  type: "ready";
  pid: number;
  baseUrl: string;
  apiBaseUrl: string;
  n2nPort: number;
  networkMagic: number;
  storage: {
    mode: YanoStorageMode;
    path: string;
  };
  workDir: string;
}

export interface YanoFundResult {
  tx_hash: string;
  index: number;
  lovelace: number;
}

export interface YanoHttpRequestOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
  headers?: Record<string, string>;
}

export class YanoHttpError extends Error {
  method: string;
  url: string;
  status: number;
  statusText: string;
  bodyText: string;
}

export interface YanoHttpClient {
  apiBaseUrl: string;
  url(path: string): URL;
  getJson<T = unknown>(path: string, options?: YanoHttpRequestOptions): Promise<T>;
  postJson<T = unknown>(path: string, body?: unknown, options?: YanoHttpRequestOptions): Promise<T>;
  deleteJson<T = unknown>(path: string, options?: YanoHttpRequestOptions): Promise<T>;
  postCbor<T = unknown>(path: string, body: YanoCborBody, options?: YanoHttpRequestOptions): Promise<T>;
  postText<T = unknown>(path: string, body: string, options?: YanoHttpRequestOptions): Promise<T>;
  getBytes(path: string, options?: YanoHttpRequestOptions): Promise<Uint8Array>;
}

export interface YanoFundingRequest {
  address: string;
  ada?: number;
  lovelace?: YanoLovelaceAmount;
}

export interface YanoTimeAdvanceResult {
  message: string;
  new_slot: number;
  new_block_number: number;
  blocks_produced: number;
}

export interface YanoSnapshotInfo {
  name: string;
  slot: number;
  block_number: number;
  created_at: number;
}

export interface YanoRollbackResult {
  message: string;
  slot: number;
  block_number: number;
}

export interface YanoAwaitOptions {
  timeoutMs?: number;
  pollIntervalMs?: number;
}

export interface YanoFaucet {
  fundAddress(address: string, ada: number): Promise<YanoFundResult>;
  fundAddressLovelace(address: string, lovelace: YanoLovelaceAmount): Promise<YanoFundResult>;
  fundAll(requests: YanoFundingRequest[]): Promise<YanoFundResult[]>;
}

export interface YanoTime {
  advanceSlots(slots: number): Promise<YanoTimeAdvanceResult>;
  advanceSeconds(seconds: number): Promise<YanoTimeAdvanceResult>;
  advanceEpochs(epochs: number): Promise<YanoTimeAdvanceResult>;
  advanceToSlot(targetSlot: number): Promise<YanoTimeAdvanceResult>;
  advanceToEpoch(targetEpoch: number): Promise<YanoTimeAdvanceResult>;
  crossEpochBoundary(): Promise<YanoTimeAdvanceResult>;
  shiftGenesisAndStartProducer(epochs: number): Promise<Record<string, unknown>>;
  catchUpToWallClock(): Promise<YanoTimeAdvanceResult>;
}

export interface YanoSnapshots {
  create(name: string): Promise<YanoSnapshotInfo>;
  restore(name: string): Promise<Record<string, unknown>>;
  list(): Promise<YanoSnapshotInfo[]>;
  delete(name: string): Promise<Record<string, unknown>>;
  exists(name: string): Promise<boolean>;
  withSnapshot<T>(name: string, action: () => Promise<T> | T): Promise<T>;
}

export interface YanoDevnetControls {
  rollback(target: { slot?: number; blockNumber?: number; count?: number }): Promise<YanoRollbackResult>;
  downloadGenesisZip(): Promise<Uint8Array>;
}

export interface YanoUtxoAmount {
  unit: string;
  quantity: string | number;
}

export interface YanoUtxo {
  tx_hash: string;
  output_index: number;
  address: string;
  amount: YanoUtxoAmount[];
  data_hash?: string | null;
  inline_datum?: string | null;
  script_ref?: string | null;
  reference_script_hash?: string | null;
  block?: string | null;
}

export interface YanoTxUtxos {
  hash: string;
  inputs: YanoUtxo[];
  outputs: YanoUtxo[];
}

export interface YanoQueries {
  status<T = Record<string, unknown>>(): Promise<T>;
  tip<T = Record<string, unknown>>(): Promise<T>;
  config<T = Record<string, unknown>>(): Promise<T>;
  genesis<T = Record<string, unknown>>(): Promise<T>;
  latestBlock<T = Record<string, unknown>>(): Promise<T>;
  block<T = Record<string, unknown>>(hashOrNumber: string | number): Promise<T>;
  protocolParameters<T = unknown>(epoch?: number): Promise<T>;
  currentSlot(): Promise<number>;
  currentBlockNumber(): Promise<number>;
  currentEpoch(): Promise<number>;
  epochStartSlot(epoch: number): Promise<number>;
  utxosByAddress(address: string, options?: {
    page?: number;
    count?: number;
    order?: "asc" | "desc";
    usePaymentCredential?: boolean;
  }): Promise<YanoUtxo[]>;
  utxosByAddressAndAsset(address: string, asset: string, options?: {
    page?: number;
    count?: number;
    order?: "asc" | "desc";
  }): Promise<YanoUtxo[]>;
  utxo(txHash: string, index: number): Promise<YanoUtxo>;
  tx<T = Record<string, unknown>>(txHash: string): Promise<T>;
  txUtxos(txHash: string): Promise<YanoTxUtxos>;
  utxosByPaymentCredential(paymentCredential: string): Promise<YanoUtxo[]>;
}

export interface YanoTransactions {
  submitCbor(txCbor: YanoCborBody): Promise<string>;
  submitHex(txHex: string): Promise<string>;
  submitAndAwait(tx: YanoCborBody | string, options?: YanoAwaitOptions): Promise<string>;
  evaluateCbor<T = Record<string, unknown>>(txCbor: YanoCborBody): Promise<T>;
  evaluateHex<T = Record<string, unknown>>(txHex: string): Promise<T>;
}

export interface YanoAwait {
  until(condition: () => Promise<boolean> | boolean, description: string, options?: YanoAwaitOptions): Promise<void>;
  untilReady(options?: YanoAwaitOptions): Promise<void>;
  untilSlotAtLeast(slot: number, options?: YanoAwaitOptions): Promise<void>;
  untilBlockAtLeast(blockNumber: number, options?: YanoAwaitOptions): Promise<void>;
  untilEpochAtLeast(epoch: number, options?: YanoAwaitOptions): Promise<void>;
  untilTxVisible(txHash: string, options?: YanoAwaitOptions): Promise<void>;
}

export interface YanoAddressAssertions {
  balanceLovelace(): Promise<bigint>;
  hasAtLeast(lovelace: YanoLovelaceAmount): Promise<void>;
  hasAtLeastAda(ada: number): Promise<void>;
  hasExactly(lovelace: YanoLovelaceAmount): Promise<void>;
  hasExactlyAda(ada: number): Promise<void>;
}

export interface YanoAssertions {
  nodeIsRunning(): Promise<void>;
  runtimeNotDegraded(): Promise<void>;
  slotAtLeast(slot: number): Promise<void>;
  blockAtLeast(blockNumber: number): Promise<void>;
  epochAtLeast(epoch: number): Promise<void>;
  snapshotExists(name: string): Promise<void>;
  snapshotMissing(name: string): Promise<void>;
  address(address: string): YanoAddressAssertions;
}

export interface YanoHelperFacades {
  client: YanoHttpClient;
  queries: YanoQueries;
  faucet: YanoFaucet;
  time: YanoTime;
  snapshots: YanoSnapshots;
  devnet: YanoDevnetControls;
  transactions: YanoTransactions;
  await: YanoAwait;
  assertions: YanoAssertions;
}

export interface YanoDevnet extends YanoReadyInfo, YanoHelperFacades {
  process: ChildProcess;
  logs(): string;
  url(path: string): URL;
  fundAddress(address: string, ada: number): Promise<YanoFundResult>;
  stop(): Promise<void>;
}

export function startYanoDevnet(options?: StartYanoDevnetOptions): Promise<YanoDevnet>;
