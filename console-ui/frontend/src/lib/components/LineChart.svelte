<script lang="ts">
  let { series, colors = ['#60a5fa'], height = 120, label = 'Trend chart' } = $props<{
    series: Array<Array<number | null>>;
    colors?: string[];
    height?: number;
    label?: string;
  }>();

  const width = 600;

  function path(values: Array<number | null>): string {
    const finite = series.flat().filter((value: number | null): value is number =>
      value !== null && Number.isFinite(value));
    if (finite.length < 2) return '';
    const min = Math.min(0, ...finite);
    const max = Math.max(1, ...finite);
    const span = Math.max(1, max - min);
    const count = Math.max(2, ...series.map((entry: Array<number | null>) => entry.length));
    let drawing = false;
    return values.map((value, index) => {
      if (value === null || !Number.isFinite(value)) {
        drawing = false;
        return '';
      }
      const x = 8 + (index / (count - 1)) * (width - 16);
      const y = height - 10 - ((value - min) / span) * (height - 20);
      const command = drawing ? 'L' : 'M';
      drawing = true;
      return `${command}${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
  }
</script>

<div class="relative min-h-[120px] overflow-hidden rounded-lg border border-slate-700/40 bg-slate-950/40">
  {#if series.flat().filter((value: number | null) => value !== null).length < 2}
    <div class="absolute inset-0 grid place-items-center text-xs text-slate-500">collecting data…</div>
  {/if}
  <svg class="block h-[120px] w-full" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}
       preserveAspectRatio="none">
    <line x1="8" x2={width - 8} y1={height * .25} y2={height * .25} stroke="rgb(148 163 184 / .10)" />
    <line x1="8" x2={width - 8} y1={height * .5} y2={height * .5} stroke="rgb(148 163 184 / .10)" />
    <line x1="8" x2={width - 8} y1={height * .75} y2={height * .75} stroke="rgb(148 163 184 / .10)" />
    {#each series as values, index}
      <path d={path(values)} fill="none" stroke={colors[index % colors.length]} stroke-width="2.5"
            vector-effect="non-scaling-stroke" stroke-linecap="round" stroke-linejoin="round" />
    {/each}
  </svg>
</div>
