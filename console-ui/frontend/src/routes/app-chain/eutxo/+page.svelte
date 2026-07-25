<script lang="ts">
  import { base } from '$app/paths';
  import { onMount } from 'svelte';
  import { ApiError, apiFailureMessage, resolveApiBase, YanoApi } from '$lib/api/client';
  import type {
    AppChainStatus, ChainSummary, EutxoTransactionDetail, EutxoTransactionPage,
    EutxoTransactionSummary
  } from '$lib/api/types';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import {
    canonicalEutxoIdentifier, EUTXO_BUNDLE_ID, formatLovelace, isEutxoChain, transactionTitle
  } from '$lib/eutxo/model';

  let api: YanoApi | null = null;
  let chains: ChainSummary[] = [];
  let selectedChain = '';
  let status: AppChainStatus | null = null;
  let transactions: EutxoTransactionSummary[] = [];
  let selected: EutxoTransactionSummary | null = null;
  let committedHeight = 0;
  let stateRoot = '';
  let nextBefore = 0;
  let searchInput = '';
  let loading = false;
  let loadingMore = false;
  let pageError = '';
  let searchError = '';
  let controller: AbortController | null = null;

  const short = (value: string, width = 18) =>
    value.length <= width ? value : `${value.slice(0, Math.max(6, width - 7))}…${value.slice(-6)}`;
  const firstAddress = (value: EutxoTransactionSummary) =>
    value.inputs[0]?.address ?? (value.status === 'REJECTED' ? 'not resolved' : '-');
  const outputValue = (value: EutxoTransactionSummary) => {
    try {
      return formatLovelace(value.outputs.reduce(
        (total, entry) => total + BigInt(entry.lovelace), 0n).toString());
    } catch {
      return '-';
    }
  };

  onMount(() => {
    let disposed = false;
    void (async () => {
      try {
        const apiBase = await resolveApiBase();
        api = new YanoApi(apiBase);
        chains = await api.chains();
        if (disposed) return;
        const query = new URLSearchParams(location.search);
        const requested = query.get('chain');
        selectedChain = chains.some((chain) => chain.chainId === requested)
          ? requested! : chains[0]?.chainId ?? '';
        if (!selectedChain) {
          pageError = 'No app chains are enabled on this node.';
          return;
        }
        await activateChain(selectedChain);
        const deepLink = query.get('transaction') ?? query.get('message');
        if (deepLink && !disposed && isEutxoChain(status)) {
          searchInput = deepLink;
          await search();
        }
      } catch (cause) {
        pageError = apiFailureMessage(cause, 'Unable to load the EUTxO explorer');
      }
    })();
    return () => {
      disposed = true;
      controller?.abort();
    };
  });

  async function activateChain(chainId: string): Promise<void> {
    if (!api) return;
    controller?.abort();
    controller = new AbortController();
    const signal = controller.signal;
    selectedChain = chainId;
    status = null;
    transactions = [];
    selected = null;
    nextBefore = 0;
    stateRoot = '';
    committedHeight = 0;
    pageError = '';
    searchError = '';
    loading = true;
    try {
      status = await api.chainStatus(chainId, signal);
      if (!isEutxoChain(status)) return;
      await loadTransactions(false, signal);
      const query = new URLSearchParams(location.search);
      query.set('chain', chainId);
      query.delete('transaction');
      query.delete('message');
      history.replaceState({}, '', `${base}/app-chain/eutxo/?${query}`);
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        pageError = apiFailureMessage(cause, 'Unable to load EUTxO transactions');
      }
    } finally {
      if (!signal.aborted) loading = false;
    }
  }

  async function loadTransactions(append: boolean, signal?: AbortSignal): Promise<void> {
    if (!api || !selectedChain || !isEutxoChain(status)) return;
    if (append) loadingMore = true;
    try {
      const parameters: Record<string, string> = {
        chain: selectedChain,
        limit: '20'
      };
      if (append && nextBefore > 0) parameters.before = String(nextBefore);
      const page = await api.domain<EutxoTransactionPage>(
        EUTXO_BUNDLE_ID, 'transactions', parameters, signal);
      if (page.chainId !== selectedChain || page.stateMachineId !== 'eutxo-ledger'
        || !Array.isArray(page.data)) {
        throw new Error('EUTxO explorer response identity does not match the selected chain');
      }
      transactions = append ? [...transactions, ...page.data] : page.data;
      committedHeight = page.committedHeight;
      stateRoot = page.stateRoot;
      nextBefore = page.nextBefore;
    } finally {
      loadingMore = false;
    }
  }

  async function search(): Promise<void> {
    if (!api || !isEutxoChain(status)) return;
    searchError = '';
    selected = null;
    try {
      const id = canonicalEutxoIdentifier(searchInput);
      let detail: EutxoTransactionDetail;
      try {
        detail = await api.domain<EutxoTransactionDetail>(
          EUTXO_BUNDLE_ID, `transactions/${id}`, { chain: selectedChain });
      } catch (cause) {
        if (!(cause instanceof ApiError) || cause.status !== 404) throw cause;
        detail = await api.domain<EutxoTransactionDetail>(
          EUTXO_BUNDLE_ID, `messages/${id}`, { chain: selectedChain });
      }
      if (detail.chainId !== selectedChain || detail.stateMachineId !== 'eutxo-ledger') {
        throw new Error('EUTxO explorer response identity does not match the selected chain');
      }
      selected = detail.data;
      committedHeight = detail.committedHeight;
      stateRoot = detail.stateRoot;
    } catch (cause) {
      searchError = cause instanceof ApiError && cause.status === 404
        ? 'No finalized EUTxO transaction matches that transaction or message ID.'
        : apiFailureMessage(cause, 'EUTxO transaction lookup failed');
    }
  }
