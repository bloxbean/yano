package com.bloxbean.cardano.yano.appchain.evidence.profile.contracts;

import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;

import java.nio.charset.StandardCharsets;

/** Evidence-profile state keys stored within the generic role-approvals component namespace. */
public final class RoleEvidenceKeys {
    private RoleEvidenceKeys() {
    }

    public static byte[] evidenceApproval(String evidenceId, long businessVersion) {
        if (businessVersion < 1) {
            throw new IllegalArgumentException("businessVersion must be positive");
        }
        return ("e/" + RoleWorkflowIdentifiers.id(evidenceId, "evidenceId")
                + "/v/" + businessVersion + "/approval")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
