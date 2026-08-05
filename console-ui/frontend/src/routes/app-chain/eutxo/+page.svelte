<script lang="ts">
  import { base } from '$app/paths';
  import { onDestroy, onMount } from 'svelte';
  import { ApiError, apiFailureMessage, resolveApiBase, YanoApi } from '$lib/api/client';
  import type {
    AppChainStatus, ChainSummary, EutxoDeposit, EutxoIndexEnvelope, EutxoIndexedAccount,
    EutxoIndexPage, EutxoIndexStatus, EutxoLineage, EutxoTransactionDetail,
    EutxoTransactionPage, EutxoTransactionSummary, EutxoValidityBatch, EutxoWithdrawal, L1Transaction,
    L1TransactionUtxos
  } from '$lib/api/types';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import Pager from '$lib/components/Pager.svelte';
  import {
    canonicalEutxoIdentifier, canonicalEutxoOutpoint, EUTXO_BUNDLE_ID, formatLovelace,
    indexStatusLabel, isCompleteProjection, isEutxoChain, transactionIdFromOutpoint,
    transactionTitle
  } from '$lib/eutxo/model';
  import { adaToLovelace, validateDeposit } from '$lib/eutxo/deposit';
  import type { EutxoBridgeInfo } from '$lib/eutxo/deposit';

  type View = 'overview' | 'transactions' | 'accounts' | 'bridge' | 'validity';
  type L1Detail = {
    id: string; state: 'loading' | 'ready' | 'unavailable' | 'not-found' | 'failed';
    transaction?: L1Transaction; utxos?: L1TransactionUtxos; message?: string
  };
  type TransactionPageState = {
    items: EutxoTransactionSummary[];
    nextCursor: string;
  };

  let api: YanoApi | null = null;
  let bridgeInfo: EutxoBridgeInfo | null = null;
  let installedWallets: string[] = [];
  let connectedWallet = '';
  let depositAmount = '5';
  let depositBusy = false;
  let depositMessage = '';
  let depositTxId = '';
  let depositL2Owner = '';
  let l2To = '';
  let l2Amount = '2';
  let l2Payout = '';
  let l2Busy = false;
  let l2Message = '';
  // The CF connect-with-wallet core touches browser globals at import time,
  // so it must never load during prerender — dynamic import only.
  let walletCoreModule:
    typeof import('@cardano-foundation/cardano-connect-with-wallet-core') | null = null;
  async function walletCore() {
    walletCoreModule ??= await import('@cardano-foundation/cardano-connect-with-wallet-core');
    return walletCoreModule;
  }

  /**
   * Enumerate window.cardano ourselves, keeping the ORIGINAL key casing
   * (connect and enable() need the exact key), and keep rescanning: wallet
   * extensions — especially native-messaging bridges — often inject their
   * API after this page has already activated.
   */
  function refreshWallets() {
    const injected = (window as unknown as {
      cardano?: Record<string, { enable?: unknown }>;
    }).cardano;
    // getOwnPropertyNames, not Object.keys: some extensions inject their
    // API via defineProperty with enumerable:false, which hides them from
    // every keys()-based scan (including the CF library's own).
    installedWallets = injected
      ? Object.getOwnPropertyNames(injected).filter(
          (name) => typeof injected[name]?.enable === 'function')
      : [];
  }

  let walletRescanTimer: ReturnType<typeof setInterval> | null = null;
  onDestroy(() => {
    if (walletRescanTimer) clearInterval(walletRescanTimer);
  });
  function ensureWalletRescan() {
    refreshWallets();
    if (walletRescanTimer) return;
    walletRescanTimer = setInterval(() => {
      if (connectedWallet || activeView !== 'bridge') return;
      refreshWallets();
    }, 2000);
  }
  let chains: ChainSummary[] = [];
  let selectedChain = '';
  let status: AppChainStatus | null = null;
  let activeView: View = 'overview';
  let indexEnvelope: EutxoIndexEnvelope<EutxoIndexStatus> | null = null;
  let indexAvailable: boolean | null = null;
  let transactions: EutxoTransactionSummary[] = [];
  let transactionCursor = '';
  let transactionPages: TransactionPageState[] = [];
  let transactionPageIndex = 0;
  let selectedTransaction: EutxoTransactionSummary | null = null;
  let deposits: EutxoDeposit[] = [];
  let withdrawals: EutxoWithdrawal[] = [];
  let selectedDeposit: EutxoDeposit | null = null;
  let selectedWithdrawal: EutxoWithdrawal | null = null;
  let validityBatches: EutxoValidityBatch[] = [];
  let selectedValidity: EutxoValidityBatch | null = null;
  let lineage: EutxoLineage | null = null;
  let account: EutxoIndexedAccount | null = null;
  let accountInput = '';
  let searchInput = '';
  let searchError = '';
  let pageError = '';
  let loading = false;
  let loadingMore = false;
  let l1Detail: L1Detail | null = null;
  let controller: AbortController | null = null;
  let refreshTimer: ReturnType<typeof setInterval> | null = null;

  const short = (value: string, width = 20) =>
    value.length <= width ? value : `${value.slice(0, Math.max(6, width - 7))}…${value.slice(-6)}`;
  const total = (entries: Array<{ lovelace: string }>) => {
    try {
      return formatLovelace(entries.reduce((sum, entry) => sum + BigInt(entry.lovelace), 0n).toString());
    } catch {
      return '-';
    }
  };
  const query = () => ({ chain: selectedChain });
  $: transactionHasPrevious = transactionPageIndex > 0;
  $: transactionHasNext = transactionCursor !== '';

  onMount(() => {
    let disposed = false;
    void (async () => {
      try {
        api = new YanoApi(await resolveApiBase());
        chains = await api.chains();
        if (disposed) return;
        const parameters = new URLSearchParams(location.search);
        const requested = parameters.get('chain');
        selectedChain = chains.some((chain) => chain.chainId === requested)
          ? requested! : chains[0]?.chainId ?? '';
        if (!selectedChain) {
          pageError = 'No app chains are enabled on this node.';
          return;
        }
        await activateChain(selectedChain);
        const deepLink = parameters.get('transaction') ?? parameters.get('message')
          ?? parameters.get('claim') ?? parameters.get('outpoint');
        if (deepLink && !disposed) {
          searchInput = deepLink;
          await search();
        }
        refreshTimer = setInterval(() => void refreshIndexStatus(), 5_000);
      } catch (cause) {
        pageError = apiFailureMessage(cause, 'Unable to load the EUTxO explorer');
      }
    })();
    return () => {
      disposed = true;
      controller?.abort();
      if (refreshTimer) clearInterval(refreshTimer);
    };
  });

  async function activateChain(chainId: string): Promise<void> {
    if (!api) return;
    controller?.abort();
    controller = new AbortController();
    const signal = controller.signal;
    selectedChain = chainId;
    status = null;
    indexEnvelope = null;
    indexAvailable = null;
    transactions = [];
    transactionCursor = '';
    transactionPages = [];
    transactionPageIndex = 0;
    deposits = [];
    withdrawals = [];
    selectedTransaction = null;
    selectedDeposit = null;
    selectedWithdrawal = null;
    validityBatches = [];
    selectedValidity = null;
    account = null;
    l1Detail = null;
    pageError = '';
    searchError = '';
    loading = true;
    try {
      status = await api.chainStatus(chainId, signal);
      if (!isEutxoChain(status)) return;
      bridgeInfo = null;
      depositTxId = '';
      depositMessage = '';
      void api.eutxoBridgeInfo(chainId, signal)
        .then((value) => (bridgeInfo = value))
        .catch(() => (bridgeInfo = null));
      ensureWalletRescan();
      try {
        await refreshIndexStatus(signal);
        indexAvailable = true;
        await Promise.all([
          loadTransactions(false, signal),
          loadBridge(signal),
          loadValidity(signal)
        ]);
      } catch (cause) {
        if (!(cause instanceof ApiError) || ![404, 409].includes(cause.status)) throw cause;
        indexAvailable = false;
        await loadCommittedFallback(signal);
      }
      const parameters = new URLSearchParams(location.search);
      parameters.set('chain', chainId);
      history.replaceState({}, '', `${base}/app-chain/eutxo/?${parameters}`);
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        pageError = apiFailureMessage(cause, 'Unable to load EUTxO lifecycle data');
      }
    } finally {
      if (!signal.aborted) loading = false;
    }
  }

  async function refreshIndexStatus(signal?: AbortSignal): Promise<void> {
    if (!api || !selectedChain || indexAvailable === false || !isEutxoChain(status)) return;
    try {
      const envelope = await api.eutxoIndex<EutxoIndexEnvelope<EutxoIndexStatus>>(
        'status', query(), signal);
      assertEnvelope(envelope);
      indexEnvelope = envelope;
      indexAvailable = true;
    } catch (cause) {
      if (cause instanceof ApiError && [404, 409].includes(cause.status)) indexAvailable = false;
      else throw cause;
    }
  }

  async function loadTransactions(append: boolean, signal?: AbortSignal): Promise<void> {
    if (!api || indexAvailable === false) return;
    if (append && transactionPages[transactionPageIndex + 1]) {
      transactionPageIndex++;
      const cached = transactionPages[transactionPageIndex];
      transactions = cached.items;
      transactionCursor = cached.nextCursor;
      return;
    }
    if (append && !transactionCursor) return;
    if (append) loadingMore = true;
    try {
      const parameters: Record<string, string> = { ...query(), limit: '25' };
      if (append && transactionCursor) parameters.cursor = transactionCursor;
      const envelope = await api.eutxoIndex<EutxoIndexEnvelope<EutxoIndexPage<EutxoTransactionSummary>>>(
        'transactions', parameters, signal);
      assertEnvelope(envelope);
      const page = { items: envelope.data.items, nextCursor: envelope.data.cursor };
      if (append) {
        transactionPageIndex++;
        transactionPages = [...transactionPages.slice(0, transactionPageIndex), page];
      } else {
        transactionPageIndex = 0;
        transactionPages = [page];
      }
      transactions = page.items;
      transactionCursor = envelope.data.cursor;
      indexEnvelope = { ...envelope, data: indexEnvelope?.data ?? {
        storeType: '', checkpointHeight: envelope.projection.indexedHeight,
        finalizedHeight: envelope.projection.finalizedHeight,
        lagBlocks: envelope.projection.lagBlocks,
        coverage: envelope.projection.fullHistory ? 'FULL' : 'PARTIAL', normalizedDigest: ''
      }};
    } finally {
      loadingMore = false;
    }
  }

  function loadPreviousTransactionPage(): void {
    if (transactionPageIndex === 0) return;
    transactionPageIndex--;
    const page = transactionPages[transactionPageIndex];
    transactions = page.items;
    transactionCursor = page.nextCursor;
  }

  async function loadBridge(signal?: AbortSignal): Promise<void> {
    if (!api || indexAvailable === false) return;
    const [depositPage, withdrawalPage] = await Promise.all([
      api.eutxoIndex<EutxoIndexEnvelope<EutxoIndexPage<EutxoDeposit>>>(
        'bridge/deposits', { ...query(), limit: '25' }, signal),
      api.eutxoIndex<EutxoIndexEnvelope<EutxoIndexPage<EutxoWithdrawal>>>(
        'bridge/withdrawals', { ...query(), limit: '25' }, signal)
    ]);
    assertEnvelope(depositPage);
    assertEnvelope(withdrawalPage);
    deposits = depositPage.data.items;
    withdrawals = withdrawalPage.data.items;
  }

  async function loadValidity(signal?: AbortSignal): Promise<void> {
    if (!api || indexAvailable === false
      || indexEnvelope?.data.validityAvailable === false) return;
    try {
      const envelope = await api.eutxoIndex<
        EutxoIndexEnvelope<EutxoIndexPage<EutxoValidityBatch>>
      >('validity/batches', { ...query(), limit: '25' }, signal);
      assertEnvelope(envelope);
      validityBatches = envelope.data.items;
    } catch (cause) {
      if (!(cause instanceof ApiError) || cause.status !== 409) throw cause;
      validityBatches = [];
    }
  }

  async function loadCommittedFallback(signal?: AbortSignal): Promise<void> {
    if (!api) return;
    const page = await api.domain<EutxoTransactionPage>(
      EUTXO_BUNDLE_ID, 'transactions', { ...query(), limit: '20' }, signal);
    if (page.chainId !== selectedChain || page.stateMachineId !== 'eutxo-ledger') {
      throw new Error('Committed EUTxO response identity does not match the selected chain');
    }
    transactions = page.data;
    transactionPages = [{ items: page.data, nextCursor: '' }];
    transactionPageIndex = 0;
    transactionCursor = '';
  }

  async function selectTransaction(id: string, byMessage = false): Promise<void> {
    if (!api) return;
    if (indexAvailable) {
      const path = byMessage ? `messages/${id}` : `transactions/${id}`;
      const envelope = await api.eutxoIndex<EutxoIndexEnvelope<EutxoTransactionSummary>>(
        path, query());
      assertEnvelope(envelope);
      selectedTransaction = envelope.data;
    } else {
      const detail = await api.domain<EutxoTransactionDetail>(
        EUTXO_BUNDLE_ID, `${byMessage ? 'messages' : 'transactions'}/${id}`, query());
      selectedTransaction = detail.data;
    }
    activeView = 'transactions';
  }

  async function loadAccount(): Promise<void> {
    if (!api || !indexAvailable) return;
    const address = accountInput.trim();
    if (!address || address.length > 256 || !/^[A-Za-z0-9_]+$/.test(address)) {
      searchError = 'Enter a canonical bounded L2 address.';
      return;
    }
    const envelope = await api.eutxoIndex<EutxoIndexEnvelope<EutxoIndexedAccount>>(
      `accounts/${address}`, query());
    assertEnvelope(envelope);
    account = envelope.data;
    activeView = 'accounts';
  }

  async function search(): Promise<void> {
    if (!api || !isEutxoChain(status)) return;
    searchError = '';
    l1Detail = null;
    const value = searchInput.trim();
    try {
      if (value.includes('#')) {
        if (!indexAvailable) throw new Error('Deposit lookup requires the local lifecycle index');
        const outpoint = canonicalEutxoOutpoint(value);
        const envelope = await api.eutxoIndex<EutxoIndexEnvelope<EutxoDeposit>>(
          `bridge/deposits/${outpoint.transactionId}/${outpoint.outputIndex}`, query());
        assertEnvelope(envelope);
        selectedDeposit = envelope.data;
        selectedWithdrawal = null;
        activeView = 'bridge';
        return;
      }
      if (value.startsWith('addr')) {
        accountInput = value;
        await loadAccount();
        return;
      }
      const id = canonicalEutxoIdentifier(value);
      try {
        await selectTransaction(id);
        return;
      } catch (cause) {
        if (!(cause instanceof ApiError) || cause.status !== 404) throw cause;
      }
      try {
        await selectTransaction(id, true);
        return;
      } catch (cause) {
        if (!(cause instanceof ApiError) || cause.status !== 404 || !indexAvailable) throw cause;
      }
      try {
        const envelope = await api.eutxoIndex<EutxoIndexEnvelope<EutxoWithdrawal>>(
          `bridge/withdrawals/${id}`, query());
        assertEnvelope(envelope);
        selectedWithdrawal = envelope.data;
        selectedDeposit = null;
        activeView = 'bridge';
      } catch (cause) {
        if (!(cause instanceof ApiError) || cause.status !== 404 || !indexAvailable) throw cause;
        const envelope = await api.eutxoIndex<EutxoIndexEnvelope<EutxoValidityBatch>>(
          `validity/batches/${id}`, query());
        assertEnvelope(envelope);
        selectedValidity = envelope.data;
        activeView = 'validity';
      }
    } catch (cause) {
      searchError = cause instanceof ApiError && cause.status === 404
        ? 'No indexed transaction, message, deposit, or withdrawal matches that value.'
        : apiFailureMessage(cause, 'Lifecycle lookup failed');
    }
  }

  async function showDeposit(value: EutxoDeposit): Promise<void> {
    selectedDeposit = value;
    selectedWithdrawal = null;
    lineage = null;
    l1Detail = null;
    activeView = 'bridge';
    if (!api) return;
    try {
      const outpoint = canonicalEutxoOutpoint(value.mirroredOutpoint);
      const envelope = await api.eutxoIndex<EutxoIndexEnvelope<EutxoLineage>>(
        `lineage/outpoints/${outpoint.transactionId}/${outpoint.outputIndex}`,
        { ...query(), direction: 'both', depth: '3' });
      assertEnvelope(envelope);
      lineage = envelope.data;
    } catch {
      lineage = null;
    }
  }

  function showWithdrawal(value: EutxoWithdrawal): void {
    selectedWithdrawal = value;
    selectedDeposit = null;
    lineage = null;
    l1Detail = null;
    activeView = 'bridge';
  }

  async function loadL1(transactionId: string): Promise<void> {
    if (!api) return;
    let id: string;
    try {
      id = canonicalEutxoIdentifier(transactionId);
    } catch {
      l1Detail = { id: transactionId, state: 'failed', message: 'Invalid L1 transaction identity' };
      return;
    }
    l1Detail = { id, state: 'loading' };
    try {
      const [transaction, utxos] = await Promise.all([
        api.l1Transaction(id),
        api.l1TransactionUtxos(id)
      ]);
      if (transaction.hash && transaction.hash !== id || utxos.hash && utxos.hash !== id) {
        l1Detail = { id, state: 'failed', message: 'L1 source identity does not match' };
      } else {
        l1Detail = { id, state: 'ready', transaction, utxos };
      }
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) {
        l1Detail = { id, state: 'not-found', message: 'Transaction is outside retained L1 history.' };
      } else if (cause instanceof ApiError && cause.status === 503) {
        l1Detail = { id, state: 'unavailable', message: 'L1 UTxO state is disabled on this node.' };
      } else {
        l1Detail = { id, state: 'failed', message: apiFailureMessage(cause, 'L1 lookup failed') };
      }
    }
  }

  function assertEnvelope(value: EutxoIndexEnvelope<unknown>): void {
    if (value.apiVersion !== 'eutxo-index/v1' || value.chainId !== selectedChain
      || value.stateMachineId !== 'eutxo-ledger' || value.projection.kind !== 'DERIVED') {
      throw new Error('EUTxO index response identity does not match the selected chain');
    }
  }
  async function connectDepositWallet(name: string) {
    depositMessage = '';
    try {
      const core = await walletCore();
      await core.Wallet.connect(name, core.NetworkType.TESTNET,
        () => { connectedWallet = name; },
        (cause) => { depositMessage = `Wallet connection failed: ${String(cause)}`; });
    } catch (cause) {
      depositMessage = `Wallet connection failed: ${String(cause)}`;
    }
  }

  async function submitVaultDeposit() {
    if (!api || !bridgeInfo || !connectedWallet || depositBusy) return;
    const lovelace = adaToLovelace(depositAmount);
    const problem = validateDeposit(lovelace, bridgeInfo);
    if (problem) { depositMessage = problem; return; }
    depositBusy = true;
    depositTxId = '';
    let step = 'connecting to the wallet';
    try {
      const walletHandle = await (window as unknown as {
        cardano: Record<string, { enable: () => Promise<{
          getChangeAddress: () => Promise<string>;
          signTx: (txHex: string, partial: boolean) => Promise<string>;
        }> }>;
      }).cardano[connectedWallet].enable();
      step = 'reading the wallet change address';
      const depositorAddress = await walletHandle.getChangeAddress();
      step = 'building the unsigned deposit on the node';
      depositMessage = 'Requesting the unsigned deposit from the node…';
      const build = await api.eutxoDepositBuild(selectedChain, {
        depositorAddress, lovelace: lovelace as number
      });
      step = 'signing in the wallet';
      depositMessage = 'Sign the deposit in your wallet…';
      const witnessSetCborHex = await walletHandle.signTx(build.unsignedTxCborHex, true);
      step = 'assembling the signed transaction on the node';
      depositMessage = 'Assembling and submitting…';
      const assembled = await api.eutxoDepositAssemble(selectedChain, {
        unsignedTxCborHex: build.unsignedTxCborHex, witnessSetCborHex
      });
      step = 'submitting to the L1';
      await api.submitTxHex(assembled.signedTxCborHex);
      depositTxId = assembled.transactionId;
      depositL2Owner = build.l2OwnerAddress;
      depositMessage = `Submitted. The deposit mirrors onto the L2 after ${bridgeInfo.stabilityDepth} stable L1 blocks.`;
      setTimeout(() => { void loadBridge(); }, 4000);
    } catch (cause) {
      const detail = cause instanceof ApiError
        ? `${cause.message} (HTTP ${cause.status})`
        : cause instanceof Error ? cause.message : JSON.stringify(cause);
      depositMessage = `Deposit failed while ${step}: ${detail}`;
    } finally {
      depositBusy = false;
    }
  }
  async function submitL2(kind: 'transfer' | 'claim') {
    if (!api || !bridgeInfo || !connectedWallet || l2Busy) return;
    const lovelace = adaToLovelace(l2Amount);
    if (lovelace === null || lovelace < 1) {
      l2Message = 'Enter an ADA amount like 2 or 2.5';
      return;
    }
    if (kind === 'transfer' && !l2To.trim()) {
      l2Message = 'Enter the destination L2 address';
      return;
    }
    l2Busy = true;
    let step = 'connecting to the wallet';
    try {
      const walletHandle = await (window as unknown as {
        cardano: Record<string, { enable: () => Promise<{
          getChangeAddress: () => Promise<string>;
          signTx: (txHex: string, partial: boolean) => Promise<string>;
        }> }>;
      }).cardano[connectedWallet].enable();
      step = 'reading the wallet change address';
      const fromAddress = await walletHandle.getChangeAddress();
      step = 'building the unsigned L2 transaction on the node';
      l2Message = 'Building the L2 transaction…';
      const build = kind === 'transfer'
        ? await api.eutxoL2TransferBuild(selectedChain, {
            fromAddress, toAddress: l2To.trim(), lovelace })
        : await api.eutxoL2ClaimBuild(selectedChain, {
            fromAddress, lovelace,
            ...(l2Payout.trim() ? { payoutAddress: l2Payout.trim() } : {}) });
      step = 'signing in the wallet';
      l2Message = 'Sign the L2 transaction in your wallet…';
      const witnessSetCborHex = await walletHandle.signTx(build.unsignedTxCborHex, true);
      step = 'assembling the signed transaction';
      const assembled = await api.eutxoDepositAssemble(selectedChain, {
        unsignedTxCborHex: build.unsignedTxCborHex, witnessSetCborHex
      });
      step = 'submitting to the chain';
      const submitted = await api.chainSubmitMessage(
        selectedChain, build.submitTopic, assembled.signedTxCborHex);
      l2Message = kind === 'transfer'
        ? `L2 transfer submitted (tx ${build.transactionId.slice(0, 16)}…, message ${submitted.messageId.slice(0, 16)}…).`
        : `Withdrawal claim submitted (tx ${build.transactionId.slice(0, 16)}…) — it appears under Withdrawal claims once finalized; the operator settles it on L1.`;
      setTimeout(() => { void loadTransactions(false); void loadBridge(); }, 4000);
    } catch (cause) {
      const detail = cause instanceof ApiError
        ? `${cause.message} (HTTP ${cause.status})`
        : cause instanceof Error ? cause.message : JSON.stringify(cause);
      l2Message = `Failed while ${step}: ${detail}`;
    } finally {
      l2Busy = false;
    }
  }
