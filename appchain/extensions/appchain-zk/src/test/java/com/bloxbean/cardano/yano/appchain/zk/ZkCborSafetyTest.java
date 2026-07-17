package com.bloxbean.cardano.yano.appchain.zk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZkCborSafetyTest {

    @Test
    void deeplyNestedMemberBodiesAreRejectedBeforeRecursiveDecode() {
        byte[] hostile = nestedArrays(10_000);

        assertThatThrownBy(() -> ZkProofBody.decode(hostile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MembershipProofBody.decode(hostile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CredentialBody.decode(hostile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] nestedArrays(int depth) {
        byte[] bytes = new byte[depth + 1];
        java.util.Arrays.fill(bytes, 0, depth, (byte) 0x81);
        bytes[depth] = 0;
        return bytes;
    }
}
