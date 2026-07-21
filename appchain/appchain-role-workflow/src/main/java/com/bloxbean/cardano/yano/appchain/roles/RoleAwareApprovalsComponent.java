package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.internal.RoleState;

import java.nio.charset.StandardCharsets;

/** State owner and exact-query surface for policy revisions and role decisions. */
public final class RoleAwareApprovalsComponent implements CompositeComponent {
    public static final String COMPONENT_ID = "role-approvals";
    public static final String QUERY_POLICY = "policy";
    public static final String QUERY_POLICY_CURRENT = "policy-current";
    public static final String QUERY_PROPOSAL = "proposal";
    public static final String QUERY_EVIDENCE_APPROVAL = "evidence-approval";
    public static final String QUERY_STATS = "stats";

    private final ComponentDescriptor descriptor;

    public RoleAwareApprovalsComponent(ComponentDescriptor descriptor) {
        this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
        if (!descriptor.componentId().equals(COMPONENT_ID) || !descriptor.topics().isEmpty()) {
            throw new IllegalArgumentException("invalid role approvals descriptor");
        }
    }

    @Override public ComponentDescriptor descriptor() { return descriptor; }

    @Override
    public void apply(AppBlock routedBlock, AppStateWriter ownState, AppEffectEmitter ownedEffects) {
        // Commands are owned by the declared cross-component workflow.
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
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
        if (QUERY_PROPOSAL.equals(localPath)) {
            if (ref.revision != 0) throw new AppQueryException(
                    AppQueryException.Code.INVALID_REQUEST,
                    "proposal queries do not accept a revision");
            return ownState.get(RoleWorkflowKeys.proposal(ref.id)).orElse(new byte[0]);
        }
        if (QUERY_EVIDENCE_APPROVAL.equals(localPath)) {
            if (ref.revision < 1) throw new AppQueryException(
                    AppQueryException.Code.INVALID_REQUEST,
                    "evidence approval queries require evidenceId@businessVersion");
            return ownState.get(RoleWorkflowKeys.evidenceApproval(
                    ref.id, ref.revision)).orElse(new byte[0]);
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
