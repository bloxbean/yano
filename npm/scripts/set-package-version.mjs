#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const version = process.argv[2];
if (!version) {
  console.error("Usage: node scripts/set-package-version.mjs <version>");
  process.exit(1);
}

const packageDirs = [
  "yano-testkit",
  "yano-testkit-linux-x64",
  "yano-testkit-linux-arm64",
  "yano-testkit-macos-arm64",
  "yano-testkit-windows-x64"
];

const npmRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

for (const dir of packageDirs) {
  const file = resolve(npmRoot, dir, "package.json");
  const json = JSON.parse(await readFile(file, "utf8"));
  json.version = version;
  if (json.optionalDependencies) {
    for (const key of Object.keys(json.optionalDependencies)) {
      json.optionalDependencies[key] = version;
    }
  }
  await writeFile(file, `${JSON.stringify(json, null, 2)}\n`);
}
