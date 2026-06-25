#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const npmRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const packageDirs = [
  "yano-testkit",
  "yano-testkit-linux-x64",
  "yano-testkit-linux-arm64",
  "yano-testkit-macos-arm64",
  "yano-testkit-windows-x64"
];

for (const dir of packageDirs) {
  const cwd = resolve(npmRoot, dir);
  const result = spawnSync("npm", ["pack", "--dry-run"], {
    cwd,
    stdio: "inherit",
    shell: process.platform === "win32"
  });

  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}
