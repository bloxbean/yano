import type { StartYanoDevnetOptions, YanoDevnet } from "./index.js";

export interface VitestYanoDevnet {
  readonly current: YanoDevnet;
  readonly baseUrl: string;
  readonly apiBaseUrl: string;
  url(path: string): URL;
}

export function yanoDevnet(options?: StartYanoDevnetOptions): VitestYanoDevnet;
