<script lang="ts">
  import { base } from '$app/paths';
  import { onMount } from 'svelte';
  import { ApiError, apiFailureMessage, resolveApiBase, YanoApi } from '$lib/api/client';
  import type {
    AppChainStatus, AuthMapApprovalConsumptionRecord, AuthMapAuthorityRecord,
    AuthMapCollection, AuthMapCommandResultRecord, AuthMapDirectConsumptionRecord,
    AuthMapDirectPolicyRecord, AuthMapEntryRecord, AuthMapEnvelope,
    AuthMapGovernanceMutationRecord, AuthMapMetadataRecord, AuthMapPendingRecord,
    AuthMapReceiptRecord, ChainSummary, RoleActorRecord, RoleApprovalStatsRecord,
    RoleOrganizationRecord, RolePolicyRecord, RoleProposalRecord, StateProofEnvelope
  } from '$lib/api/types';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import { messagePreview } from '$lib/appchain/message-preview';
  import { shortHash } from '$lib/appchain/value';
  import { assessProofBinding } from '$lib/appchain/proof-verification';
  import {
    ACTOR_COMMAND_TOPIC, AUTHMAP_API_VERSION, AUTHMAP_COMMAND_TOPIC,
    assertAuthMapEnvelope, assertPendingEnvelope, authorizationLabel,
    decodeEntryRecordValue, directSubmitAllowed, encodeBasicEnvelopeHex,
    encodeCommandHex, entryStatusLabel,
    governedCliHint, isAuthenticatedMapChain, isHex, OP_COMPARE_AND_SET, OP_PUT,
    OP_PUT_IF_ABSENT, OP_RESTORE, OP_REVOKE, OP_TRANSFER_CONTROLLER, OPERATION_LABELS,
    preflightMutation, presenceLabel, receiptErrorLabel, receiptStatusLabel,
    ROLE_COMMAND_TOPIC, utf8ToHex, valueEncodingLabel, type DecodedEntry,
    type MutationSpec
  } from '$lib/authmap/model';

  type View = 'overview' | 'collections' | 'lookup' | 'mutations' | 'actors'
    | 'policies' | 'approvals' | 'governance' | 'proofs' | 'explorer';
  type Tracking = {
    messageId: string;
    topic: string;
    entryCollection: string;
    entryKeyHex: string;
    finalizedHeight: number | null;
    receipt: AuthMapEnvelope<AuthMapReceiptRecord> | null;
    commandResult: AuthMapEnvelope<AuthMapCommandResultRecord> | null;
    entryAfter: AuthMapEnvelope<AuthMapEntryRecord> | null;
    failure: string;
    done: boolean;
  };
  type ProofPanel = {
    proof: StateProofEnvelope | null;
    verified: boolean | null;
    rootBound: boolean | null;
    message: string;
  };

  let api: YanoApi | null = null;
  let chains: ChainSummary[] = [];
  let selectedChain = '';
  let status: AppChainStatus | null = null;
  let metadata: AuthMapEnvelope<AuthMapMetadataRecord> | null = null;
  let stateIdentity: Record<string, unknown> | null = null;
  let identityFailure = '';
  let activeView: View = 'overview';
  let pageError = '';
  let loading = false;
  let controller: AbortController | null = null;
  let trackTimer: ReturnType<typeof setInterval> | null = null;

  // Lookup view
  let lookupCollection = '';
  let lookupKeyMode: 'text' | 'hex' = 'text';
  let lookupKey = '';
  let lookupError = '';
  let entry: AuthMapEnvelope<AuthMapEntryRecord> | null = null;
  let entryDecoded: DecodedEntry | null = null;
  let entryProof: ProofPanel | null = null;
  let receiptInput = '';
  let receiptError = '';
  let receipt: AuthMapEnvelope<AuthMapReceiptRecord> | null = null;

  // Mutations view
  let mutationOperation = OP_PUT;
  let mutationCollection = '';
  let mutationKeyMode: 'text' | 'hex' = 'text';
  let mutationKey = '';
  let mutationValueMode: 'text' | 'hex' = 'text';
  let mutationValue = '';
  let mutationRevision = '';
  let mutationValueHash = '';
  let mutationController = '';
  let preflight = '';
  let preflightOk = false;
  let commandHex = '';
  let submitHex = '';
  let importedCommand = '';
  let importedTopic = AUTHMAP_COMMAND_TOPIC;
  let submitError = '';
  let tracking: Tracking | null = null;

  // Governed views
  let actorInput = '';
  let actorRevision = '';
  let actor: AuthMapEnvelope<RoleActorRecord> | null = null;
  let organizationInput = '';
  let organization: AuthMapEnvelope<RoleOrganizationRecord> | null = null;
  let actorsError = '';
  let policyInput = '';
  let approvalPolicy: AuthMapEnvelope<RolePolicyRecord> | null = null;
  let directPolicyInput = '';
  let directPolicyRevision = '';
  let directPolicy: AuthMapEnvelope<AuthMapDirectPolicyRecord> | null = null;
  let authorityInput = '';
  let authorityRevision = '';
  let authority: AuthMapEnvelope<AuthMapAuthorityRecord> | null = null;
  let policiesError = '';
  let stats: AuthMapEnvelope<RoleApprovalStatsRecord> | null = null;
  let pendingApprovals: AuthMapEnvelope<AuthMapPendingRecord> | null = null;
  let proposal: AuthMapEnvelope<RoleProposalRecord> | null = null;
  let approvalConsumption: AuthMapEnvelope<AuthMapApprovalConsumptionRecord> | null = null;
  let approvalsError = '';
  let pendingActorGov: AuthMapEnvelope<AuthMapPendingRecord> | null = null;
  let pendingPolicyGov: AuthMapEnvelope<AuthMapPendingRecord> | null = null;
  let governanceMutation: AuthMapEnvelope<AuthMapGovernanceMutationRecord> | null = null;
  let governanceComponent: 'actor' | 'policy' = 'actor';
  let governanceInput = '';
  let commandResultComponent = 'role-approvals';
  let commandResultInput = '';
  let commandResult: AuthMapEnvelope<AuthMapCommandResultRecord> | null = null;
  let governanceError = '';
  let directConsumptionActor = '';
  let directConsumptionId = '';
  let directConsumption: AuthMapEnvelope<AuthMapDirectConsumptionRecord> | null = null;

  // Proofs view
  let proofKeyInput = '';
  let proofHeightInput = '';
  let proofPanel: ProofPanel | null = null;
  let proofError = '';

  $: governed = metadata?.record.governed === true;
  $: collections = metadata?.record.collections ?? [];
  $: mutationDescriptor = collections.find((entry) => entry.id === mutationCollection);
  $: mutationDirect = directSubmitAllowed(mutationDescriptor);
  $: views = (governed
    ? ['overview', 'collections', 'lookup', 'mutations', 'actors', 'policies',
      'approvals', 'governance', 'proofs', 'explorer']
    : ['overview', 'collections', 'lookup', 'mutations', 'proofs', 'explorer']) as View[];

  const query = () => ({ chain: selectedChain });
  const asHex = (mode: 'text' | 'hex', value: string) => {
    const trimmed = value.trim();
    if (mode === 'text') return utf8ToHex(trimmed);
    if (!isHex(trimmed)) throw new Error('Value must be canonical even-length lowercase hex');
    return trimmed;
  };

  onMount(() => {
    let disposed = false;
    void (async () => {
      try {
        api = new YanoApi(await resolveApiBase());
        chains = await api.chains();
        if (disposed) return;
        const parameters = new URLSearchParams(location.search);
        const requested = parameters.get('chain');
        selectedChain = chains.some((chain) => chain.chainId === requested)
          ? requested! : chains[0]?.chainId ?? '';
        if (!selectedChain) {
          pageError = 'No app chains are enabled on this node.';
          return;
        }
        await activateChain(selectedChain);
      } catch (cause) {
        pageError = apiFailureMessage(cause, 'Unable to load the authenticated-map console');
      }
    })();
    return () => {
      disposed = true;
      controller?.abort();
      if (trackTimer) clearInterval(trackTimer);
    };
  });

  async function activateChain(chainId: string): Promise<void> {
    if (!api) return;
    controller?.abort();
    controller = new AbortController();
    const signal = controller.signal;
    selectedChain = chainId;
    status = null;
    metadata = null;
    stateIdentity = null;
    identityFailure = '';
    entry = null; entryDecoded = null; entryProof = null; receipt = null;
    actor = null; organization = null; approvalPolicy = null; directPolicy = null;
    authority = null; stats = null; pendingApprovals = null; proposal = null;
    approvalConsumption = null; pendingActorGov = null; pendingPolicyGov = null;
    governanceMutation = null; commandResult = null; directConsumption = null;
    tracking = null; proofPanel = null;
    pageError = ''; lookupError = ''; submitError = '';
    loading = true;
    try {
      status = await api.chainStatus(chainId, signal);
      if (!isAuthenticatedMapChain(status)) return;
      // §10.1: the route independently rechecks chain identity and domain-API
      // version before making any further domain request.
      try {
        const envelope = await api.authenticatedMapDomain<AuthMapEnvelope<AuthMapMetadataRecord>>(
          'authenticated-map', query(), signal);
        assertAuthMapEnvelope(envelope, chainId);
        if (envelope.record.type !== 'metadata'
          || envelope.record.apiVersion !== AUTHMAP_API_VERSION) {
          throw new Error('The chain does not report the supported authenticated-map domain API');
        }
        metadata = envelope;
        lookupCollection = envelope.record.collections[0]?.id ?? '';
        mutationCollection = lookupCollection;
      } catch (cause) {
        identityFailure = apiFailureMessage(cause,
          'The authenticated-map domain API is unavailable on this chain');
        return;
      }
      try {
        stateIdentity = await api.chainStateIdentity(chainId, signal);
      } catch {
        stateIdentity = null;
      }
      if (metadata.record.governed) {
        await Promise.all([loadStats(signal), loadPendingApprovals('', signal)]);
      }
      const parameters = new URLSearchParams(location.search);
      parameters.set('chain', chainId);
      history.replaceState({}, '', `${base}/app-chain/authenticated-map/?${parameters}`);
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        pageError = apiFailureMessage(cause, 'Unable to load authenticated-map data');
      }
    } finally {
      if (!signal.aborted) loading = false;
    }
  }

  async function mapGet<T>(path: string, extra: Record<string, string> = {},
                           signal?: AbortSignal): Promise<AuthMapEnvelope<T>> {
    if (!api) throw new Error('API unavailable');
    const envelope = await api.authenticatedMapDomain<AuthMapEnvelope<T>>(
      path, { ...query(), ...extra }, signal);
    assertAuthMapEnvelope(envelope, selectedChain);
    return envelope;
  }

  async function roleGet<T>(path: string, extra: Record<string, string> = {},
                            signal?: AbortSignal): Promise<AuthMapEnvelope<T>> {
    if (!api) throw new Error('API unavailable');
    const envelope = await api.authenticatedMapDomain<AuthMapEnvelope<T>>(
      path, { ...query(), ...extra }, signal);
    assertAuthMapEnvelope(envelope, selectedChain, false);
    return envelope;
  }

  async function lookupEntry(): Promise<void> {
    lookupError = '';
    entry = null; entryDecoded = null; entryProof = null;
    try {
      const keyHex = asHex(lookupKeyMode, lookupKey);
      if (!keyHex) throw new Error('Enter an application key');
      const envelope = await mapGet<AuthMapEntryRecord>(
        `authenticated-map/entries/${lookupCollection}/${keyHex}`);
      entry = envelope;
      entryDecoded = envelope.record.presence !== 0 && envelope.recordValue
        ? decodeEntryRecordValue(envelope.recordValue) : null;
    } catch (cause) {
      lookupError = cause instanceof ApiError && cause.status === 404
        ? 'No record exists for that collection/key.'
        : apiFailureMessage(cause, 'Entry lookup failed');
    }
  }

  async function lookupReceipt(): Promise<void> {
    receiptError = '';
    receipt = null;
    try {
      const id = receiptInput.trim();
      if (!/^[0-9a-f]{64}$/.test(id)) throw new Error('Receipt lookup requires a 32-byte message ID');
      receipt = await mapGet<AuthMapReceiptRecord>(`authenticated-map/receipts/${id}`);
    } catch (cause) {
      receiptError = cause instanceof ApiError && cause.status === 404
        ? 'No receipt is retained for that message.'
        : apiFailureMessage(cause, 'Receipt lookup failed');
    }
  }

  async function fetchProof(keyHex: string, envelopeRoot: string, valueHex: string | undefined,
                            target: 'entry' | 'panel', height?: number): Promise<void> {
    if (!api) return;
    const panel: ProofPanel = { proof: null, verified: null, rootBound: null, message: '' };
    if (target === 'entry') entryProof = panel; else proofPanel = panel;
    try {
      const proof = await api.chainProof(selectedChain, keyHex, height);
      panel.proof = proof;
      panel.rootBound = envelopeRoot
        ? assessProofBinding(proof, envelopeRoot).rootMatches : null;
      if (proof.proofWireHex && proof.presence) {
        const inclusion = proof.presence !== 'ABSENT';
        const result = await api.verifyChainProof(selectedChain, {
          mode: inclusion ? 'inclusion' : 'exclusion',
          profile: proof.profile,
          presence: proof.presence,
          expectedRootHex: proof.stateRoot,
          keyHex: proof.key,
          valueHex: inclusion ? proof.valueHex ?? valueHex : undefined,
          proofWireHex: proof.proofWireHex
        });
        panel.verified = result.valid === true;
        panel.message = result.valid ? `Verified by ${result.verifier ?? 'the host verifier'}`
          : 'The host verifier rejected this proof.';
      } else {
        panel.message = 'The proof envelope lacks a verifiable wire proof.';
      }
    } catch (cause) {
      panel.message = apiFailureMessage(cause, 'Proof retrieval failed');
    }
    if (target === 'entry') entryProof = { ...panel }; else proofPanel = { ...panel };
  }

  function runPreflight(): void {
    preflight = ''; preflightOk = false; commandHex = ''; submitHex = '';
    try {
      const spec = buildSpec();
      preflightMutation(spec, mutationDescriptor);
      // Legacy plain command bytes: the offline CLI's --command-hex input.
      commandHex = encodeCommandHex([spec], collections);
      if (directSubmitAllowed(mutationDescriptor)) {
        // The chain admits only the final v1 envelope on the wire.
        submitHex = encodeBasicEnvelopeHex([spec], collections);
      }
      preflightOk = true;
      preflight = submitHex
        ? `Preflight OK — canonical command envelope is ${submitHex.length / 2} bytes on `
          + `${AUTHMAP_COMMAND_TOPIC}.`
        : 'Preflight OK — this collection requires externally signed evidence.';
    } catch (cause) {
      preflight = cause instanceof Error ? cause.message : 'Preflight failed';
    }
  }

  function buildSpec(): MutationSpec {
    const wantsValue = [OP_PUT, OP_PUT_IF_ABSENT, OP_RESTORE, OP_COMPARE_AND_SET]
      .includes(mutationOperation);
    return {
      operation: mutationOperation,
      collectionId: mutationCollection,
      keyHex: asHex(mutationKeyMode, mutationKey),
      valueHex: wantsValue ? asHex(mutationValueMode, mutationValue) : '',
      expectedRevision: mutationRevision.trim() === '' ? 0 : Number(mutationRevision.trim()),
      expectedValueHashHex: mutationValueHash.trim(),
      newControllerHex: mutationOperation === OP_TRANSFER_CONTROLLER
        ? mutationController.trim() : ''
    };
  }

  async function submitDirect(): Promise<void> {
    submitError = '';
    if (!api || !submitHex || !preflightOk) return;
    try {
      const accepted = await api.chainSubmitMessage(
        selectedChain, AUTHMAP_COMMAND_TOPIC, submitHex);
      startTracking(accepted.messageId, AUTHMAP_COMMAND_TOPIC,
        mutationCollection, asHex(mutationKeyMode, mutationKey));
    } catch (cause) {
      submitError = apiFailureMessage(cause, 'Message submission failed');
    }
  }

  async function submitImported(): Promise<void> {
    submitError = '';
    if (!api) return;
    try {
      const bodyHex = importedCommand.trim();
      if (!isHex(bodyHex) || !bodyHex) throw new Error('Paste a canonical signed command hex');
      if (bodyHex.length > 2_097_152) throw new Error('Command exceeds the console size bound');
      const accepted = await api.chainSubmitMessage(selectedChain, importedTopic, bodyHex);
      startTracking(accepted.messageId, importedTopic, '', '');
    } catch (cause) {
      submitError = apiFailureMessage(cause, 'Message submission failed');
    }
  }

  // §10.3: HTTP accepted → finalized in a block → applied/rejected →
  // approval consumed and entry changed remain separate, observable facts.
  function startTracking(messageId: string, topic: string,
                         entryCollection: string, entryKeyHex: string): void {
    if (trackTimer) clearInterval(trackTimer);
    tracking = { messageId, topic, entryCollection, entryKeyHex,
      finalizedHeight: null, receipt: null,
      commandResult: null, entryAfter: null, failure: '', done: false };
    let attempts = 0;
    trackTimer = setInterval(() => void (async () => {
      if (!api || !tracking || tracking.done) return;
      attempts++;
      try {
        if (tracking.finalizedHeight === null) {
          try {
            const message = await api.chainMessage(selectedChain, tracking.messageId);
            tracking.finalizedHeight = message.height ?? 0;
          } catch (cause) {
            if (!(cause instanceof ApiError) || cause.status !== 404) throw cause;
          }
        }
        if (tracking.finalizedHeight !== null && !tracking.receipt && !tracking.commandResult) {
          if (tracking.topic === AUTHMAP_COMMAND_TOPIC) {
            try {
              const envelope = await mapGet<AuthMapReceiptRecord>(
                `authenticated-map/receipts/${tracking.messageId}`);
              if (envelope.record.presence === 1) tracking.receipt = envelope;
            } catch (cause) {
              if (!(cause instanceof ApiError) || cause.status !== 404) throw cause;
            }
          } else {
            const component = tracking.topic === ACTOR_COMMAND_TOPIC
              ? 'domain-actors' : 'role-approvals';
            try {
              tracking.commandResult = await mapGet<AuthMapCommandResultRecord>(
                `authenticated-map/command-results/${component}/${tracking.messageId}`);
            } catch (cause) {
              if (!(cause instanceof ApiError) || cause.status !== 404) throw cause;
            }
          }
        }
        const entryTrackable = tracking.entryCollection !== '' && tracking.entryKeyHex !== '';
        if (tracking.receipt && !tracking.entryAfter && entryTrackable) {
          try {
            tracking.entryAfter = await mapGet<AuthMapEntryRecord>(
              `authenticated-map/entries/${tracking.entryCollection}/${tracking.entryKeyHex}`);
          } catch {
            tracking.entryAfter = null;
          }
        }
        tracking.done = !!tracking.commandResult
          || !!(tracking.receipt && (tracking.entryAfter || !entryTrackable));
      } catch (cause) {
        tracking.failure = apiFailureMessage(cause, 'Tracking failed');
        tracking.done = true;
      }
      if ((tracking.done || attempts > 45) && trackTimer) {
        clearInterval(trackTimer);
        trackTimer = null;
        if (!tracking.done && !tracking.failure) {
          tracking.failure = 'Stopped after 90 seconds; the command may still finalize later.';
        }
        tracking.done = true;
      }
      tracking = { ...tracking };
    })(), 2_000);
  }

  async function loadStats(signal?: AbortSignal): Promise<void> {
    try {
      stats = await roleGet<RoleApprovalStatsRecord>('stats', {}, signal);
    } catch {
      stats = null;
    }
  }

  async function loadPendingApprovals(after: string, signal?: AbortSignal): Promise<void> {
    approvalsError = '';
    try {
      if (!api) return;
      const parameters: Record<string, string> = { ...query(), limit: '25' };
      if (after) parameters.after = after;
      const envelope = await api.authenticatedMapDomain<AuthMapEnvelope<AuthMapPendingRecord>>(
        'authenticated-map/pending/approvals', parameters, signal);
      assertPendingEnvelope(envelope, selectedChain);
      pendingApprovals = envelope;
    } catch (cause) {
      approvalsError = apiFailureMessage(cause, 'Pending approvals are unavailable');
    }
  }

  async function loadPendingGovernance(): Promise<void> {
    governanceError = '';
    try {
      if (!api) return;
      const [actors, policies] = await Promise.all([
        api.authenticatedMapDomain<AuthMapEnvelope<AuthMapPendingRecord>>(
          'authenticated-map/pending/actor-governance', { ...query(), limit: '25' }),
        api.authenticatedMapDomain<AuthMapEnvelope<AuthMapPendingRecord>>(
          'authenticated-map/pending/policy-governance', { ...query(), limit: '25' })
      ]);
      assertPendingEnvelope(actors, selectedChain);
      assertPendingEnvelope(policies, selectedChain);
      pendingActorGov = actors;
      pendingPolicyGov = policies;
    } catch (cause) {
      governanceError = apiFailureMessage(cause, 'Pending governance pages are unavailable');
    }
  }

  async function openProposal(id: string): Promise<void> {
    approvalsError = '';
    proposal = null; approvalConsumption = null;
    try {
      proposal = await roleGet<RoleProposalRecord>(`proposals/${encodeURIComponent(id)}`);
      try {
        approvalConsumption = await mapGet<AuthMapApprovalConsumptionRecord>(
          `authenticated-map/approval-consumptions/${encodeURIComponent(id)}`);
      } catch (cause) {
        if (!(cause instanceof ApiError) || cause.status !== 404) throw cause;
      }
      activeView = 'approvals';
    } catch (cause) {
      approvalsError = cause instanceof ApiError && cause.status === 404
        ? 'No proposal exists with that ID.'
        : apiFailureMessage(cause, 'Proposal lookup failed');
    }
  }

  async function openGovernanceMutation(id: string, component: 'actor' | 'policy'): Promise<void> {
    governanceError = '';
    governanceMutation = null;
    try {
      const path = component === 'actor'
        ? `authenticated-map/actor-governance/${encodeURIComponent(id)}`
        : `authenticated-map/policy-governance/${encodeURIComponent(id)}`;
      governanceMutation = await mapGet<AuthMapGovernanceMutationRecord>(path);
      activeView = 'governance';
    } catch (cause) {
      governanceError = cause instanceof ApiError && cause.status === 404
        ? 'No governance mutation exists with that ID.'
        : apiFailureMessage(cause, 'Governance lookup failed');
    }
  }

  async function lookupActor(): Promise<void> {
    actorsError = '';
    actor = null;
    try {
      const extra: Record<string, string> = {};
      if (actorRevision.trim()) extra.revision = actorRevision.trim();
      actor = await roleGet<RoleActorRecord>(
        `actors/${encodeURIComponent(actorInput.trim())}`, extra);
    } catch (cause) {
      actorsError = cause instanceof ApiError && cause.status === 404
        ? 'No actor record exists for that ID/revision.'
        : apiFailureMessage(cause, 'Actor lookup failed');
    }
  }

  async function lookupOrganization(): Promise<void> {
    actorsError = '';
    organization = null;
    try {
      organization = await roleGet<RoleOrganizationRecord>(
        `organizations/${encodeURIComponent(organizationInput.trim())}`);
    } catch (cause) {
      actorsError = cause instanceof ApiError && cause.status === 404
        ? 'No organization record exists for that ID.'
        : apiFailureMessage(cause, 'Organization lookup failed');
    }
  }

  async function lookupDirectConsumption(): Promise<void> {
    actorsError = '';
    directConsumption = null;
    try {
      const id = directConsumptionId.trim();
      if (!/^[0-9a-f]{64}$/.test(id)) throw new Error('Authorization ID must be 32-byte hex');
      directConsumption = await mapGet<AuthMapDirectConsumptionRecord>(
        `authenticated-map/direct-consumptions/${encodeURIComponent(directConsumptionActor.trim())}/${id}`);
    } catch (cause) {
      actorsError = cause instanceof ApiError && cause.status === 404
        ? 'That direct authorization has not been consumed.'
        : apiFailureMessage(cause, 'Consumption lookup failed');
    }
  }

  async function lookupPolicies(): Promise<void> {
    policiesError = '';
    try {
      if (policyInput.trim()) {
        approvalPolicy = await roleGet<RolePolicyRecord>(
          `policies/${encodeURIComponent(policyInput.trim())}`);
      }
      if (directPolicyInput.trim()) {
        const extra: Record<string, string> = {};
        if (directPolicyRevision.trim()) extra.revision = directPolicyRevision.trim();
        directPolicy = await mapGet<AuthMapDirectPolicyRecord>(
          `authenticated-map/direct-policies/${encodeURIComponent(directPolicyInput.trim())}`, extra);
      }
      if (authorityInput.trim()) {
        const extra: Record<string, string> = {};
        if (authorityRevision.trim()) extra.revision = authorityRevision.trim();
        authority = await mapGet<AuthMapAuthorityRecord>(
          `authenticated-map/administrator-authorities/${encodeURIComponent(authorityInput.trim())}`, extra);
      }
    } catch (cause) {
      policiesError = cause instanceof ApiError && cause.status === 404
        ? 'No record exists for that ID/revision.'
        : apiFailureMessage(cause, 'Policy lookup failed');
    }
  }

  async function lookupCommandResult(): Promise<void> {
    governanceError = '';
    commandResult = null;
    try {
      const id = commandResultInput.trim();
      if (!/^[0-9a-f]{64}$/.test(id)) throw new Error('Command result lookup requires a 32-byte message ID');
      commandResult = await mapGet<AuthMapCommandResultRecord>(
        `authenticated-map/command-results/${commandResultComponent}/${id}`);
    } catch (cause) {
      governanceError = cause instanceof ApiError && cause.status === 404
        ? 'No command result is retained for that message.'
        : apiFailureMessage(cause, 'Command result lookup failed');
    }
  }

  async function fetchGenericProof(): Promise<void> {
    proofError = '';
    try {
      const key = proofKeyInput.trim();
      if (!isHex(key) || !key) throw new Error('Proof key must be canonical lowercase hex');
      const height = proofHeightInput.trim() === '' ? undefined : Number(proofHeightInput.trim());
      await fetchProof(key, '', undefined, 'panel', height);
    } catch (cause) {
      proofError = apiFailureMessage(cause, 'Proof retrieval failed');
    }
  }

  const preview = (hex: string) => messagePreview(hex);
  const stateString = (name: string) =>
    typeof stateIdentity?.[name] === 'string' || typeof stateIdentity?.[name] === 'number'
      ? String(stateIdentity[name]) : '-';
