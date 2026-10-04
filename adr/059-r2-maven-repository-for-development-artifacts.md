# ADR-059: Cloudflare R2 Maven Repository for Development Artifacts

**Status:** Phase 1 (snapshots) implemented, pending its first run on `main`. Phase 2 (release mirror) proposed.
**Date:** 2026-10-04
**Authors:** Claude Code (Opus 5.5)
**Related:** `.github/workflows/r2-snapshot.yml`, `.github/scripts/verify-maven-staging.py`,
`.github/workflows/snapshot_manual.yml`, `.github/workflows/release.yml`, `build.gradle` (`stagingRepository`),
ADR-023 (Docker release)

## Context

Every Yano development snapshot and release currently depends on Sonatype: snapshots go to the Central Portal
snapshot repository and releases to Maven Central. A Portal outage or a publishing quota then blocks every
development build that downstream projects (yano-x, testkits, SDK experiments) want to consume. BloxBean wants a
Maven repository it controls, readable anonymously at:

```text
https://repo.bloxbean.org/maven/snapshots
https://repo.bloxbean.org/maven/releases
```

The repository is a Cloudflare R2 bucket used as plain static storage. Maven Central stays the primary home of
official releases.

### Current publishing architecture

The analysis below describes `main` at `1564e112d` (version `0.1.0-pre17`, already released).

| Question | Finding |
|---|---|
| How Maven artifacts are published | One `mavenJava` publication per library module, defined centrally in the root `build.gradle` `subprojects` block: artifactId `yano-<project>`, the jar, `sources` and `javadoc` jars, a POM and Gradle module metadata. The root project publishes the POM-only starter `org.yanoproject:yano` (depends on `yano-core-api` and `yano-runtime`), and `:yano-bom` defines its own `java-platform` publication in `bom/build.gradle`. |
| Published modules | 24 artifactIds under `org.yanoproject`: `yano`, `yano-bom`, `yano-core-api`, `yano-plugin-catalog`, `yano-consensus`, `yano-p2p`, `yano-runtime`, `yano-ledger-rules`, `yano-ledger-state`, `yano-ccl-ledger-rules`, `yano-scalus-bridge`, `yano-tx-services`, `yano-bootstrap-providers`, `yano-devnet-toolkit`, `yano-testkit`, `yano-testkit-ccl`, `yano-app-e2e-testkit`, `yano-archive-api`, `yano-archive-core`, `yano-archive-store-ducklake`, `yano-appchain-config`, `yano-appchain-core-testkit`, `yano-appchain-proof-verifier`, `yano-appchain-anchor-onchain`. |
| Not Maven-published | `:app` (Quarkus node: uber-jar, JVM/native distribution zips, Docker images), `:console-ui`, `:utxo-output-index-example`, the `archive-modules` aggregator (all in `nonLibraryModules`), and `:appchain-plugin-conformance`, which removes its publication. |
| Snapshots | `snapshot_manual.yml`: manual, `main`/`release/**` only, `release-staging` environment, `clean fullBuild`, then `publishAggregationToCentralPortalSnapshots` (nmcp) to the Central Portal snapshot repository. Unsigned. |
| Releases | A code-owner-approved version bump on `main` → `tag-release.yml` (release-owner approval) pushes `v<version>` → `release.yml` runs `clean fullBuild`, signs, and uploads a `USER_MANAGED` Central Portal deployment (`publishAggregationToCentralPortal`). Nothing is public until `publish-central.yml` (approval-gated) or a human in the Portal publishes it. On the same tag `release-dist.yml` builds the JVM/native zips, npm packages and GitHub release, and `release.yml` calls `release-docker.yml` for images. `docker_publish`, `npm_publish` and `github_release` in `gradle.properties` gate those side effects. |
| Version | `version` in `gradle.properties`. For a `-SNAPSHOT` version, `build.gradle` inserts grgit's abbreviated commit id on the root and every subproject, so CI publishes `x.y.z-<sha7>-SNAPSHOT`, e.g. `0.1.0-pre18-1564e11-SNAPSHOT`. Release versions carry no commit id. (In a linked git worktree grgit cannot open the repository and the version stays `x.y.z-SNAPSHOT`; a CI checkout is unaffected.) |
| Centralized configuration | Yes: the root `build.gradle` owns the publications, POM content, signing (`configureReleaseSigning`), the nmcp aggregation and the deployment scope check (`verifyMavenReleasePublicationScope`). |
| Tasks producing every publication | `publishAllPublicationsToStagingRepository` with `-PstagingRepository=<path or URL>`: an existing, provider-neutral hook that adds a `staging` Maven repository to every project with `maven-publish`. The nmcp tasks build the Central bundle from the same publications. |
| Signing | Only for non-SNAPSHOT versions without `-PskipSigning`. CI passes the subkey in memory (`ORG_GRADLE_PROJECT_signingInMemoryKey*`). Snapshots are never signed. |
| Credentials | Secrets of the `release-staging` environment, whose branch policy admits only `main`, `release/**` and `v*` tags: `OSSRH_USERNAME`/`OSSRH_TOKEN`, `SIGNING_KEY_ASC`/`SIGNING_KEY_ID`/`SIGNING_PASSWORD`, Docker Hub, the release App, and now `R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY` with variables `R2_BUCKET`/`R2_ENDPOINT`. |
| Sonatype-specific logic | Only the nmcp plugin (`nmcpAggregation`, `USER_MANAGED`) and the Central snapshot repository URL in `repositories`. The publications themselves are provider-neutral. |
| Shared outputs with distributions | `:app` builds on the published libraries, but distributions are never Maven publications and no snapshot workflow builds them. |

