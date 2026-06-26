// @ts-check

import { YanoHttpError } from "./http.js";

const DEFAULT_AWAIT_TIMEOUT_MS = 30_000;
const DEFAULT_POLL_INTERVAL_MS = 100;
const LOVELACE_PER_ADA = 1_000_000n;

/**
 * @typedef {import("./http.js").YanoHttpClient} YanoHttpClient
 *
 * @typedef YanoFundingRequest
 * @property {string} address
 * @property {number=} ada
 * @property {number | string | bigint=} lovelace
 *
 * @typedef YanoAwaitOptions
 * @property {number=} timeoutMs
 * @property {number=} pollIntervalMs
 *
 * @typedef YanoFacades
 * @property {YanoHttpClient} client
 * @property {ReturnType<typeof createQueries>} queries
 * @property {ReturnType<typeof createFaucet>} faucet
 * @property {ReturnType<typeof createTime>} time
 * @property {ReturnType<typeof createSnapshots>} snapshots
 * @property {ReturnType<typeof createDevnetControls>} devnet
 * @property {ReturnType<typeof createTransactions>} transactions
 * @property {ReturnType<typeof createAwait>} await
 * @property {ReturnType<typeof createAssertions>} assertions
 */

/**
 * @param {YanoHttpClient} client
 * @returns {YanoFacades}
 */
export function createYanoFacades(client) {
  const queries = createQueries(client);
  const awaiter = createAwait(queries);
  const snapshots = createSnapshots(client);
  return {
    client,
    queries,
    faucet: createFaucet(client),
    time: createTime(client, queries),
    snapshots,
    devnet: createDevnetControls(client),
    transactions: createTransactions(client, awaiter),
    await: awaiter,
    assertions: createAssertions(queries, snapshots)
  };
}

/**
 * @param {YanoHttpClient} client
 */
function createFaucet(client) {
  return {
    /**
     * @param {string} address
     * @param {number} ada
     */
    async fundAddress(address, ada) {
      requireAddress(address);
      requirePositiveNumber(ada, "ada");
      return client.postJson("devnet/fund", { address, ada });
    },

    /**
     * @param {string} address
     * @param {number | string | bigint} lovelace
     */
    async fundAddressLovelace(address, lovelace) {
      requireAddress(address);
      return client.postJson("devnet/fund", {
        address,
        ada: lovelaceToAdaDecimal(lovelace)
      });
    },

    /**
     * Funds addresses sequentially. Runtime funding is not atomic.
     *
     * @param {YanoFundingRequest[]} requests
     */
    async fundAll(requests) {
      if (!Array.isArray(requests)) {
        throw new Error("fundAll requires an array of funding requests");
      }
      const results = [];
      for (const request of requests) {
        if (!request || typeof request !== "object") {
          throw new Error("fundAll request must be an object");
        }
        if (request.lovelace !== undefined) {
          results.push(await this.fundAddressLovelace(request.address, request.lovelace));
        } else {
          results.push(await this.fundAddress(request.address, request.ada ?? 0));
        }
      }
      return results;
    }
  };
}

/**
 * @param {YanoHttpClient} client
 * @param {ReturnType<typeof createQueries>} queries
 */
