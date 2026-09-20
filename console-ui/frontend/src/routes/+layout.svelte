<script lang="ts">
  import '../app.css';
  import { logoUrl } from '$lib/brand';
  import ConsoleShell from '$lib/components/ConsoleShell.svelte';
  let { children } = $props();
</script>

<svelte:head>
  <meta name="color-scheme" content="dark" />
  <meta name="theme-color" content="#0b100d" />
  <link rel="icon" href={logoUrl} />
</svelte:head>

<ConsoleShell>{@render children()}</ConsoleShell>
