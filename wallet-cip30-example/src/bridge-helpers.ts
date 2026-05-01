export const YANO_CIP30_BRIDGE_PORT = 47000;
export const DEFAULT_YANO_BRIDGE_URL = `http://127.0.0.1:${YANO_CIP30_BRIDGE_PORT}/cip30`;
export const DEFAULT_YANO_SHIM_URL = `http://127.0.0.1:${YANO_CIP30_BRIDGE_PORT}/yano-cip30.js`;

export function normalizeYanoShimUrl(input: string) {
  if (!input || !input.trim()) {
    throw new Error("Paste the Yano bridge endpoint or /yano-cip30.js URL first.");
  }

  const url = new URL(input.trim());
  if (url.pathname.endsWith("/cip30")) {
    url.pathname = url.pathname.replace(/\/cip30$/, "/yano-cip30.js");
  } else if (!url.pathname.endsWith("/yano-cip30.js")) {
    url.pathname = "/yano-cip30.js";
  }
  url.search = "";
  url.hash = "";

  if (!isLoopbackHttpUrl(url)) {
    throw new Error("Only loopback Yano bridge URLs are allowed by this demo.");
  }

  return url.toString();
}

export function isLoopbackHttpUrl(url: URL) {
  return url.protocol === "http:"
    && (url.hostname === "127.0.0.1" || url.hostname === "localhost");
}

export function metadataMessages(message: string) {
  return message
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .flatMap((line) => chunkUtf8Safe(line, 64));
}

export function chunkUtf8Safe(value: string, maxBytes: number) {
  const chunks: string[] = [];
  let current = "";
  for (const char of value) {
    const next = current + char;
    if (new TextEncoder().encode(next).length > maxBytes) {
      if (current) {
        chunks.push(current);
      }
      current = char;
    } else {
      current = next;
    }
  }
  if (current) {
    chunks.push(current);
  }
  return chunks;
}
