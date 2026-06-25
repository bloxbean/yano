#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const npmRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repoRoot = resolve(npmRoot, "..");
const gradleProperties = await readFile(resolve(repoRoot, "gradle.properties"), "utf8");
const version = gradleProperties.match(/^version\s*=\s*(.+)$/m)?.[1]?.trim();

if (!version) {
  throw new Error("Could not read version from gradle.properties");
}

const packageDirs = [
  "yano-testkit",
  "yano-testkit-linux-x64",
  "yano-testkit-linux-arm64",
  "yano-testkit-macos-arm64",
  "yano-testkit-windows-x64"
];

for (const dir of packageDirs) {
  const file = resolve(npmRoot, dir, "package.json");
  const json = JSON.parse(await readFile(file, "utf8"));
  if (json.version !== version) {
    throw new Error(`${dir}/package.json version ${json.version} does not match Gradle version ${version}`);
  }
  for (const [name, dependencyVersion] of Object.entries(json.optionalDependencies ?? {})) {
    if (dependencyVersion !== version) {
      throw new Error(`${dir}/package.json optional dependency ${name} version ${dependencyVersion} does not match ${version}`);
    }
  }
}

console.log(`All npm package versions match Gradle version ${version}`);
