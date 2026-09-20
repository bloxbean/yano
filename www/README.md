# Yano documentation

Astro + Starlight site for **https://getyano.dev**, maintained in this repository. The source branch starts at the namespace refactor, `fd7fe406e9364689a3e829b79f82707488cebf1e`. This is a separate documentation change.

## Work locally

Use Node.js 24 LTS (CI uses 24) and npm:

```bash
cd www
npm ci
npm run generate
npm run dev
```

Before committing:

```bash
npm run check
npm run build
```

`build` regenerates public AI artifacts, builds static HTML and Pagefind search, and verifies internal links/assets. No Java build or running node is required to build this site. Examples are source-reviewed; building the website does not execute devnet or integration examples.

## Branding

The mark is the paper plane with a lime slash in `static/yano-*.svg`, using the site tokens `#0b100d` (ink), `#f2f5ef` (paper), and `#b9f57d` (lime):

- `yano-icon.svg`: for dark surfaces. `www/public/logo.svg` is the same artwork with a tighter viewBox; the landing page and social card use it.
- `yano-icon-light.svg`: for light surfaces (ink wings, `#5e9c2c` slash, since lime has too little contrast on white). The docs header inlines the mark and switches between both through `--yano-mark-*` in `src/styles/docs.css`.
- `yano-icon-mono.svg`: single-color `currentColor` version.
- `yano-app-icon-dark.svg` / `yano-app-icon-lime.svg`: rounded-square app icons. `www/public/favicon.svg` is the dark app icon with the mark enlarged to stay legible at 16 px; `favicon.ico` (16/32/48) and `apple-touch-icon.png` (180, full-bleed) are rendered from it.

`static/readme-banner.png` uses the same mark and palette for the repository header.

## Content

- `src/pages/index.astro`: bespoke landing page with keyboard-accessible example tabs and copy controls.
- `src/content/docs/`: edited public documentation, organized by user journey.
- `content-sources.json`: review provenance for documentation pages. Update alongside changes; adapted pages are not blindly reimported during builds.
- `scripts/generate.mjs`: active YAML configuration tables, declared Java property keys, literal REST route inventory, Markdown copies, `llms.txt`, full-text bundle, and checksummed manifest.
- `scripts/check-site.mjs`: checks the built site for broken local links/assets and internal-document references.

Generated artifacts are ignored in Git and recreated for every build. A manifest records the build checkout's commit, source version, and exact exported content hashes. Local edits are reflected by hashes; the revision is not a claim that local changes are committed. The static route inventory is not OpenAPI. Obtain complete schemas from a matching running Yano artifact.

## GitHub Pages

`.github/workflows/docs.yml` builds PRs and uploads a downloadable preview artifact. Only builds from `main` can publish the generated site to the `gh-pages` branch. GitHub Pages deploys that branch through the existing `github-pages` environment and its approval rules.

For the first publication:

1. Merge a documentation change to `main` or run the Documentation workflow on `main`. The publish job creates `gh-pages`.
2. In repository **Settings → Pages**, choose **Deploy from a branch**, then select `gh-pages` and `/(root)`.
3. Set the custom domain to `getyano.dev` and verify domain ownership with GitHub.
4. Configure its DNS using the current [GitHub Pages custom-domain instructions](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site), then enable HTTPS.
5. Approve the pending deployment in the `github-pages` environment.

The site uses the domain root (no `/yano` base path). `public/CNAME` contains only `getyano.dev`. A PR artifact is downloadable for local preview; GitHub Pages does not create a separate preview URL for every PR with this workflow.

## Redirect yanoprojects.org

DNS alone does not issue HTTP redirects, and a Pages site has one canonical custom domain. Configure `yanoprojects.org` at your DNS/edge provider with HTTPS and an HTTP **301 or 308 redirect** to `https://getyano.dev`, preserving the path and query string. Also redirect `www.yanoprojects.org` if you intend to support it. Do not add a second hostname to `CNAME` or a second Pages deployment targeting the same site.

Examples to verify after the provider rule is active:

```bash
curl -I https://yanoprojects.org/
curl -I 'https://yanoprojects.org/start/quickstart/?source=redirect-check'
```

The second response's `Location` should be `https://getyano.dev/start/quickstart/?source=redirect-check`. DNS and edge-provider changes are deployment setup outside this repository; this PR does not activate them.

The deployment workflow uses explicit install/check/build steps and publishes only `www/dist` to `gh-pages`. Generated site files are not committed to `main`.