</script>

<svelte:head><title>Yano · EUTxO Explorer</title></svelte:head>

<header data-console-route="eutxo" class="mb-4 flex flex-wrap items-end justify-between gap-3">
  <div>
    <p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-cyan-400">Lifecycle explorer</p>
    <h1 class="mt-1 text-2xl font-bold">EUTxO Explorer</h1>
    <p class="mb-0 mt-1 text-sm text-slate-500">Canonical L1 → L2 → L1 history, derived from finalized records.</p>
  </div>
  <div class="flex flex-wrap items-end gap-2">
    <label class="text-xs text-slate-400">Chain
      <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
              value={selectedChain} onchange={(event) => void activateChain(event.currentTarget.value)}>
        {#each chains as chain}<option value={chain.chainId}>{chain.chainId}</option>{/each}
      </select>
    </label>
    <a class="rounded-lg border border-slate-700 px-3 py-2 text-xs text-slate-300 no-underline hover:border-slate-500"
       href={`${base}/app-chain/?chain=${encodeURIComponent(selectedChain)}`}>Generic app-chain view</a>
  </div>
</header>

{#if pageError}
  <div role="alert" class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{pageError}</div>
{/if}

{#if status && !isEutxoChain(status)}
  <section class="card p-6">
    <h2 class="m-0 text-lg font-semibold">Explorer unavailable for this chain</h2>
    <p class="mb-0 mt-2 text-sm text-slate-400">
      <strong>{selectedChain}</strong> uses <code>{status.stateMachine ?? 'an unknown state machine'}</code>.
      This reviewed route is enabled only for <code>eutxo-ledger</code>.
    </p>
  </section>
{:else}
  <section class="card p-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="flex flex-wrap gap-2 text-xs">
        <span class="badge">chain {selectedChain || '-'}</span>
        <span class="badge">eutxo-ledger</span>
        {#if indexEnvelope}
          <span class="badge {isCompleteProjection(indexEnvelope.projection.status,
            indexEnvelope.projection.fullHistory, indexEnvelope.projection.lagBlocks) ? 'badge-ok' : 'badge-warn'}">
            {indexStatusLabel(indexEnvelope.projection.status)}
          </span>
          <span class="badge">{indexEnvelope.projection.fullHistory ? 'full history' : 'partial history'}</span>
          <span class="badge">lag {indexEnvelope.projection.lagBlocks}</span>
        {/if}
      </div>
      <form class="flex min-w-[min(100%,31rem)] gap-2"
            onsubmit={(event) => { event.preventDefault(); void search(); }}>
        <label class="sr-only" for="eutxo-search">Search lifecycle identity</label>
        <input id="eutxo-search" bind:value={searchInput} autocomplete="off" spellcheck="false"
               class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs"
               placeholder="transaction, message, claim, outpoint, or address" />
        <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Find</button>
      </form>
    </div>
    {#if searchError}<p role="alert" class="mb-0 mt-3 text-sm text-rose-300">{searchError}</p>{/if}
  </section>

  {#if indexAvailable === false}
    <div class="mt-4 rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-100">
      The optional lifecycle index is unavailable. Finalized L2 transactions remain visible below;
      account history, bridge lineage, and validity batches require <code>indexer:eutxo-lifecycle</code>.
    </div>
  {/if}
  {#if indexEnvelope?.data.diagnosticCode}
    <div role="alert"
         class="mt-4 rounded-xl border border-rose-500/40 bg-rose-500/10 p-4 text-sm text-rose-100">
      <strong>Bridge reconciliation required.</strong>
      The committed bridge diagnostic is
      <code>{indexEnvelope.data.diagnosticCode}</code>. Previously accepted
      lifecycle history remains visible, but operators must reconcile the L1
      source before treating new bridge activity as healthy.
    </div>
  {/if}

  <nav aria-label="EUTxO views" class="mt-4 flex gap-1 overflow-x-auto border-b border-slate-800">
    {#each (indexAvailable
      ? ['overview', 'transactions', 'accounts', 'bridge',
        ...(indexEnvelope?.data.validityAvailable ? ['validity'] : [])]
      : ['transactions']) as view}
      <button type="button" class="whitespace-nowrap border-b-2 px-4 py-3 text-sm capitalize
              {activeView === view ? 'border-cyan-400 text-cyan-300' : 'border-transparent text-slate-400'}"
              aria-current={activeView === view ? 'page' : undefined}
              onclick={() => {
                activeView = view as View;
                // Tab data loads at chain activation; refetch on entry so
                // freshly finalized transactions/claims appear without a
                // full page reload.
                if (view === 'transactions') void loadTransactions(false);
                else if (view === 'bridge') void loadBridge();
                else if (view === 'validity') void loadValidity();
              }}>{view}</button>
    {/each}
  </nav>

  {#if activeView === 'overview' && indexAvailable}
    <div class="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <section class="card p-4"><div class="text-xs uppercase text-slate-500">Indexed height</div>
        <div class="mt-2 text-2xl font-bold">{indexEnvelope?.projection.indexedHeight.toLocaleString() ?? '-'}</div></section>
      <section class="card p-4"><div class="text-xs uppercase text-slate-500">Recent L2 attempts</div>
        <div class="mt-2 text-2xl font-bold">{transactions.length}</div><div class="text-xs text-slate-500">bounded page</div></section>
      <section class="card p-4"><div class="text-xs uppercase text-slate-500">Stable deposits</div>
        <div class="mt-2 text-2xl font-bold">{deposits.length}</div><div class="text-xs text-slate-500">bounded page</div></section>
      <section class="card p-4"><div class="text-xs uppercase text-slate-500">Withdrawal claims</div>
        <div class="mt-2 text-2xl font-bold">{withdrawals.length}</div><div class="text-xs text-slate-500">bounded page</div></section>
    </div>
    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">Projection health</h2>
      <dl class="mt-4 grid gap-4 text-xs sm:grid-cols-2 lg:grid-cols-4">
        <div><dt class="text-slate-500">Coverage</dt><dd class="mt-1">{indexEnvelope?.data.coverage ?? '-'}</dd></div>
        <div><dt class="text-slate-500">Finalized height</dt><dd class="mt-1">{indexEnvelope?.projection.finalizedHeight ?? '-'}</dd></div>
        <div><dt class="text-slate-500">Store</dt><dd class="mt-1">{indexEnvelope?.data.storeType ?? '-'}</dd></div>
        <div><dt class="text-slate-500">Projection digest</dt><dd class="mt-1"><CopyValue value={indexEnvelope?.data.normalizedDigest ?? ''} width={28} label="projection digest" /></dd></div>
      </dl>
      {#if indexEnvelope && !indexEnvelope.projection.fullHistory}
        <p class="mb-0 mt-4 text-amber-300">Results begin at app height {indexEnvelope.projection.historyFromHeight}; absence before that point is unknown, not zero.</p>
      {/if}
    </section>
  {/if}

  {#if activeView === 'transactions'}
    <section class="card mt-4 overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full min-w-[860px] text-left text-xs">
          <thead class="text-slate-500"><tr>
            <th class="p-3">Position</th><th>Status</th><th>Transaction</th><th>Message</th>
            <th>Inputs</th><th>Outputs</th><th>Output value</th><th>Authorization</th>
          </tr></thead>
          <tbody>
            {#each transactions as transaction}
              <tr class="border-t border-slate-800/60 hover:bg-slate-800/35">
                <td class="p-3 font-mono">{transaction.appHeight}:{transaction.ordinal}</td>
                <td><span class="badge {transaction.status === 'ACCEPTED' ? 'badge-ok' : 'badge-bad'}">{transaction.status}</span></td>
                <td>
                  <span class="inline-flex items-center gap-1.5">
                    <button class="font-mono text-cyan-300 hover:text-cyan-200"
                            title={transactionTitle(transaction)}
                            onclick={() => void selectTransaction(
                              transaction.transactionId || transaction.messageId,
                              !transaction.transactionId)}>
                      {short(transaction.transactionId || '-', 22)}
                    </button>
                    {#if transaction.transactionId}
                      <CopyValue value={transaction.transactionId} iconOnly
                                 label="L2 transaction ID" />
                    {/if}
                  </span>
                </td>
                <td><CopyValue value={transaction.messageId} width={20} label="app-message ID" /></td>
                <td>{transaction.inputs.length}</td><td>{transaction.outputs.length}</td>
                <td class="font-mono">{total(transaction.outputs)}</td>
                <td><span class="badge">{transaction.authorizationProfile || 'unknown'}</span></td>
              </tr>
            {:else}
              <tr><td colspan="8" class="p-6 text-center text-sm text-slate-500">
                {loading ? 'Loading finalized EUTxO transactions…' : 'No finalized EUTxO transactions in this coverage window.'}
              </td></tr>
            {/each}
          </tbody>
        </table>
      </div>
      {#if transactions.length || transactionHasPrevious}
        <Pager page={transactionPageIndex + 1}
               hasPrevious={transactionHasPrevious}
               hasNext={transactionHasNext}
               busy={loadingMore}
               label="L2 transactions"
               onPrevious={loadPreviousTransactionPage}
               onNext={() => void loadTransactions(true)} />
      {/if}
    </section>
    {#if selectedTransaction}
      <section class="card mt-4 overflow-hidden" aria-live="polite">
        <div class="flex flex-wrap justify-between gap-3 border-b border-slate-800 p-4">
          <div><h2 class="m-0 text-base font-semibold">L2 transaction {selectedTransaction.appHeight}:{selectedTransaction.ordinal}</h2>
            <p class="mb-0 mt-1 text-xs text-slate-500">L1 slot context {selectedTransaction.l1Slot} · sender sequence {selectedTransaction.sequence}</p></div>
          <span class="badge {selectedTransaction.status === 'ACCEPTED' ? 'badge-ok' : 'badge-bad'}">{selectedTransaction.status}</span>
        </div>
        <div class="grid gap-5 p-4 lg:grid-cols-2">
          <div class="space-y-3 text-xs">
            <div><div class="text-slate-500">Transaction ID</div><CopyValue value={selectedTransaction.transactionId} width={60} label="L2 transaction ID" /></div>
            <div><div class="text-slate-500">App-message ID</div><CopyValue value={selectedTransaction.messageId} width={60} label="app-message ID" /></div>
            <div><div class="text-slate-500">Authorization</div><div class="mt-1">{selectedTransaction.authorizationProfile}</div></div>
            {#if selectedTransaction.code}<div><div class="text-slate-500">Result</div><div class="mt-1 text-rose-300">{selectedTransaction.code}</div></div>{/if}
          </div>
          <div class="grid gap-3 text-xs sm:grid-cols-2">
            <div><h3 class="m-0 text-xs uppercase text-slate-500">Inputs</h3>
              {#each selectedTransaction.inputs as entry}<div class="mt-2 rounded-lg bg-slate-950/60 p-2">
                <div class="text-slate-500">Outpoint</div>
                <CopyValue value={entry.outpoint} width={28} label="input outpoint" />
                <div class="mt-2 text-slate-500">Owner address</div>
                <CopyValue value={entry.address} width={32} label="input owner address" />
                <div class="mt-2">{formatLovelace(entry.lovelace)}</div>
              </div>{/each}</div>
            <div><h3 class="m-0 text-xs uppercase text-slate-500">Outputs</h3>
              {#each selectedTransaction.outputs as entry}<div class="mt-2 rounded-lg bg-slate-950/60 p-2">
                <div class="text-slate-500">Outpoint</div>
                <CopyValue value={entry.outpoint} width={28} label="output outpoint" />
                <div class="mt-2 text-slate-500">Owner address</div>
                <CopyValue value={entry.address} width={32} label="output owner address" />
                <div class="mt-2">{formatLovelace(entry.lovelace)}</div>
              </div>{/each}</div>
          </div>
        </div>
        {#if validityBatches.find((batch) =>
          batch.transactionIds.includes(selectedTransaction!.transactionId))}
          <div class="border-t border-slate-800 p-4 text-xs">
            Included in validity batch
            <button class="ml-2 font-mono text-cyan-300" onclick={() => {
              selectedValidity = validityBatches.find((batch) =>
                batch.transactionIds.includes(selectedTransaction!.transactionId)) ?? null;
              activeView = 'validity';
            }}>{short(validityBatches.find((batch) =>
              batch.transactionIds.includes(selectedTransaction!.transactionId))?.batchId ?? '', 30)}</button>
          </div>
        {/if}
      </section>
    {/if}
  {/if}

  {#if activeView === 'accounts' && indexAvailable}
    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">Account activity</h2>
      <form class="mt-4 flex gap-2" onsubmit={(event) => { event.preventDefault(); void loadAccount(); }}>
        <label class="sr-only" for="account-address">L2 address</label>
        <input id="account-address" bind:value={accountInput} class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs"
               placeholder="addr_test… or profile address" />
        <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950">Open</button>
      </form>
      {#if account}
        <div class="mt-5 grid gap-4 lg:grid-cols-3">
          <div><div class="text-xs text-slate-500">Address</div><CopyValue value={account.address} width={34} label="account address" /></div>
          <div><div class="text-xs text-slate-500">Indexed unspent balance</div><div class="mt-1 font-mono text-cyan-300">{formatLovelace(account.lovelace)}</div></div>
          <div><div class="text-xs text-slate-500">Current UTxOs</div><div class="mt-1">{account.utxos.length}</div></div>
        </div>
        <div class="mt-5 grid gap-5 lg:grid-cols-2">
          <div><h3 class="text-xs uppercase text-slate-500">Current UTxOs</h3>
            {#each account.utxos as entry}<div class="mt-2 rounded-lg bg-slate-950/60 p-3 text-xs">
              <CopyValue value={entry.outpoint} width={42} label="account outpoint" />
              <div class="mt-2 text-slate-500">Owner address</div>
              <CopyValue value={entry.address} width={34} label="UTxO owner address" />
              <div class="mt-2 font-mono">{formatLovelace(entry.lovelace)}</div></div>
            {:else}<p class="text-sm text-slate-500">No current UTxOs.</p>{/each}</div>
          <div><h3 class="text-xs uppercase text-slate-500">Bounded activity</h3>
            {#each account.activityTransactionIds as id}<div class="mt-2 flex items-center gap-1.5">
              <button class="font-mono text-xs text-cyan-300"
                      onclick={() => void selectTransaction(id)}>{short(id, 36)}</button>
              <CopyValue value={id} iconOnly label="L2 transaction ID" />
            </div>
            {:else}<p class="text-sm text-slate-500">No activity in indexed history.</p>{/each}</div>
        </div>
      {/if}
    </section>
  {/if}

  {#if activeView === 'bridge' && bridgeInfo}
    <section class="card mt-4 p-4">
      <h2 class="m-0 text-base font-semibold">Deposit with your wallet (CIP-30)</h2>
      <p class="mt-2 text-xs text-slate-500">
        The node builds the unsigned vault deposit with the mandatory inline
        datum; your wallet signs it. A plain transfer to the vault address is
        NOT a deposit. Vault
        <span class="text-cyan-300">{bridgeInfo.vaultAddress.slice(0, 24)}…</span>
        · cap {bridgeInfo.maxDepositLovelace / 1_000_000} ADA
        {#if bridgeInfo.withdrawalsPaused}· withdrawals paused{/if}
      </p>
      {#if !connectedWallet}
        <div class="mt-3 flex flex-wrap gap-2">
          {#each installedWallets as walletName}
            <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs hover:border-cyan-700"
                    onclick={() => void connectDepositWallet(walletName)}>Connect {walletName}</button>
          {:else}
            <p class="text-sm text-slate-500">No CIP-30 wallet detected yet — extensions can inject
              after page load; this rescans every 2s.</p>
          {/each}
          <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs hover:border-cyan-700"
                  onclick={() => refreshWallets()}>Rescan wallets</button>
        </div>
      {:else}
        <div class="mt-3 flex flex-wrap items-center gap-2">
          <span class="badge badge-ok">wallet: {connectedWallet}</span>
          <input class="w-28 rounded-lg border border-slate-700 bg-transparent px-3 py-2 text-xs"
                 bind:value={depositAmount} aria-label="deposit amount in ADA" />
          <span class="text-xs text-slate-500">ADA</span>
          <button class="rounded-lg border border-cyan-700 px-3 py-2 text-xs hover:bg-cyan-950"
                  disabled={depositBusy}
                  onclick={() => void submitVaultDeposit()}>
            {depositBusy ? 'Working…' : 'Deposit'}
          </button>
          <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs"
                  onclick={() => { void walletCore().then((core) => core.Wallet.disconnect()); connectedWallet = ''; }}>Disconnect</button>
        </div>
      {/if}
      {#if connectedWallet}
        <div class="mt-4 border-t border-slate-800 pt-3">
          <h3 class="m-0 text-sm font-semibold">Spend on the L2 with the same wallet</h3>
          <div class="mt-2 flex flex-wrap items-center gap-2">
            <input class="w-72 rounded-lg border border-slate-700 bg-transparent px-3 py-2 text-xs"
                   bind:value={l2To} placeholder="destination L2 address"
                   aria-label="L2 transfer destination address" />
            <input class="w-24 rounded-lg border border-slate-700 bg-transparent px-3 py-2 text-xs"
                   bind:value={l2Amount} aria-label="L2 amount in ADA" />
            <span class="text-xs text-slate-500">ADA</span>
            <button class="rounded-lg border border-cyan-700 px-3 py-2 text-xs hover:bg-cyan-950"
                    disabled={l2Busy}
                    onclick={() => void submitL2('transfer')}>Transfer L2</button>
          </div>
          <div class="mt-2 flex flex-wrap items-center gap-2">
            <input class="w-72 rounded-lg border border-slate-700 bg-transparent px-3 py-2 text-xs"
                   bind:value={l2Payout} placeholder="L1 payout address (default: your address)"
                   aria-label="withdrawal L1 payout address" />
            <button class="rounded-lg border border-amber-700 px-3 py-2 text-xs hover:bg-amber-950"
                    disabled={l2Busy}
                    onclick={() => void submitL2('claim')}>Withdraw to L1</button>
          </div>
          <p class="mt-2 text-xs text-slate-500">Withdrawals create an irrevocable
            claim{bridgeInfo.withdrawalsPaused ? ' (currently paused)' : ''}; the
            operator settles it on the L1 to the payout address.</p>
          {#if l2Message}<p class="mt-2 text-xs text-amber-300">{l2Message}</p>{/if}
        </div>
      {/if}
      {#if depositMessage}<p class="mt-3 text-xs text-amber-300">{depositMessage}</p>{/if}
      {#if depositTxId}
        <div class="mt-2 text-xs text-slate-500">L1 transaction</div>
        <div class="text-xs text-cyan-300"><CopyValue value={depositTxId} width={40} label="deposit L1 transaction id" /></div>
        <div class="mt-2 text-xs text-slate-500">L2 owner</div>
        <div class="text-xs"><CopyValue value={depositL2Owner} width={40} label="deposit L2 owner address" /></div>
      {/if}
    </section>
  {/if}

  {#if activeView === 'bridge' && indexAvailable}
    <div class="mt-4 grid gap-4 lg:grid-cols-2">
      <section class="card p-4"><h2 class="m-0 text-base font-semibold">Stable deposits</h2>
        {#each deposits as deposit}<div class="mt-3 rounded-lg border border-slate-800 p-3">
          <div class="flex justify-between gap-2 text-xs"><span class="badge badge-ok">L1 accepted</span><span>credited #{deposit.creditedHeight}</span></div>
          <div class="mt-3 text-xs text-slate-500">Accepted outpoint</div>
          <div class="text-xs text-cyan-300"><CopyValue value={deposit.acceptedOutpoint}
              width={40} label="accepted deposit outpoint" /></div>
          <div class="mt-2 text-xs text-slate-500">L2 owner address</div>
          <div class="text-xs"><CopyValue value={deposit.l2Address} width={40}
              label="deposit L2 owner address" /></div>
          <button class="mt-3 rounded-lg border border-slate-700 px-3 py-2 text-xs hover:border-cyan-700"
                  onclick={() => void showDeposit(deposit)}>Open lifecycle</button>
        </div>{:else}<p class="text-sm text-slate-500">No stable deposits in this page.</p>{/each}
      </section>
      <section class="card p-4"><h2 class="m-0 text-base font-semibold">Withdrawal claims</h2>
        {#each withdrawals as withdrawal}<div class="mt-3 rounded-lg border border-slate-800 p-3">
          <div class="flex justify-between gap-2 text-xs"><span class="badge">{withdrawal.status}</span><span>{formatLovelace(withdrawal.lovelace)}</span></div>
          <div class="mt-3 text-xs text-slate-500">Claim ID</div>
          <div class="text-xs text-cyan-300"><CopyValue value={withdrawal.claimId}
              width={40} label="withdrawal claim ID" /></div>
          <div class="mt-2 text-xs text-slate-500">L1 destination address</div>
          <div class="text-xs"><CopyValue value={withdrawal.destinationAddress} width={40}
              label="withdrawal L1 destination address" /></div>
          <button class="mt-3 rounded-lg border border-slate-700 px-3 py-2 text-xs hover:border-cyan-700"
                  onclick={() => showWithdrawal(withdrawal)}>Open lifecycle</button>
        </div>{:else}<p class="text-sm text-slate-500">No withdrawal claims in this page.</p>{/each}
      </section>
    </div>

    {#if selectedDeposit || selectedWithdrawal}
      <section class="card mt-4 p-5">
        <h2 class="m-0 text-base font-semibold">Canonical lifecycle</h2>
        <ol class="mt-5 grid gap-3 text-xs lg:grid-cols-5">
          {#if selectedDeposit}
            <li class="rounded-lg border border-emerald-700/50 p-3"><span class="badge badge-ok">1 · L1 accepted</span>
              <div class="mt-2"><CopyValue value={selectedDeposit.acceptedOutpoint} width={28} label="accepted deposit outpoint" /></div>
              <div class="mt-1 text-slate-500">slot {selectedDeposit.l1Slot}</div></li>
            <li class="rounded-lg border border-emerald-700/50 p-3"><span class="badge badge-ok">2 · L2 credited</span>
              <div class="mt-2"><CopyValue value={selectedDeposit.mirroredOutpoint} width={28} label="mirrored L2 outpoint" /></div>
              <div class="mt-2 text-slate-500">Owner address</div>
              <div class="mt-1"><CopyValue value={selectedDeposit.l2Address} width={28}
                  label="deposit L2 owner address" /></div>
              <div class="mt-1 text-slate-500">app height {selectedDeposit.creditedHeight}</div></li>
            <li class="rounded-lg border border-slate-700 p-3"><span class="badge">3 · L2 activity</span>
              <div class="mt-2 text-slate-400">{lineage?.nodes.length ?? 0} bounded lineage nodes</div></li>
            <li class="rounded-lg border border-slate-800 p-3 text-slate-500">4 · Withdrawal not selected</li>
            <li class="rounded-lg border border-slate-800 p-3 text-slate-500">5 · L1 payout not selected</li>
          {:else if selectedWithdrawal}
            <li class="rounded-lg border border-slate-800 p-3 text-slate-500">1 · Funding origins in bounded lineage</li>
            <li class="rounded-lg border border-slate-800 p-3 text-slate-500">2 · L2 activity</li>
            <li class="rounded-lg border border-emerald-700/50 p-3"><span class="badge badge-ok">3 · Withdrawal requested</span>
              <div class="mt-2"><CopyValue value={selectedWithdrawal.claimId} width={28} label="withdrawal claim ID" /></div>
              <div class="mt-2 text-slate-500">L1 destination</div>
              <div class="mt-1"><CopyValue value={selectedWithdrawal.destinationAddress} width={28}
                  label="withdrawal L1 destination address" /></div>
              <div class="mt-1">{formatLovelace(selectedWithdrawal.lovelace)}</div></li>
            <li class="rounded-lg border border-slate-700 p-3"><span class="badge">4 · {selectedWithdrawal.status}</span>
              <div class="mt-2 text-slate-500">updated app height {selectedWithdrawal.updatedHeight}</div></li>
            <li class="rounded-lg border border-slate-700 p-3"><span class="badge {selectedWithdrawal.confirmedSlot > 0 ? 'badge-ok' : ''}">5 · L1 payout</span>
              {#if selectedWithdrawal.settlementTransactionId}<div class="mt-2"><CopyValue value={selectedWithdrawal.settlementTransactionId} width={28} label="settlement transaction ID" /></div>{/if}
              <div class="mt-1 text-slate-500">{selectedWithdrawal.confirmedSlot > 0 ? `stable at slot ${selectedWithdrawal.confirmedSlot}` : 'not stably confirmed'}</div></li>
          {/if}
        </ol>
        <div class="mt-5 flex flex-wrap gap-2">
          {#if selectedDeposit}
            <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs"
                    onclick={() => void loadL1(transactionIdFromOutpoint(selectedDeposit!.acceptedOutpoint))}>Inspect accepted L1 transaction</button>
            <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs"
                    onclick={() => void loadL1(transactionIdFromOutpoint(selectedDeposit!.stagingOutpoint))}>Inspect staging L1 transaction</button>
          {:else if selectedWithdrawal?.settlementTransactionId}
            <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs"
                    onclick={() => void loadL1(selectedWithdrawal!.settlementTransactionId)}>Inspect payout L1 transaction</button>
          {/if}
        </div>
      </section>
    {/if}

    {#if l1Detail}
      <section class="card mt-4 p-5" aria-live="polite">
        <h2 class="m-0 text-base font-semibold">L1 transaction detail</h2>
        <div class="mt-2"><CopyValue value={l1Detail.id} width={60} label="L1 transaction ID" /></div>
        {#if l1Detail.state === 'loading'}<p class="text-sm text-slate-400">Loading retained L1 data…</p>
        {:else if l1Detail.state !== 'ready'}<p class="text-sm text-amber-300">{l1Detail.message}</p>
        {:else}
          <dl class="mt-4 grid gap-4 text-xs sm:grid-cols-2 lg:grid-cols-4">
            <div><dt class="text-slate-500">Block</dt><dd class="mt-1"><CopyValue value={l1Detail.transaction?.block ?? ''} width={24} label="L1 block hash" /></dd></div>
            <div><dt class="text-slate-500">Slot</dt><dd class="mt-1">{l1Detail.transaction?.slot ?? '-'}</dd></div>
            <div><dt class="text-slate-500">Fee</dt><dd class="mt-1">{formatLovelace(l1Detail.transaction?.fees ?? '0')}</dd></div>
            <div><dt class="text-slate-500">Inputs / outputs</dt><dd class="mt-1">{l1Detail.utxos?.inputs?.length ?? 0} / {l1Detail.utxos?.outputs?.length ?? 0}</dd></div>
          </dl>
          <div class="mt-4 grid gap-4 lg:grid-cols-2">
            <div><h3 class="text-xs uppercase text-slate-500">Inputs</h3>
              {#each l1Detail.utxos?.inputs ?? [] as item}<div class="mt-2 rounded bg-slate-950/60 p-2 text-xs">
                <CopyValue value={item.address ?? ''} width={48} label="L1 input address" />
              </div>{/each}</div>
            <div><h3 class="text-xs uppercase text-slate-500">Outputs</h3>
              {#each l1Detail.utxos?.outputs ?? [] as item}<div class="mt-2 rounded bg-slate-950/60 p-2 text-xs">
                <CopyValue value={item.address ?? ''} width={48} label="L1 output address" />
              </div>{/each}</div>
          </div>
        {/if}
      </section>
    {/if}
  {/if}

  {#if activeView === 'validity' && indexAvailable}
    <section class="card mt-4 overflow-hidden">
      <div class="border-b border-slate-800 p-4">
        <h2 class="m-0 text-base font-semibold">Validity batches</h2>
        <p class="mb-0 mt-1 text-xs text-slate-500">Proof generation, verification, L1 root acceptance, and payout remain separate facts.</p>
      </div>
      <div class="overflow-x-auto">
        <table class="w-full min-w-[820px] text-left text-xs">
          <thead class="text-slate-500"><tr><th class="p-3">Batch</th><th>Profile</th>
            <th>Transactions</th><th>Proof</th><th>Data</th><th>L1 root settlement</th></tr></thead>
          <tbody>
            {#each validityBatches as batch}
              <tr class="border-t border-slate-800/60">
                <td class="p-3"><span class="inline-flex items-center gap-1.5">
                  <button class="font-mono text-cyan-300"
                          onclick={() => selectedValidity = batch}>{short(batch.batchId, 24)}</button>
                  <CopyValue value={batch.batchId} iconOnly label="validity batch ID" />
                </span></td>
                <td>{batch.profileId}</td><td>{batch.transactionIds.length}</td>
                <td><span class="badge badge-ok">{batch.proofStatus}</span></td>
                <td><span class="badge">{batch.dataStatus}</span></td>
                <td><span class="badge {batch.settlementStatus === 'STABLE' ? 'badge-ok' : ''}">{batch.settlementStatus}</span></td>
              </tr>
            {:else}<tr><td colspan="6" class="p-6 text-center text-slate-500">No verified validity batches have been published in this lifecycle source.</td></tr>{/each}
          </tbody>
        </table>
      </div>
    </section>
    {#if selectedValidity}
      <section class="card mt-4 p-5">
        <h2 class="m-0 text-base font-semibold">Validity batch detail</h2>
        <div class="mt-2"><CopyValue value={selectedValidity.batchId} width={60} label="validity batch ID" /></div>
        <ol class="mt-5 grid gap-3 text-xs lg:grid-cols-5">
          <li class="rounded-lg border border-emerald-700/50 p-3"><span class="badge badge-ok">1 · L2 app-final</span>
            <div class="mt-2">{selectedValidity.transactionIds.length} ordered transaction(s)</div></li>
          <li class="rounded-lg border border-emerald-700/50 p-3"><span class="badge badge-ok">2 · Proof generated</span>
            <div class="mt-2"><CopyValue value={selectedValidity.proofDigest} width={24} label="proof digest" /></div></li>
          <li class="rounded-lg border border-emerald-700/50 p-3"><span class="badge badge-ok">3 · {selectedValidity.proofStatus}</span>
            <div class="mt-2">{selectedValidity.provider} · {selectedValidity.proofSystem}</div></li>
          <li class="rounded-lg border border-slate-700 p-3"><span class="badge">4 · L1 root {selectedValidity.settlementStatus}</span>
            {#if selectedValidity.settlementTransactionId}<div class="mt-2"><CopyValue value={selectedValidity.settlementTransactionId} width={24} label="root settlement transaction" /></div>{/if}</li>
          <li class="rounded-lg border border-slate-700 p-3"><span class="badge">5 · Payout independent</span>
            <div class="mt-2 text-slate-500">See the withdrawal lifecycle for stable L1 payout.</div></li>
        </ol>
        <dl class="mt-5 grid gap-4 text-xs sm:grid-cols-2 lg:grid-cols-4">
          <div><dt class="text-slate-500">Previous root</dt><dd><CopyValue value={selectedValidity.previousRoot} width={24} label="previous validity root" /></dd></div>
          <div><dt class="text-slate-500">Next root</dt><dd><CopyValue value={selectedValidity.nextRoot} width={24} label="next validity root" /></dd></div>
          <div><dt class="text-slate-500">Verification key</dt><dd><CopyValue value={selectedValidity.verificationKeyDigest} width={24} label="verification-key digest" /></dd></div>
          <div><dt class="text-slate-500">DA commitment</dt><dd><CopyValue value={selectedValidity.dataCommitment} width={24} label="data-availability commitment" /></dd></div>
        </dl>
        <div class="mt-5"><h3 class="text-xs uppercase text-slate-500">Ordered L2 transactions</h3>
          {#each selectedValidity.transactionIds as id, index}
            <span class="mt-2 mr-2 inline-flex items-center gap-1.5 rounded-lg border border-slate-800 px-3 py-2">
              <button class="font-mono text-xs text-cyan-300"
                      onclick={() => void selectTransaction(id)}>{index + 1}. {short(id, 28)}</button>
              <CopyValue value={id} iconOnly label="L2 transaction ID" />
            </span>
          {/each}
        </div>
        {#if selectedValidity.settlementTransactionId}
          <button class="mt-5 rounded-lg border border-slate-700 px-3 py-2 text-xs"
                  onclick={() => void loadL1(selectedValidity!.settlementTransactionId)}>Inspect root-settlement L1 transaction</button>
        {/if}
      </section>
    {/if}
  {/if}
{/if}