</script>

<svelte:head><title>Yano · EUTxO Explorer</title></svelte:head>

<div data-console-route="eutxo" class="mb-4 flex flex-wrap items-end justify-between gap-3">
  <div>
    <p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-cyan-400">State-machine view</p>
    <h1 class="mt-1 text-2xl font-bold">EUTxO Explorer</h1>
    <p class="mb-0 mt-1 text-sm text-slate-500">Finalized Cardano-shaped L2 transactions from committed app state.</p>
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
</div>

{#if pageError}
  <div class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{pageError}</div>
{/if}

{#if status && !isEutxoChain(status)}
  <section class="card p-6">
    <h2 class="m-0 text-lg font-semibold">Explorer unavailable for this chain</h2>
    <p class="mb-0 mt-2 text-sm text-slate-400">
      <strong>{selectedChain}</strong> uses <code>{status.stateMachine ?? 'an unknown state machine'}</code>.
      This reviewed view is enabled only for the bundled <code>eutxo-ledger</code> capability.
    </p>
  </section>
{:else}
  <section class="card overflow-hidden p-5">
    <div class="flex flex-wrap items-end justify-between gap-5">
      <div>
        <div class="text-xs font-semibold uppercase tracking-[.18em] text-slate-500">Committed projection</div>
        <div class="mt-2 text-3xl font-bold text-cyan-300">{committedHeight.toLocaleString()}</div>
        <div class="mt-1 flex flex-wrap items-center gap-1 text-xs text-slate-500">
          <span>height · state root</span>
          <CopyValue value={stateRoot} width={30} label="EUTxO projection state root" />
        </div>
      </div>
      <form class="flex min-w-[min(100%,30rem)] flex-1 gap-2 sm:max-w-2xl"
            onsubmit={(event) => { event.preventDefault(); void search(); }}>
        <label class="sr-only" for="eutxo-search">Transaction or message ID</label>
        <input id="eutxo-search" class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs text-slate-100"
               bind:value={searchInput} autocomplete="off" spellcheck="false"
               placeholder="64-character transaction or app-message ID" />
        <button type="submit" class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Find</button>
      </form>
    </div>
    {#if searchError}<p class="mb-0 mt-3 text-sm text-rose-300">{searchError}</p>{/if}
  </section>

  <div class="section-title">Finalized transactions</div>
  <section class="card overflow-hidden">
    <div class="overflow-x-auto">
      <table class="w-full min-w-[860px] text-left text-xs">
        <thead class="text-slate-500">
          <tr><th class="p-3">Position</th><th>Status</th><th>Transaction</th><th>From</th><th>Outputs</th><th>Value</th><th>Authorization</th></tr>
        </thead>
        <tbody>
          {#each transactions as transaction}
            <tr class="border-t border-slate-800/60 hover:bg-slate-800/35">
              <td class="p-3 font-mono">{transaction.appHeight}:{transaction.ordinal}</td>
              <td><span class="badge {transaction.status === 'ACCEPTED' ? 'badge-ok' : 'badge-bad'}">{transaction.status}</span></td>
              <td>
                <button type="button" class="font-mono text-cyan-300 hover:text-cyan-200"
                        title={transactionTitle(transaction)} onclick={() => selected = transaction}>
                  {short(transaction.transactionId || transaction.messageId, 24)}
                </button>
              </td>
              <td class="max-w-52 truncate font-mono text-slate-400" title={firstAddress(transaction)}>{short(firstAddress(transaction), 25)}</td>
              <td>{transaction.outputs.length}</td>
              <td class="font-mono">{outputValue(transaction)}</td>
              <td><span class="badge">{transaction.authorizationProfile || 'unknown'}</span></td>
            </tr>
          {:else}
            <tr><td colspan="7" class="p-6 text-center text-sm text-slate-500">
              {loading ? 'Loading finalized EUTxO transactions…' : 'No finalized EUTxO transactions yet.'}
            </td></tr>
          {/each}
        </tbody>
      </table>
    </div>
    {#if nextBefore > 1 && transactions.length > 0}
      <div class="border-t border-slate-800 p-3 text-center">
        <button type="button" disabled={loadingMore}
                class="rounded-lg border border-slate-700 px-4 py-2 text-xs text-slate-300 hover:border-slate-500 disabled:opacity-50"
                onclick={() => void loadTransactions(true)}>
          {loadingMore ? 'Loading…' : 'Load older transactions'}
        </button>
      </div>
    {/if}
  </section>

  {#if selected}
    <div class="section-title">Transaction detail</div>
    <section class="card overflow-hidden">
      <div class="flex flex-wrap items-start justify-between gap-3 border-b border-slate-800 p-4">
        <div>
          <h2 class="m-0 text-base font-semibold">Finalized attempt {selected.appHeight}:{selected.ordinal}</h2>
          <p class="mb-0 mt-1 text-xs text-slate-500">L1 slot {selected.l1Slot.toLocaleString()} · sequence {selected.sequence.toLocaleString()}</p>
        </div>
        <span class="badge {selected.status === 'ACCEPTED' ? 'badge-ok' : 'badge-bad'}">{selected.status}</span>
      </div>
      <div class="grid gap-4 p-4 lg:grid-cols-2">
        <div class="space-y-3 text-xs">
          <div><div class="text-slate-500">Transaction ID</div><div class="mt-1 break-all font-mono"><CopyValue value={selected.transactionId} width={72} label="EUTxO transaction ID" /></div></div>
          <div><div class="text-slate-500">App-message ID</div><div class="mt-1 break-all font-mono"><CopyValue value={selected.messageId} width={72} label="app-message ID" /></div></div>
          <div><div class="text-slate-500">Authorization</div><div class="mt-1 font-mono">{selected.authorizationProfile || 'unknown'}</div></div>
          {#if selected.code}<div><div class="text-slate-500">Result code</div><div class="mt-1 font-mono text-rose-300">{selected.code}</div></div>{/if}
        </div>
        <div class="space-y-3 text-xs">
          <div><div class="text-slate-500">Committed height</div><div class="mt-1 font-mono">{committedHeight.toLocaleString()}</div></div>
          <div><div class="text-slate-500">State root</div><div class="mt-1 break-all font-mono"><CopyValue value={stateRoot} width={72} label="committed state root" /></div></div>
        </div>
      </div>
      <div class="grid border-t border-slate-800 lg:grid-cols-2">
        <div class="border-b border-slate-800 p-4 lg:border-b-0 lg:border-r">
          <h3 class="m-0 text-xs font-semibold uppercase tracking-[.14em] text-slate-500">Resolved inputs</h3>
          <div class="mt-3 space-y-3">
            {#each selected.inputs as entry}
              <div class="rounded-lg bg-slate-950/70 p-3 text-xs">
                <div class="flex items-center gap-1 break-all font-mono"><CopyValue value={entry.outpoint} width={56} label="input outpoint" /></div>
                <div class="mt-2 flex items-center gap-1 break-all font-mono text-slate-400"><CopyValue value={entry.address} width={56} label="input address" /></div>
                <div class="mt-2 font-mono text-cyan-300">{formatLovelace(entry.lovelace)}</div>
              </div>
            {:else}<p class="text-sm text-slate-500">No safe input projection is available.</p>{/each}
          </div>
        </div>
        <div class="p-4">
          <h3 class="m-0 text-xs font-semibold uppercase tracking-[.14em] text-slate-500">Created outputs</h3>
          <div class="mt-3 space-y-3">
            {#each selected.outputs as entry}
              <div class="rounded-lg bg-slate-950/70 p-3 text-xs">
                <div class="flex items-center gap-1 break-all font-mono"><CopyValue value={entry.outpoint} width={56} label="output outpoint" /></div>
                <div class="mt-2 flex items-center gap-1 break-all font-mono text-slate-400"><CopyValue value={entry.address} width={56} label="output address" /></div>
                <div class="mt-2 font-mono text-cyan-300">{formatLovelace(entry.lovelace)}</div>
              </div>
            {:else}<p class="text-sm text-slate-500">No outputs were created.</p>{/each}
          </div>
        </div>
      </div>
    </section>
  {/if}
{/if}
