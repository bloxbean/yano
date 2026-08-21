# ADR-039 Phase 5 — artifact reconstructibility and identity

**Analysis before implementation, per the directive that epoch artifacts must not be assumed
enable-able later without rebuilding.** Written from the existing epoch pipeline
(`EpochArchiveStagingSink`, `EpochBoundaryProcessor`, `EpochArchiveStagingService`,
`StandardEpochDatasets`) rather than from the ADR's description of it.

## The question that decides everything

For each artifact: **can it be recomputed later from data the node still retains, or is it a
measurement of state that exists only at the boundary instant?**

The distinction is not "is it expensive to recompute" but "is the input still there". An
artifact whose inputs are pruned, superseded, or never persisted is *irreproducible*, and an
archive that lacks it can never be completed — only rebuilt from genesis.

## Per artifact

| artifact | source today | reconstructible later? | therefore |
|---|---|---|---|
| **EPOCH_STAKE** | `EpochBoundaryProcessor` snapshot at the transition | **Yes, from a retained generation.** The stake distribution is a function of the UTXO set and delegation state at the boundary, and the node persists the delegation snapshot it used. This is the ADR's chosen first slice precisely because both representations — staged rows and an immutable persisted generation — exist and can be compared. | implement first; A/B the two representations before choosing |
| **ADA_POT** | `EpochBoundaryProcessor` | **Yes, but only with the boundary's own inputs.** Treasury/reserves/fees are the *output* of the transition calculation; recomputing needs the pre-transition pot state plus that epoch's fees and deposits. The node has them at the boundary and does not retain them indefinitely. | capture at the boundary; small and bounded, so evidence rather than a staged file |
| **REWARD** | `EpochRewardCalculator` | **No, not safely.** Rewards are the output of a long calculation over a snapshot taken two epochs earlier, parameterised by protocol version and by the calculation's own version. ADR-039 already says to add these only after proving complete deterministic input closure *and* calculation-version closure. Neither is proven. | capture as **non-reconstructible evidence**; never claim completeness without it |
| **DREP_DISTRIBUTION** | boundary state | **Partly.** The amount is a distribution over the same stake inputs, but `storedExpiry`, `dormantEpochs`, `effectiveExpiry` and `active` are boundary-time DRep-state columns that later state overwrites. | stream the amount from the persisted generation; atomically snapshot the state columns |
| **GOVERNANCE_PROPOSAL_STATUS** | boundary observation | **No.** `observationPhase`, `statusCode` and `decisionReason` are decisions made *at* a boundary about ratification and expiry. Later state records the outcome, not the observation that produced it. | irreproducible; requires an immutable source before it can be claimed complete |

Two of five (**REWARD**, **GOVERNANCE_PROPOSAL_STATUS**) are irreproducible, and one
(**DREP_DISTRIBUTION**) is half. That is the fact that forces the identity question below.

## Identity: why the fingerprint must cover artifacts

The block-section fingerprint is
`network | sink | canonicalProjectionVersion | sorted section wire names`. It does **not**
mention artifacts, because artifacts are referenced from an envelope's artifact list rather than
carried as a section.

That is convenient — adding epoch artifacts does not invalidate an existing four-section archive —
and it is **exactly why it is dangerous**. Without a change, a node configured to capture epoch
stake and a node configured to capture nothing produce archives with the *same* fingerprint. The
second would then report itself complete for an artifact it never captured and, for rewards and
governance status, could never reconstruct.

**Decision: a separate durable artifact-contract identity**, not an extension of the section
fingerprint. Reasons:

1. **Different lifecycle.** Sections are fixed for the life of an archive; an artifact set can
   legitimately grow *if and only if* every artifact added is reconstructible from retained data.
   Folding both into one string would forbid the safe case along with the unsafe one.
2. **Different failure mode.** A missing section is discovered by a query returning nothing. A
   missing irreproducible artifact is discovered when someone asks for epoch N's rewards years
   later, and by then the inputs are gone.
3. **It must record capture semantics, not just names.** `epoch-stake:v2` is not enough; the
   archive must record *how* it was captured, because a staged-file capture and a
   persisted-generation reference have different recovery classes.

The artifact contract therefore records, per artifact: dataset id, schema/codec version,
representation (`STAGED_FILE` / immutable generation reference / atomically persisted evidence),
and whether the archive holds it as reconstructible or as evidence.

## Fail-closed rules this implies

1. An archive **must not** report coverage for an epoch whose required artifacts it did not
   capture. Absence is reported as absence, never as an empty result.
2. A node configured with a **larger** artifact set than the archive records may proceed **only**
   for artifacts that are reconstructible from retained data; encountering an irreproducible one
   fails closed with the epoch it cannot satisfy.
3. A node configured with a **smaller** set than the archive records fails closed: it would
   otherwise silently stop maintaining artifacts the archive claims.
4. Changing an artifact's representation or codec version is a **rebuild**, exactly as a section
   version bump is.

## Consequence for the four-section preprod archive

It records no artifact contract. Under rule 1 it is complete for its four block datasets and
**explicitly incomplete for every epoch artifact** — it cannot be upgraded in place to a
Phase 5 archive for rewards or governance status, and the final production validation therefore
needs a fresh sync with the artifact set enabled from genesis. That is the intended cost of
fail-closed, and it is better paid knowingly now than discovered at the first query.
