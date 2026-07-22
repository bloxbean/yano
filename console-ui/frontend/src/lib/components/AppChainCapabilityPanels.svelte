<script lang="ts">
  import { onMount } from 'svelte';
  import type { AppChainStatus } from '$lib/api/types';
  import { YanoApi, apiFailureMessage } from '$lib/api/client';
  import { discoverChainCapabilities } from '$lib/appchain/capabilities';
  import { asciiHex, boundedPretty, hexSha256, PRODUCT_ID, SHA256, STATE_KEY } from '$lib/appchain/verification';
  import { numberValue, objectList, objectValue, shortHash, stringValue } from '$lib/appchain/value';
  import MetricCard from './MetricCard.svelte';
  import MetricRow from './MetricRow.svelte';

  let { api, chainId, status, pluginBundleIds = [] } = $props<{
    api: YanoApi;
    chainId: string;
    status: AppChainStatus;
    pluginBundleIds?: string[];
  }>();
  let effects = $state<Array<Record<string, unknown>>>([]);
  let effectStats = $state<Record<string, unknown>>({});
  let effectError = $state('');
  let effectProof = $state('');
  let proposalId = $state('');
  let proposal = $state('');
  let proposalError = $state('');
  let messageId = $state('');
  let stateKey = $state('');
  let expectedHash = $state('');
  let evidence = $state('');
  let proof = $state('');
  let payloadDigest = $state('');
  let proofValueDigest = $state('');
  let verificationError = $state('');
  let busy = $state(false);
  let capabilities = $derived(discoverChainCapabilities(status, pluginBundleIds));

  onMount(() => { if (capabilities.effects) void refreshEffects(); });

  async function refreshEffects(): Promise<void> {
    effectError = '';
    try {
      const [page, stats] = await Promise.all([api.chainEffects(chainId), api.chainEffectStats(chainId)]);
      effects = objectList(page.effects);
      effectStats = objectValue(stats.stats);
    } catch (cause) { effectError = apiFailureMessage(cause, 'Effects unavailable'); }
  }

  async function operate(action: 'requeue' | 'cancel', effect: Record<string, unknown>): Promise<void> {
    const height = numberValue(effect.height, -1);
    const ordinal = numberValue(effect.ordinal, -1);
    if (height < 0 || ordinal < 0 || !confirm(`${action} effect ${height}/${ordinal}?`)) return;
    busy = true;
    effectError = '';
    try {
      if (action === 'requeue') await api.requeueEffect(chainId, height, ordinal);
      else await api.cancelEffect(chainId, height, ordinal);
      await refreshEffects();
    } catch (cause) { effectError = apiFailureMessage(cause, `Unable to ${action} effect`); }
    finally { busy = false; }
  }

  async function inspectEffectProof(effect: Record<string, unknown>): Promise<void> {
    effectError = '';
    try {
      effectProof = boundedPretty(await api.chainEffectProof(
        chainId, numberValue(effect.height), numberValue(effect.ordinal)));
    } catch (cause) { effectError = apiFailureMessage(cause, 'Effect proof unavailable'); }
  }

  async function queryProposal(): Promise<void> {
    proposal = '';
    proposalError = '';
    if (!capabilities.roleDomainBundle || !PRODUCT_ID.test(proposalId)) {
      proposalError = 'Enter a valid proposal id.';
      return;
    }
    try {
      const committedQuery = await api.chainQuery(
        chainId, 'components/role-approvals/proposal', asciiHex(proposalId));
      try {
        const decodedProjection = await api.domain(capabilities.roleDomainBundle,
          `proposals/${proposalId}`, { chain: chainId });
        proposal = boundedPretty({ committedQuery, decodedProjection });
      } catch (cause) {
        proposal = boundedPretty({ committedQuery,
          decodedProjectionUnavailable: apiFailureMessage(cause, 'Decoded projection unavailable') });
      }
    } catch (cause) { proposalError = apiFailureMessage(cause, 'Proposal unavailable'); }
  }

  async function loadEvidence(): Promise<void> {
    evidence = ''; payloadDigest = ''; verificationError = '';
    if (!SHA256.test(messageId)) { verificationError = 'Message id must be 64 lowercase hex characters.'; return; }
    try {
      const [bundle, message] = await Promise.all([
        api.chainEvidence(chainId, messageId), api.chainMessage(chainId, messageId)
      ]);
      evidence = boundedPretty(bundle);
      if (typeof message.bodyHex === 'string') payloadDigest = await hexSha256(message.bodyHex);
    } catch (cause) { verificationError = apiFailureMessage(cause, 'Evidence unavailable'); }
  }

  async function loadProof(): Promise<void> {
    proof = ''; proofValueDigest = ''; verificationError = '';
    if (!STATE_KEY.test(stateKey)) { verificationError = 'State key must be 1–256 bytes of canonical lowercase hex.'; return; }
    try {
      const result = await api.chainProof(chainId, stateKey);
      proof = boundedPretty(result);
      if (typeof result.valueHex === 'string') proofValueDigest = await hexSha256(result.valueHex);
    } catch (cause) { verificationError = apiFailureMessage(cause, 'State proof unavailable'); }
  }

  const match = (digest: string) => !expectedHash ? 'computed locally'
    : !SHA256.test(expectedHash) ? 'invalid expected hash'
      : digest === expectedHash ? 'MATCH' : 'MISMATCH';
