<script lang="ts">
  import { onMount } from 'svelte';
  import { ApiError, apiFailureMessage, resolveApiBase, YanoApi } from '$lib/api/client';
  import type {
    AnchorCommitment, AppChainStatus, AuthenticatedSnapshotSummary, StateProofEnvelope
  } from '$lib/api/types';
  import { combineProofAndClaim, evaluateParameterClaim, evaluateStakeClaim,
    packageVerification, type ClaimVerdict }
    from './claim-verification';
  import { authenticatedParameter, authenticatedStake } from './authenticated-facts';

  const BUNDLE = 'com.bloxbean.cardano.yano.appchain.cardano-history';
  const STAKE_SERIES = 'l1-epoch-stake-v1.distribution';
  const DREP_SERIES = 'l1-epoch-governance-v1.drep-distribution';
  type View = 'overview' | 'parameters' | 'stake' | 'governance' | 'verify';
  type Subject = 'parameters' | 'stake' | 'drep' | 'proposal';
  type SelectedProof = { response: Record<string, unknown>; subject: Subject; epoch: number };

  let api: YanoApi | null = $state(null);
  let chains: Array<{ chainId: string; status: AppChainStatus }> = $state([]);
  let selectedChain = $state('');
  let status: AppChainStatus | null = $state(null);
  let history: Record<string, unknown> | null = $state(null);
  let parameterEpochs: number[] = $state([]);
  let stakeEpochs: number[] = $state([]);
  let drepEpochs: number[] = $state([]);
  let anchor: Record<string, unknown> | null = $state(null);
  let view: View = $state('overview');
  let loading = $state(true);
  let error = $state('');
  let initializationPending = $state(false);

  let queryResults = $state<Record<Subject, string>>({
    parameters: 'Choose a query.', stake: 'Choose a query.',
    drep: 'Choose a query.', proposal: 'Choose a query.'
  });
  let selectedProofs = $state<Record<Subject, SelectedProof | null>>({
    parameters: null, stake: null, drep: null, proposal: null
  });
  let proofBundles = $state<Record<Subject, Record<string, unknown> | null>>({
    parameters: null, stake: null, drep: null, proposal: null
  });
  let proofStatuses = $state<Record<Subject, string>>({
    parameters: 'Load an epoch, then query one named parameter to generate its proof.',
    stake: 'Query a complete stake epoch to generate its proof.',
    drep: 'Query a complete DRep epoch to generate its proof.',
    proposal: 'Query a proposal to generate its proof.'
  });
  let proofVerifications = $state<Record<Subject, unknown | null>>({
    parameters: null, stake: null, drep: null, proposal: null
  });

  let parameterEpoch = $state(0);
  let parameterDocument: Record<string, unknown> | null = $state(null);
  let parameterFieldId = $state('');
  let parameterClaimMode = $state('exact');
  let parameterExpectedValue = $state('');
  let parameterMaximumValue = $state('');
  let stakeEpoch = $state(0);
  let stakeInputMode: 'address' | 'credential' = $state('address');
  let stakeAddress = $state('');
  let credentialType = $state(0);
  let credentialHash = $state('');
  let stakeClaimMode = $state('minimum');
  let stakeClaimCoin = $state('');
  let stakeClaimPool = $state('');
  let drepEpoch = $state(0);
  let drepType = $state(0);
  let drepHash = $state('');
  let proposalEpoch = $state(0);
  let proposalTx = $state('');
  let proposalIndex = $state(0);

  let parameterFields = $derived(parameterFieldCatalog(parameterDocument));
  let selectedParameterField = $derived(
    parameterFields.find((field) => field.id === parameterFieldId) ?? null);

  let importedProof = $state('');
  let importedAnchor = $state('');
  let importedStatus = $state('Paste an exported proof bundle to verify it.');
  let importedVerdict: unknown | null = $state(null);

  let hasStake = $derived(hasComponent(status, 'l1-epoch-stake-v1'));
  let hasGovernance = $derived(hasComponent(status, 'l1-epoch-governance-v1'));

  onMount(() => void load());

  async function load() {
    loading = true;
    error = '';
    try {
      api = new YanoApi(await resolveApiBase());
      const summaries = await api.chains();
      const inspected = await Promise.all(summaries.map(async (chain) => ({
        chainId: chain.chainId,
        status: await api!.chainStatus(chain.chainId)
      })));
      chains = inspected.filter((chain) => hasComponent(chain.status, 'l1-epoch-params-v1'));
      const requested = new URLSearchParams(location.search).get('chain');
      selectedChain = chains.some((chain) => chain.chainId === requested)
        ? requested! : chains[0]?.chainId ?? '';
      if (selectedChain) await selectChain(selectedChain);
    } catch (cause) {
      error = apiFailureMessage(cause, 'Cardano History is unavailable');
    } finally {
      loading = false;
    }
  }

  async function selectChain(chainId: string) {
    if (!api) return;
    selectedChain = chainId;
    status = chains.find((chain) => chain.chainId === chainId)?.status
      ?? await api.chainStatus(chainId);
    history = null;
    parameterEpochs = [];
    parameterDocument = null;
    parameterFieldId = '';
    stakeEpochs = [];
    drepEpochs = [];
    initializationPending = false;
    resetProofState();
    const parameters = { chain: chainId };
    try {
      const supportsStake = hasComponent(status, 'l1-epoch-stake-v1');
      const supportsGovernance = hasComponent(status, 'l1-epoch-governance-v1');
      const [historyValue, epochsValue, anchorValue, stakeValues, drepValues] = await Promise.all([
        api.domain<Record<string, unknown>>(BUNDLE, 'status', parameters),
        api.domain<{ epochs?: number[] }>(BUNDLE, 'epochs', { ...parameters, limit: '15' }),
        api.chainAnchorCommitment(chainId).catch(() => null),
        supportsStake ? snapshotEpochCatalog(STAKE_SERIES, 'epoch-stake-') : Promise.resolve([]),
        supportsGovernance ? snapshotEpochCatalog(DREP_SERIES, 'epoch-drep-') : Promise.resolve([])
      ]);
      history = historyValue;
      parameterEpochs = epochsValue.epochs ?? [];
      stakeEpochs = stakeValues;
      drepEpochs = drepValues;
      anchor = anchorValue as Record<string, unknown> | null;
      const latest = Number(historyValue.latestEpoch ?? parameterEpochs[0] ?? 0);
      parameterEpoch = latest;
      stakeEpoch = stakeEpochs[0] ?? 0;
      drepEpoch = drepEpochs[0] ?? latest;
      proposalEpoch = latest;
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) {
        initializationPending = true;
        anchor = await api.chainAnchorCommitment(chainId).catch(() => null) as Record<string, unknown> | null;
      } else {
        throw cause;
      }
    }
  }

  async function snapshotEpochCatalog(series: string, prefix: string): Promise<number[]> {
    if (!api) return [];
    const values = new Set<number>();
    let cursor: string | undefined;
    for (let pageIndex = 0; pageIndex < 100; pageIndex++) {
      const page = await api.chainSnapshots(selectedChain, series, cursor, 100);
      for (const item of page.items) {
        if (!item.snapshotId.startsWith(prefix)) continue;
        const epoch = Number(item.snapshotId.slice(prefix.length));
        if (Number.isSafeInteger(epoch) && epoch >= 0) values.add(epoch);
      }
      if (!page.nextCursor) break;
      cursor = page.nextCursor;
    }
    return [...values].sort((left, right) => right - left);
  }

  function resetProofState() {
    for (const subject of ['parameters', 'stake', 'drep', 'proposal'] as Subject[]) {
      selectedProofs[subject] = null;
      proofBundles[subject] = null;
      proofVerifications[subject] = null;
    }
  }

  async function query(
    subject: Subject, epoch: number, path: string, extra: Record<string, string> = {}
  ) {
    if (!api || !selectedChain) return;
    queryResults[subject] = 'Loading…';
    selectedProofs[subject] = null;
    proofBundles[subject] = null;
    proofVerifications[subject] = null;
    try {
      const response = await api.domain<Record<string, unknown>>(
        BUNDLE, path, { chain: selectedChain, ...extra });
      queryResults[subject] = pretty(response);
      selectedProofs[subject] = { response, subject, epoch };
      proofStatuses[subject] = response.proof
        ? 'Proof coordinates are ready for this query.' : 'This response has no proof coordinates.';
      if (subject === 'stake' && response.found === true) {
        const coin = String(response.coin ?? '');
        const pool = String(response.poolHash ?? '');
        if (!stakeClaimCoin && /^\d+$/.test(coin)) stakeClaimCoin = coin;
        if (!stakeClaimPool && validHash(pool, 28)) stakeClaimPool = pool;
        if (typeof response.credentialHash === 'string') credentialHash = response.credentialHash;
        if (typeof response.credentialType === 'number') credentialType = response.credentialType;
      }
      if (subject === 'parameters' && response.dataset === 'protocol-parameters') {
        parameterDocument = response;
        const fields = parameterFieldCatalog(response);
        if (!fields.some((field) => field.id === parameterFieldId)) {
          parameterFieldId = fields.find((field) => field.id === 'key-deposit')?.id
            ?? fields[0]?.id ?? '';
        }
        selectedProofs.parameters = null;
        proofStatuses.parameters = 'Choose and query one named parameter to generate its proof.';
      }
      if (subject === 'parameters' && response.dataset === 'protocol-parameter-field') {
        const value = response.value;
        if (value != null && !parameterExpectedValue) {
          parameterExpectedValue = displayClaimValue(value, String(response.type ?? ''));
        }
      }
    } catch (cause) {
      queryResults[subject] = apiFailureMessage(cause, 'Query failed');
      proofStatuses[subject] = 'No proof is available for the failed query.';
    }
  }

  function queryStake() {
    if (stakeInputMode === 'address') {
      return query('stake', stakeEpoch, `epochs/${stakeEpoch}/stake-address/${stakeAddress}`);
    }
    return query('stake', stakeEpoch,
      `epochs/${stakeEpoch}/stake/${credentialType}/${credentialHash.toLowerCase()}`);
  }

  function queryParameterField() {
    if (!parameterFieldId) return;
    return query('parameters', parameterEpoch,
      `epochs/${parameterEpoch}/parameters/fields/${parameterFieldId}`);
  }

  function selectParameterField(fieldId: string) {
    parameterFieldId = fieldId;
    parameterClaimMode = 'exact';
    parameterMaximumValue = '';
    const field = parameterFields.find((candidate) => candidate.id === fieldId);
    parameterExpectedValue = field
      ? displayClaimValue(field.value, field.type) : '';
    selectedProofs.parameters = null;
    proofBundles.parameters = null;
    proofVerifications.parameters = null;
    proofStatuses.parameters = 'Query this parameter before generating its proof.';
  }

  async function generateProof(subject: Subject) {
    if (!api || !selectedProofs[subject]) return;
    const selected = selectedProofs[subject]!;
    proofStatuses[subject] = 'Generating a proof fixed to an L1-confirmed root…';
    proofVerifications[subject] = null;
    try {
      const coordinates = selected.response.proof as Record<string, unknown> | undefined;
      if (!coordinates) throw new Error('The query did not return proof coordinates');
      if (coordinates.kind === 'authenticated-snapshot') {
        const series = String(coordinates.seriesId ?? '');
        const prefix = subject === 'stake' ? 'epoch-stake-' : 'epoch-drep-';
        const snapshot = await findSnapshot(series, `${prefix}${selected.epoch}`);
        const nested = await api.chainSnapshotProof(
          selectedChain, series, snapshot.sequence, String(coordinates.secondaryKey ?? ''));
        const secondary = asRecord(nested.secondaryProof);
        if (secondary.keyHex !== coordinates.secondaryKey
          || selected.response.found === true
          && secondary.valueHex !== selected.response.canonicalValueHex
          || selected.response.found === false && secondary.presence !== 'ABSENT') {
          throw new Error('The snapshot proof does not match the visible query result');
        }
        proofBundles[subject] = {
          schema: 'cardano-history-console-proof-v1', kind: 'authenticated-snapshot',
          chainId: selectedChain, subject, epoch: selected.epoch,
          claim: subject === 'stake' ? stakeClaim()
            : subject === 'parameters' ? parameterClaim(selected.response) : undefined,
          history: selected.response, proof: nested
        };
      } else if (coordinates.kind === 'primary-pair') {
        const confirmedAnchor = await api.chainAnchorCommitment(selectedChain);
        const height = confirmedAnchor.anchoredHeight;
        const factKey = String(coordinates.factPhysicalKey ?? '');
        const completenessKey = String(coordinates.completenessPhysicalKey ?? '');
        if (!factKey || !completenessKey || !Number.isSafeInteger(height)) {
          throw new Error('Invalid primary-pair proof coordinates');
        }
        const [fact, completeness] = await Promise.all([
          api.chainProof(selectedChain, factKey, height),
          api.chainProof(selectedChain, completenessKey, height)
        ]);
        requireAnchoredProof(fact, confirmedAnchor);
        requireAnchoredProof(completeness, confirmedAnchor);
        requireQueryValue(selected.response, fact);
        proofBundles[subject] = {
          schema: 'cardano-history-console-proof-v1', kind: 'primary-pair',
          chainId: selectedChain, subject, epoch: selected.epoch,
          claim: subject === 'parameters' ? parameterClaim(selected.response) : undefined,
          history: selected.response, anchor: confirmedAnchor, fact, completeness
        };
      } else {
        const key = String(coordinates.physicalKey ?? '');
        const confirmedAnchor = await api.chainAnchorCommitment(selectedChain);
        const height = confirmedAnchor.anchoredHeight;
        if (!key || !Number.isSafeInteger(height)) throw new Error('Invalid primary proof coordinates');
        const envelope = await api.chainProof(selectedChain, key, height);
        requireAnchoredProof(envelope, confirmedAnchor);
        requireQueryValue(selected.response, envelope);
        proofBundles[subject] = {
          schema: 'cardano-history-console-proof-v1', kind: 'primary',
          chainId: selectedChain, subject, epoch: selected.epoch,
          history: selected.response, anchor: confirmedAnchor, proof: envelope
        };
      }
      proofStatuses[subject] = 'Proof generated. Verify it off-chain before export or use.';
    } catch (cause) {
      proofStatuses[subject] = apiFailureMessage(cause, 'Proof generation failed');
    }
  }

  async function verifyGeneratedProof(subject: Subject) {
    if (!api || !proofBundles[subject]) return;
    proofStatuses[subject] = 'Verifying proof against this node’s L1-confirmed anchor…';
    try {
      const bundle = proofBundles[subject]!;
      const kind = String(bundle.kind ?? '');
      if (kind === 'authenticated-snapshot') {
        const nested = asRecord(bundle.proof);
        const cryptographic = await api.verifyChainSnapshotProof(selectedChain, {
          bundleCborHex: requiredString(nested, 'bundleCborHex'), trustMode: 'local-anchor'
        });
        storeVerification(subject, combineProofAndClaim(
          cryptographic as Record<string, unknown>, semanticClaim(bundle)));
      } else if (kind === 'primary-pair') {
        const [fact, completeness] = await Promise.all([
          verifyPrimary(asRecord(bundle.fact)), verifyPrimary(asRecord(bundle.completeness))
        ]);
        const factAnchor = primaryAnchorBinding(bundle, asRecord(bundle.fact));
        const completenessAnchor = primaryAnchorBinding(bundle, asRecord(bundle.completeness));
        const cryptographic = {
          valid: fact.valid && completeness.valid && factAnchor.valid && completenessAnchor.valid,
          fact, completeness, factAnchorBinding: factAnchor,
          completenessAnchorBinding: completenessAnchor,
          trust: 'local-anchor-reported-by-connected-node',
          trustWarning: 'Independently verify the Cardano anchor transaction before trusting this node.'
        };
        storeVerification(subject, combineProofAndClaim(cryptographic, semanticClaim(bundle)));
      } else {
        const proof = asRecord(bundle.proof);
        const verification = await verifyPrimary(proof);
        const anchored = primaryAnchorBinding(bundle, proof);
        storeVerification(subject, {
          ...verification, valid: verification.valid && anchored.valid,
          anchorBinding: anchored, trust: 'local-anchor-reported-by-connected-node',
          trustWarning: 'Independently verify the Cardano anchor transaction before trusting this node.'
        });
      }
      proofStatuses[subject] = 'Off-chain verification completed. Inspect the trust label below.';
    } catch (cause) {
      proofStatuses[subject] = apiFailureMessage(cause, 'Proof verification failed');
      proofVerifications[subject] = null;
    }
  }

  async function verifyPrimary(proof: Record<string, unknown>) {
    if (!api) throw new Error('API unavailable');
    return api.verifyChainProof(selectedChain, {
      mode: proof.presence === 'ABSENT' ? 'exclusion' : 'inclusion',
      profile: optionalString(proof, 'profile'),
      presence: proof.presence as 'PRESENT' | 'ABSENT' | 'TOMBSTONED' | undefined,
      expectedRootHex: requiredString(proof, 'stateRoot'),
      keyHex: requiredString(proof, 'key'),
      valueHex: optionalString(proof, 'valueHex'),
      proofWireHex: requiredString(proof, 'proofWireHex')
    });
  }

  async function findSnapshot(series: string, snapshotId: string): Promise<AuthenticatedSnapshotSummary> {
    if (!api) throw new Error('API unavailable');
    let cursor: string | undefined;
    for (let page = 0; page < 100; page++) {
      const response = await api.chainSnapshots(selectedChain, series, cursor, 100);
      const found = response.items.find((item) => item.snapshotId === snapshotId);
      if (found) return found;
      if (!response.nextCursor) break;
      cursor = response.nextCursor;
    }
    throw new Error(`No online authenticated snapshot found for ${snapshotId}`);
  }

  function stakeClaim() {
    return {
      mode: stakeClaimMode,
      expectedCoinLovelace: stakeClaimCoin || null,
      expectedPoolHash: stakeClaimPool || null,
      status: 'pending-offchain-verification',
      onchainRedeemerStatus: 'not-exported'
    };
  }

  function parameterClaim(response: Record<string, unknown>) {
    if (response.dataset !== 'protocol-parameter-field') return undefined;
    return {
      mode: parameterClaimMode,
      fieldId: response.fieldId,
      fieldType: response.type,
      expectedValue: parameterExpectedValue || null,
      maximumValue: parameterClaimMode === 'range' ? parameterMaximumValue || null : null,
      status: 'pending-offchain-verification',
      onchainRedeemerStatus: 'not-exported'
    };
  }

  function semanticClaim(bundle: Record<string, unknown>): ClaimVerdict | null {
    const claim = bundle.claim;
    if (!claim || typeof claim !== 'object' || Array.isArray(claim)) return null;
    const history = asRecord(bundle.history);
    const valueHex = authenticatedValueHex(bundle);
    return bundle.subject === 'stake'
      ? evaluateStakeClaim(authenticatedStake(history, valueHex), claim as Record<string, unknown>)
      : bundle.subject === 'parameters'
        ? evaluateParameterClaim(authenticatedParameter(history, valueHex),
          claim as Record<string, unknown>) : null;
  }

  function authenticatedValueHex(bundle: Record<string, unknown>): string | null {
    const kind = String(bundle.kind ?? '');
    const envelope = kind === 'authenticated-snapshot'
      ? asRecord(asRecord(bundle.proof).secondaryProof)
      : kind === 'primary-pair' ? asRecord(bundle.fact) : asRecord(bundle.proof);
    return typeof envelope.valueHex === 'string' ? envelope.valueHex : null;
  }

  function storeVerification(subject: Subject, result: Record<string, unknown>) {
    proofVerifications[subject] = result;
    const bundle = proofBundles[subject];
    if (!bundle) return;
    const claim = bundle.claim && typeof bundle.claim === 'object' && !Array.isArray(bundle.claim)
      ? bundle.claim as Record<string, unknown> : null;
    const claimSatisfied = typeof result.claimValid === 'boolean' ? result.claimValid : null;
    const requested = claim ? claimRequest(claim) : null;
    proofBundles[subject] = {
      ...bundle,
      ...(claim ? { claim: { ...claim, status: 'evaluated-offchain', satisfied: claimSatisfied } } : {}),
      verification: packageVerification(
        result, authenticatedClaimFact(subject, bundle), requested)
    };
  }

  function claimRequest(claim: Record<string, unknown>) {
    return Object.fromEntries(Object.entries(claim).filter(([field]) =>
      field !== 'status' && field !== 'onchainRedeemerStatus' && field !== 'satisfied'));
  }

  function authenticatedClaimFact(subject: Subject, bundle: Record<string, unknown>) {
    const history = asRecord(bundle.history);
    const valueHex = authenticatedValueHex(bundle);
    if (subject === 'parameters') {
      const fact = authenticatedParameter(history, valueHex);
      return { found: fact.found, epoch: fact.epoch ?? fact.datasetEpoch,
        fieldId: fact.fieldId, fieldType: fact.type, value: fact.value,
        complete: fact.complete };
    }
    if (subject === 'stake') {
      const fact = authenticatedStake(history, valueHex);
      return { found: fact.found, epoch: fact.datasetEpoch ?? fact.epoch,
        coinLovelace: fact.coin, poolHash: fact.poolHash, complete: fact.complete };
    }
    return { canonicalValueHex: valueHex };
  }

  function downloadProof(subject: Subject) {
    const proof = proofBundles[subject];
    if (!proof) return;
    const url = URL.createObjectURL(new Blob([pretty(proof)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `cardano-history-${subject}-${selectedProofs[subject]?.epoch ?? 0}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  async function verifyImportedProof() {
    if (!api) return;
    importedStatus = 'Verifying imported proof…';
    importedVerdict = null;
    try {
      const exported = asRecord(JSON.parse(importedProof));
      const kind = String(exported.kind ?? '');
      if (kind === 'authenticated-snapshot' || typeof exported.canonicalBundleHex === 'string') {
        const nested = kind === 'authenticated-snapshot' ? asRecord(exported.proof) : exported;
        const bundleCborHex = typeof nested.bundleCborHex === 'string'
          ? nested.bundleCborHex : requiredString(nested, 'canonicalBundleHex');
        const request: Record<string, unknown> = { bundleCborHex, trustMode: 'local-anchor' };
        if (importedAnchor.trim()) Object.assign(request, pinnedAnchor(JSON.parse(importedAnchor)));
        const cryptographic = await api.verifyChainSnapshotProof(selectedChain, request);
        importedVerdict = combineProofAndClaim(cryptographic, semanticClaim(exported));
      } else if (kind === 'primary-pair') {
        const [fact, completeness] = await Promise.all([
          verifyPrimary(asRecord(exported.fact)), verifyPrimary(asRecord(exported.completeness))
        ]);
        const factAnchor = primaryAnchorBinding(exported, asRecord(exported.fact));
        const completenessAnchor = primaryAnchorBinding(exported, asRecord(exported.completeness));
        importedVerdict = combineProofAndClaim({
          valid: fact.valid && completeness.valid && factAnchor.valid && completenessAnchor.valid,
          fact, completeness, factAnchorBinding: factAnchor,
          completenessAnchorBinding: completenessAnchor,
          trust: 'local-anchor-reported-by-connected-node'
        }, semanticClaim(exported));
      } else {
        const proof = normalizePrimaryProof(asRecord(exported.proof ?? exported));
        let trust = 'proof-valid-against-bundled-root';
        let pinned: Record<string, unknown> | null = null;
        if (importedAnchor.trim()) {
          pinned = pinnedAnchor(JSON.parse(importedAnchor));
          if (pinned.expectedChainId !== selectedChain) {
            throw new Error('The caller-pinned chain differs from the selected chain');
          }
          if (pinned.expectedPrimaryRootHex !== requiredString(proof, 'stateRoot')) {
            throw new Error('The imported proof root differs from the supplied trusted root');
          }
          if (Number(pinned.expectedAnchoredHeight) !== Number(proof.committedHeight)) {
            throw new Error('The imported proof height differs from the supplied anchored height');
          }
          if (pinned.expectedPrimaryProfile !== proof.profile) {
            throw new Error('The imported proof profile differs from the supplied anchor profile');
          }
          if (pinned.expectedChainGenerationIdHex !== proof.genesisId) {
            throw new Error('The imported proof chain generation differs from the supplied anchor');
          }
          if (pinned.expectedBlockHashHex !== proof.blockHash) {
            throw new Error('The imported proof block differs from the supplied anchor');
          }
          trust = 'proof-valid-against-caller-pinned-anchor';
        }
        const verification = await verifyPrimary(proof);
        importedVerdict = combineProofAndClaim({
          ...verification, trust,
          callerPinnedAnchor: pinned,
          trustWarning: pinned
            ? 'The caller remains responsible for independently authenticating the supplied Cardano anchor.'
            : 'The proof root came from the same bundle and has not been authenticated independently.'
        }, semanticClaim(exported));
      }
      importedStatus = 'Imported proof verification completed.';
    } catch (cause) {
      importedStatus = apiFailureMessage(cause, 'Imported proof verification failed');
    }
  }

  function pinnedAnchor(value: unknown): Record<string, unknown> {
    const source = asRecord(value);
    const fields = [
      'expectedChainId', 'expectedAnchorMode', 'expectedPrimaryProfile',
      'expectedPrimaryRootHex', 'expectedChainGenerationIdHex',
      'expectedApplicationProfileDigestHex', 'expectedAnchoredHeight',
      'expectedBlockHashHex', 'expectedAnchorTransactionHash', 'expectedL1Slot'
    ];
    const result: Record<string, unknown> = { trustMode: 'caller-pinned-root' };
    for (const field of fields) {
      if (!(field in source)) throw new Error(`Independent anchor context is missing ${field}`);
      result[field] = source[field];
    }
    return result;
  }

  function requireAnchoredProof(
    proof: StateProofEnvelope, confirmedAnchor: AnchorCommitment
  ) {
    if (proof.stateRoot !== confirmedAnchor.stateRoot
      || Number(proof.committedHeight) !== Number(confirmedAnchor.anchoredHeight)) {
      throw new Error('State proof does not match the latest L1-confirmed app-chain root');
    }
  }

  function requireQueryValue(queryResponse: Record<string, unknown>, proof: StateProofEnvelope) {
    const expected = queryResponse.canonicalValueHex;
    if (typeof expected === 'string' && proof.valueHex !== expected) {
      throw new Error('The authenticated value differs from the visible query result');
    }
    if (queryResponse.found === false && proof.presence !== 'ABSENT') {
      throw new Error('The proof does not establish the visible absence result');
    }
  }

  function primaryAnchorBinding(
    bundle: Record<string, unknown>, proof: Record<string, unknown>
  ): Record<string, unknown> {
    const boundAnchor = asRecord(bundle.anchor);
    const valid = proof.stateRoot === boundAnchor.stateRoot
      && Number(proof.committedHeight) === Number(boundAnchor.anchoredHeight)
      && proof.blockHash === boundAnchor.blockHash;
    return { valid, anchoredHeight: boundAnchor.anchoredHeight,
      stateRoot: boundAnchor.stateRoot, transactionHash: boundAnchor.transactionHash,
      l1Slot: boundAnchor.l1Slot };
  }

  function normalizePrimaryProof(source: Record<string, unknown>): Record<string, unknown> {
    return {
      ...source,
      stateRoot: source.stateRoot ?? source.stateRootHex,
      key: source.key ?? source.keyHex,
      committedHeight: source.committedHeight ?? source.height,
      genesisId: source.genesisId ?? source.genesisIdHex,
      blockHash: source.blockHash ?? source.blockHashHex
    };
  }

  function hasComponent(value: AppChainStatus | null, componentId: string): boolean {
    return value?.capabilityManifest?.components.some((component) => component.id === componentId) ?? false;
  }
  function canGenerate(subject: Subject) { return Boolean(selectedProofs[subject]?.response.proof); }
  function verificationAccepted(subject: Subject) {
    const result = proofVerifications[subject];
    return Boolean(result && typeof result === 'object'
      && !Array.isArray(result) && (result as Record<string, unknown>).accepted === true);
  }
  function verificationExplanation(subject: Subject) {
    const result = proofVerifications[subject];
    return result && typeof result === 'object' && !Array.isArray(result)
      ? String((result as Record<string, unknown>).explanation ?? '') : '';
  }
  function parameterFieldCatalog(value: Record<string, unknown> | null) {
    if (!value || !Array.isArray(value.fields)) return [] as Array<{ id: string; type: string; value: unknown }>;
    return value.fields.flatMap((item) => {
      if (!item || typeof item !== 'object' || Array.isArray(item)) return [];
      const field = item as Record<string, unknown>;
      return typeof field.id === 'string' && typeof field.type === 'string'
        ? [{ id: field.id, type: field.type, value: field.value }] : [];
    });
  }
  function displayClaimValue(value: unknown, type: string) {
    if (type === 'rational' && Array.isArray(value) && value.length === 2) {
      return `${value[0]}/${value[1]}`;
    }
    return typeof value === 'string' ? value : JSON.stringify(value);
  }
  function numericParameterType(type: string | undefined) {
    return type === 'unsigned-integer' || type === 'signed-integer' || type === 'lovelace';
  }
  function stakeNeedsCoin() {
    return ['minimum', 'exact', 'minimum-and-pool', 'exact-and-pool'].includes(stakeClaimMode);
  }
  function stakeNeedsPool() {
    return ['pool', 'minimum-and-pool', 'exact-and-pool'].includes(stakeClaimMode);
  }
  function validHash(value: string, bytes: number) {
    return new RegExp(`^[0-9a-fA-F]{${bytes * 2}}$`).test(value);
  }
  function validStakeAddress(value: string) {
    return value.length <= 200 && /^(stake|stake_test)1[0-9a-z]+$/.test(value);
  }
  function asRecord(value: unknown): Record<string, unknown> {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected a JSON object');
    return value as Record<string, unknown>;
  }
  function requiredString(value: Record<string, unknown>, field: string) {
    const result = value[field];
    if (typeof result !== 'string' || !result) throw new Error(`Proof is missing ${field}`);
    return result;
  }
  function optionalString(value: Record<string, unknown>, field: string) {
    return typeof value[field] === 'string' ? value[field] as string : undefined;
  }
  function pretty(value: unknown) { return JSON.stringify(value, null, 2); }
  function short(value: unknown) {
    const text = String(value ?? '—');
    return text.length > 30 ? `${text.slice(0, 16)}…${text.slice(-10)}` : text;
  }
</script>

<svelte:head><title>Yano · Cardano History</title></svelte:head>

<div class="mb-5 flex flex-wrap items-end justify-between gap-4">
  <div><p class="eyebrow">Authenticated L1 history</p><h1 class="mt-1 text-2xl font-bold">Cardano History</h1>
    <p class="mt-2 max-w-3xl text-sm text-slate-400">Query epoch facts, generate subject-specific proofs, and verify them against L1-anchored app-chain roots.</p></div>
  {#if chains.length > 0}<label class="text-xs text-slate-400">Chain
    <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
      value={selectedChain} onchange={(event) => void selectChain(event.currentTarget.value)}>
      {#each chains as chain}<option value={chain.chainId}>{chain.chainId}</option>{/each}
    </select></label>{/if}
</div>

{#if loading}<p class="text-slate-400">Discovering Cardano History capabilities…</p>
{:else if error}<div class="card border-rose-500/30 p-5 text-rose-300">{error}</div>
{:else if chains.length === 0}<div class="card p-6 text-slate-400">No running app chain declares the <code>l1-epoch-params-v1</code> capability.</div>
{:else}
  <nav class="mb-5 flex flex-wrap gap-2" aria-label="Cardano History views">
    {#each ['overview', 'parameters', ...(hasStake ? ['stake'] : []), ...(hasGovernance ? ['governance'] : []), 'verify'] as item}
      <button class="rounded-lg px-4 py-2 text-sm font-semibold {view === item ? 'bg-cyan-500/20 text-cyan-200' : 'bg-slate-900 text-slate-400'}"
        onclick={() => view = item as View}>{item === 'verify' ? 'Verify proof' : item[0].toUpperCase() + item.slice(1)}</button>
    {/each}
  </nav>

  <div class="mb-5 rounded-xl border border-cyan-500/25 bg-cyan-500/5 p-4 text-sm text-slate-300">
    <div class="font-semibold text-cyan-200">How verification works</div>
    <ol class="mt-2 grid gap-2 md:grid-cols-3">
      <li><span class="font-semibold text-white">1. Query a fact.</span> See the value recorded for an epoch.</li>
      <li><span class="font-semibold text-white">2. Describe a claim.</span> For example, stake is at least 5 ADA.</li>
      <li><span class="font-semibold text-white">3. Verify both.</span> The proof must be authentic and the claim must be true.</li>
    </ol>
    <p class="mt-2 text-xs text-slate-400">MPF is the authenticated map behind the scenes. You do not need to understand its internal nodes to verify a fact.</p>
  </div>

  {#if initializationPending}
    <div class="mb-5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-200">
      The capability and anchor are active, but this fresh chain has not finalized its first retained or live epoch fact yet.
    </div>
  {/if}

  {#if view === 'overview'}
    <div class="grid gap-4 lg:grid-cols-2">
      <section class="card p-5"><div class="section-title">Commitment</div>
        <dl class="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
          <dt class="text-slate-500">Chain</dt><dd>{selectedChain}</dd>
          <dt class="text-slate-500">Height</dt><dd>{history?.committedHeight ?? status?.tipHeight ?? 0}</dd>
          <dt class="text-slate-500">Latest parameter epoch</dt><dd>{history?.latestEpoch ?? 'not observed'}</dd>
          <dt class="text-slate-500">Latest stake epoch</dt><dd>{stakeEpochs[0] ?? 'not observed'}</dd>
          <dt class="text-slate-500">Latest DRep epoch</dt><dd>{drepEpochs[0] ?? 'not observed'}</dd>
          <dt class="text-slate-500">State root</dt><dd class="font-mono text-xs">{short(history?.stateRoot ?? status?.stateRoot)}</dd>
        </dl></section>
      <section class="card p-5"><div class="section-title">Enabled datasets</div>
        <div class="mt-4 flex flex-wrap gap-2">
          {#each status?.capabilityManifest?.components ?? [] as component}<span class="badge badge-ok">{component.id}</span>{/each}
        </div></section>
      <section class="card p-5"><div class="section-title">Available epochs by dataset</div>
        <div class="mt-4 space-y-3 text-sm">
          <div><span class="text-slate-500">Parameters</span><div class="mt-1 flex flex-wrap gap-2">{#each parameterEpochs as epoch}<span class="badge">{epoch}</span>{/each}{#if parameterEpochs.length === 0}<span>None</span>{/if}</div></div>
          {#if hasStake}<div><span class="text-slate-500">Completed stake snapshots</span><div class="mt-1 flex flex-wrap gap-2">{#each stakeEpochs as epoch}<span class="badge">{epoch}</span>{/each}{#if stakeEpochs.length === 0}<span>None</span>{/if}</div></div>{/if}
          {#if hasGovernance}<div><span class="text-slate-500">Completed DRep snapshots</span><div class="mt-1 flex flex-wrap gap-2">{#each drepEpochs as epoch}<span class="badge">{epoch}</span>{/each}{#if drepEpochs.length === 0}<span>None</span>{/if}</div></div>{/if}
        </div></section>
      <section class="card p-5"><div class="section-title">L1 anchor</div><pre class="mt-4 max-h-80 overflow-auto text-xs">{pretty(anchor)}</pre></section>
    </div>
  {:else if view === 'parameters'}
    <div class="space-y-4">
      <section class="card p-5"><div class="section-title">Protocol parameters</div>
        <p class="mt-2 text-sm text-slate-400">Load the parameter document for an epoch, then choose one named field to prove.</p>
        <div class="mt-4 flex flex-wrap items-end gap-3"><label class="text-xs text-slate-400">Epoch
          <input class="mt-1 block rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" bind:value={parameterEpoch}></label>
          <button class="rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold" onclick={() => void query('parameters', parameterEpoch, `epochs/${parameterEpoch}/parameters`)}>Load parameters</button></div>
        {#if parameterFields.length > 0}
          <div class="mt-4 max-h-96 overflow-auto rounded-lg border border-slate-800">
            <table class="w-full text-left text-sm"><thead class="sticky top-0 bg-slate-900 text-slate-400"><tr><th class="px-3 py-2">Parameter</th><th class="px-3 py-2">Type</th><th class="px-3 py-2">Recorded value</th></tr></thead>
              <tbody>{#each parameterFields as field}<tr class="border-t border-slate-800"><td class="px-3 py-2 font-mono text-cyan-200">{field.id}</td><td class="px-3 py-2 text-slate-400">{field.type}</td><td class="max-w-xl truncate px-3 py-2 font-mono text-xs">{displayClaimValue(field.value, field.type)}</td></tr>{/each}</tbody></table>
          </div>
        {:else}<p class="mt-4 text-sm text-slate-500">Load an epoch to see its authenticated named parameters.</p>{/if}
      </section>
      <section class="card p-5"><div class="section-title">Prove a parameter claim</div>
        <p class="mt-2 text-sm text-slate-400">A field proof authenticates one named value, such as <code>key-deposit</code>, without carrying the complete parameter document.</p>
        <div class="mt-4 grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <label class="text-xs text-slate-400">Parameter<select class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" value={parameterFieldId} onchange={(event) => selectParameterField(event.currentTarget.value)}>{#each parameterFields as field}<option value={field.id}>{field.id}</option>{/each}</select></label>
          <label class="text-xs text-slate-400">Claim<select class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={parameterClaimMode}><option value="exact">Equals</option>{#if numericParameterType(selectedParameterField?.type)}<option value="minimum">At least</option><option value="maximum">At most</option><option value="range">Inside range</option>{/if}</select></label>
          <label class="text-xs text-slate-400">{parameterClaimMode === 'range' ? 'Minimum' : 'Expected value'}<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={parameterExpectedValue} placeholder={selectedParameterField?.type === 'lovelace' ? '2000000' : 'Value'}>{#if selectedParameterField?.type === 'lovelace'}<span class="mt-1 block text-[11px] text-slate-500">Enter lovelace (1 ADA = 1,000,000 lovelace).</span>{/if}</label>
          {#if parameterClaimMode === 'range'}<label class="text-xs text-slate-400">Maximum<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={parameterMaximumValue}></label>{/if}
        </div>
        <button class="mt-3 rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold disabled:opacity-40" disabled={!parameterFieldId} onclick={() => void queryParameterField()}>Query selected parameter</button>
        {#if selectedProofs.parameters?.response.dataset === 'protocol-parameter-field'}<div class="mt-4 rounded-lg border border-slate-800 bg-slate-950/40 p-4 text-sm"><div class="text-slate-400">Recorded value</div><div class="mt-1 font-mono text-lg text-white">{displayClaimValue(selectedProofs.parameters.response.value, String(selectedProofs.parameters.response.type ?? ''))}</div><div class="mt-1 text-xs text-slate-500">{selectedProofs.parameters.response.fieldId} · epoch {selectedProofs.parameters.epoch} · {selectedProofs.parameters.response.type}</div></div>{/if}
        <details class="mt-3"><summary class="cursor-pointer text-sm text-slate-400">Raw query response</summary><pre class="mt-2 max-h-64 overflow-auto text-xs">{queryResults.parameters}</pre></details>
        <p class="mt-3 text-sm text-slate-400">{proofStatuses.parameters}</p>
        <div class="mt-3 flex flex-wrap gap-2">
          <button class="rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold disabled:opacity-40" disabled={!canGenerate('parameters')} onclick={() => void generateProof('parameters')}>Generate proof</button>
          <button class="rounded-lg border border-slate-700 px-4 py-2 text-sm disabled:opacity-40" disabled={!proofBundles.parameters} onclick={() => void verifyGeneratedProof('parameters')}>Verify proof and claim</button>
          <button class="rounded-lg border border-slate-700 px-4 py-2 text-sm disabled:opacity-40" disabled={!verificationAccepted('parameters')} onclick={() => downloadProof('parameters')}>Export accepted package</button>
        </div>
        {#if proofVerifications.parameters}<div class="mt-4 rounded-lg border p-4 {verificationAccepted('parameters') ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200' : 'border-rose-500/40 bg-rose-500/10 text-rose-200'}"><div class="font-semibold">{verificationAccepted('parameters') ? 'Claim accepted' : 'Claim not accepted'}</div><p class="mt-1 text-sm">{verificationExplanation('parameters')}</p></div><details class="mt-3"><summary class="cursor-pointer text-sm text-slate-400">Technical verification details</summary><pre class="mt-2 max-h-64 overflow-auto text-xs">{pretty(proofVerifications.parameters)}</pre></details>{/if}
        {#if proofBundles.parameters}<details class="mt-3"><summary class="cursor-pointer text-sm text-slate-400">Generated proof package</summary><pre class="mt-2 max-h-[620px] overflow-auto text-xs">{pretty(proofBundles.parameters)}</pre></details>{/if}
      </section>
    </div>
  {:else if view === 'stake'}
    <div class="space-y-4">
      <section class="card p-5"><div class="section-title">End-of-epoch stake credential</div>
        <div class="mt-4 flex gap-2" role="group" aria-label="Stake credential input mode">
          <button class="rounded-lg px-3 py-2 text-sm {stakeInputMode === 'address' ? 'bg-cyan-500/20 text-cyan-200' : 'bg-slate-900 text-slate-400'}" onclick={() => stakeInputMode = 'address'}>Stake address</button>
          <button class="rounded-lg px-3 py-2 text-sm {stakeInputMode === 'credential' ? 'bg-cyan-500/20 text-cyan-200' : 'bg-slate-900 text-slate-400'}" onclick={() => stakeInputMode = 'credential'}>Advanced credential</button>
        </div>
        <div class="mt-4 grid gap-3 md:grid-cols-3"><label class="text-xs text-slate-400">Completed stake epoch<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" bind:value={stakeEpoch}></label>
          {#if stakeInputMode === 'address'}
            <label class="text-xs text-slate-400 md:col-span-2">Stake address<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" placeholder="stake_test1…" bind:value={stakeAddress}></label>
          {:else}
            <label class="text-xs text-slate-400">Credential type<select class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={credentialType}><option value={0}>Key</option><option value={1}>Script</option></select></label>
            <label class="text-xs text-slate-400">Credential hash<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={credentialHash}></label>
          {/if}</div>
        <button class="mt-3 rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold disabled:opacity-40" disabled={stakeInputMode === 'address' ? !validStakeAddress(stakeAddress) : !validHash(credentialHash, 28)} onclick={() => void queryStake()}>Query stake</button>
        {#if selectedProofs.stake}<div class="mt-4 grid gap-3 rounded-lg border border-slate-800 bg-slate-950/40 p-4 text-sm sm:grid-cols-3"><div><div class="text-slate-500">Recorded stake</div><div class="mt-1 font-mono text-white">{selectedProofs.stake.response.coin ?? 'Not found'} lovelace</div></div><div><div class="text-slate-500">Delegated pool</div><div class="mt-1 break-all font-mono text-xs text-white">{selectedProofs.stake.response.poolHash ?? 'None'}</div></div><div><div class="text-slate-500">Snapshot</div><div class="mt-1 text-white">{selectedProofs.stake.response.complete === true ? 'Complete' : 'Incomplete'}</div></div></div>{/if}
        <details class="mt-3"><summary class="cursor-pointer text-sm text-slate-400">Technical query details</summary><p class="mt-2 text-xs text-slate-500"><code>secondaryKey</code> locates this credential in the epoch snapshot. <code>completenessPhysicalKey</code> locates the record proving that the snapshot finished loading.</p><pre class="mt-3 max-h-[520px] overflow-auto text-xs">{queryResults.stake}</pre></details></section>
      <section class="card p-5"><div class="section-title">Stake proof and claim</div>
        <p class="mt-2 text-sm text-slate-400">A valid proof authenticates the recorded stake. The claim check separately decides whether your requested amount or pool condition is true.</p>
        <div class="mt-4 grid gap-3 md:grid-cols-3">
          <label class="text-xs text-slate-400">Claim<select class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={stakeClaimMode}><option value="minimum">At least amount</option><option value="exact">Exact amount</option><option value="pool">Delegated to pool</option><option value="minimum-and-pool">At least amount and pool</option><option value="exact-and-pool">Exact amount and pool</option><option value="absence">Credential absent</option></select></label>
          {#if stakeNeedsCoin()}<label class="text-xs text-slate-400">Expected lovelace<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" inputmode="numeric" bind:value={stakeClaimCoin}><span class="mt-1 block text-[11px] text-slate-500">1 ADA = 1,000,000 lovelace</span></label>{/if}
          {#if stakeNeedsPool()}<label class="text-xs text-slate-400">Expected pool hash<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={stakeClaimPool}></label>{/if}
        </div>
        <p class="mt-3 text-xs text-amber-300">This screen evaluates the claim off-chain after authenticating the MPF proof. The bounded on-chain redeemer exporter remains a separate ADR-036 milestone.</p>
        <p class="mt-3 text-sm text-slate-400">{proofStatuses.stake}</p>
        <div class="mt-3 flex flex-wrap gap-2">
          <button class="rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold disabled:opacity-40" disabled={!canGenerate('stake')} onclick={() => void generateProof('stake')}>Generate proof</button>
          <button class="rounded-lg border border-slate-700 px-4 py-2 text-sm disabled:opacity-40" disabled={!proofBundles.stake} onclick={() => void verifyGeneratedProof('stake')}>Verify proof and claim</button>
          <button class="rounded-lg border border-slate-700 px-4 py-2 text-sm disabled:opacity-40" disabled={!verificationAccepted('stake')} onclick={() => downloadProof('stake')}>Export accepted package</button>
        </div>
        {#if proofVerifications.stake}<div class="mt-4 rounded-lg border p-4 {verificationAccepted('stake') ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200' : 'border-rose-500/40 bg-rose-500/10 text-rose-200'}"><div class="font-semibold">{verificationAccepted('stake') ? 'Claim accepted' : 'Claim not accepted'}</div><p class="mt-1 text-sm">{verificationExplanation('stake')}</p></div><details class="mt-3"><summary class="cursor-pointer text-sm text-slate-400">Technical verification details</summary><pre class="mt-2 max-h-64 overflow-auto text-xs">{pretty(proofVerifications.stake)}</pre></details>{/if}
        {#if proofBundles.stake}<details class="mt-3"><summary class="cursor-pointer text-sm text-slate-400">Generated proof package</summary><pre class="mt-2 max-h-[620px] overflow-auto text-xs">{pretty(proofBundles.stake)}</pre></details>{/if}
      </section>
    </div>
  {:else if view === 'governance'}
    <div class="space-y-4">
      <div class="grid gap-4 lg:grid-cols-2"><section class="card p-5"><div class="section-title">DRep distribution</div>
        <div class="mt-4 grid gap-3"><label class="text-xs text-slate-400">Completed DRep epoch<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" bind:value={drepEpoch}></label>
          <label class="text-xs text-slate-400">DRep type<select class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={drepType}><option value={0}>Key</option><option value={1}>Script</option></select></label>
          <label class="text-xs text-slate-400">DRep hash<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={drepHash}></label></div>
        <button class="mt-3 rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold" disabled={!validHash(drepHash, 28)} onclick={() => void query('drep', drepEpoch, `epochs/${drepEpoch}/dreps/${drepType}/${drepHash}`)}>Query DRep</button>
        <pre class="mt-4 max-h-80 overflow-auto text-xs">{queryResults.drep}</pre></section>
        <section class="card p-5"><div class="section-title">Proposal history</div><div class="mt-4 grid gap-3">
          <label class="text-xs text-slate-400">Epoch<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" bind:value={proposalEpoch}></label>
          <label class="text-xs text-slate-400">Transaction ID<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={proposalTx}></label>
          <label class="text-xs text-slate-400">Action index<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" max="65535" bind:value={proposalIndex}></label></div>
          <button class="mt-3 rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold" disabled={!validHash(proposalTx, 32)} onclick={() => void query('proposal', proposalEpoch, `proposals/${proposalTx}/${proposalIndex}`, { epoch: String(proposalEpoch) })}>Query proposal</button>
          <pre class="mt-4 max-h-80 overflow-auto text-xs">{queryResults.proposal}</pre></section></div>
      {#each ['drep', 'proposal'] as subject}
        <section class="card p-5"><div class="section-title">{subject === 'drep' ? 'DRep' : 'Proposal'} proof</div>
          <p class="mt-3 text-sm text-slate-400">{proofStatuses[subject as Subject]}</p>
          <div class="mt-3 flex flex-wrap gap-2">
            <button class="rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold disabled:opacity-40" disabled={!canGenerate(subject as Subject)} onclick={() => void generateProof(subject as Subject)}>Generate proof</button>
            <button class="rounded-lg border border-slate-700 px-4 py-2 text-sm disabled:opacity-40" disabled={!proofBundles[subject as Subject]} onclick={() => void verifyGeneratedProof(subject as Subject)}>Verify off-chain</button>
            <button class="rounded-lg border border-slate-700 px-4 py-2 text-sm disabled:opacity-40" disabled={!proofBundles[subject as Subject]} onclick={() => downloadProof(subject as Subject)}>Export JSON</button>
          </div>
          {#if proofVerifications[subject as Subject]}<pre class="mt-4 max-h-64 overflow-auto text-xs">{pretty(proofVerifications[subject as Subject])}</pre>{/if}
          {#if proofBundles[subject as Subject]}<pre class="mt-4 max-h-[620px] overflow-auto text-xs">{pretty(proofBundles[subject as Subject])}</pre>{/if}
        </section>
      {/each}
    </div>
  {:else}
    <section class="card p-5"><div class="section-title">Verify an exported proof</div>
      <p class="mt-3 text-sm text-slate-400">Verification runs through the connected node’s bounded verifier. Without independent context, snapshot bundles use that node’s retained L1 anchor while primary proofs verify only against their bundled root. Supply the complete caller-pinned anchor context to verify against values obtained independently.</p>
      <label class="mt-4 block text-xs text-slate-400">Proof JSON<textarea class="mt-1 h-64 w-full rounded-lg border border-slate-700 bg-slate-950 p-3 font-mono text-xs" bind:value={importedProof}></textarea></label>
      <label class="mt-4 block text-xs text-slate-400">Independent anchor context JSON (optional)<textarea class="mt-1 h-40 w-full rounded-lg border border-slate-700 bg-slate-950 p-3 font-mono text-xs" bind:value={importedAnchor}></textarea></label>
      <button class="mt-3 rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold disabled:opacity-40" disabled={!importedProof.trim()} onclick={() => void verifyImportedProof()}>Verify proof</button>
      <p class="mt-3 text-sm text-slate-400">{importedStatus}</p>
      {#if importedVerdict}<div class="mt-4 rounded-lg border p-4 {asRecord(importedVerdict).accepted === true ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200' : 'border-rose-500/40 bg-rose-500/10 text-rose-200'}"><div class="font-semibold">{asRecord(importedVerdict).accepted === true ? 'Proof and claim accepted' : 'Not accepted'}</div><p class="mt-1 text-sm">{String(asRecord(importedVerdict).explanation ?? 'Review the technical result and trust warning.')}</p></div><details class="mt-3"><summary class="cursor-pointer text-sm text-slate-400">Technical verification details</summary><pre class="mt-2 max-h-[520px] overflow-auto text-xs">{pretty(importedVerdict)}</pre></details>{/if}
    </section>
  {/if}
{/if}
