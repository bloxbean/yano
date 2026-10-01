# ledger-rules

Yano's transaction-validation API and the Java Conway ledger rules
([ADR-056](../adr/056-conway-java-ledger-rules-with-state-overlays.md)). This page is for contributors changing the
rules. The rules follow Haskell's `cardano-ledger` at the pinned revision (`f649f975`). Haskell decides: every check cites
the Haskell lines it mirrors.

## How the Conway rules are organised

The rules are versioned by protocol version (ADR-056 Phase 5c). A new protocol version is added as a delta, and the
rules of earlier versions are never edited.

| Piece | What it is | Where |
|---|---|---|
| **Unit** (`RuleUnit`) | One Haskell predicate check (`PredicateCheck`) or one state step (`StateStep`). It has a stable id (`RULE.Constructor`, or `RULE.Constructor#suffix` when a constructor is checked in several places, or `RULE.step`), a static/dynamic label and a Haskell reference. Units are stateless named classes. | `conway/<family>/*Checks.java` |
| **Policy** (`RulePolicy`) | A versioned function rather than a check, e.g. a new DRep's expiry. | `conway/certs/DRepExpiries.java` |
| **Scope** (`Scope`) | An ordered list of units inside one Haskell rule, or one branch of it (`UTXO`, `DELEG.ConwayRegCert`, `GOV.proposal`, …). Each scope has one narrow subject type: `UtxoSubject`, `CertSubject`, `ProposalSubject`, …. | `conway/ruleset/ConwayScopes.java` |
| **Family runner** | Builds the subjects and runs the scopes in Haskell's nesting (`MempoolRule`, `LedgerPreChecks`, `CertsRule`, `GovRule`, `UtxowRule`, `UtxoRule`, `UtxosRule`). `RuleFrame` keeps the failure-list order that small-steps produces. A runner has no protocol-version branches. | `conway/<family>/*Rule.java`, `conway/RuleFrame.java` |
| **Rule set** (`ConwayRuleSet`) | For one protocol version: every scope's units in execution order, plus the policies. | `conway/ruleset/` |
| **Base + deltas** | `pv9 = ConwayBaseRules` (the bootstrap phase), `pv10 = pv9.with(ConwayDelta10)`, `pv11 = pv10.with(ConwayDelta11)`. A delta can **add** a unit (at a position relative to an existing id), **supersede** a unit by id with another implementation, **retire** a unit, or **supersede** a policy. | `conway/ruleset/ConwayDelta*.java`, `ConwayRuleSets.java` |

`JavaLedgerValidationEngine` picks the rule set from the ledger protocol major of the state being validated
(`ValidationEnv.ledgerProtocolMajor`). If no rule set exists for that version, it returns `ENGINE.EraNotSupported`
(fail closed). The engine-neutral rules that other engines share (`MempoolRule`, `TxEffectsDeriver`) use
`ConwayRuleSets.forProtocolOrLatest`. It returns the latest rule set for a version newer than the latest and logs a
WARN once per such version, so a new protocol version's rollout is visible. It throws for a version before Conway
(PV < 9).

A unit does not declare its protocol versions. Its versions come from the composition: they start at the version whose
delta introduced it and end before the delta that retired or superseded it (`ConwayRuleSets.versionsOf`). When
`ConwayRuleSets` loads, it checks the composition:

- deltas are consecutive, and every id a delta names exists;
- units and policies are named classes;
- each version's rule set reports a constructor exactly when the pinned catalogue says the constructor exists at that
  version (`ConwayPredicate.pvRange`).

`ConwayRuleSetsTest` adds a per-scope check. From one version to the next, the scopes that report a constructor, or
that hold a unit, may change only where the delta names the scope. A unit that a delta supersedes or retires must be
superseded or retired in every scope that holds it.

**What the frozen manifests pin.** The manifests (`src/test/resources/…/ruleset/conway-pv<N>.manifest`) pin the
composition and each unit's content. The `content` column is a digest of the unit's source (`SourceFingerprints`). The
digest covers:

- the class;
- its superclasses;
- the non-type members of those classes' files, i.e. the shared helpers.

Comments and whitespace don't count. Behaviour is pinned by the per-protocol-version gates (below), but only where a
mutant or scenario exercises it. Subjects, the `tx` decoders and helpers in other files are not in the digest.