</script>

<div class="section-title">Discovered capabilities</div>
<section class="card p-4">
  <div class="flex flex-wrap gap-2">
    <span class="badge badge-ok">EVIDENCE BUNDLES</span><span class="badge badge-ok">STATE PROOFS</span>
    {#if capabilities.effects}<span class="badge badge-ok">EFFECTS</span>{/if}
    {#if capabilities.roleApprovals}<span class="badge badge-ok">ROLE APPROVALS</span>{/if}
  </div>
  <p class="mb-0 mt-3 text-xs text-slate-500">Source: {capabilities.sources.join(' · ')}</p>
</section>

{#if capabilities.effects}
  <div class="section-title">Effect lifecycle</div>
  {#if effectError}<div class="mb-3 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{effectError}</div>{/if}
  <div class="grid gap-4 xl:grid-cols-[1fr_2fr]">
    <MetricCard title="Effect statistics" subtitle="Consensus and node-local execution">
      {#each Object.entries(effectStats).slice(0, 18) as [name, value]}
        <MetricRow label={name} value={typeof value === 'object' ? boundedPretty(value, 160) : stringValue(value)} />
      {:else}<p class="text-sm text-slate-500">No effect statistics reported.</p>{/each}
      <button class="mt-3 rounded-lg border border-slate-700 px-3 py-2 text-xs" onclick={refreshEffects}>Refresh</button>
    </MetricCard>
    <section class="card overflow-hidden">
      <div class="border-b border-slate-800 px-4 py-3"><h2 class="m-0 text-sm font-semibold">Emitted effects</h2><p class="m-0 text-xs text-slate-500">First 100 retained records · privileged actions require an operator API key</p></div>
      <div class="max-h-96 overflow-auto">
        {#each effects as effect}
          <div class="grid gap-2 border-b border-slate-800/60 p-4 text-xs md:grid-cols-[90px_1fr_100px_auto]">
            <span class="font-mono text-violet-300">{numberValue(effect.height)}/{numberValue(effect.ordinal)}</span>
            <div><strong>{stringValue(effect.type)}</strong><div class="mt-1 text-slate-500">{stringValue(effect.scope)} · {stringValue(effect.gate)} · expires {stringValue(effect.expiryHeight)}</div></div>
            <span>{stringValue(effect.resultPolicy)}</span>
            <div class="flex flex-wrap gap-1">
              <button class="rounded border border-slate-700 px-2 py-1" onclick={() => inspectEffectProof(effect)}>Proof</button>
              <button disabled={busy} class="rounded border border-amber-700 px-2 py-1 text-amber-300" onclick={() => operate('requeue', effect)}>Requeue</button>
              <button disabled={busy} class="rounded border border-rose-700 px-2 py-1 text-rose-300" onclick={() => operate('cancel', effect)}>Cancel</button>
            </div>
          </div>
        {:else}<p class="p-5 text-sm text-slate-500">No effects emitted yet.</p>{/each}
      </div>
      {#if effectProof}<pre class="m-4 max-h-64 overflow-auto rounded-lg bg-slate-950 p-3 text-xs">{effectProof}</pre>{/if}
    </section>
  </div>
{/if}

{#if capabilities.roleApprovals}
  <div class="section-title">Committed proposal</div>
  <section class="card p-5">
    <p class="mt-0 text-sm text-slate-400">Runs the stock role-aware committed query, then adds the decoded domain projection when available. The root-fixed result includes its committed height and state root.</p>
    <div class="flex flex-wrap gap-2"><input class="min-w-64 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={proposalId} placeholder="proposal id" />
      <button class="rounded-lg bg-violet-500 px-4 py-2 text-sm font-semibold" onclick={queryProposal}>Query</button></div>
    {#if proposalError}<p class="text-sm text-rose-300">{proposalError}</p>{/if}
    {#if proposal}<pre class="mt-4 max-h-96 overflow-auto rounded-lg bg-slate-950 p-3 text-xs">{proposal}</pre>{/if}
  </section>
{/if}

<div class="section-title">Portable verification</div>
<div class="grid gap-4 xl:grid-cols-2">
  <section class="card p-5">
    <h2 class="mt-0 text-sm font-semibold">Evidence bundle</h2>
    <p class="text-xs text-slate-500">Fetch the finalized bundle and message, then SHA-256 the exact payload bytes in this browser.</p>
    <div class="flex gap-2"><input class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" bind:value={messageId} placeholder="64-character message id" />
      <button class="rounded-lg bg-blue-500 px-3 py-2 text-sm font-semibold" onclick={loadEvidence}>Load</button></div>
    {#if payloadDigest}<div class="mt-3 break-all text-xs"><span class="text-slate-500">Payload SHA-256 · {match(payloadDigest)}</span><div class="font-mono">{payloadDigest}</div></div>{/if}
    {#if evidence}<pre class="mt-4 max-h-96 overflow-auto rounded-lg bg-slate-950 p-3 text-xs">{evidence}</pre>{/if}
  </section>
  <section class="card p-5">
    <h2 class="mt-0 text-sm font-semibold">MPF state proof</h2>
    <p class="text-xs text-slate-500">Inspect the proof envelope and locally hash its included value. Full MPF/finality verification remains the app-chain client library's job.</p>
    <div class="flex gap-2"><input class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" bind:value={stateKey} placeholder="state key hex (message id for ordered-log)" />
      <button class="rounded-lg bg-cyan-500 px-3 py-2 text-sm font-semibold text-slate-950" onclick={loadProof}>Load</button></div>
    {#if proofValueDigest}<div class="mt-3 break-all text-xs"><span class="text-slate-500">Proof value SHA-256 · {match(proofValueDigest)}</span><div class="font-mono">{proofValueDigest}</div></div>{/if}
    {#if proof}<pre class="mt-4 max-h-96 overflow-auto rounded-lg bg-slate-950 p-3 text-xs">{proof}</pre>{/if}
  </section>
</div>
<label class="mt-3 block text-xs text-slate-400">Expected payload/value SHA-256 (optional comparison)
  <input class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={expectedHash} placeholder="64 lowercase hex characters" />
</label>
{#if expectedHash && !SHA256.test(expectedHash)}<p class="text-xs text-amber-300">Expected hash must be 64 lowercase hex characters.</p>{/if}
{#if verificationError}<p class="text-sm text-rose-300">{verificationError}</p>{/if}
