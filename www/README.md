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

`static/logo-dark.svg` remains the original artwork and is the source for `www/public/favicon.ico`. `static/logo-docs.svg` is the lime/mint documentation variant, with the same geometry, a transparent background, and a tighter viewBox. Its copy in `www/public/logo.svg` serves the site; the social card embeds the same artwork.

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
