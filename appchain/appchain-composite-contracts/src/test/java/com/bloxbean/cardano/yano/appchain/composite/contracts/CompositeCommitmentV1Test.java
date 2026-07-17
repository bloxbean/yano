package com.bloxbean.cardano.yano.appchain.composite.contracts;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeCommitmentV1Test {
    @Test
    void freezesMarkerNamespaceAndDigestDomain() {
        assertThat(CompositeCommitmentV1.profileMarkerKey())
                .isEqualTo("~composite/profile/v1".getBytes(StandardCharsets.US_ASCII));
        assertThat(HexFormat.of().formatHex(CompositeCommitmentV1.componentKey(
                "evidence", new byte[]{1, 2}))).isEqualTo(
                "79616e6f2d636f6d706f736974652d73746174652d7631000865766964656e636500020102");
        assertThat(HexFormat.of().formatHex(
                CompositeCommitmentV1.profileDigest(new byte[]{1, 2, 3})))
                .isEqualTo("c5a90ddcb4e282f4a871b846f5d8840b2b78180919c6df4e285181685d03b8ef");
    }

    @Test
    void rejectsUnboundedOrNonCanonicalInputs() {
        assertThatThrownBy(() -> CompositeCommitmentV1.componentKey("Evidence", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompositeCommitmentV1.componentKey("evidence", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompositeCommitmentV1.profileDigest(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
