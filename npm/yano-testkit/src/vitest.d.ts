import type {
  StartYanoDevnetOptions,
  YanoAssertions,
  YanoAwait,
  YanoDevnet,
  YanoDevnetControls,
  YanoFaucet,
  YanoHttpClient,
  YanoQueries,
  YanoSnapshots,
  YanoTime,
  YanoTransactions
} from "./index.js";

export interface VitestYanoDevnet {
  readonly current: YanoDevnet;
  readonly baseUrl: string;
  readonly apiBaseUrl: string;
  readonly client: YanoHttpClient;
  readonly queries: YanoQueries;
  readonly faucet: YanoFaucet;
  readonly time: YanoTime;
  readonly snapshots: YanoSnapshots;
  readonly devnet: YanoDevnetControls;
  readonly transactions: YanoTransactions;
  readonly await: YanoAwait;
  readonly assertions: YanoAssertions;
  url(path: string): URL;
}

export function yanoDevnet(options?: StartYanoDevnetOptions): VitestYanoDevnet;
