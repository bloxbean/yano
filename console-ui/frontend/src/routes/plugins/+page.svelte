<script lang="ts">
  import { onMount } from 'svelte';
  import MetricCard from '$lib/components/MetricCard.svelte';
  import MetricRow from '$lib/components/MetricRow.svelte';
  import { ApiError, resolvePluginApiBase, YanoApi } from '$lib/api/client';
  import type { PluginOperationsSummary } from '$lib/api/types';
  import { createPoller, type Poller } from '$lib/telemetry/poller';
  import { bundleId, contributionLabel, detailLabel, first, normalizedList,
    overallHealth, stateBadge, summaryCounts } from '$lib/plugins/model';
  import { numberValue, objectValue, shortHash, stringValue } from '$lib/appchain/value';

  const KEY_STORAGE = 'yano-plugin-operations-session';
  const PAGE_SIZE = 100;
  const MAX_LOADED_BUNDLES = 500;
  let apiPrefix = '';
  let api: YanoApi | null = null;
  let apiKey = '';
  let keyInput = '';
  let prefixVerified = false;
  let authenticated = false;
  let authMessage = 'Verifying the host-provided plugin API prefix…';
  let summary: PluginOperationsSummary | null = null;
  let bundles: Array<Record<string, unknown>> = [];
  let nextAfter: string | null = null;
  let expandedId = '';
  let search = '';
  let refreshState = '';
  let poller: Poller | null = null;

  $: counts = summaryCounts(summary);
  $: overall = overallHealth(summary);
  $: visibleBundles = bundles.filter((bundle) => {
    const query = search.trim().toLocaleLowerCase();
    if (!query) return true;
    return bundleId(bundle).toLocaleLowerCase().includes(query)
      || normalizedList(first(bundle, ['contributions'], [])).some((item) =>
        contributionLabel(item).toLocaleLowerCase().includes(query));
  });

  onMount(() => {
    let disposed = false;
    void (async () => {
      try {
        apiPrefix = await resolvePluginApiBase();
        if (disposed) return;
        prefixVerified = true;
        const stored = readCredential();
        if (stored) {
          apiKey = stored;
          api = new YanoApi(apiPrefix, apiKey);
          await startDashboard();
        } else authMessage = 'Enter a full API key to view plugin inventory.';
      } catch {
        authMessage = 'The host plugin API prefix could not be verified. Credentials are disabled.';
      }
    })();
    return () => { disposed = true; poller?.stop(); };
  });

  function readCredential(): string {
    try {
      const stored = JSON.parse(sessionStorage.getItem(KEY_STORAGE) ?? 'null') as { prefix?: unknown; key?: unknown } | null;
      return stored?.prefix === apiPrefix && typeof stored.key === 'string' ? stored.key : '';
    } catch { return ''; }
  }

  function saveCredential(value: string): void {
    apiKey = value;
    try {
      if (value) sessionStorage.setItem(KEY_STORAGE, JSON.stringify({ prefix: apiPrefix, key: value }));
      else sessionStorage.removeItem(KEY_STORAGE);
    } catch { /* in-memory credential remains usable */ }
  }

  async function connect(): Promise<void> {
    if (!prefixVerified || !keyInput) return;
    saveCredential(keyInput);
    keyInput = '';
    api = new YanoApi(apiPrefix, apiKey);
    await startDashboard();
  }

  async function startDashboard(): Promise<void> {
    poller?.stop();
    authMessage = 'Authenticating…';
    try {
      await refresh(new AbortController().signal, true);
      authenticated = true;
      poller = createPoller((signal) => refresh(signal, false));
      poller.start();
    } catch (cause) { handleFailure(cause); }
  }

  function forget(): void {
    poller?.stop();
    saveCredential('');
    api = null;
    authenticated = false;
    summary = null;
    bundles = [];
    authMessage = 'The session key was removed.';
  }

  async function refresh(signal: AbortSignal, reset: boolean): Promise<void> {
    if (!api) return;
    try {
      refreshState = 'Refreshing…';
      const nextSummary = await api.pluginSummary(signal);
      const target = reset ? PAGE_SIZE : Math.min(MAX_LOADED_BUNDLES,
        Math.max(PAGE_SIZE, Math.ceil(bundles.length / PAGE_SIZE) * PAGE_SIZE));
      const page = await loadPages(target, signal);
      if (expandedId && page.items.some((bundle) => bundleId(bundle) === expandedId)) {
        const detail = await api.pluginBundle(expandedId, signal);
        if (detail.bundle) page.items = page.items.map((bundle) => bundleId(bundle) === expandedId
          ? { ...bundle, ...detail.bundle } : bundle);
      } else if (expandedId) expandedId = '';
      summary = nextSummary;
      bundles = page.items;
      nextAfter = page.nextAfter;
      refreshState = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (cause) {
      handleFailure(cause);
      throw cause;
    }
  }

  async function loadPages(target: number, signal: AbortSignal): Promise<{
    items: Array<Record<string, unknown>>; nextAfter: string | null;
  }> {
    if (!api) return { items: [], nextAfter: null };
    const items: Array<Record<string, unknown>> = [];
    const ids = new Set<string>();
    const cursors = new Set<string>();
    let after: string | null = null;
    do {
      const page = await api.pluginBundles(after, PAGE_SIZE, signal);
      for (const item of page.items ?? []) {
        if (items.length >= MAX_LOADED_BUNDLES) break;
        if (!ids.has(bundleId(item))) { ids.add(bundleId(item)); items.push(item); }
      }
      const candidate = page.nextAfter ?? null;
      after = candidate && !cursors.has(candidate) && items.length < MAX_LOADED_BUNDLES ? candidate : null;
      if (after) cursors.add(after);
    } while (after && items.length < target);
    return { items, nextAfter: after };
  }

  async function loadMore(): Promise<void> {
    if (!api || !nextAfter || bundles.length >= MAX_LOADED_BUNDLES) return;
    try {
      const page = await api.pluginBundles(nextAfter, PAGE_SIZE);
      const ids = new Set(bundles.map(bundleId));
      const additions: Array<Record<string, unknown>> = [];
      for (const item of page.items ?? []) {
        if (!ids.has(bundleId(item))) { ids.add(bundleId(item)); additions.push(item); }
      }
      bundles = [...bundles, ...additions].slice(0, MAX_LOADED_BUNDLES);
      nextAfter = bundles.length >= MAX_LOADED_BUNDLES ? null : page.nextAfter ?? null;
    } catch (cause) { handleFailure(cause); }
  }

  async function toggleDetail(id: string): Promise<void> {
    if (expandedId === id) { expandedId = ''; return; }
    if (!api) return;
    try {
      const response = await api.pluginBundle(id);
      if (!response.bundle) throw new Error('Bundle detail unavailable');
      bundles = bundles.map((bundle) => bundleId(bundle) === id ? { ...bundle, ...response.bundle } : bundle);
      expandedId = id;
    } catch (cause) { handleFailure(cause); }
  }

  function handleFailure(cause: unknown): void {
    if (cause instanceof ApiError && [401, 403, 503].includes(cause.status)) {
      poller?.stop();
      saveCredential('');
      authenticated = false;
      authMessage = cause.status === 403 ? 'This key is topic-scoped. An unscoped full key is required.'
        : cause.status === 503 ? 'Plugin operations authentication is not configured on this node.'
          : 'The API key is missing or invalid.';
    } else if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
      refreshState = cause instanceof Error ? `Refresh failed (${cause.message})` : 'Refresh failed';
    }
  }

  function formatInstant(value: unknown): string {
    const amount = numberValue(value);
    return amount ? new Date(amount < 100_000_000_000 ? amount * 1_000 : amount).toLocaleString() : '-';
  }
