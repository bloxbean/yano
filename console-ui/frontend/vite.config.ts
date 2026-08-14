import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [tailwindcss(), sveltekit()],
  resolve: {
    alias: {
      '@yano-static': fileURLToPath(new URL('../../static', import.meta.url))
    }
  },
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:7070',
      '/q': 'http://127.0.0.1:7070'
    }
  }
});
