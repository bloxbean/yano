# ADR-059: Cloudflare R2 Maven Repository for Development Artifacts

**Status:** Phase 1 (snapshots) implemented and in use since 2026-10-04 (`0.1.0-pre18-de81cc5-SNAPSHOT`), with
approval-gated retention and snapshot distribution zips. Phase 2 (releases to Central and the BloxBean
repository, chosen per release) implemented.
**Date:** 2026-10-04
**Authors:** Claude Code (Opus 5.5)
**Related:** `.github/workflows/bloxbean-snapshot.yml`, `bloxbean-snapshot-cleanup.yml`,
`bloxbean-snapshot-dist.yml`, `dist-dev.yml`, `.github/scripts/verify-maven-staging.py`, `clean-maven-snapshots.py`,
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
existing MavenPublications ──► publishAllPublicationsToStagingRepository ──► build/bloxbean-maven/  (Gradle)
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

### 4. The workflow: `.github/workflows/bloxbean-snapshot.yml`

A dedicated workflow, separate from `snapshot_manual.yml` and the release workflows.

| Step | Credentials | What it does |
|---|---|---|
| Trigger | — | `workflow_dispatch` only. The job runs only on `main` or `release/**` and uses the `release-staging` environment. |
| Resolve the snapshot version | none | Reads the effective version through Gradle and fails unless it ends in `-SNAPSHOT`. |
| Require green CI for this commit | GitHub token, `actions: read` | Requires a successful push run of `build.yml` and `integration.yml` for the exact commit. Together they run `build`, `extendedTest`, `distributionCheck` and the native build, everything `fullBuild` runs, in parallel jobs. The publication is therefore gated on the same tests without spending about 40 minutes repeating them on one runner. Dispatched before CI finishes, the run stops and is run again later. The check is `.github/scripts/require-green-ci.sh`, shared with the distribution workflow, and runs before any setup. `build.yml` and `integration.yml` now trigger on `release**`: GitHub's `*` does not match `/`, so `release/**` branches previously ran no CI and could never pass this check. |
| Discover the Maven publications | none | Runs `verifyMavenReleasePublicationScope` and stages every publication into `build/bloxbean-maven`. Records the artifactIds this build owns and requires their count to equal the Central deployment scope. |
| Seed metadata from the repository | bucket | Empties `build/bloxbean-maven`, then for each of those artifactIds copies its current `maven-metadata.xml` from R2 through the S3 API into it, keeping a copy of each seed. A 404 means a first publication. Any other error fails the run. |
| Stage the Maven publications | none | `publishAllPublicationsToStagingRepository -PstagingRepository=build/bloxbean-maven`. Gradle appends the new version to the seeded metadata, sets `<latest>`, and regenerates the checksums. |
| Verify the staged repository | none | `.github/scripts/verify-maven-staging.py`: exactly the discovered artifactIds, only under `org/yanoproject/`, only Maven file types, a checksum for every file, one version per artifact, metadata naming that version, every version of the seed still listed, every file the version metadata names present and nothing else, and jar + sources + javadoc + module for every jar-packaged artifact. |
| Upload to the repository | bucket | `aws s3 cp --recursive` from `build/bloxbean-maven/org/yanoproject/` to `maven/snapshots/org/yanoproject/` in three passes: artifacts, then version-level metadata, then artifact-level metadata, both metadata passes with `Cache-Control: no-cache`. No `--delete`, no `sync` of a parent prefix. |
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

Seed → publish → upload is a read-modify-write of each artifact's metadata. `concurrency: bloxbean-maven-snapshots`
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
- `permissions: contents: read` plus `actions: read` for the CI gate, `persist-credentials: false`, and no
  Gradle or setup-java cache in the credentialed job, matching the other release workflows.
- R2 credentials are injected only into the AWS CLI steps. GitHub masks them, and nothing echoes them.
- Anonymous users get read-only HTTP access through the custom domain. Writes need the S3 API with a token.
- Residual risk: R2 API tokens can be scoped to a bucket but not to a key prefix (as of 2026-10), so any BloxBean
  repository's token can write anywhere in the shared bucket. Mitigations: an Object Read & Write token scoped to
  the one bucket, environment branch policies and CODEOWNERS in every publishing repository, and workflows that only
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
2. Wait until the commit's "Clean, Build" and "Integration, Distribution, Native" runs are green. Then
   Actions → "Publish snapshot to the BloxBean Maven repository" → Run workflow on `main`, or
   `gh workflow run bloxbean-snapshot.yml --ref main`.
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