</script>

<svelte:head><title>Yano · Authenticated Map</title></svelte:head>

<header data-console-route="authenticated-map" class="mb-4 flex flex-wrap items-end justify-between gap-3">
  <div>
    <p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-cyan-400">Committed state console</p>
    <h1 class="mt-1 text-2xl font-bold">Authenticated Map</h1>
    <p class="mb-0 mt-1 text-sm text-slate-500">
      Exact records, receipts, approvals, and proofs from the committed authenticated map.
    </p>
  </div>
  <div class="flex flex-wrap items-end gap-2">
    <label class="text-xs text-slate-400">Chain
      <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
              value={selectedChain} onchange={(event) => void activateChain(event.currentTarget.value)}>
        {#each chains as chain}<option value={chain.chainId}>{chain.chainId}</option>{/each}
      </select>
    </label>
    <a class="rounded-lg border border-slate-700 px-3 py-2 text-xs text-slate-300 no-underline hover:border-slate-500"
       href={`${base}/app-chain/?chain=${encodeURIComponent(selectedChain)}`}>Generic app-chain view</a>
  </div>
</header>

{#if pageError}
  <div role="alert" class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{pageError}</div>
{/if}

{#if status && !isAuthenticatedMapChain(status)}
  <section class="card p-6">
    <h2 class="m-0 text-lg font-semibold">Console unavailable for this chain</h2>
    <p class="mb-0 mt-2 text-sm text-slate-400">
      <strong>{selectedChain}</strong> uses <code>{status.stateMachine ?? 'an unknown state machine'}</code>.
      This reviewed route is enabled only for <code>authenticated-map</code> chains and performs
      no authenticated-map parsing for other state machines.
    </p>
  </section>
{:else if identityFailure}
  <section class="card p-6" role="alert">
    <h2 class="m-0 text-lg font-semibold">Domain API identity could not be verified</h2>
    <p class="mb-0 mt-2 text-sm text-amber-300">{identityFailure}</p>
    <p class="mb-0 mt-2 text-sm text-slate-500">
      The route requires <code>{AUTHMAP_API_VERSION}</code> reported by the chain itself before
      rendering any record.
    </p>
  </section>
{:else}
  <section class="card p-4">
    <div class="flex flex-wrap gap-2 text-xs">
      <span class="badge">chain {selectedChain || '-'}</span>
      <span class="badge">authenticated-map</span>
      {#if metadata}
        <span class="badge">{metadata.record.profile}</span>
        <span class="badge {governed ? 'badge-ok' : ''}">{governed ? 'governed authorization' : 'basic profile'}</span>
        <span class="badge">height {metadata.committedHeight.toLocaleString()}</span>
      {/if}
      {#if loading}<span class="badge badge-warn">loading…</span>{/if}
    </div>
  </section>

  <nav aria-label="Authenticated-map views" class="mt-4 flex gap-1 overflow-x-auto border-b border-slate-800">
    {#each views as view}
      <button type="button" class="whitespace-nowrap border-b-2 px-4 py-3 text-sm capitalize
              {activeView === view ? 'border-cyan-400 text-cyan-300' : 'border-transparent text-slate-400'}"
              aria-current={activeView === view ? 'page' : undefined}
              onclick={() => activeView = view}>{view}</button>
    {/each}
  </nav>

  {#if activeView === 'overview'}
    <div class="mt-4 grid gap-4 lg:grid-cols-2">
      <section class="card p-5">
        <h2 class="m-0 text-base font-semibold">Chain and genesis identity</h2>
        <dl class="mt-4 grid gap-3 text-xs">
          <div><dt class="text-slate-500">Genesis ID</dt>
            <dd class="mt-1"><CopyValue value={metadata?.record.genesisId ?? ''} width={48} label="genesis ID" /></dd></div>
          <div><dt class="text-slate-500">Domain API</dt><dd class="mt-1"><code>{metadata?.record.apiVersion ?? '-'}</code></dd></div>
          <div><dt class="text-slate-500">Commitment profile</dt><dd class="mt-1">{metadata?.record.profile ?? '-'}</dd></div>
          <div><dt class="text-slate-500">Finalized height / state root</dt>
            <dd class="mt-1">{metadata?.committedHeight ?? '-'} ·
              <CopyValue value={metadata?.stateRoot ?? ''} width={28} label="state root" /></dd></div>
          <div><dt class="text-slate-500">Governed authorization</dt>
            <dd class="mt-1"><span class="badge {governed ? 'badge-ok' : ''}">{governed ? 'committed' : 'not present'}</span></dd></div>
        </dl>
      </section>
      <section class="card p-5">
        <h2 class="m-0 text-base font-semibold">State backend</h2>
        <dl class="mt-4 grid gap-3 text-xs">
          <div><dt class="text-slate-500">Backend</dt><dd class="mt-1">{stateString('backend')}</dd></div>
          <div><dt class="text-slate-500">Commitment schema</dt><dd class="mt-1">{stateString('schemaVersion')} · {stateString('profile')}</dd></div>
          <div><dt class="text-slate-500">Format fingerprint</dt>
            <dd class="mt-1"><CopyValue value={typeof stateIdentity?.formatFingerprint === 'string' ? stateIdentity.formatFingerprint : ''} width={28} label="format fingerprint" /></dd></div>
          <div><dt class="text-slate-500">Oldest provable height</dt><dd class="mt-1">{stateString('oldestProvableHeight')}</dd></div>
          <div><dt class="text-slate-500">Collections</dt><dd class="mt-1">{collections.length}</dd></div>
        </dl>
      </section>
    </div>
    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">Validation catalog</h2>
      <p class="mt-1 text-xs text-slate-500">Value validators pinned by the committed genesis.</p>
      <div class="mt-3 flex flex-wrap gap-2 text-xs">
        {#each collections.filter((entry) => entry.validator) as entry}
          <span class="badge">{entry.id} → {entry.validator}</span>
        {:else}
          <span class="text-slate-500">No value validators are configured.</span>
        {/each}
      </div>
    </section>
  {/if}

  {#if activeView === 'collections'}
    <section class="card mt-4 overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full min-w-[860px] text-left text-xs">
          <thead class="text-slate-500"><tr>
            <th class="p-3">Collection</th><th>Authorization</th><th>Policy binding</th>
            <th>Restore</th><th>Key bound</th><th>Value bound</th><th>Encoding</th><th>Validator</th>
          </tr></thead>
          <tbody>
            {#each collections as entry}
              <tr class="border-t border-slate-800/60 hover:bg-slate-800/35">
                <td class="p-3 font-mono">{entry.id}</td>
                <td><span class="badge {entry.authorization >= 3 ? 'badge-ok' : ''}">{authorizationLabel(entry.authorization)}</span></td>
                <td class="font-mono">{entry.authorizationPolicy || '-'}</td>
                <td>{entry.restoreAllowed ? 'allowed' : 'forbidden'}</td>
                <td>{entry.maxKeyBytes} B</td><td>{entry.maxValueBytes.toLocaleString()} B</td>
                <td>{valueEncodingLabel(entry.valueEncoding)}</td>
                <td class="font-mono">{entry.validator || '-'}</td>
              </tr>
            {:else}
              <tr><td colspan="8" class="p-6 text-center text-sm text-slate-500">The committed genesis defines no collections.</td></tr>
            {/each}
          </tbody>
        </table>
      </div>
    </section>
  {/if}

  {#if activeView === 'lookup'}
    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">Exact entry lookup</h2>
      <form class="mt-4 flex flex-wrap items-end gap-2"
            onsubmit={(event) => { event.preventDefault(); void lookupEntry(); }}>
        <label class="text-xs text-slate-400">Collection
          <select bind:value={lookupCollection} class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100">
            {#each collections as entry}<option value={entry.id}>{entry.id}</option>{/each}
          </select>
        </label>
        <label class="text-xs text-slate-400">Key as
          <select bind:value={lookupKeyMode} class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100">
            <option value="text">text</option><option value="hex">hex</option>
          </select>
        </label>
        <label class="min-w-[16rem] flex-1 text-xs text-slate-400">Application key
          <input bind:value={lookupKey} autocomplete="off" spellcheck="false"
                 class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        </label>
        <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Look up</button>
      </form>
      {#if lookupError}<p role="alert" class="mb-0 mt-3 text-sm text-rose-300">{lookupError}</p>{/if}
      {#if entry}
        <div class="mt-5 grid gap-4 lg:grid-cols-2">
          <div class="space-y-3 text-xs">
            <div><span class="badge {entry.record.presence === 1 ? 'badge-ok' : entry.record.presence === 2 ? 'badge-warn' : ''}">{presenceLabel(entry.record.presence)}</span>
              {#if entry.record.status !== undefined}<span class="badge ml-2">{entryStatusLabel(entry.record.status)}</span>{/if}
              {#if entry.record.revision !== undefined}<span class="badge ml-2">revision {entry.record.revision}</span>{/if}</div>
            <div><div class="text-slate-500">Application key</div><CopyValue value={entry.record.applicationKey} width={48} label="application key" /></div>
            {#if entry.record.logicalValueHash}
              <div><div class="text-slate-500">Logical value hash</div><CopyValue value={entry.record.logicalValueHash} width={48} label="logical value hash" /></div>
            {/if}
            {#if entryDecoded}
              <div><div class="text-slate-500">Controller</div><CopyValue value={entryDecoded.controllerHex} width={48} label="controller" /></div>
              <div class="text-slate-500">Created height {entryDecoded.createdHeight} · last mutation {entryDecoded.lastMutationHeight}</div>
            {/if}
            <div><div class="text-slate-500">Exact proof key</div><CopyValue value={entry.proofKey ?? ''} width={48} label="proof key" /></div>
            <div class="flex gap-2">
              <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs hover:border-cyan-700"
                      onclick={() => void fetchProof(entry!.proofKey ?? '', entry!.stateRoot, entry!.recordValue, 'entry')}>
                Retrieve and verify proof</button>
              <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs hover:border-cyan-700"
                      onclick={() => { activeView = 'proofs'; proofKeyInput = entry!.proofKey ?? ''; }}>Open in proofs view</button>
            </div>
          </div>
          <div class="text-xs">
            <div class="text-slate-500">Canonical value (bounded preview)</div>
            {#if entryDecoded}
              {@const valuePreview = preview(entryDecoded.valueHex)}
              <pre class="mt-2 max-h-64 overflow-auto rounded-lg bg-slate-950/70 p-3 font-mono text-[11px] whitespace-pre-wrap break-all">{valuePreview.format === 'hex' ? valuePreview.rawHex : valuePreview.bodyText}</pre>
              <div class="mt-1 text-slate-500">{entryDecoded.valueHex.length / 2} bytes · shown as {valuePreview.format}{valuePreview.truncated ? ' · truncated' : ''}</div>
            {:else}
              <p class="mt-2 text-slate-500">No committed value for this key.</p>
            {/if}
          </div>
        </div>
        {#if entryProof}
          <div class="mt-4 rounded-lg border border-slate-800 p-3 text-xs">
            {#if entryProof.proof}
              <span class="badge {entryProof.verified ? 'badge-ok' : entryProof.verified === false ? 'badge-bad' : ''}">
                {entryProof.verified === null ? 'proof retrieved' : entryProof.verified ? 'proof verified' : 'proof rejected'}</span>
              {#if entryProof.rootBound !== null}
                <span class="badge ml-2 {entryProof.rootBound ? 'badge-ok' : 'badge-warn'}">{entryProof.rootBound ? 'root matches record' : 'root differs from record height'}</span>
              {/if}
              <div class="mt-2 text-slate-500">presence {entryProof.proof.presence ?? '-'} · height {entryProof.proof.committedHeight}</div>
            {/if}
            {#if entryProof.message}<div class="mt-2 text-slate-400">{entryProof.message}</div>{/if}
          </div>
        {/if}
      {/if}
    </section>
    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">Receipt lookup</h2>
      <p class="mt-1 text-xs text-slate-500">Finalized command receipts are keyed by app-message ID.</p>
      <form class="mt-3 flex flex-wrap gap-2"
            onsubmit={(event) => { event.preventDefault(); void lookupReceipt(); }}>
        <input bind:value={receiptInput} autocomplete="off" spellcheck="false" placeholder="64-hex message ID"
               class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Find receipt</button>
      </form>
      {#if receiptError}<p role="alert" class="mb-0 mt-3 text-sm text-rose-300">{receiptError}</p>{/if}
      {#if receipt}
        <div class="mt-4 text-xs">
          <span class="badge {receipt.record.status === 0 ? 'badge-ok' : 'badge-bad'}">{receiptStatusLabel(receipt.record.status)}</span>
          {#if receipt.record.errorCode}<span class="badge badge-bad ml-2">{receiptErrorLabel(receipt.record.errorCode)}</span>{/if}
          {#if receipt.record.actionCommitment}
            <div class="mt-2"><span class="text-slate-500">Action commitment</span>
              <CopyValue value={receipt.record.actionCommitment} width={48} label="action commitment" /></div>
          {/if}
          <div class="mt-2"><span class="text-slate-500">Proof key</span>
            <CopyValue value={receipt.proofKey ?? ''} width={48} label="receipt proof key" /></div>
        </div>
      {/if}
    </section>
  {/if}

  {#if activeView === 'mutations'}
    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">Canonical mutation builder</h2>
      <p class="mt-1 text-xs text-slate-500">
        Commands are preflighted locally against the committed collection bounds before submission.
      </p>
      <form class="mt-4 grid gap-3 lg:grid-cols-2" onsubmit={(event) => { event.preventDefault(); runPreflight(); }}>
        <label class="text-xs text-slate-400">Operation
          <select bind:value={mutationOperation} class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100">
            {#each [OP_PUT, OP_PUT_IF_ABSENT, OP_COMPARE_AND_SET, OP_TRANSFER_CONTROLLER, OP_REVOKE, OP_RESTORE] as op}
              <option value={op}>{OPERATION_LABELS[op]}</option>
            {/each}
          </select>
        </label>
        <label class="text-xs text-slate-400">Collection
          <select bind:value={mutationCollection} class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100">
            {#each collections as entry}<option value={entry.id}>{entry.id} · {authorizationLabel(entry.authorization)}</option>{/each}
          </select>
        </label>
        <label class="text-xs text-slate-400">Key ({mutationKeyMode})
          <div class="mt-1 flex gap-2">
            <select bind:value={mutationKeyMode} class="rounded-lg border border-slate-700 bg-slate-950 px-2 py-2 text-slate-100">
              <option value="text">text</option><option value="hex">hex</option>
            </select>
            <input bind:value={mutationKey} autocomplete="off" spellcheck="false"
                   class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          </div>
        </label>
        <label class="text-xs text-slate-400">Value ({mutationValueMode})
          <div class="mt-1 flex gap-2">
            <select bind:value={mutationValueMode} class="rounded-lg border border-slate-700 bg-slate-950 px-2 py-2 text-slate-100">
              <option value="text">text</option><option value="hex">hex</option>
            </select>
            <input bind:value={mutationValue} autocomplete="off" spellcheck="false"
                   disabled={[OP_TRANSFER_CONTROLLER, OP_REVOKE].includes(mutationOperation)}
                   class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs disabled:opacity-40" />
          </div>
        </label>
        <label class="text-xs text-slate-400">Expected revision (CAS/transfer/revoke)
          <input bind:value={mutationRevision} autocomplete="off" inputmode="numeric"
                 class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        </label>
        <label class="text-xs text-slate-400">Expected value hash (optional 32-byte hex)
          <input bind:value={mutationValueHash} autocomplete="off" spellcheck="false"
                 class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        </label>
        {#if mutationOperation === OP_TRANSFER_CONTROLLER}
          <label class="text-xs text-slate-400 lg:col-span-2">New controller (32-byte hex)
            <input bind:value={mutationController} autocomplete="off" spellcheck="false"
                   class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          </label>
        {/if}
        <div class="flex items-end gap-2">
          <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Preflight</button>
          {#if preflightOk && mutationDirect}
            <button type="button" class="rounded-lg bg-emerald-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-emerald-400"
                    onclick={() => void submitDirect()}>Submit</button>
          {/if}
        </div>
      </form>
      {#if preflight}
        <p class="mb-0 mt-3 text-sm {preflightOk ? 'text-emerald-300' : 'text-rose-300'}" role={preflightOk ? undefined : 'alert'}>{preflight}</p>
      {/if}
      {#if preflightOk && submitHex}
        <div class="mt-3 text-xs"><span class="text-slate-500">Canonical command envelope</span>
          <CopyValue value={submitHex} width={64} label="canonical command envelope hex" /></div>
      {/if}
      {#if preflightOk && !mutationDirect && mutationDescriptor}
        <div class="mt-4 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-100">
          <strong>{authorizationLabel(mutationDescriptor.authorization)}</strong> collections require
          externally signed evidence. The console never asks for or retains seed phrases or private
          keys; produce the action and signed envelope offline, then import it below.
          <pre class="mt-2 overflow-x-auto rounded bg-slate-950/70 p-2 font-mono text-[11px]">{governedCliHint(commandHex, mutationDescriptor)}</pre>
        </div>
      {/if}
    </section>

    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">Import an externally signed command</h2>
      <form class="mt-3 grid gap-2" onsubmit={(event) => { event.preventDefault(); void submitImported(); }}>
        <label class="text-xs text-slate-400">Topic
          <select bind:value={importedTopic} class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100">
            <option value={AUTHMAP_COMMAND_TOPIC}>{AUTHMAP_COMMAND_TOPIC}</option>
            <option value={ROLE_COMMAND_TOPIC}>{ROLE_COMMAND_TOPIC}</option>
            <option value={ACTOR_COMMAND_TOPIC}>{ACTOR_COMMAND_TOPIC}</option>
          </select>
        </label>
        <textarea bind:value={importedCommand} rows="3" spellcheck="false" placeholder="signed command hex"
                  class="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs"></textarea>
        <div><button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Submit signed command</button></div>
      </form>
      {#if submitError}<p role="alert" class="mb-0 mt-3 text-sm text-rose-300">{submitError}</p>{/if}
    </section>

    {#if tracking}
      <section class="card mt-4 p-5" aria-live="polite">
        <h2 class="m-0 text-base font-semibold">Finality and receipt tracking</h2>
        <div class="mt-2 text-xs"><span class="text-slate-500">Message</span>
          <CopyValue value={tracking.messageId} width={48} label="app-message ID" />
          <span class="badge ml-2">{tracking.topic}</span></div>
        <ol class="mt-4 grid gap-3 text-xs lg:grid-cols-4">
          <li class="rounded-lg border border-emerald-700/50 p-3">
            <span class="badge badge-ok">1 · HTTP accepted</span>
            <div class="mt-2 text-slate-500">accepted for sequencing only</div></li>
          <li class="rounded-lg border p-3 {tracking.finalizedHeight !== null ? 'border-emerald-700/50' : 'border-slate-800'}">
            <span class="badge {tracking.finalizedHeight !== null ? 'badge-ok' : ''}">2 · Finalized in a block</span>
            <div class="mt-2 text-slate-500">{tracking.finalizedHeight !== null ? `height ${tracking.finalizedHeight}` : 'waiting…'}</div></li>
          <li class="rounded-lg border p-3 {tracking.receipt || tracking.commandResult ? 'border-emerald-700/50' : 'border-slate-800'}">
            <span class="badge {tracking.receipt?.record.status === 0 || tracking.commandResult ? 'badge-ok' : tracking.receipt ? 'badge-bad' : ''}">3 · Applied or rejected</span>
            <div class="mt-2 text-slate-500">
              {#if tracking.receipt}
                {receiptStatusLabel(tracking.receipt.record.status)}
                {#if tracking.receipt.record.errorCode}· {receiptErrorLabel(tracking.receipt.record.errorCode)}{/if}
              {:else if tracking.commandResult}
                {tracking.commandResult.record.resultCode} at height {tracking.commandResult.record.appliedHeight}
              {:else}waiting…{/if}
            </div></li>
          <li class="rounded-lg border p-3 {tracking.entryAfter ? 'border-emerald-700/50' : 'border-slate-800'}">
            <span class="badge {tracking.entryAfter ? 'badge-ok' : ''}">4 · State changed</span>
            <div class="mt-2 text-slate-500">
              {#if tracking.entryAfter}
                {presenceLabel(tracking.entryAfter.record.presence)}
                {#if tracking.entryAfter.record.revision !== undefined}· revision {tracking.entryAfter.record.revision}{/if}
              {:else}approval consumption and entry change are separate facts{/if}
            </div></li>
        </ol>
        {#if tracking.failure}<p role="alert" class="mb-0 mt-3 text-sm text-amber-300">{tracking.failure}</p>{/if}
      </section>
    {/if}
  {/if}

  {#if activeView === 'actors' && governed}
    <div class="mt-4 grid gap-4 lg:grid-cols-2">
      <section class="card p-5">
        <h2 class="m-0 text-base font-semibold">Actor lookup</h2>
        <form class="mt-3 flex flex-wrap gap-2" onsubmit={(event) => { event.preventDefault(); void lookupActor(); }}>
          <input bind:value={actorInput} placeholder="actor ID" autocomplete="off" spellcheck="false"
                 class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          <input bind:value={actorRevision} placeholder="revision (current)" inputmode="numeric" autocomplete="off"
                 class="w-32 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Open</button>
        </form>
        {#if actor}
          <div class="mt-4 text-xs">
            <div class="flex flex-wrap gap-2">
              <span class="badge">{actor.record.actorId}</span>
              <span class="badge">org {actor.record.organizationId}</span>
              <span class="badge">revision {actor.record.revision}</span>
              <span class="badge {actor.record.status === 'ACTIVE' ? 'badge-ok' : 'badge-warn'}">{actor.record.status}</span>
              {#if actor.currentPointerValue}<span class="badge badge-ok">current pointer</span>{/if}
            </div>
            <div class="mt-3 text-slate-500">Roles</div>
            <div class="mt-1 flex flex-wrap gap-1.5">{#each actor.record.roles as role}<span class="badge">{role}</span>{/each}</div>
            <div class="mt-3 text-slate-500">Key epochs</div>
            {#each actor.record.keys as key}
              <div class="mt-2 rounded-lg bg-slate-950/60 p-2">
                <span class="badge">{key.keyId}</span>
                <span class="badge ml-1 {key.status === 'ACTIVE' ? 'badge-ok' : 'badge-warn'}">{key.status}</span>
                <span class="ml-2 text-slate-500">heights {key.validFromHeight} → {key.validUntilHeight === 0 ? '∞' : key.validUntilHeight}</span>
                <div class="mt-1"><CopyValue value={key.publicKey} width={44} label="actor public key" /></div>
              </div>
            {/each}
            <div class="mt-3"><span class="text-slate-500">Exact proof key</span>
              <CopyValue value={actor.proofKey ?? ''} width={48} label="actor proof key" /></div>
          </div>
        {/if}
      </section>
      <section class="card p-5">
        <h2 class="m-0 text-base font-semibold">Organization lookup</h2>
        <form class="mt-3 flex gap-2" onsubmit={(event) => { event.preventDefault(); void lookupOrganization(); }}>
          <input bind:value={organizationInput} placeholder="organization ID" autocomplete="off" spellcheck="false"
                 class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Open</button>
        </form>
        {#if organization}
          <div class="mt-4 text-xs">
            <span class="badge">{organization.record.organizationId}</span>
            <span class="badge ml-1">revision {organization.record.revision}</span>
            <span class="badge ml-1 {organization.record.status === 'ACTIVE' ? 'badge-ok' : 'badge-warn'}">{organization.record.status}</span>
            <div class="mt-2"><span class="text-slate-500">Metadata commitment</span>
              <CopyValue value={organization.record.metadataCommitment} width={44} label="metadata commitment" /></div>
            <div class="mt-2"><span class="text-slate-500">Exact proof key</span>
              <CopyValue value={organization.proofKey ?? ''} width={44} label="organization proof key" /></div>
          </div>
        {/if}
        <h3 class="mt-6 text-sm font-semibold">Direct consumption lookup</h3>
        <form class="mt-2 grid gap-2" onsubmit={(event) => { event.preventDefault(); void lookupDirectConsumption(); }}>
          <input bind:value={directConsumptionActor} placeholder="actor ID" autocomplete="off" spellcheck="false"
                 class="rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          <input bind:value={directConsumptionId} placeholder="authorization ID (64 hex)" autocomplete="off" spellcheck="false"
                 class="rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          <div><button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Check consumption</button></div>
        </form>
        {#if directConsumption}
          <div class="mt-3 text-xs">
            <span class="badge badge-ok">consumed at height {directConsumption.record.appliedHeight}</span>
            <span class="badge ml-1">{directConsumption.record.policyId} r{directConsumption.record.policyRevision}</span>
            <div class="mt-2"><span class="text-slate-500">Action commitment</span>
              <CopyValue value={directConsumption.record.actionCommitment} width={44} label="action commitment" /></div>
          </div>
        {/if}
      </section>
    </div>
    {#if actorsError}<p role="alert" class="mt-3 text-sm text-rose-300">{actorsError}</p>{/if}
  {/if}

  {#if activeView === 'policies' && governed}
    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">Policies and administrator authority</h2>
      <form class="mt-3 grid gap-3 lg:grid-cols-3" onsubmit={(event) => { event.preventDefault(); void lookupPolicies(); }}>
        <label class="text-xs text-slate-400">Approval policy ID
          <input bind:value={policyInput} autocomplete="off" spellcheck="false"
                 class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        </label>
        <label class="text-xs text-slate-400">Direct-role policy ID · revision
          <div class="mt-1 flex gap-2">
            <input bind:value={directPolicyInput} autocomplete="off" spellcheck="false"
                   class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
            <input bind:value={directPolicyRevision} placeholder="current" inputmode="numeric" autocomplete="off"
                   class="w-24 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          </div>
        </label>
        <label class="text-xs text-slate-400">Administrator authority ID · revision
          <div class="mt-1 flex gap-2">
            <input bind:value={authorityInput} autocomplete="off" spellcheck="false"
                   class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
            <input bind:value={authorityRevision} placeholder="current" inputmode="numeric" autocomplete="off"
                   class="w-24 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
          </div>
        </label>
        <div class="lg:col-span-3"><button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Look up</button></div>
      </form>
      {#if policiesError}<p role="alert" class="mb-0 mt-3 text-sm text-rose-300">{policiesError}</p>{/if}
      <div class="mt-4 grid gap-4 lg:grid-cols-3 text-xs">
        {#if approvalPolicy}
          <div class="rounded-lg border border-slate-800 p-3">
            <div class="flex flex-wrap gap-1.5">
              <span class="badge">{approvalPolicy.record.policyId}</span>
              <span class="badge">revision {approvalPolicy.record.revision}</span>
              <span class="badge">{approvalPolicy.record.rejectionMode}</span>
              <span class="badge">≤ {approvalPolicy.record.maximumLifetimeBlocks} blocks</span>
            </div>
            <div class="mt-2 text-slate-500">Proposer roles</div>
            <div class="mt-1 flex flex-wrap gap-1.5">{#each approvalPolicy.record.proposerRoles as role}<span class="badge">{role}</span>{/each}</div>
            <div class="mt-2 text-slate-500">Clauses</div>
            {#each approvalPolicy.record.clauses as clause}
              <div class="mt-1 rounded bg-slate-950/60 p-2">
                <span class="badge">{clause.clauseId}</span> role <code>{clause.role}</code> ·
                minimum {clause.minimumCount} · distinct by {clause.distinctBy || 'actor'}
              </div>
            {/each}
            <div class="mt-2"><span class="text-slate-500">Proof key</span>
              <CopyValue value={approvalPolicy.proofKey ?? ''} width={36} label="policy proof key" /></div>
          </div>
        {/if}
        {#if directPolicy}
          <div class="rounded-lg border border-slate-800 p-3">
            <div class="flex flex-wrap gap-1.5">
              <span class="badge">{directPolicy.record.policyId}</span>
              <span class="badge">revision {directPolicy.record.revision}</span>
              <span class="badge {directPolicy.record.status === 'ACTIVE' ? 'badge-ok' : 'badge-warn'}">{directPolicy.record.status}</span>
              {#if directPolicy.currentPointerValue}<span class="badge badge-ok">current pointer</span>{/if}
            </div>
            <div class="mt-2">Required role <code>{directPolicy.record.requiredRole}</code></div>
            <div class="mt-1 text-slate-500">Authorization lifetime ≤ {directPolicy.record.maximumAuthorizationLifetimeBlocks} blocks</div>
            <div class="mt-2"><span class="text-slate-500">Proof key</span>
              <CopyValue value={directPolicy.proofKey ?? ''} width={36} label="direct policy proof key" /></div>
          </div>
        {/if}
        {#if authority}
          <div class="rounded-lg border border-slate-800 p-3">
            <div class="flex flex-wrap gap-1.5">
              <span class="badge">{authority.record.authorityId}</span>
              <span class="badge">revision {authority.record.revision}</span>
              <span class="badge badge-ok">threshold {authority.record.threshold}</span>
              {#if authority.currentPointerValue}<span class="badge badge-ok">current pointer</span>{/if}
            </div>
            <div class="mt-2 text-slate-500">Administrator actors</div>
            <div class="mt-1 flex flex-wrap gap-1.5">{#each authority.record.administratorActors as id}<span class="badge">{id}</span>{/each}</div>
            <div class="mt-2"><span class="text-slate-500">Proof key</span>
              <CopyValue value={authority.proofKey ?? ''} width={36} label="authority proof key" /></div>
          </div>
        {/if}
      </div>
    </section>
  {/if}

  {#if activeView === 'approvals' && governed}
    {#if stats}
      <div class="mt-4 grid gap-4 sm:grid-cols-3 xl:grid-cols-6">
        {#each [['created', stats.record.created], ['pending', stats.record.pending], ['approved', stats.record.approved], ['rejected', stats.record.rejected], ['cancelled', stats.record.cancelled], ['expired', stats.record.expired]] as [label, count]}
          <section class="card p-4"><div class="text-xs uppercase text-slate-500">{label}</div>
            <div class="mt-2 text-2xl font-bold">{count}</div></section>
        {/each}
      </div>
    {/if}
    <section class="card mt-4 p-5">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h2 class="m-0 text-base font-semibold">Pending approvals</h2>
        <span class="badge badge-warn">DERIVED_FROM_PENDING_INDEX</span>
      </div>
      <p class="mt-1 text-xs text-slate-500">
        Bounded deterministic projection of the committed pending index at height
        {pendingApprovals?.committedHeight ?? '-'} — not the value of a single proof key.
      </p>
      {#if approvalsError}<p role="alert" class="mb-0 mt-3 text-sm text-rose-300">{approvalsError}</p>{/if}
      <div class="mt-3 flex flex-wrap gap-2">
        {#each pendingApprovals?.record.ids ?? [] as id}
          <button class="rounded-lg border border-slate-700 px-3 py-2 font-mono text-xs text-cyan-300 hover:border-cyan-700"
                  onclick={() => void openProposal(id)}>{id}</button>
        {:else}
          <span class="text-sm text-slate-500">No pending approval proposals at this height.</span>
        {/each}
      </div>
      {#if pendingApprovals?.record.nextAfterId}
        <button class="mt-3 rounded-lg border border-slate-700 px-3 py-2 text-xs"
                onclick={() => void loadPendingApprovals(pendingApprovals!.record.nextAfterId)}>Next page</button>
      {/if}
      <form class="mt-4 flex gap-2" onsubmit={(event) => { event.preventDefault(); void openProposal(governanceInput.trim()); }}>
        <input bind:value={governanceInput} placeholder="proposal ID" autocomplete="off" spellcheck="false"
               class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Open proposal</button>
      </form>
    </section>
    {#if proposal}
      <section class="card mt-4 p-5">
        <div class="flex flex-wrap items-center gap-2">
          <h2 class="m-0 text-base font-semibold">Proposal {proposal.record.proposalId}</h2>
          <span class="badge {proposal.record.status === 'APPROVED' ? 'badge-ok' : proposal.record.status === 'PENDING' ? 'badge-warn' : 'badge-bad'}">{proposal.record.status}</span>
          {#if approvalConsumption}
            <span class="badge badge-ok">consumed at height {approvalConsumption.record.appliedHeight}</span>
          {:else}
            <span class="badge">not consumed</span>
          {/if}
        </div>
        <dl class="mt-4 grid gap-3 text-xs sm:grid-cols-2 lg:grid-cols-4">
          <div><dt class="text-slate-500">Policy</dt><dd class="mt-1">{proposal.record.policyId} r{proposal.record.policyRevision}</dd></div>
          <div><dt class="text-slate-500">Proposer</dt><dd class="mt-1">{proposal.record.proposerActorId} · {proposal.record.proposerOrganizationId} · {proposal.record.proposerRole}</dd></div>
          <div><dt class="text-slate-500">Deadline height</dt>
            <dd class="mt-1">{proposal.record.deadlineHeight}
              {#if proposal.record.status === 'PENDING'}
                <span class="text-slate-500">({Math.max(0, proposal.record.deadlineHeight - proposal.committedHeight)} blocks remain)</span>
              {/if}</dd></div>
          <div><dt class="text-slate-500">Action digest</dt>
            <dd class="mt-1"><CopyValue value={proposal.record.payloadHash} width={28} label="proposal payload hash" /></dd></div>
        </dl>
        <div class="mt-4 overflow-x-auto">
          <table class="w-full min-w-[720px] text-left text-xs">
            <thead class="text-slate-500"><tr><th class="p-2">Decision</th><th>Actor</th><th>Organization</th><th>Role</th><th>Clause</th><th>Key</th><th>Height</th></tr></thead>
            <tbody>
              {#each proposal.record.decisions as decision}
                <tr class="border-t border-slate-800/60">
                  <td class="p-2"><span class="badge {decision.decision === 'APPROVE' ? 'badge-ok' : 'badge-bad'}">{decision.decision}</span></td>
                  <td class="font-mono">{decision.actorId} r{decision.actorRevision}</td>
                  <td class="font-mono">{decision.organizationId} r{decision.organizationRevision}</td>
                  <td>{decision.role}</td><td>{decision.clauseId}</td>
                  <td class="font-mono">{decision.keyId}</td><td>{decision.acceptedHeight}</td>
                </tr>
              {:else}
                <tr><td colspan="7" class="p-4 text-center text-slate-500">No accepted decisions yet.</td></tr>
              {/each}
            </tbody>
          </table>
        </div>
        <div class="mt-3 text-xs"><span class="text-slate-500">Proof key</span>
          <CopyValue value={proposal.proofKey ?? ''} width={48} label="proposal proof key" /></div>
      </section>
    {/if}
  {/if}

  {#if activeView === 'governance' && governed}
    <section class="card mt-4 p-5">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h2 class="m-0 text-base font-semibold">Governance changes</h2>
        <span class="badge badge-warn">separate from business approvals</span>
      </div>
      <p class="mt-1 text-xs text-slate-500">
        Pending registry, policy, and authority mutations with their actor-signed activation state.
      </p>
      <button class="mt-3 rounded-lg border border-slate-700 px-3 py-2 text-xs hover:border-cyan-700"
              onclick={() => void loadPendingGovernance()}>Load pending governance pages</button>
      {#if governanceError}<p role="alert" class="mb-0 mt-3 text-sm text-rose-300">{governanceError}</p>{/if}
      <div class="mt-4 grid gap-4 lg:grid-cols-2 text-xs">
        <div>
          <div class="section-title">Actor/organization registry</div>
          <div class="mt-2 flex flex-wrap gap-2">
            {#each pendingActorGov?.record.ids ?? [] as id}
              <button class="rounded-lg border border-slate-700 px-3 py-2 font-mono text-cyan-300 hover:border-cyan-700"
                      onclick={() => void openGovernanceMutation(id, 'actor')}>{id}</button>
            {:else}<span class="text-slate-500">No pending registry mutations.</span>{/each}
          </div>
        </div>
        <div>
          <div class="section-title">Policy governance</div>
          <div class="mt-2 flex flex-wrap gap-2">
            {#each pendingPolicyGov?.record.ids ?? [] as id}
              <button class="rounded-lg border border-slate-700 px-3 py-2 font-mono text-cyan-300 hover:border-cyan-700"
                      onclick={() => void openGovernanceMutation(id, 'policy')}>{id}</button>
            {:else}<span class="text-slate-500">No pending policy mutations.</span>{/each}
          </div>
        </div>
      </div>
      <form class="mt-4 flex flex-wrap gap-2"
            onsubmit={(event) => { event.preventDefault(); void openGovernanceMutation(governanceInput.trim(), governanceComponent); }}>
        <select bind:value={governanceComponent} class="rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100">
          <option value="actor">registry mutation</option>
          <option value="policy">policy mutation</option>
        </select>
        <input bind:value={governanceInput} placeholder="mutation ID" autocomplete="off" spellcheck="false"
               class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Open</button>
      </form>
      {#if governanceMutation}
        <div class="mt-4 rounded-lg border border-slate-800 p-3 text-xs">
          <div class="flex flex-wrap gap-1.5">
            <span class="badge">{governanceMutation.record.mutationId}</span>
            <span class="badge {governanceMutation.record.status === 'PENDING' ? 'badge-warn' : governanceMutation.record.status === 'ACTIVATED' ? 'badge-ok' : ''}">{governanceMutation.record.status}</span>
            <span class="badge">authority {governanceMutation.record.authorityId} r{governanceMutation.record.authorityRevision}</span>
            <span class="badge">expires at height {governanceMutation.record.expiryHeight}</span>
          </div>
          <div class="mt-2"><span class="text-slate-500">Proof key</span>
            <CopyValue value={governanceMutation.proofKey ?? ''} width={44} label="mutation proof key" /></div>
        </div>
      {/if}
      <h3 class="mt-6 text-sm font-semibold">Component command result</h3>
      <form class="mt-2 flex flex-wrap gap-2" onsubmit={(event) => { event.preventDefault(); void lookupCommandResult(); }}>
        <select bind:value={commandResultComponent} class="rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100">
          <option value="role-approvals">role-approvals</option>
          <option value="domain-actors">domain-actors</option>
        </select>
        <input bind:value={commandResultInput} placeholder="64-hex message ID" autocomplete="off" spellcheck="false"
               class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Look up</button>
      </form>
      {#if commandResult}
        <div class="mt-3 rounded-lg border border-slate-800 p-3 text-xs">
          <span class="badge">{commandResult.record.subjectId}</span>
          <span class="badge ml-1 {commandResult.record.resultCode === 'APPLIED' ? 'badge-ok' : 'badge-warn'}">{commandResult.record.resultCode}</span>
          <span class="ml-2 text-slate-500">kind {commandResult.record.commandKind} · height {commandResult.record.appliedHeight}</span>
        </div>
      {/if}
    </section>
  {/if}

  {#if activeView === 'proofs'}
    <section class="card mt-4 p-5">
      <h2 class="m-0 text-base font-semibold">State proof retrieval and verification</h2>
      <p class="mt-1 text-xs text-slate-500">
        Entry, absence, tombstone, receipt, actor, policy, proposal, and consumption records all
        prove against their exact physical key. Copy a proof key from any view.
      </p>
      <form class="mt-3 flex flex-wrap gap-2" onsubmit={(event) => { event.preventDefault(); void fetchGenericProof(); }}>
        <input bind:value={proofKeyInput} placeholder="proof key (canonical hex)" autocomplete="off" spellcheck="false"
               class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        <input bind:value={proofHeightInput} placeholder="height (tip)" inputmode="numeric" autocomplete="off"
               class="w-28 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" />
        <button class="rounded-lg bg-cyan-500 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-cyan-400">Retrieve and verify</button>
      </form>
      {#if proofError}<p role="alert" class="mb-0 mt-3 text-sm text-rose-300">{proofError}</p>{/if}
      {#if proofPanel?.proof}
        <div class="mt-4 text-xs">
          <span class="badge {proofPanel.verified ? 'badge-ok' : proofPanel.verified === false ? 'badge-bad' : ''}">
            {proofPanel.verified === null ? 'retrieved' : proofPanel.verified ? 'verified by the host release verifier' : 'rejected'}</span>
          <span class="badge ml-1">{proofPanel.proof.presence ?? '-'}</span>
          <span class="badge ml-1">height {proofPanel.proof.committedHeight}</span>
          {#if proofPanel.proof.profile}<span class="badge ml-1">{proofPanel.proof.profile}</span>{/if}
          <dl class="mt-3 grid gap-3 sm:grid-cols-2">
            <div><dt class="text-slate-500">State root</dt>
              <dd class="mt-1"><CopyValue value={proofPanel.proof.stateRoot} width={44} label="proof state root" /></dd></div>
            <div><dt class="text-slate-500">Block hash</dt>
              <dd class="mt-1"><CopyValue value={proofPanel.proof.blockHash ?? ''} width={44} label="proof block hash" /></dd></div>
            {#if proofPanel.proof.valueHex !== undefined}
              <div class="sm:col-span-2"><dt class="text-slate-500">Committed value ({(proofPanel.proof.valueHex.length / 2).toLocaleString()} bytes)</dt>
                <dd class="mt-1"><CopyValue value={proofPanel.proof.valueHex} width={64} label="committed value hex" /></dd></div>
            {/if}
            <div class="sm:col-span-2"><dt class="text-slate-500">Proof wire</dt>
              <dd class="mt-1"><CopyValue value={proofPanel.proof.proofWireHex} width={64} label="proof wire hex" /></dd></div>
          </dl>
          {#if proofPanel.proof.finalityCertificate}
            <div class="mt-3 text-slate-500">Finality certificate scheme
              <code>{String((proofPanel.proof.finalityCertificate as { scheme?: unknown }).scheme ?? '-')}</code> ·
              {Array.isArray((proofPanel.proof.finalityCertificate as { signatures?: unknown[] }).signatures)
                ? ((proofPanel.proof.finalityCertificate as { signatures?: unknown[] }).signatures?.length ?? 0) : 0} member signatures</div>
          {/if}
          {#if proofPanel.message}<div class="mt-2 text-slate-400">{proofPanel.message}</div>{/if}
        </div>
      {/if}
    </section>
  {/if}

  {#if activeView === 'explorer'}
    <section class="card mt-4 p-6">
      <div class="flex flex-wrap items-center gap-2">
        <h2 class="m-0 text-base font-semibold">Derived entry explorer</h2>
        <span class="badge badge-warn">DERIVED</span>
      </div>
      <p class="mt-2 text-sm text-slate-400">
        The authenticated map is a point-query structure; the state machine adds no consensus
        range iteration for browsing. An optional rebuildable entry projection can serve this
        view when configured, and it must report projection identity, projector version,
        projected-through height, lag, and rebuild status before any row is shown. A row becomes
        authoritative only after its exact point proof is verified.
      </p>
      <p class="mb-0 mt-3 text-sm text-amber-300">
        No derived entry projection is configured on this node. Exact lookups, receipts, pending
        pages, and proofs above remain fully available without it.
      </p>
    </section>
  {/if}
{/if}