A local `publishAllPublicationsToStagingRepository` produces 24 artifactIds, 810 files, about 36 MB per snapshot.
`yano-archive-store-ducklake` alone is 17 MB, because it bundles DuckDB extensions. The tree contains nothing but
`org/yanoproject/**` jars, POMs, module files, metadata and checksums.

## Decision

### 1. Gradle stays provider-neutral and unchanged

Gradle publishes to a local directory through the existing `stagingRepository` hook. It holds no R2 endpoint,
bucket, credential, S3 logic or Cloudflare dependency, and no publication is duplicated. Any object store,
reached by any S3-compatible client, can replace R2 without touching the build.

```text
existing MavenPublications ──► publishAllPublicationsToStagingRepository ──► build/r2-maven/  (Gradle)
                                                                                   │
                                                                     AWS CLI over the S3 API     (CI)
                                                                                   ▼
                                                    R2 bucket bloxbean-maven, key prefix maven/snapshots/
                                                                                   │
                                                                    custom domain, anonymous reads
                                                                                   ▼
                                                          https://repo.bloxbean.org/maven/snapshots
```

### 2. Repository layout

One bucket shared by all BloxBean JVM projects. Each project owns its Maven coordinates inside two prefixes:

```text
maven/snapshots/<group path>/<artifactId>/...     e.g. maven/snapshots/org/yanoproject/yano-core-api/
maven/releases/<group path>/<artifactId>/...
```

The bucket is exactly the Maven repository tree, so the custom domain serves it with no rewriting. Everything in
the bucket is public through the domain, so nothing private may ever be stored in it.

### 3. Snapshot version convention: keep `x.y.z-<sha7>-SNAPSHOT`

Yano's existing convention publishes one Maven version per commit. R2 keeps it rather than introducing a new one:

- A consumer pins an exact commit (`0.1.0-pre18-1564e11-SNAPSHOT`) and always gets a build of that source
  commit. That is what yano-x and the testkits need when they reproduce a failure against a specific host build.
  The bytes stay the same until the commit is re-published or retention removes the version (jars are not
  byte-reproducible across builds).
- Each commit's files live in their own version directory, which makes retention a matter of dropping whole
  directories (see Retention).
