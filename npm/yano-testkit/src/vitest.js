// @ts-check

import { beforeAll, afterAll } from "vitest";
import { startYanoDevnet } from "./index.js";

/**
 * Starts one Yano devnet for a Vitest suite and stops it after the suite.
 *
 * @param {import("./index.js").StartYanoDevnetOptions} [options]
 */
export function yanoDevnet(options = {}) {
  /** @type {import("./index.js").YanoDevnet | null} */
  let current = null;

  beforeAll(async () => {
    current = await startYanoDevnet(options);
  });

  afterAll(async () => {
    await current?.stop();
    current = null;
  });

  return {
    get current() {
      if (!current) {
        throw new Error("Yano devnet is not started yet");
      }
      return current;
    },
    get baseUrl() {
      return this.current.baseUrl;
    },
    get apiBaseUrl() {
      return this.current.apiBaseUrl;
    },
    get client() {
      return this.current.client;
    },
    get queries() {
      return this.current.queries;
    },
    get faucet() {
      return this.current.faucet;
    },
    get time() {
      return this.current.time;
    },
    get snapshots() {
      return this.current.snapshots;
    },
    get devnet() {
      return this.current.devnet;
    },
    get transactions() {
      return this.current.transactions;
    },
    get await() {
      return this.current.await;
    },
    get assertions() {
      return this.current.assertions;
    },
    /** @param {string} path */
    url(path) {
      return this.current.url(path);
    }
  };
}