function createTime(client, queries) {
  return {
    /** @param {number} slots */
    async advanceSlots(slots) {
      requirePositiveInteger(slots, "slots");
      return client.postJson("devnet/time/advance", { slots });
    },

    /** @param {number} seconds */
    async advanceSeconds(seconds) {
      requirePositiveInteger(seconds, "seconds");
      return client.postJson("devnet/time/advance", { seconds });
    },

    /** @param {number} epochs */
    async advanceEpochs(epochs) {
      requirePositiveInteger(epochs, "epochs");
      return client.postJson("devnet/time/advance", { epochs });
    },

    /** @param {number} targetSlot */
    async advanceToSlot(targetSlot) {
      requireNonNegativeInteger(targetSlot, "targetSlot");
      const currentSlot = await queries.currentSlot();
      if (targetSlot <= currentSlot) {
        return {
          message: "Already at or past target slot",
          new_slot: currentSlot,
          new_block_number: await queries.currentBlockNumber(),
          blocks_produced: 0
        };
      }
      return this.advanceSlots(targetSlot - currentSlot);
    },

    /** @param {number} targetEpoch */
    async advanceToEpoch(targetEpoch) {
      requireNonNegativeInteger(targetEpoch, "targetEpoch");
      const currentEpoch = await queries.currentEpoch();
      if (targetEpoch <= currentEpoch) {
        return {
          message: "Already at or past target epoch",
          new_slot: await queries.currentSlot(),
          new_block_number: await queries.currentBlockNumber(),
          blocks_produced: 0
        };
      }
      return this.advanceToSlot(await queries.epochStartSlot(targetEpoch));
    },

    async crossEpochBoundary() {
      const currentEpoch = await queries.currentEpoch();
      return this.advanceToEpoch(currentEpoch + 1);
    },

    /** @param {number} epochs */
    async shiftGenesisAndStartProducer(epochs) {
      requirePositiveInteger(epochs, "epochs");
      return client.postJson("devnet/epochs/shift", { epochs });
    },

    async catchUpToWallClock() {
      return client.postJson("devnet/epochs/catch-up");
    }
  };
}

/**
 * @param {YanoHttpClient} client
 */
function createSnapshots(client) {
  return {
    /** @param {string} name */
    async create(name) {
      requireName(name, "snapshot name");
      return client.postJson("devnet/snapshot", { name });
    },

    /** @param {string} name */
    async restore(name) {
      requireName(name, "snapshot name");
      return client.postJson(`devnet/restore/${encodeURIComponent(name)}`);
    },

    async list() {
      return client.getJson("devnet/snapshots");
    },

    /** @param {string} name */
    async delete(name) {
      requireName(name, "snapshot name");
      return client.deleteJson(`devnet/snapshot/${encodeURIComponent(name)}`);
    },

    /** @param {string} name */
    async exists(name) {
      requireName(name, "snapshot name");
      const snapshots = await this.list();
      return Array.isArray(snapshots)
        && snapshots.some((snapshot) => snapshot && typeof snapshot === "object"
          && "name" in snapshot && snapshot.name === name);
    },

    /**
     * @template T
     * @param {string} name
     * @param {() => Promise<T> | T} action
     * @returns {Promise<T>}
     */
    async withSnapshot(name, action) {
      requireName(name, "snapshot name");
      if (typeof action !== "function") {
        throw new Error("withSnapshot requires an action function");
      }
      await this.create(name);
      /** @type {unknown} */
      let failure;
      /** @type {T | undefined} */
      let result;
      try {
        result = await action();
      } catch (error) {
        failure = error;
      }

      try {
        await this.restore(name);
      } catch (restoreError) {
        if (failure instanceof Error) {
          attachSuppressed(failure, restoreError);
          throw failure;
        }
        throw restoreError;
      }

      if (failure) {
        throw failure;
      }
      return /** @type {T} */ (result);
    }
  };
}

/**
 * @param {YanoHttpClient} client
 */
function createDevnetControls(client) {
  return {
    /**
     * @param {{ slot?: number, blockNumber?: number, count?: number }} target
     */
    async rollback(target) {
      if (!target || typeof target !== "object") {
        throw new Error("rollback requires a target");
      }
      const supplied = [
        target.slot !== undefined,
        target.blockNumber !== undefined,
        target.count !== undefined
      ].filter(Boolean).length;
      if (supplied !== 1) {
        throw new Error("rollback requires exactly one of slot, blockNumber, or count");
      }
      const body = {
        slot: target.slot,
        block_number: target.blockNumber,
        count: target.count
      };
      return client.postJson("devnet/rollback", body);
    },

    async downloadGenesisZip() {
      return client.getBytes("devnet/genesis/download");
    }
  };
}

