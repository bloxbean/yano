<script lang="ts">
  import { onMount } from 'svelte';
  import LineChart from '$lib/components/LineChart.svelte';
  import MetricCard from '$lib/components/MetricCard.svelte';
  import MetricRow from '$lib/components/MetricRow.svelte';
  import { PrometheusHistoryProvider, resolveMetricsBase, type PrometheusSeries, type QueryId } from '$lib/telemetry/prometheus';

  let metricsBase = '';
  let provider: PrometheusHistoryProvider | null = null;
  let windowHours = 1;
  let state: 'PLAIN' | 'CONNECTING' | 'HEALTHY' | 'UNAVAILABLE' = 'PLAIN';
  let error = '';
  let targets: PrometheusSeries[] = [];
  let syncGap: PrometheusSeries[] = [];
  let peers: PrometheusSeries[] = [];
  let mempool: PrometheusSeries[] = [];
  let utxo: PrometheusSeries[] = [];
  let txRate: PrometheusSeries[] = [];

  const values = (series: PrometheusSeries[]) => series.map((entry) => entry.points.map((point) => point[1]));
  const latest = (series: PrometheusSeries[]) => series.reduce((total, entry) => total + (entry.points.at(-1)?.[1] ?? 0), 0);
  const label = (series: PrometheusSeries) => series.labels.yano_origin ?? series.labels.instance ?? series.labels.job ?? 'target';

  onMount(() => {
    let disposed = false;
    void (async () => {
      try {
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

  async function range(id: QueryId): Promise<PrometheusSeries[]> {
    if (!provider) return [];
    const end = Date.now() / 1_000;
    const start = end - windowHours * 3_600;
    const step = Math.max(5, Math.ceil((end - start) / 720));
    return provider.range(id, { start, end, step });
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
    <p class="text-sm text-slate-400">The Node page collects bounded one-hour history in this browser tab. For durable local history, start the optional companion and open the URL it prints:</p>
    <pre class="overflow-auto rounded-lg bg-slate-950 p-4 text-sm text-emerald-300">./yano.sh observability start</pre>
    <p class="mb-0 text-xs text-slate-500">Production operators can set an existing read-only Prometheus-compatible origin from Connection.</p>
  </section>
{:else}
  {#if error}<div class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{error}. Browser session charts remain available on the Node page.</div>{/if}
  <section class="card mb-4 flex flex-wrap items-end gap-4 p-4">
    <div class="mr-auto"><small class="text-slate-500">Provider</small><div class="font-mono text-sm">{metricsBase}</div></div>
    <label class="text-xs text-slate-400">Window
      <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={windowHours}>
        <option value={1}>1 hour</option><option value={6}>6 hours</option><option value={24}>24 hours</option><option value={168}>7 days</option>
      </select>
    </label>
    <button class="rounded-lg bg-emerald-500 px-4 py-2 text-sm font-semibold text-slate-950" onclick={refresh}>Query</button>
  </section>

  <div class="grid gap-4 md:grid-cols-2">
    <MetricCard title="Scrape targets" subtitle="Prometheus up series">
      {#each targets as target}<MetricRow label={label(target)} value={target.points.at(-1)?.[1] === 1 ? 'UP' : 'DOWN'} />
      {:else}<p class="text-sm text-slate-500">No target data.</p>{/each}
    </MetricCard>
    <MetricCard title="L1 current"><MetricRow label="Sync gap" value={`${latest(syncGap).toLocaleString()} blocks`} />
      <MetricRow label="Mempool" value={`${latest(mempool).toLocaleString()} tx`} /><MetricRow label="UTXO lag" value={`${latest(utxo).toLocaleString()} blocks`} />
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
{/if}