- The artifact-level `maven-metadata.xml` lists every published commit version in publication order, and
  `<latest>` names the most recent one. Tools read "the newest snapshot" from `<latest>`. Gradle dynamic
  selectors such as `latest.integration` or `+` order versions by their own rules, so with a commit hash in the
  version they do not mean "newest" and should not be used.

A moving `x.y.z-SNAPSHOT` would have let consumers float on the newest build, but it would change Yano's version
semantics for every existing consumer and lose the per-commit pinning above. It is not adopted.

### 4. The workflow: `.github/workflows/r2-snapshot.yml`

A dedicated workflow, separate from `snapshot_manual.yml` and the release workflows.

| Step | Credentials | What it does |
|---|---|---|
| Trigger | — | `workflow_dispatch` only. The job runs only on `main` or `release/**` and uses the `release-staging` environment. |
| Resolve the snapshot version | none | Reads the effective version through Gradle and fails unless it ends in `-SNAPSHOT`. |
| Build with Gradle | none | `clean fullBuild -PskipSigning=true`, the same gate as `snapshot_manual.yml`. |
| Discover the Maven publications | none | Runs `verifyMavenReleasePublicationScope` and stages every publication into `build/r2-maven`. Records the artifactIds this build owns and requires their count to equal the Central deployment scope. |
| Seed metadata from R2 | R2 | Empties `build/r2-maven`, then for each of those artifactIds copies its current `maven-metadata.xml` from R2 through the S3 API into it, keeping a copy of each seed. A 404 means a first publication. Any other error fails the run. |
| Stage the Maven publications | none | `publishAllPublicationsToStagingRepository -PstagingRepository=build/r2-maven`. Gradle appends the new version to the seeded metadata, sets `<latest>`, and regenerates the checksums. |
| Verify the staged repository | none | `.github/scripts/verify-maven-staging.py`: exactly the discovered artifactIds, only under `org/yanoproject/`, only Maven file types, a checksum for every file, one version per artifact, metadata naming that version, every version of the seed still listed, every file the version metadata names present and nothing else, and jar + sources + javadoc + module for every jar-packaged artifact. |
| Upload to R2 | R2 | `aws s3 cp --recursive` from `build/r2-maven/org/yanoproject/` to `maven/snapshots/org/yanoproject/` in three passes: artifacts, then version-level metadata, then artifact-level metadata, both metadata passes with `Cache-Control: no-cache`. No `--delete`, no `sync` of a parent prefix. |
| Verify the public repository | none | For every artifact, the artifact-level and version-level `maven-metadata.xml` served anonymously by `repo.bloxbean.org` must be byte-identical to the uploaded files. |
| Resolve with a Gradle consumer | none | A throwaway Gradle project resolves `org.yanoproject:yano` and `yano-bom` with `org.yanoproject` restricted (`exclusiveContent`) to the public R2 URL and everything else from Maven Central. Every Yano jar Gradle downloads must be byte-identical to the staged one, and `yano-core-api` and `yano-runtime` must be among them. |

Gradle never runs with R2 credentials: they are set only on the two AWS CLI steps.

#### Why the metadata is seeded

Gradle writes a fresh artifact-level `maven-metadata.xml` into an empty local repository, listing only the
version it just published. Uploading that file as-is would erase every earlier version from the list on R2.
Gradle's file-repository publisher, however, reads an existing `maven-metadata.xml` in the target repository and
appends to it (verified: a seeded list of two versions came back with three, `<latest>` moved to the new one, and
the checksums were regenerated). Seeding the staging directory from R2 therefore yields correct merged metadata
with no metadata code in the workflow and none in Gradle. Because the merge is a Gradle implementation detail,
the validator compares every staged version list with its seed and fails if a published version went missing. A
Gradle upgrade that stopped merging would therefore stop the run instead of erasing the history on R2.

The seed is read through the authenticated S3 API rather than the public URL, so it is the stored object and not
an edge-cached copy. Only artifactIds the build itself publishes are seeded and uploaded. That matters because
`org.yanoproject` may later be shared with another repository (yano-x), and `com.bloxbean.cardano` is already
shared by Yaci, CCL and Yaci Store. A workflow that re-uploaded metadata it does not own could overwrite another
project's concurrent update with a stale copy.

