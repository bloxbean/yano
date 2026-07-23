package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.RoleEvidenceKeys;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;

import java.nio.charset.StandardCharsets;

/** Evidence-specific query adapter over the reusable role-approvals component. */
final class EvidenceRoleApprovalsComponent implements CompositeComponent {
    static final String QUERY_EVIDENCE_APPROVAL = "evidence-approval";

    private final RoleAwareApprovalsComponent delegate;

    EvidenceRoleApprovalsComponent(ComponentDescriptor descriptor) {
        delegate = new RoleAwareApprovalsComponent(descriptor);
    }

    @Override
    public ComponentDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public void init(AppStateReader ownState, AppChainInfo chain) {
        delegate.init(ownState, chain);
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return delegate.validate(routedMessage);
    }

    @Override
    public void apply(AppBlock routedBlock, AppStateWriter ownState, AppEffectEmitter ownedEffects) {
        delegate.apply(routedBlock, ownState, ownedEffects);
    }

    @Override
    public void onEffectResult(AppBlock block, EffectResult result,
                               AppStateWriter ownState, AppEffectEmitter ownedEffects) {
        delegate.onEffectResult(block, result, ownState, ownedEffects);
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
        if (!QUERY_EVIDENCE_APPROVAL.equals(localPath)) {
            return delegate.query(localPath, params, ownState);
        }
        QueryRef ref = queryRef(params);
        return ownState.get(RoleEvidenceKeys.evidenceApproval(
                ref.evidenceId(), ref.businessVersion())).orElse(new byte[0]);
    }

    private static QueryRef queryRef(byte[] params) {
        try {
            String value = new String(params, StandardCharsets.US_ASCII);
            String[] fields = value.split("@", -1);
            if (fields.length != 2 || fields[0].isEmpty()) {
                throw new IllegalArgumentException();
            }
            String evidenceId = RoleWorkflowIdentifiers.id(fields[0], "evidenceId");
            long businessVersion = Long.parseLong(fields[1]);
            if (businessVersion < 1) {
                throw new IllegalArgumentException();
            }
            return new QueryRef(evidenceId, businessVersion);
        } catch (RuntimeException invalid) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "query must be evidenceId@businessVersion");
        }
    }

    private record QueryRef(String evidenceId, long businessVersion) {
    }
}
