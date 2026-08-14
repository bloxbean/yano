<script lang="ts">
  import { onMount } from 'svelte';
  import LineChart from '$lib/components/LineChart.svelte';
  import MetricCard from '$lib/components/MetricCard.svelte';
  import MetricRow from '$lib/components/MetricRow.svelte';
  import { resolveApiBase, YanoApi } from '$lib/api/client';
  import type { ChainSummary } from '$lib/api/types';
  import { PrometheusHistoryProvider, resolveMetricsBase, type PrometheusSeries, type QueryId } from '$lib/telemetry/prometheus';

  let metricsBase = '';
  let provider: PrometheusHistoryProvider | null = null;
  let chains: ChainSummary[] = [];
  let selectedChain = '';
  let windowHours = 1;
  let state: 'PLAIN' | 'CONNECTING' | 'HEALTHY' | 'UNAVAILABLE' = 'PLAIN';
  let error = '';
  let targets: PrometheusSeries[] = [];
  let syncGap: PrometheusSeries[] = [];
  let peers: PrometheusSeries[] = [];
  let mempool: PrometheusSeries[] = [];
  let utxo: PrometheusSeries[] = [];
  let txRate: PrometheusSeries[] = [];
  let tipRate: PrometheusSeries[] = [];
  let pool: PrometheusSeries[] = [];
  let interval: PrometheusSeries[] = [];
  let anchor: PrometheusSeries[] = [];
  let effectsOpen: PrometheusSeries[] = [];
  let effectsRate: PrometheusSeries[] = [];

  const values = (series: PrometheusSeries[]) => series.map((entry) => entry.points.map((point) => point[1]));
  const latest = (series: PrometheusSeries[]) => series.reduce((total, entry) => total + (entry.points.at(-1)?.[1] ?? 0), 0);
  const label = (series: PrometheusSeries) => series.labels.yano_origin ?? series.labels.instance ?? series.labels.job ?? 'target';

  onMount(() => {
    let disposed = false;
    void (async () => {
      try {
        const api = new YanoApi(await resolveApiBase());
        chains = await api.chains().catch(() => []);
        selectedChain = chains[0]?.chainId ?? '';
        metricsBase = resolveMetricsBase();
        if (!metricsBase) return;
        provider = new PrometheusHistoryProvider(metricsBase);
        if (!disposed) await refresh();
      } catch (cause) {
        state = 'UNAVAILABLE';
        error = cause instanceof Error ? cause.message : 'Metrics provider unavailable';
      }
    })();
    return () => { disposed = true; };
  });

  async function range(id: QueryId, chain?: string): Promise<PrometheusSeries[]> {
    if (!provider) return [];
    const end = Date.now() / 1_000;
    const start = end - windowHours * 3_600;
    const step = Math.max(5, Math.ceil((end - start) / 720));
    return provider.range(id, { start, end, step }, chain);
  }

  async function refresh(): Promise<void> {
    if (!provider) return;
    state = 'CONNECTING';
    error = '';
    try {
      targets = await provider.instant('health');
      [syncGap, peers, mempool, utxo, txRate] = await Promise.all([
        range('node.sync-gap'), range('node.peers'), range('node.mempool-transactions'),
        range('node.utxo-lag'), range('node.tx-rate')
      ]);
      if (selectedChain) {
        [tipRate, pool, interval, anchor, effectsOpen, effectsRate] = await Promise.all([
          range('appchain.tip-rate', selectedChain), range('appchain.pool', selectedChain),
          range('appchain.block-interval-ms', selectedChain), range('appchain.anchor-lag', selectedChain),
          range('appchain.effects-open', selectedChain), range('appchain.effects-rate', selectedChain)
        ]);
      } else {
        tipRate = []; pool = []; interval = []; anchor = []; effectsOpen = []; effectsRate = [];
      }
      state = 'HEALTHY';
    } catch (cause) {
      state = 'UNAVAILABLE';
      error = cause instanceof Error ? cause.message : 'Metrics provider unavailable';
    }
  }
</script>

<svelte:head><title>Yano · Observability</title></svelte:head>

<div data-console-route="observability" class="mb-5 flex flex-wrap items-end justify-between gap-3">
  <div><p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-emerald-400">Historical telemetry</p>
    <h1 class="mt-1 text-2xl font-bold">Observability</h1></div>
  <span class="badge {state === 'HEALTHY' ? 'badge-ok' : state === 'UNAVAILABLE' ? 'badge-bad' : 'badge-warn'}">{state}</span>