Before merging, the step scripts of the workflow (then `r2-snapshot.yml`) ran in an Ubuntu JDK 25 container against
MinIO, with MinIO's anonymous path standing in for `repo.bloxbean.org`. Two deviations from the real run: the build
gate was skipped, and the consumer allowed MinIO's plain-`http` URL. Results:

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

## Snapshot retention

Storage is about 36 MB per published commit. At about five `main` pushes a day, once publishing follows `main`,
that is about 5 GB a month. R2 charges no egress, so storage and Class A writes (about 810 PUTs per run) are the
cost.

Policy:

| Content | Retention |
|---|---|
| Commit snapshot versions (`x.y.z-<sha>-SNAPSHOT`) | Delete after 60 days, but always keep the newest 20 and every `<latest>` version |
| Superseded timestamped builds inside a kept version (same-commit re-runs) | Kept for now: at about 36 MB per re-run they are not worth the extra deletion logic |
| Pre-release and RC versions under `maven/releases` | Permanent, like Maven Central |
| GA releases | Permanent |

**R2 lifecycle rules are not safe here.** They expire objects by prefix and age only, and so they would:

- expire an artifact's `maven-metadata.xml` once that module stops being republished, erasing its whole listing;
- leave expired versions in `<versions>`;
- delete even the newest version when publishing pauses for longer than the rule's age;
- be unable to express "keep the newest N".

Cleanup must be Maven-aware. It is `.github/workflows/bloxbean-snapshot-cleanup.yml`, running
`.github/scripts/clean-maven-snapshots.py`.

**Choosing what to drop.** Every publication uploads all artifacts under one commit version, so versions are
chosen once and dropped from every artifact. Choosing per artifact could leave `yano-runtime` at a version whose
`yano-core-api` is gone. A version's age is the newest object time in any of its directories, so a same-commit
re-run counts as recent. Version directories that no metadata lists (an interrupted upload) are aged and dropped
the same way.

**Running it.**

- **Plan:** every run plans first and writes the versions it would drop, with sizes, to the run summary.
- **Approval:** with `dry_run` off, removal then waits for a release owner in the `release` environment, the same
  reviewers and the same gate as `publish-central.yml`. The requester can't approve their own run.
- **Removal:** only the versions the approver saw are removed, and only those still eligible. Each artifact's
  `maven-metadata.xml` is rewritten first without them, with fresh checksums and `Cache-Control: no-cache`, so it
  never lists a deleted version. The version directories are deleted after that, with `aws s3 rm`, one exact
  `<artifact>/<version>/` filter per pair. That command deletes object by object. The batch `DeleteObjects` call is
  avoided because AWS CLI 2.23+ always attaches a CRC32 checksum to it, which R2 has rejected.
- **Concurrency:** the removal job holds the publish workflow's `bloxbean-maven-snapshots` concurrency group.
  The approval wait holds nothing, so a pending approval never blocks a publication.
- **Recovery:** a run that fails part-way is repaired by running it again. GitHub keeps at most one pending job
  per concurrency group. An approved removal still waiting behind a publication is therefore cancelled if another
  publication is queued, and has to be dispatched and approved again.

**Scope.** It touches only artifact directories directly under `maven/snapshots/org/yanoproject/` that carry a
`maven-metadata.xml`. yano-x's `org/yanoproject/x/`, other groups in the bucket, and `maven/releases/` are never
listed or written.

**Who can run it.** Anyone with write access can start it. Removal needs a release owner, and the policy is in
CODEOWNERS-reviewed code, not in run inputs.

**Trigger.** It runs manually only. Every removal needs an approval, so a schedule would just open an approval
request every week. Storage grows slowly: about 36 MB per publication is roughly 1.5 GB a month at ten
publications a week. Revisit this if publishing follows every `main` push.

**Testing.** It was tested against MinIO with:

- seven versions, one of them a same-commit re-run and one an orphan directory;
- decoys under `org/yanoproject/x/`, `com/bloxbean/` and `maven/releases/`;
- an approver-trimmed removal list;
- requests for the latest version and for an unknown version, both refused with a warning;
- bad credentials.

The version lists, checksums, deleted and kept directories and untouched decoys were all checked.

## Snapshot distributions

