import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    csp: {
      mode: 'hash',
      directives: {
        'default-src': ['self'],
        'script-src': ['self'],
        'style-src': ['self'],
        'connect-src': ['self', 'http:', 'https:'],
        'img-src': ['self', 'data:'],
        'object-src': ['none'],
        'base-uri': ['none'],
        'form-action': ['self']
      }
    },
    adapter: adapter({
      pages: '../build/generated/console-ui/META-INF/resources/ui',
      assets: '../build/generated/console-ui/META-INF/resources/ui',
      fallback: undefined,
      precompress: false,
      strict: true
    }),
    paths: { base: '/ui' },
    prerender: {
      crawl: false,
      entries: ['*']
    }
  }
};

export default config;
