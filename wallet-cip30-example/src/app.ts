import { MeshTxBuilder } from "@meshsdk/transaction";
import { BrowserWallet } from "@meshsdk/wallet";
import {
  addressToBech32,
  deserializeAddress,
  deserializeTxUnspentOutput,
  deserializeValue,
  fromTxUnspentOutput,
  fromValue
} from "@meshsdk/core-cst";
import { DEFAULT_YANO_BRIDGE_URL, metadataMessages, normalizeYanoShimUrl } from "./bridge-helpers";
import "./styles.css";

type WalletSource = "mesh" | "cip30";

type WalletOption = {
  id: string;
  name: string;
  icon?: string;
  version?: string;
  source: WalletSource;
};

type AssetAmount = {
  unit: string;
  quantity: string;
};

type OutputDraft = {
  address: string;
  amounts: AssetAmount[];
};

type ConnectedWallet = {
  option: WalletOption;
  api: any;
  mode: WalletSource;
};

const state: {
  wallets: WalletOption[];
  connected?: ConnectedWallet;
  balance: AssetAmount[];
  recipientAssets: AssetAmount[];
  outputs: OutputDraft[];
  unsignedTx?: string;
  signedTx?: string;
  submittedTxHash?: string;
} = {
  wallets: [],
  balance: [],
  recipientAssets: [],
  outputs: []
};

document.querySelector<HTMLDivElement>("#app")!.innerHTML = `
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true"></div>
        <div>
          <h1>Yano CIP-30 Demo</h1>
          <p>Browser dApp test surface for Yano Wallet and standard Cardano extensions.</p>
        </div>
      </div>
      <p class="muted">
        Start the Yano wallet bridge on the reserved local port, then load the shim.
        Extension wallets such as Eternl or Lace are discovered automatically.
      </p>
      <div id="status" class="status">Ready.</div>
    </aside>

    <main class="main">
      <div class="topline">
        <div>
          <h2>Wallet Bridge Transaction Lab</h2>
          <p class="muted">Connect, build, sign, and submit through the selected CIP-30 wallet.</p>
        </div>
        <div class="row">
          <button id="discover-wallets" class="secondary">Discover Wallets</button>
          <button id="refresh-wallet" class="secondary" disabled>Refresh Wallet</button>
        </div>
      </div>

      <div class="grid">
        <section class="panel">
          <h3>Yano Local Bridge</h3>
          <div class="field">
            <label for="bridge-url">Bridge endpoint or shim URL</label>
            <input id="bridge-url" placeholder="${DEFAULT_YANO_BRIDGE_URL}" />
          </div>
          <div class="row">
            <button id="load-yano-shim" class="primary">Load Yano Shim</button>
            <button id="clear-yano-shim" class="secondary">Clear URL</button>
          </div>
        </section>

        <section class="panel">
          <h3>Wallets</h3>
          <div id="wallet-list" class="wallet-list"></div>
        </section>

        <section class="panel">
          <h3>Connected Wallet</h3>
          <div id="wallet-details"></div>
        </section>

        <section class="panel">
          <h3>Balance</h3>
          <div id="balance-details"></div>
        </section>

        <section class="panel wide">
          <h3>Compose Transaction</h3>
          <div class="grid">
            <div>
              <div class="field">
                <label for="recipient-address">Recipient address</label>
                <input id="recipient-address" placeholder="addr_test1..." />
              </div>
              <div class="field">
                <label for="recipient-ada">ADA</label>
                <input id="recipient-ada" inputmode="decimal" placeholder="Optional ADA amount" />
              </div>
              <div class="field">
                <label for="asset-unit">Native asset unit</label>
                <input id="asset-unit" list="known-assets" placeholder="policyId + asset name hex" />
                <datalist id="known-assets"></datalist>
              </div>
              <div class="field">
                <label for="asset-quantity">Asset quantity</label>
                <input id="asset-quantity" inputmode="numeric" placeholder="0" />
              </div>
              <div class="row">
                <button id="add-asset" class="secondary">Add Asset</button>
                <button id="add-output" class="primary">Add Recipient Output</button>
              </div>
            </div>

            <div>
              <div class="field">
                <label for="metadata-message">CIP-20 message metadata</label>
                <textarea id="metadata-message" placeholder="Optional transaction message"></textarea>
              </div>
              <div id="recipient-assets"></div>
            </div>
          </div>

          <div id="output-table"></div>
          <div class="row">
            <button id="build-tx" class="primary" disabled>Build Unsigned Tx</button>
            <button id="sign-tx" class="secondary" disabled>Sign With Wallet</button>
            <button id="submit-tx" class="primary" disabled>Submit Signed Tx</button>
            <button id="reset-tx" class="danger">Reset Draft</button>
          </div>
        </section>

        <section class="panel wide">
          <h3>Transaction State</h3>
          <div id="tx-state"></div>
        </section>
      </div>
    </main>
  </div>
`;

