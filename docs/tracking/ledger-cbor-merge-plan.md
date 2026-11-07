# Merge plan: ledger-cbor reader, Dijkstra models and Amaru removal

Tracking only. This file lives on a `DONT MERGE` branch, and the PR that holds it is the umbrella for the work below.
Tick items in the PR description as they land.

Across three repositories: CCL (bloxbean/cardano-client-lib), Yaci (bloxbean/yaci) and Yano (bloxbean/yano).

## Phase A: independent, can merge now

| Step | Repo | Item | Action |
|---|---|---|---|
| A1 | Yano | #240, Amaru bump to `v10.11.20261105`, plus the ledger-rules report fix 4d416510c | Review, then merge with **"Create a merge commit", not squash**, because #241 is built on its commits |
| A2 | Yano | #241, remove the Amaru validator (ADR-062) | Retarget to `main` after A1 (automatic if #240's branch is deleted, else `gh pr edit 241 --base main`); wait for CI, including the first run of `ledger-conformance.yml`; merge |
| A3 | Yano | #226, stop attaching the wasm module to releases | Close without merging after A2: #241 deletes that workflow |
| A4 | CCL | #747, rerun the CBOR conformance test when the dataset changes | Independent; merge any time |

## Phase B: the ledger-cbor reader, in this order

Each step needs the release made in the previous one.

| Step | Repo | Action |
|---|---|---|
| B1 | CCL | #748 (draft; 57 commits on `master`, none behind): update the title (it covers M0 to M7c and ADR 0004, not only M0 to M6); approve the behaviour differences (ADR 0003 sections 7.2 and 7.3, ADR 0004); mark ready; final review; merge |
| B2 | CCL | Release the next pre-release with the reader (0.8.0-pre8) |
| B3 | Yaci | Branch `feat/ledger-cbor-reader` (local, on `next`, 20 commits): set CCL to the B2 release; rebase on the latest `next` (including #238); run the core and helper tests and a native-image build (the reflect-config changed); push; PR to `next`; review; merge |
| B4 | Yaci | Release 0.5.0-pre16 |
| B5 | Yano | Branch `feat/ledger-cbor-reader` (local, 29 commits): rebase on `main` after Phase A (expect conflicts with #240 and #241 in `AmaruCorpusNames`, `ConformanceSettings` and the ADR-056 and ADR-060 text); set CCL pre8 and Yaci pre16; fix the `conway3.block` test fixture; run all four conformance reports with the newest upstream data; Fable and Codex review; push; PR to `main`; merge |

## Decisions needed before B1 and B5

- Approve the behaviour differences listed in ADR 0003 sections 7.2 and 7.3 and in ADR 0004.
- I10: whether Yano's strict reading path is worth about +30% time per Conway block.
- Indefinite-length headers: deployed nodes reject them, the newest ledger accepts them, Yano accepts them.
- Byron wide-head address: the test-harness writer fix.

## Risks

- The Yaci and Yano `feat/ledger-cbor-reader` branches exist only on one machine.
- No native image has been built with the new Yaci models (B3).

## Open, not blocking

- CCL #749: aux data drops Plutus V3 scripts with metadata; rationals with bignum or negative parts.
- Yaci #240: certificate JSON writes `"type"` twice.
- Yaci #242: handshake `VersionData` equality ignores `networkMagic`.
- Yano #238: `isMultiAssetSmallEnough`.
- Yano #239: what Yano needs before it can follow a Dijkstra chain.
- Yano #242 (low priority): check the conformance mutants against the Haskell ledger.
- Reported upstream offline:
  - node-diversity fixture 00280's expected name (Yano keeps a temporary alias until it changes);
  - the cardano-cbor-dataset label `bootstrap-witnesses-empty`;
  - the aux-data decoding difference between the dataset and the node (64 samples).
