<script lang="ts">
  import '../app.css';
  import { logoDarkUrl } from '$lib/brand';
  import ConsoleShell from '$lib/components/ConsoleShell.svelte';
  let { children } = $props();
</script>

<svelte:head>
  <meta name="color-scheme" content="dark" />
  <meta name="theme-color" content="#05070f" />
  <link rel="icon" href={logoDarkUrl} />
</svelte:head>

<ConsoleShell>{@render children()}</ConsoleShell>