const bridgeUrlInput = element<HTMLInputElement>("bridge-url");
const walletList = element<HTMLDivElement>("wallet-list");
const walletDetails = element<HTMLDivElement>("wallet-details");
const balanceDetails = element<HTMLDivElement>("balance-details");
const recipientAddressInput = element<HTMLInputElement>("recipient-address");
const recipientAdaInput = element<HTMLInputElement>("recipient-ada");
const assetUnitInput = element<HTMLInputElement>("asset-unit");
const assetQuantityInput = element<HTMLInputElement>("asset-quantity");
const knownAssets = element<HTMLDataListElement>("known-assets");
const metadataInput = element<HTMLTextAreaElement>("metadata-message");
const recipientAssets = element<HTMLDivElement>("recipient-assets");
const outputTable = element<HTMLDivElement>("output-table");
const txState = element<HTMLDivElement>("tx-state");

element<HTMLButtonElement>("load-yano-shim").addEventListener("click", () => run(loadYanoShim));
element<HTMLButtonElement>("clear-yano-shim").addEventListener("click", () => {
  bridgeUrlInput.value = "";
  localStorage.removeItem("yano.cip30.bridgeUrl");
});
element<HTMLButtonElement>("discover-wallets").addEventListener("click", () => run(discoverWallets));
element<HTMLButtonElement>("refresh-wallet").addEventListener("click", () => run(refreshConnectedWallet));
element<HTMLButtonElement>("add-asset").addEventListener("click", addRecipientAsset);
element<HTMLButtonElement>("add-output").addEventListener("click", addRecipientOutput);
element<HTMLButtonElement>("build-tx").addEventListener("click", () => run(buildUnsignedTx));
element<HTMLButtonElement>("sign-tx").addEventListener("click", () => run(signTx));
element<HTMLButtonElement>("submit-tx").addEventListener("click", () => run(submitTx));
element<HTMLButtonElement>("reset-tx").addEventListener("click", resetDraft);

init();

function init() {
  const params = new URLSearchParams(window.location.search);
  const configuredBridge = params.get("yanoBridge")
    || localStorage.getItem("yano.cip30.bridgeUrl")
    || DEFAULT_YANO_BRIDGE_URL;
  const shouldAutoLoadBridge = params.has("yanoBridge") || localStorage.getItem("yano.cip30.bridgeUrl") !== null;
  bridgeUrlInput.value = configuredBridge;
  render();
  run(async () => {
    if (shouldAutoLoadBridge) {
      try {
        await loadYanoShim();
      } catch (error) {
        setStatus(error instanceof Error ? error.message : String(error), "error");
      }
    }
    await discoverWallets();
  });
}

async function loadYanoShim() {
  const scriptUrl = normalizeYanoShimUrl(bridgeUrlInput.value);

  if (!window.cardano?.yano) {
    await injectScript(scriptUrl);
  }
  localStorage.setItem("yano.cip30.bridgeUrl", scriptUrl);
  setStatus(`Loaded Yano CIP-30 shim from ${scriptUrl}`, "ok");
  await discoverWallets();
}

