# cardano-blueprint ledger conformance vectors (vendored)

Conway-era ledger conformance vectors from
[cardano-blueprint](https://github.com/cardano-scaling/cardano-blueprint), vendored here so that the ADR-056
Phase 7b gate (`BlueprintVectorGateTest`) runs in every build with no download.

The vectors are dumped from the Haskell cardano-ledger Imp test suite. Each vector holds the ledger state before,
the events (transactions, slot ticks, epoch changes), the ledger state after, and the test title.

## Pinned version

| | |
|---|---|
| Source | cardano-scaling/cardano-blueprint [PR #71](https://github.com/cardano-scaling/cardano-blueprint/pull/71), "update ledger rules conformance test vectors" (not merged as of 2026-09-29) |
| Commit | `d57b8d765395e54645824b6718b02c6565bc0ffc` (head of PR #71, branch `ledger-rules-conformance-vectors` in `KtorZ/cardano-blueprint`) |
| File | `src/ledger/conformance-test-vectors/vectors.tar.gz` |
| Tarball sha256 | `5041539c9e2908cc897512f249ea16ec26bbb04cb1ec288dc59f47024a9b727d` |
| Corpus digest | `39bf59ed66e31d7e61f7f9c41e924905d0795c0ae4324590d3a6bfe072a05ed2` (`BlueprintVectorLoader.CORPUS_DIGEST`) |
| Contents | 320 vectors under `eras/`, 44 protocol-parameter records under `pparams/` |
| Vendored | 2026-09-29 |

The same 364 files, byte for byte, are in Amaru at tag `v10.11.20260925`
(`crates/amaru-ledger/tests/data/rules-conformance`), the tag `amaru-validator-wasm/AMARU_VERSION` pins.
cardano-blueprint `main` (`0f0c17e1` at the time of vendoring) still carries an older tarball in a different format
(one JSON file per transaction), which the loader does not read.

The corpus digest is a sha256 over `<relative path>\n<sha256 of the file>\n` for every file under `eras/` and
`pparams/`, in path order. The tests check these files against it, so an edit here fails the gate until the pin
is updated.

## Where to look for newer vectors

Note for AI agents and maintainers: when asked to update these vectors, or to check whether newer ones exist, look
in these places, in this order, and compare what you find with the pin above. Newer vectors are only worth taking in
the binary `[config, NewEpochState, NewEpochState, events, title]` format (a directory with `eras/` and `pparams/`);
the older JSON format on cardano-blueprint `main` is not read by the loader.

1. **cardano-blueprint PR #71.** Is it merged, or does it have a newer head commit?
   `gh api repos/cardano-scaling/cardano-blueprint/pulls/71 --jq '{state,merged,merge_commit_sha,head:.head.sha}'`
   If merged, re-pin to the commit on `main` (a merged commit does not depend on the fork).
2. **cardano-blueprint `main`.** Has `src/ledger/conformance-test-vectors/vectors.tar.gz` changed, or has a
   successor PR replaced #71?
   `gh api 'repos/cardano-scaling/cardano-blueprint/commits?path=src/ledger/conformance-test-vectors&per_page=5' --jq '.[] | {sha,date:.commit.committer.date,message:.commit.message}'`
   `gh pr list -R cardano-scaling/cardano-blueprint --search 'conformance vectors' --state all`
3. **Amaru.** Amaru vendors the vectors it tests against under
   `crates/amaru-ledger/tests/data/rules-conformance` (with `eras/` and `pparams/`). Check the latest release tag,
   and whether its files differ from ours (compare the corpus digest, computed as below).
   `gh release list -R pragma-org/amaru --limit 5`
   `gh api 'repos/pragma-org/amaru/commits?path=crates/amaru-ledger/tests/data/rules-conformance&per_page=5' --jq '.[] | {sha,date:.commit.committer.date,message:.commit.message}'`
   Amaru's copy can also be vendored directly: point `update.sh` at a tarball of that directory, or copy its
   `eras/` and `pparams/` here and recompute the digest. Prefer an Amaru tag that matches
   `amaru-validator-wasm/AMARU_VERSION`, so that the Amaru reference engine and the vectors move together.
4. **The generator.** The vectors are dumped from the cardano-ledger Conway Imp tests by a patched cardano-ledger
   (see the README next to the tarball in cardano-blueprint, and
   [cardano-ledger#4892](https://github.com/IntersectMBO/cardano-ledger/issues/4892)). If the ledger version Yano
   follows (`adr/reports/adr-056-haskell-pinned-revisions.md`) has moved well past the one the vectors were dumped
   from, look for a regenerated set there, or regenerate following that README.

Take a newer set only after reading what changed (new vectors, removed vectors, a format change) and record the
source in the table above.

## Updating

When newer vectors are found (see above):

```bash
# from a URL or a local tarball
ledger-conformance/vectors/cardano-blueprint/update.sh <vectors.tar.gz URL or path>
```

The script downloads the tarball, prints its sha256, replaces `eras/` and `pparams/` with its contents, and prints
the new corpus digest and file counts. Then:

1. Update the table above (source, commit, tarball sha256, corpus digest, counts, date).
2. Update the pin constants in `BlueprintVectorLoader` (`ledger-rules/src/testFixtures/.../blueprint/`):
   `BLUEPRINT_COMMIT`, `TARBALL_SHA256`, `CORPUS_DIGEST`, `EXPECTED_VECTORS`, `EXPECTED_PPARAMS`.
3. Run `./gradlew :ledger-conformance:test --tests '*BlueprintVectorGateTest' --tests '*NewEpochStateDecoderTest'`,
   triage every change in the pinned counts and mismatch indexes against Haskell, and update the pins in
   `BlueprintVectorGateTest` and `amaru-known-failures.txt`.
4. Regenerate `ledger-conformance/docs/blueprint-vectors-2026-09.md` with `./gradlew :ledger-conformance:conformanceReport`
   (see `ledger-conformance/build.gradle`).

## License

The vectors come from cardano-blueprint, licensed under the Apache License 2.0; see `LICENSE` in this directory and
`NOTICE`.
