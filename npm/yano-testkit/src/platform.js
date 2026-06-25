// @ts-check

/** @type {Record<string, { packageName: string, binaryRelativePath: string }>} */
const PLATFORM_PACKAGES = {
  "linux-x64": {
    packageName: "@bloxbean/yano-testkit-linux-x64",
    binaryRelativePath: "bin/yano"
  },
  "linux-arm64": {
    packageName: "@bloxbean/yano-testkit-linux-arm64",
    binaryRelativePath: "bin/yano"
  },
  "darwin-arm64": {
    packageName: "@bloxbean/yano-testkit-macos-arm64",
    binaryRelativePath: "bin/yano"
  },
  "win32-x64": {
    packageName: "@bloxbean/yano-testkit-windows-x64",
    binaryRelativePath: "bin/yano.exe"
  }
};

/**
 * @param {NodeJS.Platform} [platform]
 * @param {NodeJS.Architecture} [arch]
 */
export function platformKey(platform = process.platform, arch = process.arch) {
  return `${platform}-${arch}`;
}

/**
 * @param {NodeJS.Platform} [platform]
 * @param {NodeJS.Architecture} [arch]
 */
export function platformPackage(platform = process.platform, arch = process.arch) {
  return PLATFORM_PACKAGES[platformKey(platform, arch)] ?? null;
}

export function supportedPlatformKeys() {
  return Object.keys(PLATFORM_PACKAGES);
}