Version-level metadata is not seeded. Re-running the same commit therefore restarts the build number at 1 under
a new timestamp. The version-level metadata always names the files of the latest build, and the earlier
timestamped files stay as unreferenced objects for retention to remove.

#### Concurrency

Seed → publish → upload is a read-modify-write of each artifact's metadata. `concurrency: r2-maven-snapshots`
with `cancel-in-progress: false` serializes Yano's runs and never cancels one that may be uploading. GitHub
concurrency groups are per repository. No cross-repository serialization is needed because Maven metadata is per
artifactId: as long as no two repositories publish the same artifactId, no two workflows ever write the same
metadata object. Gradle writes no group-level metadata for libraries. Snapshot and release (Phase 2) workflows
use different prefixes and so different metadata. With a queue, GitHub keeps one pending run and cancels older
pending ones. For snapshots that only means an intermediate commit is not published.

Upload order narrows, but cannot close, the window in which a reader sees inconsistent files. The order is:

1. artifacts;
2. each version's `maven-metadata.xml`;
3. each artifact's version list.

Metadata therefore never names a file or a version that is not uploaded yet. A reader can still briefly see a new
`maven-metadata.xml` with its previous `.sha1`, which Maven reports as a checksum warning on that one request.

A run stopped part-way leaves some artifacts updated and others not, for example by the 120-minute job timeout or
a network failure. Nothing published is lost: every write is an add or a replace of this commit's files and of a
metadata list that already includes the old versions. The repair is to run the workflow again, which seeds,
merges and uploads idempotently. No manual object surgery is needed.

### 5. Security

- Credentials live only in the `release-staging` environment. Its branch policy (`main`, `release/**`, `v*`)
  keeps them away from any other ref, and the job's `if:` skips other branches instead of failing on the policy.
- No `pull_request` or `pull_request_target` trigger: fork and PR code never runs in this workflow. Dispatch
  requires write access, and the workflow and script are under CODEOWNERS (`@bloxbean/release-owners`).
- `permissions: contents: read`, `persist-credentials: false`, and no Gradle or setup-java cache in the
  credentialed job, matching the other release workflows.
- R2 credentials are injected only into the AWS CLI steps. GitHub masks them, and nothing echoes them.
- Anonymous users get read-only HTTP access through the custom domain. Writes need the S3 API with a token.
- Residual risk: R2 API tokens can be scoped to a bucket but not to a key prefix (as of 2026-10), so any BloxBean repository's
  token can write anywhere in the shared bucket. Mitigations: an Object Read & Write token scoped to the one
  bucket, environment branch policies and CODEOWNERS in every publishing repository, and workflows that only
  write the artifactIds they staged. Separate buckets per project would allow per-project tokens, but a single
  domain would then need a routing Worker. Not adopted for now.

### 6. Validation

A run counts as successful only after all three checks pass: the local tree check, byte-identical public
metadata, and a real Gradle resolution through the public URL with byte-identical jars. Resolution through
Gradle is the strongest check: it exercises the POM, the Gradle module metadata, the BOM, the snapshot
timestamp resolution and the transitive graph exactly as a consumer would.

## Cloudflare setup (manual, one-time)

1. **Bucket.** `bloxbean-maven` exists, and its name is the `R2_BUCKET` variable. Keep the `r2.dev` public
   development URL disabled. It is not the documented repository URL and bypasses any domain rules.
2. **API token.** R2 → Manage API tokens → create an **Object Read & Write** token restricted to `bloxbean-maven`
   only. Store its Access Key ID and Secret Access Key. The S3 endpoint is
   `https://<account-id>.r2.cloudflarestorage.com`.