/**
 * @param {YanoHttpClient} client
 */
function createQueries(client) {
  /** @type {Promise<unknown> | undefined} */
  let configPromise;
  return {
    status: () => client.getJson("node/status"),
    tip: () => client.getJson("node/tip"),
    /** node/config is immutable for the fixture lifetime, so fetch it once and reuse it. */
    config() {
      if (!configPromise) {
        configPromise = client.getJson("node/config").catch((error) => {
          configPromise = undefined;
          throw error;
        });
      }
      return configPromise;
    },
    genesis: () => client.getJson("genesis"),
    latestBlock: () => client.getJson("blocks/latest"),

    /** @param {string | number} hashOrNumber */
    async block(hashOrNumber) {
      return client.getJson(`blocks/${encodeURIComponent(String(hashOrNumber))}`);
    },

    /** @param {number=} epoch */
    async protocolParameters(epoch) {
      if (epoch === undefined) {
        return client.getJson("epochs/latest/parameters");
      }
      requireNonNegativeInteger(epoch, "epoch");
      return client.getJson(`epochs/${epoch}/parameters`);
    },

    async currentSlot() {
      const tip = await this.tip();
      return numberField(tip, ["slot", "slotNo", "slot_no"], 0);
    },

    async currentBlockNumber() {
      const tip = await this.tip();
      return numberField(tip, ["blockNumber", "block_number", "block"], 0);
    },

    async currentEpoch() {
      const config = await this.config();
      const calc = epochSlotCalc(config);
      return calc.slotToEpoch(await this.currentSlot());
    },

    /** @param {number} epoch */
    async epochStartSlot(epoch) {
      requireNonNegativeInteger(epoch, "epoch");
      const config = await this.config();
      return epochSlotCalc(config).epochToStartSlot(epoch);
    },

    /**
     * @param {string} address
     * @param {{ page?: number, count?: number, order?: "asc" | "desc", usePaymentCredential?: boolean }=} options
     */
    async utxosByAddress(address, options = {}) {
      requireAddress(address);
      const params = queryParams({
        page: options.page,
        count: options.count,
        order: options.order,
        use_payment_credential: options.usePaymentCredential
      });
      return client.getJson(`addresses/${encodeURIComponent(address)}/utxos${params}`);
    },

    /**
     * @param {string} address
     * @param {string} asset
     * @param {{ page?: number, count?: number, order?: "asc" | "desc" }=} options
     */
    async utxosByAddressAndAsset(address, asset, options = {}) {
      requireAddress(address);
      requireName(asset, "asset");
      const params = queryParams({
        page: options.page,
        count: options.count,
        order: options.order
      });
      return client.getJson(`addresses/${encodeURIComponent(address)}/utxos/${encodeURIComponent(asset)}${params}`);
    },

    /**
     * @param {string} txHash
     * @param {number} index
     */
    async utxo(txHash, index) {
      requireName(txHash, "txHash");
      requireNonNegativeInteger(index, "index");
      return client.getJson(`utxos/${encodeURIComponent(txHash)}/${index}`);
    },

    /** @param {string} txHash */
    async tx(txHash) {
      requireName(txHash, "txHash");
      return client.getJson(`txs/${encodeURIComponent(txHash)}`);
    },

    /** @param {string} txHash */
    async txUtxos(txHash) {
      requireName(txHash, "txHash");
      return client.getJson(`txs/${encodeURIComponent(txHash)}/utxos`);
    },

    /** @param {string} paymentCredential */
    async utxosByPaymentCredential(paymentCredential) {
      requireName(paymentCredential, "paymentCredential");
      return client.getJson(`credentials/${encodeURIComponent(paymentCredential)}/utxos`);
    }
  };
}

/**
 * @param {YanoHttpClient} client
 * @param {ReturnType<typeof createAwait>} awaiter
 */
