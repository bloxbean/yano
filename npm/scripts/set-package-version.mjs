#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const packageDirs = [
  "yano-testkit",
  "yano-testkit-linux-x64",
  "yano-testkit-linux-arm64",
  "yano-testkit-macos-arm64",
  "yano-testkit-windows-x64"
];

const npmRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repoRoot = resolve(npmRoot, "..");
const version = process.argv[2] ?? await readGradleVersion();

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

await updateMainPackageLock(version);

async function readGradleVersion() {
  const gradleProperties = await readFile(resolve(repoRoot, "gradle.properties"), "utf8");
  const detected = gradleProperties.match(/^version\s*=\s*(.+)$/m)?.[1]?.trim();
  if (!detected) {
    throw new Error("Could not read version from gradle.properties");
  }
  return detected;
}

async function updateMainPackageLock(version) {
  const file = resolve(npmRoot, "yano-testkit", "package-lock.json");
  const json = JSON.parse(await readFile(file, "utf8"));
  json.version = version;
  if (json.packages?.[""]) {
    json.packages[""].version = version;
    for (const key of Object.keys(json.packages[""].optionalDependencies ?? {})) {
      json.packages[""].optionalDependencies[key] = version;
    }
  }
  await writeFile(file, `${JSON.stringify(json, null, 2)}\n`);
}