3. **Custom domain.** Bucket → Settings → Custom Domains → connect `repo.bloxbean.org`. The `bloxbean.org` zone
   must be in the same Cloudflare account. This makes every object publicly readable at
   `https://repo.bloxbean.org/<key>`, so `maven/snapshots/...` maps to `https://repo.bloxbean.org/maven/snapshots/...`.
4. **Caching.** Cloudflare caches `.jar` by default and leaves `.xml`, `.pom`, `.module` and checksums uncached.
   The workflow marks metadata `Cache-Control: no-cache`, so a later "cache everything" rule cannot serve a stale
   version list. Do not add a cache rule that ignores origin cache headers for `*maven-metadata.xml*`.
5. **GitHub.** In the `release-staging` environment: secrets `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY`,
   variables `R2_BUCKET` and `R2_ENDPOINT`. All four are configured.
6. **Verify anonymous access** after the first run:
   `curl -fsS https://repo.bloxbean.org/maven/snapshots/org/yanoproject/yano-core-api/maven-metadata.xml`.
   A directory URL returns 404, because R2 serves objects, not listings. Maven and Gradle never need listings.
7. **Lifecycle.** Add a rule to abort incomplete multipart uploads after 1 day. That is safe because it touches
   no complete object. Add no age-based expiry rule (see Retention).

On 2026-10-04 `repo.bloxbean.org` already resolves to Cloudflare and answers 404 for every path. That is
consistent with a connected domain on an empty bucket. The first run's public verification confirms the
connection.

## End-to-end verification

1. `main` must carry a `-SNAPSHOT` version. The PR that adds this workflow also moves `main` from the released
   `0.1.0-pre17` to `0.1.0-pre18-SNAPSHOT`. The workflow file must be on `main` too, because GitHub only
   dispatches workflows that exist on the default branch.
2. Actions → "Publish snapshot to the R2 Maven repository" → Run workflow on `main`, or
   `gh workflow run r2-snapshot.yml --ref main`.
3. The run verifies the local tree, the public metadata and a Gradle consumer by itself. Its summary prints the
   version and a ready-to-paste consumer block.
4. Independently, from any machine:

   ```groovy
   repositories {
       mavenCentral()
       maven {
           url = uri("https://repo.bloxbean.org/maven/snapshots")
           mavenContent { snapshotsOnly() }
       }
   }
   dependencies {
       implementation("org.yanoproject:yano:0.1.0-pre18-<sha7>-SNAPSHOT")
   }
   ```

   `./gradlew dependencies --configuration runtimeClasspath --refresh-dependencies` must resolve every
   `org.yanoproject` module.
5. Running the workflow twice on different commits must leave both versions in
   `.../yano-core-api/maven-metadata.xml`, with `<latest>` naming the second.

Before merging, the step scripts of `r2-snapshot.yml` ran in an Ubuntu JDK 25 container against MinIO, with
MinIO's anonymous path standing in for `repo.bloxbean.org`. Two deviations from the real run: the `fullBuild` gate
was skipped, and the consumer allowed MinIO's plain-`http` URL. Results:

- **Release version on `main`:** `0.1.0-pre17` was rejected before any build.
- **First publication:** 24 × "first publication", 810 files validated, uploaded and verified.
- **Same-commit re-run:** 24 × "appending"; the public metadata still matched.
- **Second commit:** `yano-core-api/maven-metadata.xml` listed both versions with `<latest>` on the second, and
  the consumer resolved 9 Yano jars byte-identical to the staged ones.
- **Third commit, with the final workflow:** three-pass upload and seed check; every artifact listed all three
  versions.
- **Bad credentials:** the seed failed on the 403 instead of treating it as a first publication.
- **Validator:** it rejected a stray zip, a missing sources jar, missing version metadata, a foreign group path,
  a wrong artifact set, a wrong version, and a staged list that dropped a seeded version.

## Snapshot retention (proposal, not implemented)

Storage is about 36 MB per published commit. At about five `main` pushes a day, once publishing follows `main`,
that is about 5 GB a month. R2 charges no egress, so storage and Class A writes (about 810 PUTs per run) are the
cost.

Proposed policy:

