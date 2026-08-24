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

## Corrections after review (2026-08-21)

Three of the classifications above were wrong or incomplete. Recorded rather than silently
edited, because the reasoning that produced them is the part worth not repeating.

### ADA_POT is RECONSTRUCTIBLE, not BOUNDARY_INPUTS_ONLY

The original reasoning was that ada pots are the transition's *output*, so recomputing them would
need the pre-transition pots plus that epoch's fees and deposits — inputs the node does not keep.
That reasoning is sound and irrelevant: **the computed pot is persisted per epoch under its own
key and no code path deletes it**, verified by searching every use of `adaPotKey`. There is
nothing to recompute, because the value is still there.

The lesson generalises: reconstructibility is a question about what the node *retains*, and the
answer lives in the storage layer, not in the derivation's shape.

### RECONSTRUCTIBLE does not license adding an artifact to an existing archive

The first implementation let any artifact classed `RECONSTRUCTIBLE` be added to an archive that
lacked it. That conflates two questions:

- **is this kind derivable from retained state?** — a property of the dataset;
- **does *this node, today* still hold the sources for the epochs that are missing?** — a
  property of the deployment.

Epoch stake separates them sharply: it is a function of the delegation snapshot, and those
generations are **pruned unless an archive lease has protected them since genesis**. On a normal
node it is derivable in principle and unavailable in practice. Deciding from the class alone
would produce an archive that claims epochs it can never fill — discovered only when someone asks
for one.

Coverage is now proved separately through `ProjectionArtifactCoverage`, defaulting to `NONE`.
Being wrong in that direction costs a rebuild that may not have been needed; being wrong the
other way is undetectable until it matters.

### DREP_DISTRIBUTION: whole dataset at its strictest class

Its provenance is genuinely mixed — `amount` is reconstructible, while `storedExpiry`,
`dormantEpochs`, `effectiveExpiry` and `active` are boundary state that later state overwrites.
One contract per dataset cannot express that.

**Decision: capture the whole dataset together, classed at its strictest column.** Composite
components with independently versioned representations were considered and rejected as the
default: they double the identity surface and make partial capture *expressible*, which invites
an archive holding amounts without state columns — a row that cannot be emitted at all. Splitting
remains available if measurement shows capturing the reconstructible half separately is
materially cheaper; it is not being paid for speculatively.

### ATOMIC_EVIDENCE added

The representation set lacked the case the design actually relies on for small irreproducible
boundary observations: facts written atomically into the contributor's own batch, with no staged
file and no retained generation behind them. Governance proposal status is the clearest example —
there is nothing to reference later, so the evidence *is* the artifact, and it is durable only
because it committed with the canonical state it describes.

## Measurements this phase must produce

Recorded here so they are collected with the slice rather than reconstructed afterwards:

- **DuckLake write throughput for EPOCH_STAKE and ADA_POT** — rows/s and bytes/s into the sink,
  separately from block-section throughput, since an epoch artifact arrives as one large burst at
  a boundary rather than as a steady stream.
- Epoch transition latency with and without artifact capture, to show capture does not delay the
  authoritative transition.
- Retained bytes per artifact and the resulting lease/clamp footprint.
- The persisted-generation versus staged-file A/B the ADR requires before choosing a
  representation for epoch stake.