function createTransactions(client, awaiter) {
  return {
    /** @param {BodyInit} txCbor */
    async submitCbor(txCbor) {
      requireBinaryBody(txCbor, "txCbor");
      return extractTxHash(await client.postCbor("tx/submit", txCbor));
    },

    /** @param {string} txHex */
    async submitHex(txHex) {
      requireName(txHex, "txHex");
      return extractTxHash(await client.postText("tx/submit", txHex));
    },

    /**
     * @param {BodyInit | string} tx
     * @param {YanoAwaitOptions=} options
     */
    async submitAndAwait(tx, options = {}) {
      const txHash = typeof tx === "string"
        ? await this.submitHex(tx)
        : await this.submitCbor(tx);
      await awaiter.untilTxVisible(txHash, options);
      return txHash;
    },

    /** @param {BodyInit} txCbor */
    async evaluateCbor(txCbor) {
      requireBinaryBody(txCbor, "txCbor");
      return client.postCbor("utils/txs/evaluate", txCbor);
    },

    /** @param {string} txHex */
    async evaluateHex(txHex) {
      requireName(txHex, "txHex");
      return client.postText("utils/txs/evaluate", txHex);
    }
  };
}

/**
 * @param {ReturnType<typeof createQueries>} queries
 */
function createAwait(queries) {
  return {
    /**
     * @param {() => Promise<boolean> | boolean} condition
     * @param {string} description
     * @param {YanoAwaitOptions=} options
     */
    async until(condition, description, options = {}) {
      if (typeof condition !== "function") {
        throw new Error("until requires a condition function");
      }
      requireName(description, "description");
      const timeoutMs = options.timeoutMs ?? DEFAULT_AWAIT_TIMEOUT_MS;
      const pollIntervalMs = options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
      const deadline = Date.now() + timeoutMs;
      /** @type {unknown} */
      let lastFailure;
      while (Date.now() <= deadline) {
        try {
          if (await condition()) {
            return;
          }
          lastFailure = undefined;
        } catch (error) {
          lastFailure = error;
        }
        await delay(Math.max(1, pollIntervalMs));
      }

      const error = assertionError(`Timed out after ${timeoutMs}ms waiting for ${description}`);
      if (lastFailure) {
        attachSuppressed(error, lastFailure);
      }
      throw error;
    },

    /** @param {YanoAwaitOptions=} options */
    untilReady(options = {}) {
      return this.until(async () => {
        const status = await queries.status();
        return booleanField(status, "running", true)
          && !booleanField(status, "runtimeDegraded", false);
      }, "Yano devnet to be ready", options);
    },

    /** @param {number} slot @param {YanoAwaitOptions=} options */
    untilSlotAtLeast(slot, options = {}) {
      requireNonNegativeInteger(slot, "slot");
      return this.until(async () => await queries.currentSlot() >= slot, `slot >= ${slot}`, options);
    },

    /** @param {number} blockNumber @param {YanoAwaitOptions=} options */
    untilBlockAtLeast(blockNumber, options = {}) {
      requireNonNegativeInteger(blockNumber, "blockNumber");
      return this.until(async () => await queries.currentBlockNumber() >= blockNumber, `block >= ${blockNumber}`, options);
    },

    /** @param {number} epoch @param {YanoAwaitOptions=} options */
    untilEpochAtLeast(epoch, options = {}) {
      requireNonNegativeInteger(epoch, "epoch");
      return this.until(async () => await queries.currentEpoch() >= epoch, `epoch >= ${epoch}`, options);
    },

    /** @param {string} txHash @param {YanoAwaitOptions=} options */
    untilTxVisible(txHash, options = {}) {
      requireName(txHash, "txHash");
      return this.until(async () => {
        try {
          const txUtxos = await queries.txUtxos(txHash);
          return hasVisibleTxOutput(txUtxos);
        } catch (error) {
          if (error instanceof YanoHttpError && error.status === 404) {
            return false;
          }
          throw error;
        }
      }, `tx ${txHash} to be visible`, options);
    }
  };
}