The node's JVM and native zips are not Maven publications, so they never go to the Maven repository. They are for
people and tools that want to run a commit without building it: testers, the wallet's managed node, scripted
installers. Building native images locally needs GraalVM and takes 10+ minutes per platform, and `dist-dev.yml`
keeps its zips as Actions artifacts for only 3 days, behind a GitHub login.

`bloxbean-snapshot-dist.yml` publishes them:

- **When it runs:** manually, only on `main` or `release/**`, and only for a `-SNAPSHOT` commit whose push CI
  passed. That is the same `require-green-ci.sh` rule as the Maven snapshots. A release version or a commit without
  green CI is rejected before the build starts.
- **Build:** it calls `dist-dev.yml`, which has a `workflow_call` trigger for this. That is the same build and the
  same packaged-catalog smoke tests as the existing dev distribution build, for the JVM zip and four native zips.
  `release-dist.yml` (GitHub releases) is untouched.
- **Approval:** after the build, a release owner approves in the `release` environment, so the reviewer approves
  zips that already built and passed their smoke tests. Then the upload job, with the `release-staging`
  credentials, publishes exactly those files.
- **Checks before upload:** the job requires exactly one JVM zip and the four native zips, all named with this
  commit's version.
- **Upload order:** zips and `SHA256SUMS` first, then `manifest.json`, then `latest.json`.
- **Immutable builds:** `manifest.json` marks a build as published, and a published build is never overwritten. A
  re-run of the same commit is refused, and a run that stopped before the manifest is completed by running again.
- **Check after upload:** `latest.json` served publicly must be byte-identical to the manifest, and every zip must
  be served at its exact size.

```text
https://repo.bloxbean.org/dist/snapshots/yano/latest.json        most recently published build's manifest.json, no-cache
https://repo.bloxbean.org/dist/snapshots/yano/0.1.0-pre18-<sha7>/
    yano-0.1.0-pre18-<sha7>.zip
    yano-native-0.1.0-pre18-<sha7>-{linux-x64,linux-arm64,macos-arm64,windows-x64}.zip
    SHA256SUMS
    manifest.json        version, commit, branch, publication time, run URL; per file: url, size, sha256
```

**One bucket for every project.** Distributions share the Maven repository's bucket and domain, under `dist/`,
mirroring `maven/`:

```text
bloxbean-maven  (https://repo.bloxbean.org)
    maven/snapshots/<group path>/...     Maven snapshots: no lifecycle rule, approval-gated cleanup
    maven/releases/<group path>/...      Maven releases (release.yml)
    dist/snapshots/<project>/...         snapshot distributions of every project: one 30-day lifecycle rule
    dist/releases/<project>/...          release distributions, if ever mirrored: permanent
```

Each project writes only `dist/snapshots/<project>/`, including its own `latest.json`. No object is shared between
projects, so their workflows need no common serialization, and a new project needs no Cloudflare setup.

A separate bucket was considered. Its only real gain is that a mistyped lifecycle rule could not reach the Maven
repository. Its token isolation is nominal: both workflows run in `release-staging`, behind the same reviewers. It
would also cost a bucket, a token, a domain and three more GitHub settings. Instead, the one lifecycle rule must be
created on exactly `dist/snapshots/`. An empty prefix applies to the whole bucket.

**Retention.** A build is about 1 GB; the JVM zip alone is about 317 MB. A lifecycle rule deletes objects under
`dist/snapshots/` 30 days after they were written. That is safe here, unlike for the Maven repository, because a
version directory is self-contained and nothing else references it. Every publication rewrites the project's
`latest.json`, which resets its age, so it outlives every build it could name. If a project publishes nothing for
30 days, its pointer and its builds expire together and the URLs answer 404 until the next publication.

**Setup.**

- Cloudflare: on `bloxbean-maven`, add one lifecycle rule that deletes objects with prefix `dist/snapshots/`
  after 30 days. Check the prefix before saving.
- Nothing else is needed. The workflow uses the existing `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`
  and `R2_ENDPOINT` of `release-staging`, and the existing `repo.bloxbean.org` domain.

**Testing.** The upload job's step scripts ran against MinIO with generated zips. Results:

- a publication passed the public checks;
- a re-publication of the same commit was refused;
- a second commit moved `latest.json` and left the first build in place;
- a missing native zip and zips of another commit both failed before upload;
- the served zip, `SHA256SUMS` and `manifest.json` agreed on every SHA-256;
- the version gate rejected `0.1.0-pre17`.

## Phase 2: releases to Maven Central and the BloxBean repository