async function discoverWallets() {
  const discovered = new Map<string, WalletOption>();

  for (const wallet of safeMeshWallets()) {
    if (isYanoAlias(wallet.id)) {
      continue;
    }
    discovered.set(wallet.id, {
      id: wallet.id,
      name: wallet.name || wallet.id,
      icon: wallet.icon,
      version: wallet.version,
      source: "mesh"
    });
  }

  for (const [id, wallet] of Object.entries(window.cardano || {})) {
    if (!wallet || typeof wallet.enable !== "function" || isYanoAlias(id)) {
      continue;
    }
    if (!discovered.has(id)) {
      discovered.set(id, {
        id,
        name: wallet.name || id,
        icon: wallet.icon,
        version: wallet.apiVersion,
        source: "cip30"
      });
    }
  }

  state.wallets = Array.from(discovered.values()).sort((a, b) => a.name.localeCompare(b.name));
  setStatus(state.wallets.length === 0
    ? "No CIP-30 wallets discovered. Load the Yano shim or install/unlock a browser wallet."
    : `Discovered ${state.wallets.length} wallet(s).`, state.wallets.length === 0 ? "" : "ok");
  render();
}

async function connectWallet(option: WalletOption) {
  let api: any;
  let mode: WalletSource = option.source;
  if (option.source === "mesh") {
    api = await (BrowserWallet as any).enable(option.id);
  } else {
    const injected = window.cardano?.[option.id];
    if (!injected || typeof injected.enable !== "function") {
      throw new Error(`Wallet ${option.id} is not available.`);
    }
    api = await injected.enable();
  }

  state.connected = { option, api, mode };
  state.unsignedTx = undefined;
  state.signedTx = undefined;
  state.submittedTxHash = undefined;
  await refreshConnectedWallet();
  setStatus(`Connected to ${option.name}.`, "ok");
}

async function refreshConnectedWallet() {
  const wallet = requireWallet();
  state.balance = await getBalance(wallet.api);
  render();
}

async function buildUnsignedTx() {
  const wallet = requireWallet();
  if (state.outputs.length === 0) {
    throw new Error("Add at least one recipient output.");
  }

  validateOutputsAgainstBalance();

  const changeAddress = await getChangeAddress(wallet);
  const networkId = await wallet.api.getNetworkId();
  const utxos = await getUtxosForBuilder(wallet);
  const txBuilder = new MeshTxBuilder({ verbose: true });
  txBuilder.setNetwork(networkId === 1 ? "mainnet" : "preprod");

  for (const output of state.outputs) {
    txBuilder.txOut(output.address, output.amounts);
  }

  const messages = metadataMessages(metadataInput.value);
  if (messages.length > 0) {
    txBuilder.metadataValue(674, { msg: messages });
  }

  state.unsignedTx = await txBuilder
    .changeAddress(changeAddress)
    .selectUtxosFrom(utxos)
    .complete();
  state.signedTx = undefined;
  state.submittedTxHash = undefined;

  setStatus("Unsigned transaction built. Use Sign With Wallet to trigger the wallet approval prompt.", "ok");
  render();
}

async function signTx() {
  const wallet = requireWallet();
  if (!state.unsignedTx) {
    throw new Error("Build an unsigned transaction first.");
  }
  if (typeof wallet.api.signTx !== "function") {
    throw new Error("The connected wallet does not expose signTx.");
  }

  state.signedTx = wallet.mode === "mesh"
    ? await wallet.api.signTx(state.unsignedTx, false, true)
    : await rawSignTxToFullTx(wallet.api, state.unsignedTx);
  setStatus("Transaction signed by the selected wallet.", "ok");
  render();
}

async function submitTx() {
  const wallet = requireWallet();
  if (!state.signedTx) {
    throw new Error("Sign the transaction before submitting.");
  }

  state.submittedTxHash = await wallet.api.submitTx(state.signedTx);
  setStatus(`Transaction submitted: ${state.submittedTxHash}`, "ok");
  render();
}

