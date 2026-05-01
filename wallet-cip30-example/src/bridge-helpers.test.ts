import { describe, expect, it } from "vitest";
import {
  DEFAULT_YANO_BRIDGE_URL,
  DEFAULT_YANO_SHIM_URL,
  chunkUtf8Safe,
  metadataMessages,
  normalizeYanoShimUrl
} from "./bridge-helpers";

describe("normalizeYanoShimUrl", () => {
  it("defines the deterministic Yano wallet bridge endpoint", () => {
    expect(DEFAULT_YANO_BRIDGE_URL).toBe("http://127.0.0.1:47000/cip30");
    expect(DEFAULT_YANO_SHIM_URL).toBe("http://127.0.0.1:47000/yano-cip30.js");
  });

  it("maps the local cip30 endpoint to the script URL", () => {
    expect(normalizeYanoShimUrl("http://127.0.0.1:47000/cip30"))
      .toBe("http://127.0.0.1:47000/yano-cip30.js");
  });

  it("removes query strings and fragments", () => {
    expect(normalizeYanoShimUrl("http://localhost:47000/yano-cip30.js?token=nope#x"))
      .toBe("http://localhost:47000/yano-cip30.js");
  });

  it("rejects non-loopback bridge URLs", () => {
    expect(() => normalizeYanoShimUrl("https://wallet.example/yano-cip30.js"))
      .toThrow("Only loopback Yano bridge URLs are allowed");
  });
});

describe("metadataMessages", () => {
  it("trims blank lines and keeps each CIP-20 message chunk under 64 bytes", () => {
    const chunks = metadataMessages(` hello\n\n${"a".repeat(80)}`);

    expect(chunks[0]).toBe("hello");
    expect(chunks).toHaveLength(3);
    expect(chunks.every((chunk) => new TextEncoder().encode(chunk).length <= 64)).toBe(true);
  });

  it("does not split a multi-byte character across chunks", () => {
    const chunks = chunkUtf8Safe("é".repeat(40), 64);

    expect(chunks.join("")).toBe("é".repeat(40));
    expect(chunks.every((chunk) => new TextEncoder().encode(chunk).length <= 64)).toBe(true);
  });
});