Implemented in `release.yml`. Two flags in `gradle.properties` choose where a release's Maven artifacts go:

```properties
maven_central_publish = true   # USER_MANAGED Central deployment; publish-central.yml (approval) publishes it
bloxbean_repo_publish = true   # https://repo.bloxbean.org/maven/releases, public and permanent at once
```

Both must be set explicitly to `true` or `false`; a missing or misspelled value fails the release before the
build. With both off, a tag only builds, stages and signs. The staging checks run only when the BloxBean repository
is on.

```text
v* tag ─► validate tag and flags ─► clean fullBuild
       ─► [bloxbean] discover publications (unsigned), seed maven/releases metadata, refuse an existing version
       ─► stage and sign once: one Gradle run builds the staged repository AND the Central bundle
       ─► [bloxbean] verify: staged tree + every Central bundle file byte-identical
       ─► [central]  keep the checked bundle as the run artifact central-bundle-<version>
       ─► [bloxbean] upload ─► [central] upload that same bundle (-x nmcpZipAggregation), USER_MANAGED
       ─► [bloxbean] verify public + Gradle consumer (also after a Central failure)
release-dist.yml / release-docker.yml: unchanged
```

Rules:

1. **Same artifacts in both places.** One Gradle run produces the staged repository and nmcp's Central bundle
   from the same jar and signature outputs. `verify-maven-staging.py --central-bundle` then requires two things:
   every file in the bundle is byte-identical to the staged one, and every staged jar, POM, module and signature is
   in the bundle. The Central upload runs `publishAggregationToCentralPortal -x nmcpZipAggregation`. Its task graph
   is the scope check, the upload and its alias: nothing compiles, signs or zips again, so Central receives the
   checked bundle.
2. **Uploads first, checks after.** The BloxBean repository is uploaded first, then Central. The public checks
   and the Gradle consumer run only afterwards, so a flaky check can never keep a release off Central. They still
   run when the Central upload failed, because the BloxBean copy is then the release's only copy. The run summary
   reports what each destination actually received.
3. **Releases are immutable.** The artifact metadata is seeded from `maven/releases` as for snapshots. A version is
   refused if any object exists under its directory, which covers a run that stopped before writing its metadata,
   or if the seed already lists it. Releases carry the same `.asc` signatures as on Central, and every jar, POM and
   module must be signed.
4. **Visibility.** The BloxBean copy is public as soon as the tag's run uploads it, while Central waits for a
   human. The tag itself already required a release owner's approval (`tag-release.yml`). Like a pushed Docker
   image, a version that reached the BloxBean repository is permanent. Accepted by the release owners on
   2026-10-04.
5. **Central quota.** When Central cannot take a release, set `maven_central_publish = false` and release the
   patch to the BloxBean repository only. Such a release stays BloxBean-only; the next version goes to Central
   once the quota resets. There is no workflow to copy a BloxBean-only release to Central later (decided
   2026-10-04).
6. **Central fails after the BloxBean upload.** Re-running the tag is then refused, because the BloxBean repository
   already has the version. The run keeps the checked Central bundle as the artifact `central-bundle-<version>`
   for 90 days. To put the same bytes on Central, upload that zip in the Central Portal, then publish it as usual.
   It is a manual step, not a workflow, and the run summary says so when it applies.
7. **Serialization.** Seeding, staging and uploading is a read-modify-write of each artifact's version list.
   The publish job therefore holds the `bloxbean-maven-releases` concurrency group, and releases of different tags
   run one at a time. A pending release run has uploaded nothing. If GitHub cancels it because a third release
   queued, run it again.

**Release layout.** The validator checks releases differently from snapshots:

- There is no version-level metadata, and file names are not timestamped.
- The artifact metadata must name the version as `<release>`.
- Classifiers beyond `sources` and `javadoc` are allowed: `yano-archive-core` publishes a `test-fixtures` jar. The
  bundle check ties that set to Central's.
- The consumer check compares jars by the group and module Gradle resolved, never by file name. Dependencies such
  as julc use the same `0.1.0-preN` version scheme, so a file name ending in the release version is not proof that
  the jar is Yano's.

**Testing.** Before merging, the release job's step scripts ran in a JDK 25 container against MinIO, signing with a
throwaway key. `fullBuild` and the Central upload were skipped:

- **First release:** 1,260 files staged, all 570 Central bundle files byte-identical, the public metadata and POMs
  matched, and a consumer resolved 9 identical jars.