| Content | Retention |
|---|---|
| Commit snapshot versions (`x.y.z-<sha>-SNAPSHOT`) | Delete after 60 days, but always keep the newest 20 and the `<latest>` version |
| Superseded timestamped builds inside a kept version (same-commit re-runs) | Delete files the version-level metadata no longer names |
| Pre-release and RC versions under `maven/releases` | Permanent, like Maven Central |
| GA releases | Permanent |

**R2 lifecycle rules are not safe here.** They expire objects by prefix and age only, and so they would:

- expire an artifact's `maven-metadata.xml` once that module stops being republished, erasing its whole listing;
- leave expired versions in `<versions>`;
- delete even the newest version when publishing pauses for longer than the rule's age;
- be unable to express "keep the newest N".

Cleanup must be Maven-aware. It belongs in a scheduled workflow in the same `r2-maven-snapshots` concurrency
group, so it never interleaves with a publication. For each artifact it:

1. reads the metadata and selects versions to drop;
2. rewrites and uploads the artifact-level metadata without those versions, with fresh checksums;
3. only then deletes the version directories.

Metadata goes first so that it never lists a deleted version. The cleanup deletes only keys under the artifactIds
it owns, and never anything under `maven/releases/`.

## Phase 2: mirroring official releases (proposal)

Target:

```text
v* tag ──► release.yml: clean fullBuild, sign once, stage once
                │
                ├──► staged Maven files kept as a workflow artifact (the immutable record)
                ├──► R2 maven/releases/  (same files; gated by a new r2_publish flag)
                └──► Maven Central USER_MANAGED deployment (unchanged), then publish-central.yml (unchanged)

release-dist.yml / release-docker.yml: JVM and native zips, npm, GitHub release, images (unchanged)
```

Design rules:

1. **Build and sign once.** In the existing upload step, the same Gradle invocation that runs
   `publishAggregationToCentralPortal` also runs `publishAllPublicationsToStagingRepository` (seeded from
   `maven/releases`). Both consume the same jar and signature task outputs. The step then asserts that every
   file in the nmcp Central bundle is byte-identical to its staged counterpart. That makes "same artifacts" a
   checked property, not an assumption. Releases carry `.asc` signatures on R2 too, made by the same key as on
   Central. `verify-maven-staging.py` gains the release layout at that point: no version-level metadata,
   non-timestamped file names, and a required `.asc` for every file with its checksums.
2. **R2 first, independent of Central.** The R2 upload runs before the Central upload, so a Central outage or
   quota rejection leaves the R2 copy in place and the run fails visibly on Central.
3. **Releases are immutable on R2.** Before uploading, the step lists `maven/releases/org/yanoproject/<a>/<version>/`
   for every artifact. If any object exists, it fails unless every staged file is byte-identical, which makes it
   idempotent for a re-run of the same job. A re-run of `release.yml` rebuilds: Gradle jars are not
   byte-reproducible and signatures carry timestamps. It must therefore never upload a rebuilt set to Central
   after R2 already holds the version. Central retries go through rule 4 instead.
4. **Central retry without a rebuild.** This is the requirement that Central eventually receives the same
   artifacts after it failed, without rebuilding the release. A small approval-gated workflow downloads the
   version's files from R2, zips them as the Portal bundle (every file except `maven-metadata.xml*`), and uploads
   that with one Portal API call (`POST /api/v1/publisher/upload?publishingType=USER_MANAGED`). From there
   `publish-central.yml` works as today. Central receives exactly the bytes R2 serves.
5. **What "released" means does not change.** A release is official when Maven Central publishes it. R2 is an
   additional, independent copy. Like a pushed Docker image, a version that has reached R2 is permanent. A release
   abandoned after its tag burns its version number, which is already true when `docker_publish` or `npm_publish`
   is on. Gate the mirror with an `r2_publish` flag in `gradle.properties`, consistent with the existing release
   side-effect flags.

**Recommendation:** the tag-time mirror above (rules 1-5). R2's purpose is to publish when Central cannot.
Tying R2's visibility to Central's approval would also tie it to Central's availability.