</div>

{#if !metricsBase}
  <section class="card p-6">
    <h2 class="mt-0 text-lg font-semibold">Plain Yano mode</h2>
    <p class="text-sm text-slate-400">Node and app-chain pages collect bounded one-hour history in this browser tab. For durable local history, start the optional companion and open the URL it prints:</p>
    <pre class="overflow-auto rounded-lg bg-slate-950 p-4 text-sm text-emerald-300">./yano.sh observability start</pre>
    <p class="mb-0 text-xs text-slate-500">Production operators can set an existing read-only Prometheus-compatible origin from Connection.</p>
  </section>
{:else}
  {#if error}<div class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{error}. Browser session charts remain available on the Node and App chains pages.</div>{/if}
  <section class="card mb-4 flex flex-wrap items-end gap-4 p-4">
    <div class="mr-auto"><small class="text-slate-500">Provider</small><div class="font-mono text-sm">{metricsBase}</div></div>
    <label class="text-xs text-slate-400">Window
      <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={windowHours}>
        <option value={1}>1 hour</option><option value={6}>6 hours</option><option value={24}>24 hours</option><option value={168}>7 days</option>
      </select>
    </label>
    <label class="text-xs text-slate-400">Chain
      <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={selectedChain}>
        {#each chains as chain}<option value={chain.chainId}>{chain.chainId}</option>{/each}
      </select>
    </label>
    <button class="rounded-lg bg-emerald-500 px-4 py-2 text-sm font-semibold text-slate-950" onclick={refresh}>Query</button>
  </section>

  <div class="grid gap-4 md:grid-cols-3">
    <MetricCard title="Scrape targets" subtitle="Prometheus up series">
      {#each targets as target}<MetricRow label={label(target)} value={target.points.at(-1)?.[1] === 1 ? 'UP' : 'DOWN'} />
      {:else}<p class="text-sm text-slate-500">No target data.</p>{/each}
    </MetricCard>
    <MetricCard title="L1 current"><MetricRow label="Sync gap" value={`${latest(syncGap).toLocaleString()} blocks`} />
      <MetricRow label="Mempool" value={`${latest(mempool).toLocaleString()} tx`} /><MetricRow label="UTXO lag" value={`${latest(utxo).toLocaleString()} blocks`} />
    </MetricCard>
    <MetricCard title="App-chain current"><MetricRow label="Pending pool" value={latest(pool).toLocaleString()} />
      <MetricRow label="Open effects" value={latest(effectsOpen).toLocaleString()} /><MetricRow label="Anchor lag" value={latest(anchor).toLocaleString()} />
    </MetricCard>
  </div>

  <div class="section-title">L1 history · {windowHours}h</div>
  <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
    <MetricCard title="Sync gap"><LineChart series={values(syncGap)} label="Durable sync gap" /></MetricCard>
    <MetricCard title="Peer connections"><LineChart series={values(peers)} label="Durable peer connections" /></MetricCard>
    <MetricCard title="Mempool"><LineChart series={values(mempool)} label="Durable mempool" /></MetricCard>
    <MetricCard title="UTXO lag"><LineChart series={values(utxo)} label="Durable UTXO lag" /></MetricCard>
    <MetricCard title="Transaction diffusion rate"><LineChart series={values(txRate)} label="Durable transaction rate" /></MetricCard>
  </div>

  <div class="section-title">App-chain & effects · {selectedChain || 'none'}</div>
  <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
    <MetricCard title="Finalized block rate"><LineChart series={values(tipRate)} label="Durable app-chain block rate" /></MetricCard>
    <MetricCard title="Pending pool"><LineChart series={values(pool)} label="Durable app-chain pool" /></MetricCard>
    <MetricCard title="Block interval"><LineChart series={values(interval)} label="Durable app-chain interval" /></MetricCard>
    <MetricCard title="Anchor lag"><LineChart series={values(anchor)} label="Durable anchor lag" /></MetricCard>
    <MetricCard title="Open effects"><LineChart series={values(effectsOpen)} label="Durable open effects" /></MetricCard>
    <MetricCard title="Effect execution rate"><LineChart series={values(effectsRate)} label="Durable effect execution rate" /></MetricCard>
  </div>
{/if}
