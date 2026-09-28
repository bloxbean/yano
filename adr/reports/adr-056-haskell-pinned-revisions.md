# ADR-056 Phase 1: pinned Haskell references

Researched 2026-09-28. Clones sit next to this file:
`cardano-ledger/` is checked out at `f649f975`, `ouroboros-consensus/` at `82ecba32`.

## 1. Target cardano-node

| Source | Version found |
|---|---|
| Yano release-QA harness: `qa/release-qa.sh:127`, `qa/harness/common.sh:16` → `$REPO/test-data-dir/haskell-node/bin/cardano-node`. In the main checkout `/Users/satya/work/bloxbean/yano/test-data-dir/haskell-node` | **11.0.1**, git rev `97036a66` |
| `/Users/satya/work/cardano-node/compatibility-node-test/{haskell-node,haskell-node-preprod}/bin`, plus `devnet/bin`. README says "Haskell cardano-node 10.5.2" | 10.5.2, git rev `1ec98e95` (older compatibility folder from the Yaci era) |
| Yano ADR-028 tracker | Mentions the "PV10 overlay for cardano-node 10.5.2" (historical) |
| GitHub `IntersectMBO/cardano-node` latest release (`gh release list`) | **11.1.2**, 2026-09-17, marked "Latest". 11.1.1 was released 2026-09-08 and 11.1.0 was a pre-release. |

**Decision: pin cardano-node 11.1.2**, the current mainnet release. Tag object
`2e00ec99`, commit `fef83fed01d7926f3de83b3b917be5a4a48768b5`.

The release-QA harness still runs 11.0.1. Section 1b shows that the Conway
transaction-rule semantics are identical between the two pins. Two differences
remain: the refactor to a state-annotated transaction (`StAnnTx`), and the
`reapplyValidatedTx` API that exists only in 11.1.2 and is not wired into
consensus. Upgrading the QA node to 11.1.2 is recommended but not required for
the rule semantics.

## 1a. Exact package versions

`cabal.project` sets only loose bounds, with index-state CHaP
`2026-09-16T23:53:07Z`. The resolved versions below were extracted from the
official release binary (`cardano-node-11.1.2-macos-amd64.tar.gz`, SHA-256
`2520238a…9a83`, which matches the published sha256sums) using `strings`. Each
version was mapped to a commit through CHaP `_sources/<pkg>/<ver>/meta.toml`.

| Package | 11.1.2 (PINNED) | cardano-ledger / consensus commit | 11.0.1 (QA harness) |
|---|---|---|---|
| cardano-ledger-conway | **1.23.0.0** | `f649f9751074d2ab3de033fc3912f29c9862c1f5` (tag `cardano-ledger-conway-1.23.0.0`, 2026-07-29) | 1.22.1.0 @ `226b002d` |
| cardano-ledger-babbage | 1.14.0.0 | `f649f975` | 1.13.0.0 @ `30a3e666` |
| cardano-ledger-alonzo | 1.16.0.0 | `f649f975` | 1.15.0.1 @ `94e9618c` |
| cardano-ledger-mary | 1.11.0.0 | `f649f975` | 1.10.0.0 |
| cardano-ledger-allegra | 1.10.1.0 | `0372ead6df89a7bf52eb8fc4ea9f6633366f91c7` (2026-09-16; only Timelock memory: `Allegra/Scripts.hs`, descendant of `f649f975`) | 1.9.0.0 |
| cardano-ledger-shelley | 1.19.0.1 | `4c81e909df7555b6a58a000db8f8f4f89c7acdc4` (no git tag; CHaP meta. Diff vs `f649f975` touches only `Shelley/Transition.hs`, the initial-funds forcing) | 1.18.1.0 @ `b7c17cf3` |
| cardano-ledger-api | 1.14.0.0 | `f649f975` | 1.13.0.0 @ `30a3e666` |
| cardano-ledger-core | 1.21.0.0 | `f649f975` | 1.20.0.0 @ `94e9618c` |
| cardano-ledger-binary | 1.9.0.0 | `f649f975` | 1.8.1.0 |
| cardano-ledger-dijkstra | 0.3.0.0 | `f649f975` | 0.2.0.1 |
| cardano-protocol-tpraos | 1.6.0.0 | – | 1.5.0.0 |
| ouroboros-consensus (single package with `cardano`, `diffusion`, `protocol`, `lsm` sub-libraries; there is no separate ouroboros-consensus-cardano package any more) | **4.2.1.0** | `82ecba329d7d054340bf707d44fe6e9ac27cec40` (tag `release-ouroboros-consensus-4.2.1.0`, 2026-09-01) | 3.0.1.0 @ `c87aa760` |
| ouroboros-network | 1.2.0.0 | – | 1.1.0.0 |
| plutus-core / plutus-ledger-api | 1.65.0.0 | – | – |

