<script lang="ts">
  import { base } from '$app/paths';
  import { onMount } from 'svelte';
  import { apiFailureMessage, currentApiKey, resolveApiBase, resolvePluginApiBase, saveConnection, YanoApi } from '$lib/api/client';
  import type { NodeConfig, NodeStatus } from '$lib/api/types';
  import { metricsCredential, resolveMetricsBase, saveMetricsConnection } from '$lib/telemetry/prometheus';

  let { children } = $props<{ children: import('svelte').Snippet }>();
  let config: NodeConfig | null = $state(null);
  let status: NodeStatus | null = $state(null);
  let apiBase = $state('/api/v1');
  let key = $state('');
  let persistKey = $state(false);
  let showConnection = $state(false);
  let identityError = $state('');
  let hostBound = $state(false);
  let metricsBase = $state('');
  let metricsBearer = $state('');

  onMount(async () => {
    hostBound = location.pathname.startsWith('/ui/plugins/');
    try {
      apiBase = hostBound ? await resolvePluginApiBase() : await resolveApiBase();
      key = hostBound ? '' : currentApiKey();
      metricsBase = hostBound ? '' : resolveMetricsBase();
      metricsBearer = metricsBase ? metricsCredential(metricsBase) : '';
      persistKey = !!localStorage.getItem('yano.console.api-key.v1');
      const api = new YanoApi(apiBase, key);
      [config, status] = await Promise.all([api.config(), api.status()]);
    } catch (error) {
      identityError = apiFailureMessage(error, 'Node unavailable');
    }
  });

  function connect() {
    try {
      saveConnection(apiBase, key, persistKey);
      saveMetricsConnection(metricsBase, metricsBearer);
      location.reload();
    } catch (error) {
      identityError = error instanceof Error ? error.message : 'Invalid connection';
    }
  }
</script>

<header class="sticky top-0 z-30 border-b border-slate-700/30 bg-yano-950/80 backdrop-blur-xl">
  <div class="mx-auto flex max-w-[1320px] flex-wrap items-center justify-between gap-3 px-5 py-3">
    <div class="flex items-center gap-4">
      <a href={`${base}/`} class="flex items-center gap-2 text-slate-100 no-underline">
        <span class="grid h-9 w-9 place-items-center rounded-xl bg-gradient-to-br from-cyan-400 via-blue-500 to-violet-600
                     font-bold text-white shadow-lg shadow-blue-500/20">Y</span>
        <span><strong>Yano</strong><small class="ml-2 text-slate-500">Console</small></span>
      </a>
      <nav class="hidden items-center gap-1 md:flex" aria-label="Console">
        <a class="rounded-lg px-3 py-2 text-xs text-slate-400 hover:bg-slate-800 hover:text-white"
           href={`${base}/status/`}>Node</a>
        <a class="rounded-lg px-3 py-2 text-xs text-slate-400 hover:bg-slate-800 hover:text-white"
           href={`${base}/app-chain/`}>App chains</a>
        <a class="rounded-lg px-3 py-2 text-xs text-slate-400 hover:bg-slate-800 hover:text-white"
           href={`${base}/plugins/`}>Plugins</a>
        <a class="rounded-lg px-3 py-2 text-xs text-slate-400 hover:bg-slate-800 hover:text-white"
           href={`${base}/observability/`}>Observability</a>
      </nav>
    </div>
    <div class="flex items-center gap-2">
      <span class="badge {status?.running && !status?.runtimeDegraded ? 'badge-ok' : identityError ? 'badge-bad' : 'badge-warn'}">
        {#if config}
          {config.network ?? config.protocolMagic} · {config.version ?? 'unknown'} · #{status?.localTipBlockNumber ?? 0}
        {:else if identityError}{identityError}{:else}connecting…{/if}
      </span>
      {#if hostBound}
        <span class="rounded-lg border border-slate-700 px-3 py-2 text-xs text-slate-500">host-bound API</span>
      {:else}
        <button type="button" class="rounded-lg border border-slate-700 px-3 py-2 text-xs text-slate-300 hover:border-slate-500"
                onclick={() => showConnection = !showConnection}>Connection</button>
      {/if}
    </div>
  </div>
  {#if showConnection}
    <div class="mx-auto grid max-w-[1320px] gap-3 border-t border-slate-800 px-5 py-4 md:grid-cols-2 xl:grid-cols-[1fr_1fr_1fr_1fr_auto]">
      <label class="text-xs text-slate-400">API base
        <input class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
               bind:value={apiBase} placeholder="/api/v1 or https://node.example/api/v1" />
      </label>
      <label class="text-xs text-slate-400">API key
        <input type="password" autocomplete="off"
               class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
               bind:value={key} placeholder="Optional" />
      </label>
      <label class="text-xs text-slate-400">Metrics origin
        <input class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
               bind:value={metricsBase} placeholder="Optional · http://127.0.0.1:9090" />
      </label>
      <label class="text-xs text-slate-400">Metrics bearer
        <input type="password" autocomplete="off"
               class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
               bind:value={metricsBearer} placeholder="Optional · current tab only" />
      </label>
      <div class="flex items-end gap-3">
        <label class="mb-2 flex items-center gap-2 text-xs text-amber-300">
          <input type="checkbox" bind:checked={persistKey} /> persist key on this browser
        </label>
        <button type="button" class="rounded-lg bg-blue-500 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-400"
                onclick={connect}>Connect</button>
      </div>
    </div>
  {/if}
</header>

<main class="mx-auto max-w-[1320px] px-5 py-6">{@render children()}</main>