function addRecipientAsset() {
  const unit = assetUnitInput.value.trim();
  const quantity = assetQuantityInput.value.trim();
  if (!unit) {
    setStatus("Asset unit is required.", "error");
    return;
  }
  if (!/^[0-9a-fA-F]+$/.test(unit)) {
    setStatus("Asset unit must be policy id + asset name in hex.", "error");
    return;
  }
  if (!positiveInteger(quantity)) {
    setStatus("Asset quantity must be a positive integer.", "error");
    return;
  }

  state.recipientAssets.push({ unit: unit.toLowerCase(), quantity });
  assetUnitInput.value = "";
  assetQuantityInput.value = "";
  setStatus("Asset added to the pending recipient output.", "ok");
  render();
}

function addRecipientOutput() {
  const address = recipientAddressInput.value.trim();
  if (!address) {
    setStatus("Recipient address is required.", "error");
    return;
  }

  const amounts: AssetAmount[] = [];
  const lovelace = adaToLovelace(recipientAdaInput.value.trim());
  if (lovelace !== "0") {
    amounts.push({ unit: "lovelace", quantity: lovelace });
  }
  amounts.push(...state.recipientAssets);

  if (amounts.length === 0) {
    setStatus("Add ADA or at least one native asset for this recipient.", "error");
    return;
  }

  state.outputs.push({ address, amounts });
  state.recipientAssets = [];
  recipientAddressInput.value = "";
  recipientAdaInput.value = "";
  state.unsignedTx = undefined;
  state.signedTx = undefined;
  state.submittedTxHash = undefined;
  setStatus("Recipient output added.", "ok");
  render();
}

function resetDraft() {
  state.recipientAssets = [];
  state.outputs = [];
  state.unsignedTx = undefined;
  state.signedTx = undefined;
  state.submittedTxHash = undefined;
  render();
  setStatus("Transaction draft cleared.");
}

function render() {
  walletList.innerHTML = state.wallets.length === 0
    ? `<p class="muted">No wallets discovered yet.</p>`
    : state.wallets.map((wallet) => `
        <div class="wallet-card">
          <div class="wallet-meta">
            <strong>${escapeHtml(wallet.name)}</strong>
            <span>${escapeHtml(wallet.id)} · ${wallet.source}${wallet.version ? ` · ${escapeHtml(wallet.version)}` : ""}</span>
          </div>
          <button class="primary" data-wallet-id="${escapeHtml(wallet.id)}">Connect</button>
        </div>
      `).join("");

  walletList.querySelectorAll<HTMLButtonElement>("[data-wallet-id]").forEach((button) => {
    button.addEventListener("click", () => {
      const option = state.wallets.find((wallet) => wallet.id === button.dataset.walletId);
      if (option) {
        run(() => connectWallet(option));
      }
    });
  });

  walletDetails.innerHTML = state.connected
    ? walletDetailMarkup()
    : `<p class="muted">No wallet connected.</p>`;
  balanceDetails.innerHTML = balanceMarkup();
  knownAssets.innerHTML = state.balance
    .filter((asset) => asset.unit !== "lovelace")
    .map((asset) => `<option value="${escapeHtml(asset.unit)}">${escapeHtml(shortUnit(asset.unit))} · ${escapeHtml(asset.quantity)}</option>`)
    .join("");
  recipientAssets.innerHTML = assetListMarkup("Assets queued for next recipient", state.recipientAssets);
  recipientAssets.querySelectorAll<HTMLButtonElement>("[data-remove-recipient-asset]").forEach((button) => {
    button.addEventListener("click", () => {
      state.recipientAssets.splice(Number(button.dataset.removeRecipientAsset), 1);
      render();
    });
  });
  outputTable.innerHTML = outputTableMarkup();
  txState.innerHTML = txStateMarkup();

  element<HTMLButtonElement>("refresh-wallet").disabled = !state.connected;
  element<HTMLButtonElement>("build-tx").disabled = !state.connected || state.outputs.length === 0;
  element<HTMLButtonElement>("sign-tx").disabled = !state.unsignedTx;
  element<HTMLButtonElement>("submit-tx").disabled = !state.signedTx;
}