- **The same version again:** refused for all 24 artifacts.
- **The next version:** appended, with `<latest>` and `<release>` moving to it.
- **`bloxbean_repo_publish=false`:** every BloxBean step skipped, and nothing reached the bucket.
- **`maven_central_publish=false`:** BloxBean-only, and the summary says so.
- **An invalid flag value:** failed before the build.
- **The snapshot workflow on the shared script:** still published and resolved.

The validator also rejected an unsigned jar and a jar that differed from the bundle. The Central upload with
`-x nmcpZipAggregation` was checked with `--dry-run` only: it runs nothing but the scope check, the upload and its
alias.

## Discoverability and the release-ops standard (2026-10-04)

**Users find snapshots in three places.** None of them commits to the repository:

- **Development snapshot release.** A GitHub pre-release with tag `snapshot`, kept current by
  `.github/scripts/update-snapshot-release.sh`. It runs in a `github-snapshot` job after both snapshot workflows;
  that job is the only one with a write token and never sees the bucket credentials. The page shows:
  - the latest Maven snapshot version and a Gradle snippet;
  - the latest distribution zips with sizes and SHA-256, linked to the bucket.

  It is created once, then edited, and never marked Latest. Its tag moves to the newest published commit, and only
  `v*` tags are protected or trigger release workflows.
- **README badge.** A shields.io `maven-metadata` badge with `strategy=latestProperty`, read from
  `repo.bloxbean.org`.
- **README section** on using the snapshot repository. `build.gradle` also resolves BloxBean snapshots from it,
  next to the Central snapshot repository.

**The scripts are now the release-ops standard.** They live in `bloxbean/release-ops` as
`docs/12-bloxbean-maven-repository.md` and `templates/scripts/`, identical in every repository and configured by
environment:

| Setting | Purpose |
|---|---|
| `CI_WORKFLOWS` | which push CI workflows gate publishing |
| `CONSUMER_PLATFORM`, `CONSUMER_DEPENDENCIES`, `CONSUMER_REQUIRED` | what the Gradle consumer check resolves |

Two rules follow from groups shared between repositories, such as `com.bloxbean.cardano`:

- the consumer check takes only this build's artifactIds from the BloxBean repository (`includeModule`);
- the cleanup discovers the build's artifactIds and touches nothing else.

`bloxbean-dist.sh` holds the generic distribution steps; only the expected-files check stays in the workflow.

## Reuse by other BloxBean projects

No shared workflow yet. It will be extracted once two or three projects run their own copy. The stable inputs
are already visible:

| Input | Yano value |
|---|---|
| Gradle staging command | `publishAllPublicationsToStagingRepository -PstagingRepository=<dir>` |
| Local repository path | `build/bloxbean-maven` |
| Group path owned | `org/yanoproject` |
| Prefix | `maven/snapshots` or `maven/releases` |
| Public repository URL | `https://repo.bloxbean.org/maven/<prefix>` |
| Concurrency group | `bloxbean-maven-snapshots` (one per repository and prefix) |

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
- Snapshot storage grows until someone runs the approval-gated cleanup.
- A run takes minutes, not a second `fullBuild`, because it reuses the commit's CI result. Publishing on every
  `main` push later would be a `workflow_run` trigger on the CI workflows completing, with the same gate.

## Implementation status

| Item | State |
|---|---|
| Analysis of current publishing | Done (this ADR) |
| Local staging | Existing `stagingRepository` hook, no Gradle change |
| `bloxbean-snapshot.yml` + `verify-maven-staging.py` | Implemented, dry-run against MinIO |
| R2 secrets, variables, bucket | Configured in `release-staging` |
| First run on `main` | Done 2026-10-04: `0.1.0-pre18-de81cc5-SNAPSHOT`, all checks green |
| Gate on the commit's CI instead of `fullBuild` | Implemented |
| Snapshot distributions (`bloxbean-snapshot-dist.yml`) | Implemented, dry-run against MinIO; needs the `dist/snapshots/` lifecycle rule |
| `push: main` trigger | After the manual run is proven |
| Maven-aware retention | Implemented: `bloxbean-snapshot-cleanup.yml`, manual and approval-gated |
| Releases to Central and the BloxBean repository (Phase 2) | Implemented: `maven_central_publish` / `bloxbean_repo_publish` in `release.yml` |
| Shared reusable workflow | After two or three projects use it |
