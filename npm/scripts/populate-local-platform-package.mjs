#!/usr/bin/env node

import { chmod, cp, mkdir, rm, stat } from "node:fs/promises";
import { basename, dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const npmRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repoRoot = resolve(npmRoot, "..");

const platformPackages = {
  "linux-x64": {
    packageDir: "yano-testkit-linux-x64",
    binaryName: "yano"
  },
  "linux-arm64": {
    packageDir: "yano-testkit-linux-arm64",
    binaryName: "yano"
  },
  "darwin-arm64": {
    packageDir: "yano-testkit-macos-arm64",
    binaryName: "yano"
  },
  "win32-x64": {
    packageDir: "yano-testkit-windows-x64",
    binaryName: "yano.exe"
  }
};

const args = parseArgs(process.argv.slice(2));
const platformKey = args.platform ?? `${process.platform}-${process.arch}`;
const descriptor = platformPackages[platformKey];

if (!descriptor) {
  throw new Error(
    `Unsupported platform ${platformKey}. Supported platforms: ${Object.keys(platformPackages).join(", ")}`
  );
}

const binaryPath = resolve(repoRoot, args.binary ?? `app/build/${descriptor.binaryName}`);
const configPath = resolve(repoRoot, args.config ?? "app/config");
const packagePath = resolve(npmRoot, descriptor.packageDir);
const targetBinDir = resolve(packagePath, "bin");
const targetConfigDir = resolve(packagePath, "config");
const targetBinaryPath = resolve(targetBinDir, descriptor.binaryName);

await assertFile(binaryPath, "Yano native binary");
await assertDirectory(configPath, "Yano config directory");

await rm(targetBinDir, { recursive: true, force: true });
await rm(targetConfigDir, { recursive: true, force: true });
await mkdir(targetBinDir, { recursive: true });
await cp(binaryPath, targetBinaryPath);
if (process.platform !== "win32") {
  await chmod(targetBinaryPath, 0o755);
}
await cp(configPath, targetConfigDir, { recursive: true });

console.log(`Populated ${descriptor.packageDir}`);
console.log(`  binary: ${relative(npmRoot, targetBinaryPath)}`);
console.log(`  config: ${relative(npmRoot, targetConfigDir)}`);
console.log("");
console.log("To inspect or create the platform package tarball:");
console.log(`  npm pack ./${descriptor.packageDir} --dry-run`);
console.log(`  npm pack ./${descriptor.packageDir}`);

/**
 * @param {string[]} rawArgs
 * @returns {{ platform?: string, binary?: string, config?: string }}
 */
function parseArgs(rawArgs) {
  const parsed = {};
  for (let i = 0; i < rawArgs.length; i++) {
    const arg = rawArgs[i];
    if (arg === "--platform" || arg === "--binary" || arg === "--config") {
      const value = rawArgs[++i];
      if (!value) {
        throw new Error(`${arg} requires a value`);
      }
      parsed[arg.slice(2)] = value;
      continue;
    }
    throw new Error(`Unknown argument: ${arg}`);
  }
  return parsed;
}

/**
 * @param {string} path
 * @param {string} label
 */
async function assertFile(path, label) {
  let info;
  try {
    info = await stat(path);
  } catch {
    throw new Error(`${label} not found at ${path}. Build native Yano first.`);
  }
  if (!info.isFile()) {
    throw new Error(`${label} is not a file: ${path}`);
  }
}

/**
 * @param {string} path
 * @param {string} label
 */
async function assertDirectory(path, label) {
  let info;
  try {
    info = await stat(path);
  } catch {
    throw new Error(`${label} not found at ${path}.`);
  }
  if (!info.isDirectory()) {
    throw new Error(`${label} is not a directory: ${path}`);
  }
}
