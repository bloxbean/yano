package com.bloxbean.cardano.yano.appchain.roles;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoleEvidenceProfileCliTest {
    @Test
    void profileTrustRootIsStableAndCommitsGenesisAdministrationAndMode() {
        Map<String, String> first = options("01".repeat(32) + "," + "02".repeat(32)
                + "," + "03".repeat(32), "explicit");
        byte[] digest = RoleEvidenceProfileCli.profileDigest(first);

        assertThat(digest).hasSize(32)
                .containsExactly(RoleEvidenceProfileCli.profileDigest(first));
        assertThat(digest).isNotEqualTo(RoleEvidenceProfileCli.profileDigest(
                options("01".repeat(32) + "," + "02".repeat(32)
                        + "," + "04".repeat(32), "explicit")));
        assertThat(digest).isNotEqualTo(RoleEvidenceProfileCli.profileDigest(
                options("01".repeat(32) + "," + "02".repeat(32)
                        + "," + "03".repeat(32), "direct")));
        assertThat(HexFormat.of().formatHex(digest)).hasSize(64);
    }

    private static Map<String, String> options(String members, String continuation) {
        return Map.of("--chain", "evidence-role-demo", "--members", members,
                "--threshold", "2", "--storage-gate", "app-final",
                "--continuation", continuation, "--evidence-capacity", "8");
    }
}
