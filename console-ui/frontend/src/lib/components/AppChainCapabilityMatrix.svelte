<script lang="ts">
  import { base } from '$app/paths';
  import type { AppChainStatus } from '$lib/api/types';

  export let statuses: AppChainStatus[] = [];
  const short = (id: string) => id
    .replace('state-commitment:', '')
    .replace('state-index:', '')
    .replace('approval:', '')
    .replace('authorization:', '')
    .replace('l1-observer:', '')
    .replace('effects:', '');
</script>

<div class="section-title">Reference capability matrix</div>
<section class="card overflow-hidden">
  <div class="border-b border-slate-800 px-4 py-3">
    <h2 class="m-0 text-sm font-semibold">Running applications</h2>
    <p class="m-0 mt-1 text-xs text-slate-500">Declared by each application manifest; membership governance is intentionally separate.</p>
  </div>
  <div class="overflow-x-auto">
    <table class="w-full min-w-[760px] text-left text-xs">
      <thead class="text-slate-500"><tr><th class="p-3">Chain / application</th><th>Components</th><th>Business capabilities</th><th>Proof target</th></tr></thead>
      <tbody>
        {#each statuses as status}
          <tr class="border-t border-slate-800/60 align-top">
            <td class="p-3"><a class="font-semibold text-violet-300 no-underline hover:text-violet-200"
                                 href={`${base}/app-chain/?chain=${encodeURIComponent(status.chainId ?? '')}`}>{status.chainId ?? 'unknown chain'}</a><div class="mt-1 text-slate-500">{status.capabilityManifest?.applicationId ?? 'manifest unavailable'}</div></td>
            <td class="py-3">{status.capabilityManifest?.components.map((item) => item.id).join(', ') || 'standalone state'}</td>
            <td class="py-3"><div class="flex max-w-xl flex-wrap gap-1">
              {#each status.capabilityManifest?.crossCutting.filter((item) => item.enabled && !item.capabilityId.startsWith('state-commitment:')) ?? [] as capability}
                <span class="badge">{short(capability.capabilityId)}</span>
              {:else}<span class="text-slate-500">none declared</span>{/each}
            </div></td>
            <td class="py-3 pr-3">{status.capabilityManifest?.crossCutting.some((item) => item.capabilityId === 'state-commitment:mpf-blake2b256-v1')
              ? 'MPF · off-chain + on-chain' : status.capabilityManifest?.crossCutting.some((item) => item.capabilityId === 'state-commitment:jmt-blake2b256-v1')
                ? 'JMT · off-chain' : 'not declared'}</td>
          </tr>
        {:else}<tr><td colspan="4" class="p-4 text-slate-500">Loading capability manifests…</td></tr>{/each}
      </tbody>
    </table>
  </div>
</section>