function walletDetailMarkup() {
  const wallet = state.connected!;
  return `
    <div class="metric"><span>Name</span><span>${escapeHtml(wallet.option.name)}</span></div>
    <div class="metric"><span>ID</span><span class="mono">${escapeHtml(wallet.option.id)}</span></div>
    <div class="metric"><span>Mode</span><span>${wallet.mode === "mesh" ? "Mesh browser wallet" : "Generic CIP-30"}</span></div>
    <div class="metric"><span>Network</span><span id="network-id">Loading...</span></div>
    <div class="metric"><span>Change address</span><span class="mono" id="change-address">Loading...</span></div>
  `;
}

function balanceMarkup() {
  if (!state.connected) {
    return `<p class="muted">Connect a wallet to inspect balance.</p>`;
  }
  if (state.balance.length === 0) {
    return `<p class="muted">No balance returned.</p>`;
  }
  return `
    <table class="table">
      <thead><tr><th>Asset</th><th>Quantity</th></tr></thead>
      <tbody>
        ${state.balance.map((asset) => `
          <tr>
            <td class="mono">${escapeHtml(asset.unit === "lovelace" ? "ADA lovelace" : shortUnit(asset.unit))}</td>
            <td>${escapeHtml(asset.quantity)}</td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function assetListMarkup(title: string, assets: AssetAmount[]) {
  if (assets.length === 0) {
    return `<p class="muted">${title}: none.</p>`;
  }
  return `
    <p class="muted">${escapeHtml(title)}</p>
    <table class="table">
      <tbody>${assets.map((asset, index) => `
        <tr>
          <td class="mono">${escapeHtml(shortUnit(asset.unit))}</td>
          <td>${escapeHtml(asset.quantity)}</td>
          <td><button class="secondary" data-remove-recipient-asset="${index}">Remove</button></td>
        </tr>
      `).join("")}</tbody>
    </table>
  `;
}

function outputTableMarkup() {
  if (state.outputs.length === 0) {
    return `<p class="muted">No recipient outputs added.</p>`;
  }
  queueMicrotask(() => {
    document.querySelectorAll<HTMLButtonElement>("[data-remove-output]").forEach((button) => {
      button.addEventListener("click", () => {
        state.outputs.splice(Number(button.dataset.removeOutput), 1);
        state.unsignedTx = undefined;
        state.signedTx = undefined;
        render();
      });
    });
  });

  return `
    <table class="table">
      <thead><tr><th>Recipient</th><th>Amounts</th><th></th></tr></thead>
      <tbody>
        ${state.outputs.map((output, index) => `
          <tr>
            <td class="mono">${escapeHtml(abbrev(output.address, 24))}</td>
            <td>${output.amounts.map((amount) => `${escapeHtml(shortUnit(amount.unit))}: ${escapeHtml(amount.quantity)}`).join("<br />")}</td>
            <td><button class="secondary" data-remove-output="${index}">Remove</button></td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function txStateMarkup() {
  return `
    <div class="metric"><span>Unsigned CBOR</span><span class="mono">${state.unsignedTx ? `${state.unsignedTx.length} chars · ${abbrev(state.unsignedTx, 32)}` : "-"}</span></div>
    <div class="metric"><span>Signed CBOR</span><span class="mono">${state.signedTx ? `${state.signedTx.length} chars · ${abbrev(state.signedTx, 32)}` : "-"}</span></div>
    <div class="metric"><span>Submitted tx</span><span class="mono">${state.submittedTxHash || "-"}</span></div>
  `;
}

async function getBalance(api: any): Promise<AssetAmount[]> {
  if (typeof api.getBalance === "function") {
    const balance = await api.getBalance();
    if (Array.isArray(balance)) {
      return balance.map((asset: AssetAmount) => ({
        unit: asset.unit,
        quantity: String(asset.quantity)
      }));
    }
    return fromValue(deserializeValue(balance)).map((asset: AssetAmount) => ({
      unit: asset.unit,
      quantity: String(asset.quantity)
    }));
  }
  return [];
}

async function updateWalletMetrics() {
  if (!state.connected) {
    return;
  }
  const api = state.connected.api;
  const networkId = await api.getNetworkId();
  const changeAddress = await getChangeAddress(state.connected);
  const networkEl = document.querySelector<HTMLSpanElement>("#network-id");
  const addressEl = document.querySelector<HTMLSpanElement>("#change-address");
  if (networkEl) {
    networkEl.textContent = networkId === 1 ? "mainnet" : "testnet/preprod/preview";
  }
  if (addressEl) {
    addressEl.textContent = changeAddress;
  }
}

function validateOutputsAgainstBalance() {
  const available = assetMap(state.balance);
  const requested = assetMap(state.outputs.flatMap((output) => output.amounts));
  for (const [unit, quantity] of requested.entries()) {
    if ((available.get(unit) || 0n) < quantity) {
      throw new Error(`Requested ${shortUnit(unit)} exceeds the connected wallet balance.`);
    }
  }
}

function assetMap(assets: AssetAmount[]) {
  const result = new Map<string, bigint>();
  for (const asset of assets) {
    if (!positiveInteger(asset.quantity) && asset.quantity !== "0") {
      continue;
    }
    result.set(asset.unit, (result.get(asset.unit) || 0n) + BigInt(asset.quantity));
  }
  return result;
}

function safeMeshWallets(): WalletOption[] {
  try {
    return (BrowserWallet as any).getInstalledWallets() || [];
  } catch {
    return [];
  }
}

async function getChangeAddress(wallet: ConnectedWallet) {
  const changeAddress = await wallet.api.getChangeAddress();
  return wallet.mode === "mesh"
    ? changeAddress
    : addressToBech32(deserializeAddress(changeAddress));
}

async function getUtxosForBuilder(wallet: ConnectedWallet) {
  const utxos = await wallet.api.getUtxos();
  if (wallet.mode === "mesh") {
    return utxos || [];
  }
  return (utxos || []).map((utxoCborHex: string) => fromTxUnspentOutput(deserializeTxUnspentOutput(utxoCborHex)));
}

async function rawSignTxToFullTx(api: any, unsignedTx: string) {
  const witnessSet = await api.signTx(unsignedTx, false);
  return (BrowserWallet as any).addBrowserWitnesses(unsignedTx, witnessSet);
}

function isYanoAlias(id: string) {
  return id === "yanoWallet";
}

function requireWallet() {
  if (!state.connected) {
    throw new Error("Connect a wallet first.");
  }
  return state.connected;
}

function injectScript(src: string) {
  return new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[data-yano-cip30="${CSS.escape(src)}"]`);
    if (existing) {
      resolve();
      return;
    }

    const script = document.createElement("script");
    script.src = src;
    script.async = true;
    script.dataset.yanoCip30 = src;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Unable to load ${src}`));
    document.head.append(script);
  });
}

