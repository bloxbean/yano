<script lang="ts">
  let {
    page,
    hasPrevious,
    hasNext,
    onPrevious,
    onNext,
    busy = false,
    label = 'results'
  } = $props<{
    page: number;
    hasPrevious: boolean;
    hasNext: boolean;
    onPrevious: () => void;
    onNext: () => void;
    busy?: boolean;
    label?: string;
  }>();
</script>

<nav aria-label={`${label} pagination`}
     class="flex items-center justify-between gap-3 border-t border-slate-800 px-3 py-2">
  <button type="button"
          class="inline-flex items-center gap-1 rounded-lg border border-slate-700 px-3 py-1.5 text-xs hover:border-cyan-600 disabled:cursor-not-allowed disabled:opacity-40"
          disabled={!hasPrevious || busy}
          aria-label={`Previous ${label} page`}
          onclick={onPrevious}>
    <svg class="h-3.5 w-3.5" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path d="m12.5 4.5-5.5 5.5 5.5 5.5" stroke="currentColor" stroke-width="1.8"
            stroke-linecap="round" stroke-linejoin="round" />
    </svg>
    <span class="hidden sm:inline">Previous</span>
  </button>
  <span class="text-xs text-slate-500">Page {page.toLocaleString()} · 25 per page</span>
  <button type="button"
          class="inline-flex items-center gap-1 rounded-lg border border-slate-700 px-3 py-1.5 text-xs hover:border-cyan-600 disabled:cursor-not-allowed disabled:opacity-40"
          disabled={!hasNext || busy}
          aria-label={`Next ${label} page`}
          onclick={onNext}>
    <span class="hidden sm:inline">{busy ? 'Loading…' : 'Next'}</span>
    <svg class="h-3.5 w-3.5" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path d="m7.5 4.5 5.5 5.5-5.5 5.5" stroke="currentColor" stroke-width="1.8"
            stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  </button>
</nav>