</script>

<svelte:head><title>Yano · Plugin Operations</title></svelte:head>

<div data-console-route="plugins" class="mb-5 flex flex-wrap items-end justify-between gap-3">
  <div><p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-cyan-400">Privileged operator surface</p><h1 class="mt-1 text-2xl font-bold">Plugin operations</h1></div>
  <div class="flex gap-2"><span class="badge {stateBadge(overall)}">{overall}</span>{#if authenticated}<button type="button" class="rounded-lg border border-slate-700 px-3 py-2 text-xs" onclick={forget}>Forget key</button>{/if}</div>
</div>

{#if !authenticated}
  <section class="card mx-auto max-w-2xl p-6">
    <h2 class="mt-0 text-xl">Connect to plugin operations</h2>
    <p class="text-sm text-slate-400">Enter an unscoped app-chain API key. It is retained only for this browser tab and sent in the <code>X-API-Key</code> header after the host-provided API prefix is verified.</p>
    <form class="mt-5 flex gap-2" onsubmit={(event) => { event.preventDefault(); void connect(); }}>
      <input class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="password" autocomplete="off" maxlength="4096" disabled={!prefixVerified} bind:value={keyInput} aria-label="API key" />
      <button class="rounded-lg bg-cyan-500 px-4 py-2 font-semibold text-slate-950 disabled:opacity-40" disabled={!prefixVerified || !keyInput}>Connect</button>
    </form>
    <p class="mb-0 mt-3 text-sm {prefixVerified ? 'text-slate-400' : 'text-rose-300'}">{authMessage}</p>
  </section>
{:else}
  <section class="card p-5">
    <div class="flex flex-wrap justify-between gap-4"><div><p class="m-0 text-xs uppercase tracking-[.18em] text-slate-500">Cached operations snapshot</p><h2 class="mt-1 text-xl">Plugin runtime</h2></div><span class="text-xs text-slate-500">{refreshState}</span></div>
    <div class="mt-4 grid gap-4 md:grid-cols-[2fr_1fr_1fr]">
      <MetricCard title="Catalog fingerprint"><div class="break-all font-mono text-xs">{summary?.catalogFingerprint ?? '-'}</div></MetricCard>
      <MetricCard title="Generation"><div class="text-2xl font-bold">{numberValue(summary?.generation).toLocaleString()}</div></MetricCard>
      <MetricCard title="Captured"><div class="text-sm">{formatInstant(summary?.capturedAtEpochMillis)}</div></MetricCard>
    </div>
  </section>

  <div class="section-title">Snapshot totals</div>
  <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5">
    {#each counts as [name, value]}<MetricCard title={name.replace(/([a-z])([A-Z])/g, '$1 $2')}><div class="text-2xl font-bold">{value.toLocaleString()}</div></MetricCard>{/each}
  </div>

  <div class="section-title">Catalog bundles</div>
  <div class="mb-3 flex flex-wrap items-center justify-between gap-3"><input type="search" class="w-full max-w-md rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-sm" maxlength="256" placeholder="Search bundle or loaded contribution" bind:value={search} /><span class="text-xs text-slate-500">{visibleBundles.length} shown · {bundles.length} loaded{bundles.length >= MAX_LOADED_BUNDLES ? ' · dashboard cap reached' : ''}</span></div>
  <div class="space-y-3">
    {#each visibleBundles as bundle}
      {@const id = bundleId(bundle)}
      {@const lifecycle = first(bundle, ['lifecycle', 'lifecycleState', 'state'], 'UNKNOWN')}
      {@const healthField = first(bundle, ['health', 'healthState'], 'UNKNOWN')}
      {@const health = typeof healthField === 'object' ? first(healthField, ['state', 'status'], 'UNKNOWN') : healthField}
      <article class="card overflow-hidden">
        <div class="flex flex-wrap items-center justify-between gap-3 p-4">
          <div><h3 class="m-0 font-mono text-sm">{id}</h3><p class="mb-0 mt-1 text-xs text-slate-500">{stringValue(first(bundle, ['version'], ''))} · {numberValue(first(bundle, ['contributionCount'], 0))} contributions ({numberValue(first(bundle, ['observedContributionCount'], 0))} lifecycle observed) · {numberValue(first(bundle, ['metricCount'], 0))} metrics</p></div>
          <div class="flex items-center gap-2"><span class="badge {stateBadge(lifecycle)}">{stringValue(lifecycle)}</span><span class="badge {stateBadge(health)}">{stringValue(health)}</span><button type="button" class="rounded-lg border border-slate-700 px-3 py-2 text-xs" onclick={() => void toggleDetail(id)}>{expandedId === id ? 'Hide details' : 'Details'}</button></div>
        </div>
        {#if expandedId === id}
          <div class="grid gap-3 border-t border-slate-800 p-4 md:grid-cols-2 xl:grid-cols-4">
            {#each [['Contributions', 'contributions'], ['Health checks', 'healthChecks'], ['Cached metrics', 'metrics'], ['Operations', 'operationCounts']] as section}
              <MetricCard title={section[0]}>
                {#each normalizedList(first(bundle, [section[1]], [])) as item}<MetricRow label="" value={section[0] === 'Contributions' ? contributionLabel(item) : detailLabel(section[0], item)} />{:else}<p class="text-xs text-slate-500">None</p>{/each}
              </MetricCard>
            {/each}
          </div>
        {/if}
      </article>
    {:else}<div class="card p-6 text-sm text-slate-500">{search ? 'No bundles match the search.' : 'No bundles are present.'}</div>{/each}
  </div>
  {#if nextAfter && bundles.length < MAX_LOADED_BUNDLES}<button type="button" class="mt-4 rounded-lg border border-slate-700 px-4 py-2 text-sm" onclick={() => void loadMore()}>Load more</button>{/if}
{/if}