function adaToLovelace(value: string) {
  if (!value) {
    return "0";
  }
  if (!/^\d+(\.\d{1,6})?$/.test(value)) {
    throw new Error("ADA amount must have at most 6 decimal places.");
  }
  const [whole, fraction = ""] = value.split(".");
  return (BigInt(whole) * 1_000_000n + BigInt(fraction.padEnd(6, "0"))).toString();
}

function positiveInteger(value: string) {
  return /^\d+$/.test(value) && BigInt(value) > 0n;
}

function shortUnit(unit: string) {
  return unit === "lovelace" ? "lovelace" : abbrev(unit, 12);
}

function abbrev(value: string, visible: number) {
  if (value.length <= visible * 2 + 3) {
    return value;
  }
  return `${value.slice(0, visible)}...${value.slice(-visible)}`;
}

function escapeHtml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function setStatus(message: string, level: "" | "ok" | "error" = "") {
  const status = element<HTMLDivElement>("status");
  status.textContent = message;
  status.className = `status${level ? ` ${level}` : ""}`;
}

async function run(action: () => Promise<void> | void) {
  try {
    await action();
    await updateWalletMetrics();
  } catch (error) {
    setStatus(error instanceof Error ? error.message : String(error), "error");
  }
}

function element<T extends HTMLElement>(id: string) {
  return document.getElementById(id) as T;
}
