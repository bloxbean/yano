package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleApprovalStatsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RolePendingQueriesV1;
import com.bloxbean.cardano.yano.appchain.roles.internal.ActorApprovalProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.ActorGovernanceProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.RoleState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** State owner and exact-query surface for policy revisions and role decisions. */
public final class RoleAwareApprovalsComponent implements AppStateMachine {
    public static final String COMPONENT_ID = RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID;
    public static final String QUERY_POLICY = "policy";
    public static final String QUERY_POLICY_CURRENT = "policy-current";
    public static final String QUERY_DIRECT_POLICY = "direct-policy";
    public static final String QUERY_DIRECT_POLICY_CURRENT = "direct-policy-current";
    public static final String QUERY_PROPOSAL = "proposal";
    public static final String QUERY_GOVERNANCE_MUTATION = "governance-mutation";
    public static final String QUERY_STATS = "stats";
    public static final String QUERY_COMMAND_RESULT = "command-result";
    public static final String QUERY_PENDING_APPROVALS = "pending-approvals";
    public static final String QUERY_PENDING_GOVERNANCE = "pending-governance";

    private final ComponentDescriptor descriptor;
    private final GovernedGenesisV1 genesis;

    public RoleAwareApprovalsComponent(ComponentDescriptor descriptor) {
        this(descriptor, null);
    }

    public RoleAwareApprovalsComponent(
            ComponentDescriptor descriptor,
            GovernedGenesisV1 genesis
    ) {
        this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
        this.genesis = genesis;
        if (!descriptor.componentId().equals(COMPONENT_ID) || !descriptor.topics().isEmpty()) {
            throw new IllegalArgumentException("invalid role approvals descriptor");
        }
    }

    public ComponentDescriptor descriptor() { return descriptor; }

    @Override public String id() { return descriptor.componentId(); }

    @Override
    public void init(AppStateReader ownState, AppChainInfo chain) {
        if (genesis != null && ownState.committedHeight() > 0) {
            verifyGenesis(ownState);
        }
    }

    @Override
    public void apply(AppBlockExecutionContext execution, AppStateWriter ownState, AppEffectEmitter ownedEffects) {
        AppBlock block = execution.block();
        if (genesis != null) {
            initializeOrVerify(block.height(), ownState);
        }
        // Commands are owned by the declared cross-component workflow.
    }

    private void initializeOrVerify(long height, AppStateWriter state) {
        if (height != 1) {
            verifyGenesis(state);
            return;
        }
        requireAbsent(state, RoleWorkflowKeys.approvalStats());
        for (DirectRolePolicyV1 policy : genesis.directPolicies()) {
            requireAbsent(state, RoleWorkflowKeys.directPolicyCurrent(policy.policyId()));
            requireAbsent(state, RoleWorkflowKeys.directPolicyRevision(
                    policy.policyId(), policy.revision()));
        }
        for (ApprovalPolicyV1 policy : genesis.approvalPolicies()) {
            requireAbsent(state, RoleWorkflowKeys.policyCurrent(policy.policyId()));
            requireAbsent(state, RoleWorkflowKeys.policyRevision(
                    policy.policyId(), policy.revision()));
        }

        state.put(RoleWorkflowKeys.approvalStats(), RoleApprovalStatsV1.empty().encode());
        for (DirectRolePolicyV1 policy : genesis.directPolicies()) {
            state.put(RoleWorkflowKeys.directPolicyRevision(
                    policy.policyId(), policy.revision()), policy.encode());
            RoleState.pointer(state, RoleWorkflowKeys.directPolicyCurrent(
                    policy.policyId()), policy.revision());
        }
        for (ApprovalPolicyV1 policy : genesis.approvalPolicies()) {
            state.put(RoleWorkflowKeys.policyRevision(
                    policy.policyId(), policy.revision()), policy.encode());
            RoleState.pointer(state, RoleWorkflowKeys.policyCurrent(
                    policy.policyId()), policy.revision());
        }
    }

