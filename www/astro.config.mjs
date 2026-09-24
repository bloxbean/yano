import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
export default defineConfig({
  site: 'https://getyano.dev',
  trailingSlash: 'always',
  redirects: { '/reference/distributions/': '/contribute/build-from-source/' },
  integrations: [
    starlight({
      title: 'Yano',
      description:
        'The Cardano data node you can build with. Query, test, embed, and create multi-party app ledgers.',
      logo: { src: './public/logo.svg' },
      favicon: '/favicon.svg',
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/bloxbean/yano' }],
      customCss: ['./src/styles/docs.css'],
      components: {
        PageTitle: './src/components/DocTitle.astro',
        SiteTitle: './src/components/SiteTitle.astro',
      },
      sidebar: [
        { label: 'Start here', items: [{ autogenerate: { directory: 'start' } }] },
        { label: 'Run a data node', items: [{ autogenerate: { directory: 'node' } }] },
        { label: 'Develop & test', items: [{ autogenerate: { directory: 'develop' } }] },
        {
          label: 'Build an app ledger',
          badge: { text: 'Experimental', variant: 'caution' },
          items: [{ autogenerate: { directory: 'app-chains' } }],
        },
        { label: 'Operate & extend', items: [{ autogenerate: { directory: 'operate' } }] },
        { label: 'Reference', items: [{ autogenerate: { directory: 'reference' } }] },
        { label: 'Build with AI', items: [{ autogenerate: { directory: 'ai' } }] },
        { label: 'Contribute', items: [{ autogenerate: { directory: 'contribute' } }] },
      ],
      head: [
        { tag: 'link', attrs: { rel: 'icon', href: '/favicon.ico', sizes: '32x32' } },
        { tag: 'link', attrs: { rel: 'apple-touch-icon', href: '/apple-touch-icon.png' } },
        { tag: 'meta', attrs: { property: 'og:image', content: 'https://getyano.dev/social.svg' } },
      ],
    }),
  ],
});
