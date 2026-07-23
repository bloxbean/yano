package com.bloxbean.cardano.yano.appchain.roles.internal;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceRecordV1Test {
    private static final byte[] MEMBER = new byte[32];

    @Test
    void rejectsMutationHashDriftAndDuplicateApprovalWeight() throws Exception {
        byte[] mutation = {1, 2, 3};
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(mutation);
        byte[] wrongHash = hash.clone();
        wrongHash[0] ^= 1;

        assertThatThrownBy(() -> record(mutation, wrongHash, List.of(MEMBER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(mutation, hash, List.of(MEMBER, MEMBER)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonCanonicalApprovalOrderInRetainedState() throws Exception {
        byte[] mutation = {1, 2, 3};
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(mutation);
        byte[] memberA = filled(0x11);
        byte[] memberB = filled(0x22);
        GovernanceRecordV1 canonical = record(mutation, hash, List.of(memberA, memberB));
        Array root = RoleWorkflowCbor.decodeArray(canonical.encode(), 8);
        Array approvals = (Array) root.getDataItems().get(6);
        Collections.swap(approvals.getDataItems(), 0, 1);

        assertThatThrownBy(() -> GovernanceRecordV1.decode(RoleWorkflowCbor.encode(root)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static GovernanceRecordV1 record(byte[] mutation, byte[] hash,
                                               List<byte[]> approvals) {
        return new GovernanceRecordV1("mutation-1", mutation, hash, 10,
                MEMBER, approvals, GovernanceRecordV1.Status.PENDING);
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