/**
 * @param {ReturnType<typeof createQueries>} queries
 * @param {ReturnType<typeof createSnapshots>} snapshots
 */
function createAssertions(queries, snapshots) {
  return {
    async nodeIsRunning() {
      const status = await queries.status();
      if (!booleanField(status, "running", false)) {
        throw assertionError("Expected Yano devnet node to be running");
      }
    },

    async runtimeNotDegraded() {
      const status = await queries.status();
      if (booleanField(status, "runtimeDegraded", false)) {
        throw assertionError(`Expected runtime to be healthy, got degraded: ${stringField(status, "runtimeDegradedReason", "")}`);
      }
    },

    /** @param {number} slot */
    async slotAtLeast(slot) {
      const actual = await queries.currentSlot();
      if (actual < slot) {
        throw assertionError(`Expected slot >= ${slot}, got ${actual}`);
      }
    },

    /** @param {number} blockNumber */
    async blockAtLeast(blockNumber) {
      const actual = await queries.currentBlockNumber();
      if (actual < blockNumber) {
        throw assertionError(`Expected block >= ${blockNumber}, got ${actual}`);
      }
    },

    /** @param {number} epoch */
    async epochAtLeast(epoch) {
      const actual = await queries.currentEpoch();
      if (actual < epoch) {
        throw assertionError(`Expected epoch >= ${epoch}, got ${actual}`);
      }
    },

    /** @param {string} name */
    async snapshotExists(name) {
      if (!await snapshots.exists(name)) {
        throw assertionError(`Expected snapshot to exist: ${name}`);
      }
    },

    /** @param {string} name */
    async snapshotMissing(name) {
      if (await snapshots.exists(name)) {
        throw assertionError(`Expected snapshot to be missing: ${name}`);
      }
    },

    /** @param {string} address */
    address(address) {
      requireAddress(address);
      return createAddressAssertions(queries, address);
    }
  };
}

/**
 * @param {ReturnType<typeof createQueries>} queries
 * @param {string} address
 */
function createAddressAssertions(queries, address) {
  return {
    async balanceLovelace() {
      return addressBalanceLovelace(queries, address);
    },

    /** @param {number | string | bigint} lovelace */
    async hasAtLeast(lovelace) {
      const expected = toPositiveOrZeroBigInt(lovelace, "lovelace");
      const actual = await this.balanceLovelace();
      if (actual < expected) {
        throw assertionError(`Expected address ${address} to have at least ${expected} lovelace, got ${actual}`);
      }
    },

    /** @param {number} ada */
    async hasAtLeastAda(ada) {
      requireNonNegativeNumber(ada, "ada");
      await this.hasAtLeast(adaToLovelaceBigInt(ada));
    },

    /** @param {number | string | bigint} lovelace */
    async hasExactly(lovelace) {
      const expected = toPositiveOrZeroBigInt(lovelace, "lovelace");
      const actual = await this.balanceLovelace();
      if (actual !== expected) {
        throw assertionError(`Expected address ${address} to have exactly ${expected} lovelace, got ${actual}`);
      }
    },

    /** @param {number} ada */
    async hasExactlyAda(ada) {
      requireNonNegativeNumber(ada, "ada");
      await this.hasExactly(adaToLovelaceBigInt(ada));
    }
  };
}

/**
 * @param {ReturnType<typeof createQueries>} queries
 * @param {string} address
 */
async function addressBalanceLovelace(queries, address) {
  let total = 0n;
  let page = 1;
  while (true) {
    const utxos = await queries.utxosByAddress(address, { page, count: 100 });
    if (!Array.isArray(utxos) || utxos.length === 0) {
      return total;
    }
    for (const utxo of utxos) {
      total += lovelaceFromAmounts(objectField(utxo, "amount"));
    }
    if (utxos.length < 100) {
      return total;
    }
    page++;
  }
}

/**
 * @param {unknown} amounts
 */