**Open question for release owners, to settle before Phase 2 is built.** An alternative is to stage under a
non-public prefix at tag time and promote with a byte-identical server-side copy (`CopyObject`) when
`publish-central.yml` is approved. R2 visibility would then match Central, and an abandoned tag would burn no
version on R2. The cost is a second promotion path, needed exactly when Central cannot take the deployment at all.
This ADR recommends against it, but it is the release owners' call.

## Reuse by other BloxBean projects

No shared workflow yet. It will be extracted once two or three projects run their own copy. The stable inputs
are already visible:

| Input | Yano value |
|---|---|
| Gradle staging command | `publishAllPublicationsToStagingRepository -PstagingRepository=<dir>` |
| Local repository path | `build/r2-maven` |
| Group path owned | `org/yanoproject` |
| Prefix | `maven/snapshots` or `maven/releases` |
| Public repository URL | `https://repo.bloxbean.org/maven/<prefix>` |
| Concurrency group | `r2-maven-snapshots` (one per repository and prefix) |

What another project needs:

- A `stagingRepository`-style hook: the ten-line `allprojects { pluginManager.withPlugin('maven-publish') ... }`
  block in Yano's `build.gradle`.
- An environment holding the four R2 values.
- A copy of the workflow and validator with its own group path and consumer coordinates.

A future `bloxbean/.github/.github/workflows/publish-r2-maven.yml` would take the inputs above and run the
seed, stage, verify, upload and check sequence. The calling job keeps the environment so that its branch policy
still applies. The rule that must survive extraction is that a project seeds and uploads only the artifactIds its
own build staged, because group paths such as `com/bloxbean/cardano` are shared between repositories.

## Alternatives considered

- **Gradle's built-in `s3://` repositories, or an R2 Gradle plugin.** Rejected: endpoint and credentials would
  move into the build that runs untrusted-by-default code paths, and the build would be tied to one provider.
- **Nexus, Artifactory or a custom Maven server.** Rejected: a server to run and secure for what static storage
  already serves.
- **GitHub Packages.** Rejected: Maven reads need a token even for public packages, which breaks anonymous
  consumption.
- **`aws s3 sync --delete` of the staging directory.** Rejected: on a shared bucket a delete-sync of the wrong
  prefix removes other projects' artifacts. The workflow only ever adds keys.
- **Uploading Gradle's fresh metadata without seeding.** Rejected: it erases every earlier version from the list.
- **Merging metadata in a script.** Rejected: it re-implements what Gradle already does correctly.
- **Publishing snapshots from `snapshot_manual.yml`.** Rejected: the Central and R2 flows stay independent, so
  either can fail or be retired alone.

## Consequences

- Yano development builds can be consumed without Sonatype, from a repository BloxBean controls.
- `snapshot_manual.yml` (Central snapshots) is unchanged and can run alongside. Retiring it, and pointing
  README's snapshot section at R2, is a separate decision once R2 has proven itself.
- The release flow, signing, Central deployment, distributions, Docker and npm are untouched.
- Snapshot storage grows until Maven-aware retention exists.
- Turning on `push: branches: [main]` later costs one `fullBuild` (about 40 minutes) per push. Consider
  `workflow_run` after `build.yml` with a lighter gate at that point.

## Implementation status

| Item | State |
|---|---|
| Analysis of current publishing | Done (this ADR) |
| Local staging | Existing `stagingRepository` hook, no Gradle change |
| `r2-snapshot.yml` + `verify-maven-staging.py` | Implemented, dry-run against MinIO |
| R2 secrets, variables, bucket | Configured in `release-staging` |
| First run on `main` | Pending: after the PR (workflow + `0.1.0-pre18-SNAPSHOT`) merges |
| `push: main` trigger | After the manual run is proven |
| Maven-aware retention | Proposed |
| Release mirror (Phase 2) | Proposed |
| Shared reusable workflow | After two or three projects use it |
