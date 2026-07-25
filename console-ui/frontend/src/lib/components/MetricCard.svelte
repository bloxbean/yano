<script lang="ts">
  let { title, subtitle = '', children } = $props<{
    title: string;
    subtitle?: string;
    children: import('svelte').Snippet;
  }>();
</script>

<section class="card p-4">
  <header class="mb-3 flex items-start justify-between gap-3">
    <div>
      <h2 class="m-0 text-sm font-semibold text-slate-100">{title}</h2>
      {#if subtitle}<p class="mt-1 text-xs text-slate-500">{subtitle}</p>{/if}
    </div>
  </header>
  {@render children()}
</section>