function lovelaceFromAmounts(amounts) {
  if (!Array.isArray(amounts)) {
    return 0n;
  }
  for (const amount of amounts) {
    if (amount && typeof amount === "object" && "unit" in amount && amount.unit === "lovelace") {
      return BigInt(String("quantity" in amount ? amount.quantity : 0));
    }
  }
  return 0n;
}

/**
 * @param {unknown} value
 */
function hasVisibleTxOutput(value) {
  if (!value || typeof value !== "object") {
    return false;
  }
  if ("outputs" in value && Array.isArray(value.outputs)) {
    return value.outputs.length > 0;
  }
  return true;
}

/**
 * @param {unknown} response
 */
function extractTxHash(response) {
  if (typeof response === "string" && response.trim()) {
    return response;
  }
  if (response && typeof response === "object") {
    const record = /** @type {Record<string, unknown>} */ (response);
    for (const field of ["txHash", "tx_hash", "hash"]) {
      const value = record[field];
      if (typeof value === "string" && value) {
        return value;
      }
    }
  }
  throw new Error(`Transaction submission response did not include a transaction hash: ${JSON.stringify(response)}`);
}

/**
 * @param {unknown} config
 */
function epochSlotCalc(config) {
  const epochLength = numberField(config, ["epochLength", "epoch_length"], NaN);
  const byronSlotsPerEpoch = numberField(config, ["byronSlotsPerEpoch", "byron_slots_per_epoch"], epochLength);
  const firstNonByronSlot = numberField(config, ["firstNonByronSlot", "first_non_byron_slot"], 0);
  if (!Number.isFinite(epochLength) || epochLength <= 0) {
    throw new Error("node/config does not include a positive epochLength");
  }
  if (!Number.isFinite(byronSlotsPerEpoch) || byronSlotsPerEpoch <= 0) {
    throw new Error("node/config does not include a positive byronSlotsPerEpoch");
  }
  if (firstNonByronSlot > 0 && firstNonByronSlot % byronSlotsPerEpoch !== 0) {
    throw new Error("node/config firstNonByronSlot must be aligned to byronSlotsPerEpoch");
  }
  const firstNonByronEpoch = firstNonByronSlot > 0
    ? Math.trunc(firstNonByronSlot / byronSlotsPerEpoch)
    : 0;
  return {
    /** @param {number} slot */
    slotToEpoch(slot) {
      if (firstNonByronSlot === 0) {
        return Math.trunc(slot / epochLength);
      }
      if (slot < firstNonByronSlot) {
        return Math.trunc(slot / byronSlotsPerEpoch);
      }
      return firstNonByronEpoch + Math.trunc((slot - firstNonByronSlot) / epochLength);
    },
    /** @param {number} epoch */
    epochToStartSlot(epoch) {
      if (firstNonByronSlot === 0) {
        return epoch * epochLength;
      }
      if (epoch <= firstNonByronEpoch) {
        return epoch * byronSlotsPerEpoch;
      }
      return firstNonByronSlot + (epoch - firstNonByronEpoch) * epochLength;
    }
  };
}

/**
 * @param {Record<string, unknown>} values
 */
function queryParams(values) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null) {
      params.set(key, String(value));
    }
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

/**
 * @param {number | string | bigint} lovelace
 */
function lovelaceToAdaDecimal(lovelace) {
  const value = toPositiveBigInt(lovelace, "lovelace");
  const whole = value / LOVELACE_PER_ADA;
  const fraction = value % LOVELACE_PER_ADA;
  if (fraction === 0n) {
    return whole.toString();
  }
  return `${whole}.${fraction.toString().padStart(6, "0").replace(/0+$/, "")}`;
}

/**
 * @param {number} ada
 */
function adaToLovelaceBigInt(ada) {
  return BigInt(Math.trunc(ada * Number(LOVELACE_PER_ADA)));
}

/**
 * @param {unknown} value
 * @returns {Record<string, unknown> | undefined}
 */