**An empty `ConwayDeltaN` passes the composition check.** If no catalogue range changes at N, nothing forces a delta to
contain anything. The Haskell gate review in step 1 of the checklist is the only guard that PV N needs changes.

## Changing the rules

**Units of a released protocol version are immutable.** There is one exception: a Haskell-cited bug fix that applies to
every version containing the unit. For such a fix, edit the unit, add a test, and regenerate the manifests. Every
manifest that contains the unit then changes its `content`. Regenerating is the explicit "I mean all these versions"
act, so review each affected manifest.

Everything else is a superseding unit in the newest delta.

**A change that applies only from protocol version N:** do not edit the existing unit, because earlier versions still
use it. Write a new unit class that cites the Haskell hard-fork gate, and add it, supersede with it, or retire the old
one in `ConwayDeltaN`. If the difference is a table (for example `ppuWellFormed`'s non-zero keys), a new instance with
the new parameters is enough. Its `variant()` shows the parameters in the manifest.

**Don't:** branch on `protocolMajor` inside a unit or a runner; use a lambda or anonymous class as a unit; keep per-run
state in a unit (the subject carries it).

## How to add a protocol version (N)

1. **Haskell:** list every `hardfork…` gate and every `pvMajor` comparison that changes at N in the pinned
   `cardano-ledger` (Conway/Era.hs, the rules, `ppuWellFormed`, TxInfo, plutus-ledger-api `builtinsAvailableIn`).
2. **Catalogue:** update the constructor ranges (new constructors, constructors that end) in
   `src/testFixtures/…/conway-constructors.json` and `ConwayPredicate`. `ConwayPredicateCatalogueTest`
   keeps them in step.
3. **Delta:** create `ConwayDeltaN` (add, supersede or retire; new unit classes with Haskell references) and register it
   in `ConwayRuleSets.DELTAS`. If the composition check fails, it names what is missing or stale. A new certificate kind
   or branch is a new scope in `ConwayScopes.ALL`, filled by the delta. The base leaves it empty, and manifests omit
   empty scopes.
4. **Manifest:** run `./gradlew :ledger-rules:test --tests '*ConwayRuleSetManifestTest' -PupdateRuleManifests=true`.
   Review the diff:
   - `conway-pvN.manifest` is new;
   - **no earlier manifest may change**, whether in composition or in the `content` digests;
   - a new scope appears only in `conway-pvN.manifest`.
5. **Mutation world:** add N to `Mutations.WORLDS` (`MutationWorld.view(N)` builds the world) and to the pinned counts
   (`MutationWorldMatrixTest.PINNED`). Add mutants for the new constructors, and a `WORLD_EXPECTATIONS` entry (with its
   Haskell gate) for each mutant that Haskell judges differently at N. `Mutations.worldCases` refuses a replay whose
   failure list does not exist at N.
6. **Coverage:** every constructor that exists at N must be covered at N (`CoverageMatrixTest`, strict), by the mutation
   world matrix or by a `@Covers(value = …, pv = {N})` test.
7. **Oracles:** refresh the Amaru module and scenarios when Amaru supports N, and pin the new revisions
   (`adr/reports/adr-056-haskell-pinned-revisions.md`, `ConformanceSettings.AMARU_TAG`). Add the new scenario versions
   to `JavaEnginePhase5GateTest.PER_VERSION`.
8. **Things outside the rule sets:** check `PlutusScriptDecoder` (builtins and limits per version, as in
   plutus-ledger-api), the phase-2 evaluator, and the protocol-parameter decoding.
9. **Record it:** add an ADR-056 entry with the gates, the delta, the test counts and any divergences.

A **new era** is not a delta. Its transition has a different structure. Add a new transition skeleton with its scopes,
subjects, catalogue and base rule set (`<Era>BaseRules`), reuse units wherever Haskell reuses the rule, and extend the
engine's selection to it. The versioned deltas within the era then work exactly as they do for Conway.

## Tests

```
./gradlew :ledger-rules:test                        # unit tests, ConwayRuleSetsTest, the frozen manifests
./gradlew :ledger-conformance:test \
    -PamaruScenariosDir=<amaru>/crates/amaru-ledger/tests/data/transaction \
    -PwithAmaru=true -PamaruWasm=<amaru_validator.wasm>   # gates per protocol version, mutation world matrix, coverage
./gradlew :ledger-conformance:conformanceReport …   # regenerates docs/conway-rule-coverage.md and the baseline
```