**Canonical rule revision: cardano-ledger `f649f975`.** Every rule file cited
below is byte-identical at `4c81e909` and `0372ead6`, apart from the files noted
above. In this document, `CL/` means `cardano-ledger@f649f975` and `OC/` means
`ouroboros-consensus@82ecba32`.

## 1b. 11.0.1 vs 11.1.2 rule semantics

The rule sources show about 2.6k changed lines. Most of the change is the
`StAnnTx` refactor (cardano-ledger #5946) and the Conway UTXOS state moving into
UTXO. Checked mechanically:

- Every Conway predicate-failure `data` declaration is identical. The only
  change is `ValidationTagMismatch IsValid` → `IsPhase2Valid`, a rename.
- All `hardfork*` gates in `Conway/Era.hs` and `Shelley/Era.hs` are identical.
- The `disjointRefInputs` gate and the `PPViewHashesDontMatch` →
  `ScriptIntegrityHashMismatch` switch are identical.
- MEMPOOL and LEDGER ordering are identical (`226b002d` Ledger.hs:391-466, Mempool.hs:114-137).
- `reapplyValidatedTx` does **not** exist in shelley 1.18.1.0. Only the
  deprecated-style `reapplyTx` does, at `b7c17cf3` Mempool.hs:234-242.

## 3a. `CL/eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Mempool.hs`

- `ValidatedTx { vtStAnnTx, vtProtocolVersion :: ProtVer, vtSlotNo }` (:81-88).
- `reapplyValidatedTx` **exists** (:420-439):
  ```haskell
  -- | Reapply a previously validated transaction, skipping static checks.
  -- ... If major protocol version has changed from when `ValidatedTx`
  -- was constructed, then full validation is triggered again.
  reapplyValidatedTx globals env ledgerState vtx
    | pvMajor (vtProtocolVersion vtx) == pvMajor currentPv =
        internalReapplyValidatedTx globals env ledgerState vtx
    | otherwise =
        fst <$> internalApplyTxWithValidation ValidateAll globals env ledgerState
                  (vtStAnnTx vtx ^. txStAnnTxG)
    where
      currentPv = ledgerState ^. lsUTxOStateL . utxosGovStateL . curPParamsGovStateL . ppProtocolVersionL
  ```
  Only the **major** version is compared. The minor version and `vtSlotNo` do
  not trigger full validation. The full-validation branch recomputes `StAnnTx`
  from the bare transaction through `mkStAnnTx`.
- The skip mechanism, `defaultReapplyValidatedTx` (:298-323), runs the era rule
  with `ValidateSuchThat (notElem lblStatic)`. Conway uses it with `@"MEMPOOL"`
  (`CL/eras/conway/impl/src/Cardano/Ledger/Conway.hs:57-59`).
- `reapplyTx` (:465-474, DEPRECATED "Use 'reapplyValidatedTx'") is
  `internalApplyTxWithValidation (ValidateSuchThat (notElem lblStatic))`. It has
  **no** protocol-version guard.
- `applyTx` (:447-457, DEPRECATED) and `applyTxWithFullValidation` (:406-413)
  both use `ValidateAll`.
- `mkMempoolEnv` (:363-377) sets `ledgerEpochNo = Nothing`, `ledgerIx = minBound`
  and `ledgerPp = curPParams`. `mkMempoolState` is `esLState` (:384-385).
- **What node 11.1.2 actually runs.** OC 4.2.1.0 calls the deprecated
  `SL.applyTx` (`OC/ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Eras.hs:158-164`)
  and `SL.reapplyTx` (`.../Shelley/Ledger/Mempool.hs:332-342`). Mempool
  re-application in the pinned node therefore **skips static checks without
  forcing full validation on a protocol-major change.**
  Consensus `origin/main` commit `8307d87d` (2026-09-15, "Integrate
  cardano-ledger for the 11.2 node release", not yet in any tag) switches to
  `SL.applyTxWithFullValidation` and `SL.reapplyValidatedTx`. The PV-major guard
  is therefore a node 11.2 behaviour.
- The MEMPOOL-level checks (all-inputs-spent, unelected committee) and all
  LEDGER, CERTS and GOV checks are unlabelled, so they **run on re-application**.

## 3b. `CL/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Mempool.hs`

`PredicateFailure (MEMPOOL era) = ConwayLedgerPredFailure era` (:86). **No
`ConwayMempoolPredFailure` type exists.** Mempool failures are
`ConwayMempoolFailure Text`, a LEDGER constructor with CBOR tag 7 (Ledger.hs:124,238).

`mempoolTransition` (:103-138):
```haskell
  let inputs = tx ^. bodyTxL . inputsTxBodyL          -- spending inputs only
      UTxO utxo = ledgerState ^. utxoG
      notAllSpent = any (`Map.member` utxo) inputs
  notAllSpent ?! ConwayMempoolFailure
      "All inputs are spent. Transaction has probably already been included"
  -- Skip all other checks if the transaction is probably a duplicate
  whenFailureFreeDefault ledgerState $ do
    let protVer = ledgerEnv ^. Shelley.ledgerPpL . ppProtocolVersionL
    unless (hardforkConwayDisallowUnelectedCommitteeFromVoting protVer) $   -- i.e. PV <= 10
      let addPrefix = ("Unelected committee members are not allowed to cast votes: " <>)
       in failOnNonEmpty (unelectedCommitteeVoters committee committeeState votingProcedures)
            (ConwayMempoolFailure . addPrefix . T.pack . show . NE.toList)
    trans @(EraRule "LEDGER" era) $ TRC trc
```
The steps run in this order:

1. The all-inputs-spent check runs first. It fires only if **every** spending
   input is missing; reference and collateral inputs are not considered.
2. If step 1 failed, everything else is skipped and the input state is returned.
3. For PV ≤ 10 only, votes by unelected or unauthorized committee members are
   rejected in the mempool. For PV ≥ 11 the GOV rule enforces the same check as
   `UnelectedCommitteeVoters` (Gov.hs:478-481), which also applies to blocks.
4. `trans @LEDGER`. The `unelectedCommitteeVoters` helper is at Gov.hs:652-665.

In consensus, `ConwayMempoolFailure` is also used for soft timeout rejections
(`MempoolTxTooSlow`), and only for local clients
(`OC/.../Shelley/Eras.hs:209-210`; `OC/ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/Update.hs:277-296`).

## 3c. `CL/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ledger.hs`, `conwayLedgerTransitionTRC` (:350-440)

```haskell
    curEpochNo <- maybe (liftSTS $ epochFromSlot slot) pure mbCurEpochNo
    (utxoState', certStateAfterCERTS) <-
      if tx ^. isPhase2ValidTxL == Phase2Valid
        then do
          runTest $ validateTreasuryValue txBody (chainAccountState ^. casTreasuryL)   -- (1)
          runTest $ validateRefScriptSize pp (utxoState ^. utxoL) tx                    -- (2)
          unless (hardforkConwayBootstrapPhase (pp ^. ppProtocolVersionL)) $ do         -- (3) PV>=10
            runTest $ validateWithdrawalsDelegated accounts tx          -- uses PRE-cert accounts
          certState' <-
            if hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule $ pp ^. ppProtocolVersionL  -- (4) PV>=11
              then do
                Shelley.testIncompleteAndMissingWithdrawals (certState ^. certDStateL . accountsL) withdrawals
                pure $ certState & updateDormantDRepExpiries tx curEpochNo
                                 & updateVotingDRepExpiries tx curEpochNo (pp ^. ppDRepActivityL)
                                 & certDStateL . accountsL %~ drainAccounts withdrawals
              else pure certState
          certStateAfterCERTS <- trans @(EraRule "CERTS" era) $                         -- (5)
              TRC (CertsEnv tx pp curEpochNo committee committeeProposals, certState', certs)
          proposalsState <- trans @(EraRule "GOV" era) $ TRC (GovEnv txid curEpochNo pp  -- (6)
                 guardrailsHash certStateAfterCERTS committee, proposals, govSignal)
          pure (utxoState & ...proposalsGovStateL .~ proposalsState, certStateAfterCERTS)
        else pure (utxoState, certState)                                                 -- isValid=False
    utxoState'' <- trans @(EraRule "UTXOW" era) $ TRC
          -- Pass to UTXOW the unmodified CertState in its Environment,
          -- so it can process refunds of deposits for deregistering
          -- stake credentials and DReps. ...
          ( Shelley.UtxoEnv @era slot pp certState , utxoState' , stAnnTx )              -- (7)
    pure $ LedgerState utxoState'' certStateAfterCERTS
```

- **When isValid is True**, the order is: TREASURY_VALUE (:364), REF_SCRIPT_SIZE
  (:365), WDRL_DELEGATED for PV ≥ 10 (:379-380), then for PV ≥ 11 the
  missing/incomplete withdrawals check and the DRep expiry/dormancy updates and
  account drain (:383-392), then CERTS (:394-400), then GOV with
  **certStateAfterCERTS** (:409-421), then UTXOW (:428-439).
- **When isValid is False**, only UTXOW runs. The certificate state and the
  proposals are unchanged, and no treasury, ref-script-size, withdrawal,
  certificate or governance checks run.
- **UTXOW always receives the pre-CERTS `certState`**. This is the original,
  from before the PV ≥ 11 drain and before any certificate (:436). UTXO passes
  it on to `validateValueNotConservedUTxO` (deposits/refunds) and to
  `Babbage.updateUTxOState`.
- For PV ≤ 10, the withdrawal check and drain live in the CERTS base case
  (`Certs.hs:222-241`). Because CERTS recurses `gamma :|> txCert` and handles
  the prefix first, the `Empty` case runs **before the first certificate**, so
  it also sees the pre-certificate accounts. It reports
  `WithdrawalsNotInRewardsCERTS` instead of the two LEDGER constructors.
- STS semantics: predicate failures **accumulate** and sub-rules still run.
  Only `whenFailureFree` blocks skip. See
  `CL/libs/small-steps/src/Control/State/Transition/Extended.hs:683-716`.
  `ApplyTxError` is a `NonEmpty` of every collected failure, in rule order.

## 3d. Conway leaf predicate-failure constructors

The table is in section 3d-table below.

## 3e. Static (`lblStatic`) vs dynamic checks

Definitions: `CL/libs/cardano-ledger-core/src/Cardano/Ledger/Rules/ValidationMode.hs`
has `lblStatic = "static"` (:56-57), `runTest` = unlabelled (:85-86),
`runTestOnSignal` = labelled `lblStatic` (:88-89), and `?!#` / `failOnJustStatic`
= static (:63-64, :91-93). `when2Phase` is labelled `lblStatic :| [lbl2Phase]`
(`CL/eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxos.hs:420-421`).
The Conway chain has **no other** static label. MEMPOOL, LEDGER, CERTS, CERT,
DELEG, POOL, GOVCERT and GOV are entirely dynamic.

| Rule / file:line | Static (skipped on reapply) | Dynamic (always run) |
|---|---|---|
| UTXOW, `Babbage/Rules/Utxow.hs:328-391` (`babbageUtxowTransition`, used by Conway, `Conway/Rules/Utxow.hs:198`) | `validateVerifiedWits`: InvalidWitnessesUTXOW (:366). `validateMetadata`: MissingTxMetadata, MissingTxBodyMetadataHash, ConflictingMetadataHash, InvalidMetadata (:374). `validateScriptsWellFormed`: MalformedScriptWitnesses, MalformedReferenceScripts (:379) | `validateFailedBabbageScripts`, native scripts: ScriptWitnessNotValidatingUTXOW (:349, **runTest**, because it depends on reference scripts in the UTxO, unlike Shelley/Alonzo where it is static). `babbageMissingScripts`: Missing/ExtraneousScriptWitnessesUTXOW (:354). `missingRequiredDatums`: UnspendableUTxONoDatumHash, MissingRequiredDatums, NotAllowedSupplementalDatums (:357). `hasExactSetOfRedeemers`: Missing/ExtraRedeemers (:361). `validateNeededWitnesses`: MissingVKeyWitnessesUTXOW (:369). `checkScriptIntegrityHash`: PPViewHashesDontMatch / ScriptIntegrityHashMismatch (:389) |
| UTXO, `Babbage/Rules/Utxo.hs:342-412` (`babbageUtxoValidation`, called from `Conway/Rules/Utxo.hs:235-244`) | InputSetEmptyUTxO (:368). OutputBootAddrAttrsTooBig (:392). WrongNetwork (:397). WrongNetworkWithdrawal (:400). WrongNetworkInTxBody (:403). MaxTxSizeUTxO (:406) | BabbageNonDisjointRefInputs (:356). OutsideValidityIntervalUTxO (:359). OutsideForecast (:365). feesOK (:371), which covers FeeTooSmallUTxO, ScriptsNotPaidUTxO, CollateralContainsNonADA, InsufficientCollateral, IncorrectTotalCollateralField and NoCollateralInputs (:180-239). BadInputsUTxO (:375). ValueNotConservedUTxO (:378). BabbageOutputTooSmallUTxO (:385). OutputTooBigUTxO (:389). ExUnitsTooBigUTxO (:409). TooManyCollateralInputs (:412) |
| UTXOS, `Conway/Rules/Utxos.hs:218-242` → `Babbage/Rules/Utxos.hs:139-155` (valid) and `:201-222` (invalid) | Plutus evaluation inside `when2Phase $ whenFailureFree`: ValidationTagMismatch (FailedUnexpectedly / PassedUnexpectedly) | CollectErrors (`?!:` unlabelled, :143 / :206) |

In reapply mode, Plutus scripts are therefore **not re-run**, but script
context collection is.

## 4. `WhetherToIntervene` (OC @ 82ecba32)

- Definition: `OC/ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Ledger/SupportsMempool.hs:79-97`.
  `DoNotIntervene` is for remote peers: a problematic but valid transaction
  (a phase-2 failure) is accepted, lands in a block, and the ledger penalizes
  it. `Intervene` is for trusted local clients: such a transaction is rejected
  so the wallet is not penalized. It is a parameter of `applyTx` (:133-140) but
  **not** of `reapplyTx`.
- Chosen per caller at `Mempool/Update.hs:127-130`: `AddTxForRemotePeer →
  DoNotIntervene`, `AddTxForLocalClient → Intervene`. `addTxs` is remote and
  `addLocalTxs` is local (`Mempool/API.hs:356-375`). The hard-fork combinator
  `reapplyTx` hard-codes `DoNotIntervene` (`HardFork/Combinator/Mempool.hs:134-141`),
  which has no effect because reapply does not flip the flag.
- Semantics are in `applyAlonzoBasedTx`
  (`OC/ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Eras.hs:238-271`),
  used by Alonzo, Babbage, Conway and Dijkstra (:190-213):
  ```haskell
  intervenedTx = case wti of
    DoNotIntervene -> tx & Core.isValidTxL .~ Alonzo.IsValid True   -- force the flag to True first
    Intervene -> tx                                                  -- use the submitter's flag
  handler e = case (wti, e) of
    (DoNotIntervene, err) | isIncorrectClaimedFlag (Proxy @era) err ->
        -- rectify the flag and include the transaction
        defaultApplyShelleyBasedTx ... (tx & Core.isValidTxL .~ Alonzo.IsValid False)
    _ -> throwError e     -- reject the transaction, protecting the local wallet
  ```
  For a **peer** transaction, the node ignores the submitted `isValid`, first
  applies it as `IsValid True`, and if the **only** failure is `ValidationTagMismatch`
  it re-applies with `IsValid False`. That path collects collateral and admits
  the transaction.
  `isIncorrectClaimedFlag` for Conway (:326-338) matches only a singleton
  `ConwayUtxowFailure (UtxoFailure (UtxosFailure (ValidationTagMismatch …)))`.
  Any other accompanying failure means rejection.
  For a **local** transaction the submitted flag is used as-is, so a wrong flag
  is rejected.
  A "TODO … it's a reason to disconnect from the peer" (Issue #3276) remains
  unimplemented.
- The re-applied transaction keeps its flipped flag. The `Validated` wrapper
  holds the transaction with `IsValid False`, and block forging emits that body.

---

## 3d-table. Conway leaf constructors at CL@f649f975

PV gates are in `Conway/Era.hs:257-284`:

- `hardforkConwayBootstrapPhase` = `pvMajor == 9`
- `hardforkConwayDisallowUnelectedCommitteeFromVoting`, `hardforkConwayDELEGIncorrectDepositsAndRefunds` and `hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule` = `pvMajor > 10`

`Shelley/Era.hs:231-262`:

- `hardforkAlonzoValidatePoolAccountAddressNetID` = `> 4`
- `hardforkConwayDisallowDuplicatedVRFKeys` = `> 10`

`Shelley/SoftForks.hs:14`:

- `restrictPoolMetadataHash` = `pv > ProtVer 4 0`

Unmarked constructors are always active in Conway (PV 9 and later).

Wrapper constructors are not leaves:

- `ConwayUtxowFailure`, `ConwayCertsFailure`, `ConwayGovFailure` (LEDGER tags 1, 2, 3)
- `CertFailure` (CERTS)
- `DelegFailure`, `PoolFailure`, `GovCertFailure` (CERT)
- `UtxoFailure` (UTXOW tag 0)
- `UtxosFailure` (UTXO tag 0)

Babbage, Alonzo, Shelley and Allegra UTXO/UTXOW failures are **not** separately
reachable. The `InjectRuleFailure` instances rewrite every one of them onto the
Conway constructors below (`Conway/Rules/Utxow.hs:278-316`, `Conway/Rules/Utxo.hs:375-426`,
`Conway/Rules/Ledger.hs:135-195`). `MIRInsufficientGenesisSigsUTXOW`,
`DelegsFailure` and `UpdateFailure` map to `error`/`absurd`, which makes them
impossible.

| Rule | Constructor | Fields | PV gate | Notes |
|---|---|---|---|---|
| MEMPOOL | ConwayMempoolFailure (a LEDGER constructor, tag 7) | Text | "All inputs are spent…": any PV. "Unelected committee…": **PV ≤ 10 only** | Mempool.hs:116-135. Also used by consensus for soft timeouts on local clients only |
| LEDGER | ConwayTreasuryValueMismatch (5) | Mismatch RelEQ Coin (CBOR serialized swapped) | – | Ledger.hs:442-454. Only if `currentTreasuryValue` is set. isValid=True only |
| LEDGER | ConwayTxRefScriptsSizeTooBig (6) | Mismatch RelLTEQ Int | – | Ledger.hs:456-471. `txNonDistinctRefScriptsSize` over spending and reference inputs vs `maxRefScriptSizePerTx`. isValid=True only |
| LEDGER | ConwayWdrlNotDelegatedToDRep (4) | NonEmpty (KeyHash Staking) | **PV ≥ 10** (not bootstrap) | Ledger.hs:379-380, 473-488. Key-hash reward accounts only. Pre-cert accounts |
| LEDGER | ConwayWithdrawalsMissingAccounts (8) | Withdrawals | **PV ≥ 11** | Via Shelley `testIncompleteAndMissingWithdrawals` (Shelley/Rules/Ledger.hs:351-359). Also covers the wrong network |
| LEDGER | ConwayIncompleteWithdrawals (9) | NonEmptyMap AccountAddress (Mismatch RelEQ Coin) | **PV ≥ 11** | Same place |
| CERTS | WithdrawalsNotInRewardsCERTS | Withdrawals | **PV ≤ 10** | Certs.hs:222-236. Merges missing and incomplete. Runs before any certificate |
| DELEG | IncorrectDepositDELEG | Coin | **PV ≤ 10** (for PV ≥ 11 it is replaced by Deposit/RefundIncorrectDELEG) | Deleg.hs:199-211, 250-259. Used for both a wrong deposit and a wrong refund |
| DELEG | DepositIncorrectDELEG | Mismatch RelEQ Coin | **PV ≥ 11** | Deleg.hs:202-210. RegCert with a deposit, RegDelegCert |
| DELEG | RefundIncorrectDELEG | Mismatch RelEQ Coin | **PV ≥ 11** | Deleg.hs:250-258. Only if the credential is registered |
| DELEG | StakeKeyRegisteredDELEG | Credential Staking | – | Deleg.hs:212-214 |
| DELEG | StakeKeyNotRegisteredDELEG | Credential Staking | – | Deleg.hs:271, 283. UnReg and Deleg certificates |
| DELEG | StakeKeyHasNonZeroAccountBalanceDELEG | Coin | – | Deleg.hs:260-268 |
| DELEG | DelegateeDRepNotRegisteredDELEG | Credential DRepRole | **PV ≥ 10** (skipped in bootstrap) | Deleg.hs:220-226. AlwaysAbstain and NoConfidence are exempt |
| DELEG | DelegateeStakePoolNotRegisteredDELEG | KeyHash StakePool | – | Deleg.hs:216-219. PV < 10 keeps buggy DRep re-delegation bookkeeping (:288, :298, #4772). That is state behaviour, not a failure |
| POOL (Shelley) | StakePoolNotRegisteredOnKeyPOOL | KeyHash StakePool | – | Pool.hs:308. Retirement |
| POOL | StakePoolRetirementWrongEpochPOOL | Mismatch RelGT EpochNo, Mismatch RelLTEQ EpochNo | – | Pool.hs:309-322. `cEpoch < e ≤ cEpoch + eMax` |
| POOL | StakePoolCostTooLowPOOL | Mismatch RelGTEQ Coin | – | Pool.hs:252-261 |
| POOL | WrongNetworkPOOL | Mismatch RelEQ Network, KeyHash StakePool | PV > 4 (always in Conway) | Pool.hs:231-243 |
| POOL | PoolMedataHashTooBig | KeyHash StakePool, Int | PV > 4.0 (always) | Pool.hs:245-250 |
| POOL | VRFKeyHashAlreadyRegistered | KeyHash StakePool, VRFVerKeyHash | **PV ≥ 11** | Pool.hs:265-267, 279-282. Also changes `psVRFKeyHashes` bookkeeping |
| GOVCERT | ConwayDRepAlreadyRegistered | Credential DRepRole | – | GovCert.hs:211-212 |
| GOVCERT | ConwayDRepIncorrectDeposit | Mismatch RelEQ Coin | – | GovCert.hs:213-219. DRep expiry on registration ignores dormant epochs in PV 9 (:286-292) |
| GOVCERT | ConwayDRepNotRegistered | Credential DRepRole | – | GovCert.hs:241, 257-258. Unreg and Update |
| GOVCERT | ConwayDRepIncorrectRefund | Mismatch RelEQ Coin | – | GovCert.hs:236-242 |
| GOVCERT | ConwayCommitteeHasPreviouslyResigned | Credential ColdCommitteeRole | – | GovCert.hs:190-196 |
| GOVCERT | ConwayCommitteeIsUnknown | Credential ColdCommitteeRole | – | GovCert.hs:197-205. Current committee or any pending UpdateCommittee proposal |
| GOV | UnelectedCommitteeVoters | NonEmpty (Credential HotCommitteeRole) | **PV ≥ 11** | Gov.hs:478-481. Checked first in GOV |
| GOV | DisallowedProposalDuringBootstrap | ProposalProcedure | **PV = 9** | Gov.hs:435-443, 484 |
| GOV | ProposalCantFollow | StrictMaybe (GovPurposeId HardFork), Mismatch RelGT ProtVer | – | Gov.hs:489-499, 673-695 |
| GOV | MalformedProposal | GovAction | – | Gov.hs:393-399, 502. `ppuWellFormed pv` (the PParamsUpdate well-formedness is itself PV-dependent) |
| GOV | ProposalReturnAccountDoesNotExist | AccountAddress | **PV ≥ 10** | Gov.hs:504-508 |
| GOV | TreasuryWithdrawalReturnAccountsDoNotExist | NonEmpty AccountAddress | **PV ≥ 10** | Gov.hs:509-520 |
| GOV | ProposalDepositIncorrect | Mismatch RelEQ Coin | – | Gov.hs:522-530 |
| GOV | ProposalProcedureNetworkIdMismatch | AccountAddress, Network | – | Gov.hs:532-535 |
| GOV | TreasuryWithdrawalsNetworkIdMismatch | NonEmptySet AccountAddress, Network | – | Gov.hs:539-544 |
| GOV | InvalidGuardrailsScriptHash | StrictMaybe ScriptHash (proposal), StrictMaybe ScriptHash (constitution) | – | Gov.hs:547, 557-558. TreasuryWithdrawals and ParameterChange |
| GOV | ZeroTreasuryWithdrawals | GovAction | – | Gov.hs:550 |
| GOV | ConflictingCommitteeUpdate | NonEmptySet (Credential ColdCommitteeRole) | – | Gov.hs:551-553 |
| GOV | ExpirationEpochTooSmall | NonEmptyMap (Credential ColdCommitteeRole) EpochNo | – | Gov.hs:555-556. `expiry ≤ currentEpoch` |
| GOV | InvalidPrevGovActionId | ProposalProcedure | – | Gov.hs:561-566. Proposal parent/root ancestry |
| GOV | VotersDoNotExist | NonEmpty Voter | – | Gov.hs:604. Uses **certStateAfterCERTS** |
| GOV | GovActionsDoNotExist | NonEmpty GovActionId | – | Gov.hs:605. Includes proposals made in the same transaction |
| GOV | DisallowedVotesDuringBootstrap | NonEmpty (Voter, GovActionId) | **PV = 9** | Gov.hs:384-391, 606 |
| GOV | VotingOnExpiredGovAction | NonEmpty (Voter, GovActionId) | – | Gov.hs:356-362, 607 |
| GOV | DisallowedVoters | NonEmpty (Voter, GovActionId) | – | Gov.hs:364-376, 608. Voter-type vs action-type matrix |
| UTXOW | InvalidWitnessesUTXOW | NonEmpty (VKey Witness) | – | Static. Shelley/Rules/Utxow.hs:391 |
| UTXOW | MissingVKeyWitnessesUTXOW | NonEmptySet (KeyHash Witness) | – | Dynamic. Shelley/Rules/Utxow.hs:412. Uses the pre-CERTS certState |
| UTXOW | MissingScriptWitnessesUTXOW | NonEmptySet ScriptHash | – | Dynamic. Babbage/Rules/Utxow.hs:193-208 |
| UTXOW | ExtraneousScriptWitnessesUTXOW | NonEmptySet ScriptHash | – | Dynamic. Same place |
| UTXOW | ScriptWitnessNotValidatingUTXOW | NonEmptySet ScriptHash | – | **Dynamic in Conway** (Babbage/Rules/Utxow.hs:211-232, 349). Native scripts only |
| UTXOW | MissingTxBodyMetadataHash | TxAuxDataHash | – | Static. Shelley/Rules/Utxow.hs:427-443 |
| UTXOW | MissingTxMetadata | TxAuxDataHash | – | Static |
| UTXOW | ConflictingMetadataHash | Mismatch RelEQ TxAuxDataHash | – | Static |
| UTXOW | InvalidMetadata | – | – | Static. `validateTxAuxData … pv` |
| UTXOW | MissingRedeemers | NonEmpty (PlutusPurpose AsItem, ScriptHash) | – | Dynamic. Alonzo/Rules/Utxow.hs:260-261 |
| UTXOW | ExtraRedeemers | NonEmpty (PlutusPurpose AsIx) | – | Dynamic |
| UTXOW | MissingRequiredDatums | NonEmptySet DataHash, Set DataHash | – | Dynamic. Alonzo/Rules/Utxow.hs:229-231 |
| UTXOW | NotAllowedSupplementalDatums | NonEmptySet DataHash, Set DataHash | – | Dynamic |
| UTXOW | UnspendableUTxONoDatumHash | NonEmptySet TxIn | – | Dynamic. PlutusV1/V2 spending inputs only; V3 is exempt (CIP-69, Alonzo/UTxO.hs:256-275) |
| UTXOW | PPViewHashesDontMatch (13) | Mismatch RelEQ (StrictMaybe ScriptIntegrityHash) | **PV ≤ 10** | Alonzo/Rules/Utxow.hs:273-286 |
| UTXOW | ScriptIntegrityHashMismatch (18) | Mismatch RelEQ (StrictMaybe ScriptIntegrityHash), StrictMaybe ByteString (expected preimage) | **PV ≥ 11** | Same place |
| UTXOW | MalformedScriptWitnesses | NonEmptySet ScriptHash | – | Static. Babbage/Rules/Utxow.hs:264-273. `isValidScript … pv` (language allowed at PV) |
| UTXOW | MalformedReferenceScripts | NonEmptySet ScriptHash | – | Static. Outputs and collateral return |
| UTXO | BabbageNonDisjointRefInputs | NonEmpty TxIn | **PV 9-10 only** (`> eraProtVerHigh Babbage(8) && < 11`) | Babbage/Rules/Utxo.hs:207-214. **Removed in PV 11**: overlapping spending and reference inputs are allowed |
| UTXO | OutsideValidityIntervalUTxO | ValidityInterval, SlotNo | – | Allegra/Rules/Utxo.hs:235 |
| UTXO | OutsideForecast | SlotNo | – | Alonzo/Rules/Utxo.hs:377-385. Only if there are redeemers and an upper bound |
| UTXO | InputSetEmptyUTxO | – | – | Static |
| UTXO | FeeTooSmallUTxO | Mismatch RelGTEQ Coin | – | Babbage feesOK:189-193. `getMinFeeTxUtxo` (includes the ref-script fee) |
| UTXO | ScriptsNotPaidUTxO | NonEmptyMap TxIn TxOut | – | feesOK part 3. Only if redeemers are non-empty |
| UTXO | CollateralContainsNonADA | Value | – | feesOK part 4 (Babbage return-output rule, Utxo.hs:253-292) |
| UTXO | InsufficientCollateral | DeltaCoin, Coin | – | feesOK part 5 |
| UTXO | IncorrectTotalCollateralField | DeltaCoin, Coin | – | feesOK part 6 |
| UTXO | NoCollateralInputs | – | – | feesOK part 7 |
| UTXO | BadInputsUTxO | NonEmptySet TxIn | – | Spending, collateral and reference inputs (Utxo.hs:375) |
| UTXO | ValueNotConservedUTxO | Mismatch RelEQ Value | – | Utxo.hs:378. Pre-CERTS certState for deposits/refunds |
| UTXO | BabbageOutputTooSmallUTxO | NonEmpty (TxOut, Coin) | – | Babbage/Rules/Utxo.hs:303-323. All outputs including collateral return |
| UTXO | OutputTooBigUTxO | NonEmpty (Int, Int, TxOut) | – | Alonzo/Rules/Utxo.hs:412-428 |
| UTXO | OutputBootAddrAttrsTooBig | NonEmpty TxOut | – | Static |
| UTXO | WrongNetwork | Network, NonEmptySet Addr | – | Static |
| UTXO | WrongNetworkWithdrawal | Network, NonEmptySet AccountAddress | – | Static |
| UTXO | WrongNetworkInTxBody | Mismatch RelEQ Network | – | Static |
| UTXO | MaxTxSizeUTxO | Mismatch RelLTEQ Word32 | – | Static |
| UTXO | ExUnitsTooBigUTxO | Mismatch RelLTEQ ExUnits | – | Alonzo/Rules/Utxo.hs:459 |
| UTXO | TooManyCollateralInputs | Mismatch RelLTEQ Word16 | – | Alonzo/Rules/Utxo.hs:475 |
| UTXO | OutputTooSmallUTxO | NonEmpty TxOut | – | **Unreachable in Conway** (Babbage uses BabbageOutputTooSmallUTxO). Present for CBOR only |
| UTXOS | CollectErrors | NonEmpty CollectError (NoRedeemer, NoWitness, NoCostModel, BadTranslation) | – | Dynamic. Babbage/Rules/Utxos.hs:143, 206 |
| UTXOS | ValidationTagMismatch | IsPhase2Valid, TagMismatchDescription (FailedUnexpectedly [..] / PassedUnexpectedly) | – | Static (`when2Phase`). Runs only if failure-free so far. This is the constructor consensus matches to flip a peer transaction to `IsValid False` |

`ConwayMempoolPredFailure` does not exist at this revision.