function asRecord(value) {
  return value && typeof value === "object"
    ? /** @type {Record<string, unknown>} */ (value)
    : undefined;
}

/**
 * @param {unknown} value
 * @param {string[]} names
 * @param {number} fallback
 */
function numberField(value, names, fallback) {
  const record = asRecord(value);
  if (!record) {
    return fallback;
  }
  for (const name of names) {
    if (typeof record[name] === "number") {
      return record[name];
    }
  }
  return fallback;
}

/**
 * @param {unknown} value
 * @param {string} name
 * @param {boolean} fallback
 */
function booleanField(value, name, fallback) {
  const record = asRecord(value);
  return record && typeof record[name] === "boolean" ? record[name] : fallback;
}

/**
 * @param {unknown} value
 * @param {string} name
 * @param {string} fallback
 */
function stringField(value, name, fallback) {
  const record = asRecord(value);
  return record && typeof record[name] === "string" ? record[name] : fallback;
}

/**
 * @param {unknown} value
 * @param {string} name
 */
function objectField(value, name) {
  const record = asRecord(value);
  return record ? record[name] : undefined;
}

/**
 * @param {string} address
 */
function requireAddress(address) {
  requireName(address, "address");
}

/**
 * @param {string} value
 * @param {string} name
 */
function requireName(value, name) {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`${name} must not be blank`);
  }
}

/**
 * @param {number} value
 * @param {string} name
 */
function requirePositiveNumber(value, name) {
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${name} must be positive`);
  }
}

/**
 * @param {number} value
 * @param {string} name
 */
function requireNonNegativeNumber(value, name) {
  if (!Number.isFinite(value) || value < 0) {
    throw new Error(`${name} must be non-negative`);
  }
}

/**
 * @param {number} value
 * @param {string} name
 */
function requirePositiveInteger(value, name) {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive safe integer`);
  }
}

/**
 * @param {number} value
 * @param {string} name
 */
function requireNonNegativeInteger(value, name) {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${name} must be a non-negative safe integer`);
  }
}

/**
 * @param {number | string | bigint} value
 * @param {string} name
 */
function toPositiveBigInt(value, name) {
  const parsed = toBigInt(value, name);
  if (parsed <= 0n) {
    throw new Error(`${name} must be positive`);
  }
  return parsed;
}

/**
 * @param {number | string | bigint} value
 * @param {string} name
 */
function toPositiveOrZeroBigInt(value, name) {
  const parsed = toBigInt(value, name);
  if (parsed < 0n) {
    throw new Error(`${name} must be non-negative`);
  }
  return parsed;
}

/**
 * @param {number | string | bigint} value
 * @param {string} name
 */
function toBigInt(value, name) {
  if (typeof value === "bigint") {
    return value;
  }
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) {
      throw new Error(`${name} must be a safe integer`);
    }
    return BigInt(value);
  }
  if (/^\d+$/.test(value)) {
    return BigInt(value);
  }
  throw new Error(`${name} must be an integer`);
}

/**
 * @param {BodyInit} body
 * @param {string} name
 */
function requireBinaryBody(body, name) {
  if (body == null) {
    throw new Error(`${name} must not be empty`);
  }
  if (body instanceof ArrayBuffer && body.byteLength === 0) {
    throw new Error(`${name} must not be empty`);
  }
  if (ArrayBuffer.isView(body) && body.byteLength === 0) {
    throw new Error(`${name} must not be empty`);
  }
}

/**
 * @param {string} message
 */
function assertionError(message) {
  const error = new Error(message);
  error.name = "AssertionError";
  return error;
}

/**
 * @param {Error} error
 * @param {unknown} suppressed
 */
function attachSuppressed(error, suppressed) {
  const target = /** @type {Error & { suppressed?: unknown[] }} */ (error);
  target.suppressed = [...(target.suppressed ?? []), suppressed];
}

/**
 * @param {number} ms
 */
function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
