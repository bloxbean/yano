import type { ChildProcess } from "node:child_process";

export type YanoStorageMode = "temp-rocksdb" | "persistent-rocksdb";

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

export interface YanoDevnet extends YanoReadyInfo {
  process: ChildProcess;
  logs(): string;
  url(path: string): URL;
  stop(): Promise<void>;
}

export function startYanoDevnet(options?: StartYanoDevnetOptions): Promise<YanoDevnet>;
