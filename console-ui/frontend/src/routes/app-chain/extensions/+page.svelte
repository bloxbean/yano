<script lang="ts">
  import { onMount } from 'svelte';
  import { apiFailureMessage, resolveApiBase, YanoApi } from '$lib/api/client';
  import type { AppChainStatus, ChainSummary, UiExtensionCatalogEntry } from '$lib/api/types';
  import PluginUiHost from '$lib/components/PluginUiHost.svelte';
  import { isEligible } from '$lib/plugins/ui-extension';

  let api: YanoApi | null = $state(null);
  let chains: ChainSummary[] = $state([]);
  let extensions: UiExtensionCatalogEntry[] = $state([]);
  let selectedChain = $state('');
  let selectedExtension = $state('');
  let status: AppChainStatus | null = $state(null);
  let error = $state('');
  let loading = $state(true);

  let eligible = $derived(extensions.filter((extension) => isEligible(extension, status)));
  let active = $derived(eligible.find((extension) =>
    `${extension.bundleId}/${extension.extensionId}` === selectedExtension) ?? eligible[0]);

  onMount(() => void load());

  async function load() {
    loading = true;
    try {
      api = new YanoApi(await resolveApiBase());
      [chains, extensions] = await Promise.all([api.chains(), api.uiExtensions()]);
      selectedChain = localStorage.getItem('yano.console.app-chain.selected.v1')
        ?? chains[0]?.chainId ?? '';
      await selectChain(selectedChain);
    } catch (cause) {
      error = apiFailureMessage(cause, 'UI extensions unavailable');
    } finally {
      loading = false;
    }
  }

  async function selectChain(chainId: string) {
    selectedChain = chainId;
    selectedExtension = '';
    status = null;
    if (!api || !chainId) return;
    localStorage.setItem('yano.console.app-chain.selected.v1', chainId);
    status = await api.chainStatus(chainId);
  }
</script>

<svelte:head><title>App-chain extensions · Yano</title></svelte:head>

<section class="mb-5 flex flex-wrap items-end justify-between gap-4">
  <div><p class="eyebrow">Sandboxed plugin UI</p><h1 class="mt-1 text-2xl font-bold">App-chain extensions</h1></div>
  <label class="text-xs text-slate-400">Selected chain
    <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
      value={selectedChain} onchange={(event) => void selectChain(event.currentTarget.value)}>
      {#each chains as chain}<option value={chain.chainId}>{chain.chainId}</option>{/each}
    </select>
  </label>
</section>

{#if loading}<p class="text-slate-400">Loading extensions…</p>
{:else if error}<div class="rounded-xl border border-red-500/30 bg-red-500/10 p-4 text-red-200">{error}</div>
{:else if eligible.length === 0}
  <div class="rounded-xl border border-slate-800 bg-slate-900/60 p-6 text-slate-400">
    No active UI extension matches this chain's declared capabilities.
  </div>
{:else}
  <div class="mb-4 flex flex-wrap gap-2">
    {#each eligible as extension}
      <button class="rounded-lg px-4 py-2 text-sm {active === extension ? 'bg-blue-500 text-white' : 'bg-slate-800 text-slate-300'}"
        onclick={() => selectedExtension = `${extension.bundleId}/${extension.extensionId}`}>
        {extension.title}
      </button>
    {/each}
  </div>
  {#if active && api}<PluginUiHost extension={active} chainId={selectedChain} {api} />{/if}
{/if}
