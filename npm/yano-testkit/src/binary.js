// @ts-check

import { accessSync, constants, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { platformPackage, platformKey, supportedPlatformKeys } from "./platform.js";

/**
 * @typedef ResolveYanoBinaryOptions
 * @property {string=} binaryPath
 * @property {NodeJS.Platform=} platform
 * @property {NodeJS.Architecture=} arch
 */

/**
 * @param {ResolveYanoBinaryOptions} [options]
 * @returns {string}
 */
export function resolveYanoBinary(options = {}) {
  const override = options.binaryPath || process.env.YANO_TESTKIT_BINARY;
  if (override && override.trim().length > 0) {
    return resolve(override);
  }

  const descriptor = platformPackage(options.platform, options.arch);
  if (!descriptor) {
    throw new Error(
      `Unsupported platform ${platformKey(options.platform, options.arch)}. ` +
      `Supported platforms: ${supportedPlatformKeys().join(", ")}. ` +
      "Set YANO_TESTKIT_BINARY to use a custom Yano binary."
    );
  }

  let packageEntry;
  try {
    packageEntry = import.meta.resolve(descriptor.packageName);
  } catch (error) {
    throw new Error(
      `Yano native package ${descriptor.packageName} is not installed. ` +
      "Install @bloxbean/yano-testkit normally or set YANO_TESTKIT_BINARY. " +
      `Cause: ${error instanceof Error ? error.message : String(error)}`
    );
  }

  const packageRoot = dirname(dirname(fileURLToPath(packageEntry)));
  return resolve(packageRoot, descriptor.binaryRelativePath);
}

/**
 * @param {string} binaryPath
 */
export function assertBinaryExists(binaryPath) {
  if (!existsSync(binaryPath)) {
    throw new Error(
      `Yano binary not found at ${binaryPath}. ` +
      "Build or install a platform package, or set YANO_TESTKIT_BINARY."
    );
  }
  if (process.platform !== "win32") {
    try {
      accessSync(binaryPath, constants.X_OK);
    } catch {
      throw new Error(`Yano binary is not executable at ${binaryPath}. Run chmod +x or rebuild the package.`);
    }
  }
}