    private void verifyGenesis(AppStateReader state) {
        byte[] stats = state.get(RoleWorkflowKeys.approvalStats()).orElseThrow(() ->
                new IllegalStateException("role approval genesis state is absent"));
        try {
            RoleApprovalStatsV1.decode(stats);
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("role approval stats are corrupt", corrupt);
        }
        for (DirectRolePolicyV1 policy : genesis.directPolicies()) {
            requireExact(state, RoleWorkflowKeys.directPolicyRevision(
                    policy.policyId(), policy.revision()), policy.encode());
            long revision = requireCurrent(state,
                    RoleWorkflowKeys.directPolicyCurrent(policy.policyId()));
            DirectRolePolicyV1 current = decodeCurrent(state,
                    RoleWorkflowKeys.directPolicyRevision(policy.policyId(), revision),
                    DirectRolePolicyV1::decode, "direct policy");
            if (!current.policyId().equals(policy.policyId())
                    || current.revision() != revision) {
                throw new IllegalStateException(
                        "role approval direct-policy pointer is incompatible");
            }
        }
        for (ApprovalPolicyV1 policy : genesis.approvalPolicies()) {
            requireExact(state, RoleWorkflowKeys.policyRevision(
                    policy.policyId(), policy.revision()), policy.encode());
            long revision = requireCurrent(state,
                    RoleWorkflowKeys.policyCurrent(policy.policyId()));
            ApprovalPolicyV1 current = decodeCurrent(state,
                    RoleWorkflowKeys.policyRevision(policy.policyId(), revision),
                    ApprovalPolicyV1::decode, "approval policy");
            if (!current.policyId().equals(policy.policyId())
                    || current.revision() != revision) {
                throw new IllegalStateException(
                        "role approval policy pointer is incompatible");
            }
        }
        ActorApprovalProcessor.verifyPendingState(state, genesis.limits());
        ActorGovernanceProcessor.verifyPendingState(state, genesis.limits());
    }

    private static void requireAbsent(AppStateReader state, byte[] key) {
        if (state.get(key).isPresent()) {
            throw new IllegalStateException("role approval genesis state already exists");
        }
    }

