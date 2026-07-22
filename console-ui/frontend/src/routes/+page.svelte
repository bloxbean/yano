<script lang="ts">
  import { base } from '$app/paths';
</script>

<svelte:head><title>Yano · Console</title></svelte:head>

<section class="py-10 text-center">
  <p class="mb-3 text-xs font-semibold uppercase tracking-[.22em] text-cyan-400">Unified node operations</p>
  <h1 class="m-0 bg-gradient-to-r from-cyan-300 via-blue-400 to-violet-400 bg-clip-text text-4xl font-bold
             text-transparent md:text-5xl">One console for Yano</h1>
  <p class="mx-auto mt-4 max-w-2xl text-slate-400">Inspect L1 sync, application chains, plugins, and operational health
    from the console embedded in this node.</p>
</section>

<div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
  <a href={`${base}/status/`} class="card p-6 text-slate-100 no-underline transition hover:-translate-y-1 hover:border-blue-400/40">
    <div class="mb-4 text-2xl">◉</div><h2 class="text-lg font-semibold">Node status</h2>
    <p class="mt-2 text-sm text-slate-400">Sync, peers, mempool, diffusion, storage, and short-lived trends.</p>
  </a>
  <a href={`${base}/app-chain/`} class="card p-6 text-slate-100 no-underline transition hover:-translate-y-1 hover:border-violet-400/40">
    <div class="mb-4 text-2xl">◇</div><h2 class="text-lg font-semibold">App chains</h2>
    <p class="mt-2 text-sm text-slate-400">Finality, anchors, messages, state machines, effects, and proofs.</p>
  </a>
  <a href={`${base}/plugins/`} class="card p-6 text-slate-100 no-underline transition hover:-translate-y-1 hover:border-cyan-400/40">
    <div class="mb-4 text-2xl">⬡</div><h2 class="text-lg font-semibold">Plugins</h2>
    <p class="mt-2 text-sm text-slate-400">Catalog provenance, activation state, health, and operations.</p>
  </a>
  <a href={`${base}/observability/`} class="card p-6 text-slate-100 no-underline transition hover:-translate-y-1 hover:border-emerald-400/40">
    <div class="mb-4 text-2xl">⌁</div><h2 class="text-lg font-semibold">Observability</h2>
    <p class="mt-2 text-sm text-slate-400">Durable L1, app-chain, and effects history from an explicit Prometheus endpoint.</p>
  </a>
</div>