    private static void requireExact(AppStateReader state, byte[] key, byte[] expected) {
        byte[] actual = state.get(key).orElseThrow(() ->
                new IllegalStateException("role approval genesis policy is absent"));
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new IllegalStateException("role approval genesis policy is incompatible");
        }
    }

    private static long requireCurrent(AppStateReader state, byte[] key) {
        long revision = RoleState.pointer(state, key);
        if (revision < 1) {
            throw new IllegalStateException("role approval current pointer is absent");
        }
        return revision;
    }

    private static <T> T decodeCurrent(
            AppStateReader state,
            byte[] key,
            java.util.function.Function<byte[], T> decoder,
            String kind
    ) {
        byte[] encoded = state.get(key).orElseThrow(() -> new IllegalStateException(
                "role approval " + kind + " current pointer is dangling"));
        try {
            return decoder.apply(encoded);
        } catch (RuntimeException malformed) {
            throw new IllegalStateException(
                    "role approval " + kind + " current record is corrupt", malformed);
        }
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
        if (QUERY_COMMAND_RESULT.equals(localPath)) {
            if (params == null || params.length != 32) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "command-result query requires a 32-byte message id");
            }
            return ownState.get(RoleWorkflowKeys.commandResult(params))
                    .orElse(new byte[0]);
        }
        if (QUERY_PENDING_APPROVALS.equals(localPath)
                || QUERY_PENDING_GOVERNANCE.equals(localPath)) {
            if (genesis == null) {
                throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                        "governed pending queries are disabled by genesis");
            }
            RolePendingQueriesV1.PageQuery query;
            try {
                query = RolePendingQueriesV1.PageQuery.decode(params);
            } catch (IllegalArgumentException malformed) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "invalid pending page query");
            }
            if (query.limit() > genesis.limits().maximumQueryPageSize()) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "pending page exceeds genesis limit");
            }
            return QUERY_PENDING_APPROVALS.equals(localPath)
                    ? ActorApprovalProcessor.pendingPage(ownState, query,
                    genesis.limits().maximumQueryPageSize()).encode()
                    : ActorGovernanceProcessor.pendingPage(ownState, query,
                    genesis.limits().maximumQueryPageSize()).encode();
        }
        if (QUERY_STATS.equals(localPath)) {
            if (params.length != 0) throw new AppQueryException(
                    AppQueryException.Code.INVALID_REQUEST,
                    "role approval stats query does not accept parameters");
            return ownState.get(RoleWorkflowKeys.approvalStats()).orElse(new byte[0]);
        }
        QueryRef ref = queryRef(params);
        if (QUERY_POLICY_CURRENT.equals(localPath)) {
            if (ref.revision != 0) throw new AppQueryException(
                    AppQueryException.Code.INVALID_REQUEST,
                    "current-pointer queries do not accept a revision");
            return RoleState.pointerBytes(ownState, RoleWorkflowKeys.policyCurrent(ref.id));
        }
        if (QUERY_POLICY.equals(localPath)) {
            long revision = ref.revision != 0 ? ref.revision
                    : RoleState.pointer(ownState, RoleWorkflowKeys.policyCurrent(ref.id));
            return revision == 0 ? new byte[0] : ownState.get(
                    RoleWorkflowKeys.policyRevision(ref.id, revision)).orElse(new byte[0]);
        }
        if (QUERY_DIRECT_POLICY_CURRENT.equals(localPath)) {
            if (ref.revision != 0) throw new AppQueryException(
                    AppQueryException.Code.INVALID_REQUEST,
                    "current-pointer queries do not accept a revision");
            return RoleState.pointerBytes(ownState,
                    RoleWorkflowKeys.directPolicyCurrent(ref.id));
        }
        if (QUERY_DIRECT_POLICY.equals(localPath)) {
            long revision = ref.revision != 0 ? ref.revision
                    : RoleState.pointer(ownState,
                    RoleWorkflowKeys.directPolicyCurrent(ref.id));
            return revision == 0 ? new byte[0] : ownState.get(
                    RoleWorkflowKeys.directPolicyRevision(ref.id, revision))
                    .orElse(new byte[0]);
        }
        if (QUERY_PROPOSAL.equals(localPath)) {
            if (ref.revision != 0) throw new AppQueryException(
                    AppQueryException.Code.INVALID_REQUEST,
                    "proposal queries do not accept a revision");
            return ownState.get(RoleWorkflowKeys.proposal(ref.id)).orElse(new byte[0]);
        }
        if (QUERY_GOVERNANCE_MUTATION.equals(localPath)) {
            if (ref.revision != 0) throw new AppQueryException(
                    AppQueryException.Code.INVALID_REQUEST,
                    "governance-mutation queries do not accept a revision");
            return ownState.get(RoleWorkflowKeys.governedMutation(ref.id))
                    .orElse(new byte[0]);
        }
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "unsupported role approval query");
    }

    private static QueryRef queryRef(byte[] params) {
        try {
            String value = new String(params, StandardCharsets.US_ASCII);
            String[] fields = value.split("@", -1);
            if (fields.length > 2 || fields[0].isEmpty()) throw new IllegalArgumentException();
            RoleWorkflowIdentifiers.id(fields[0], "query id");
            long revision = fields.length == 2 ? Long.parseLong(fields[1]) : 0;
            if (revision < 0) throw new IllegalArgumentException();
            return new QueryRef(fields[0], revision);
        } catch (RuntimeException invalid) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "query must be id or id@revision");
        }
    }

    private record QueryRef(String id, long revision) {
    }
}
