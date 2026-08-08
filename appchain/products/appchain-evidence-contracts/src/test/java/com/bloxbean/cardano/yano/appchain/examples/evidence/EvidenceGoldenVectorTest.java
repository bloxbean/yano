package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.EvidenceApprovalConsumptionV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.RoleEvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.NotifyEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.event.EvidenceAvailableEventV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectOperation;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGoldenVectorTest {
    private static final String RESOURCE =
            "/META-INF/yano/contracts/evidence/v1/golden-vectors.properties";
    private static Properties vectors;

    @BeforeAll
    static void loadVectors() throws IOException {
        vectors = new Properties();
        try (var input = EvidenceGoldenVectorTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(input).isNotNull();
            vectors.load(input);
        }
    }

    @Test
    void commandsMatchLiteralGoldenVectors() {
        assertVector("command.submit", EvidenceFixtures.submit().encode());
        assertVector("command.notify",
                new NotifyEvidenceCommandV1(EvidenceFixtures.ID, 1).encode());
        assertVector("command.republish", EvidenceFixtures.republish().encode());
    }

    @Test
    void stateEventAndQueryMatchLiteralGoldenVectors() {
        EvidenceHeadV1 head = new EvidenceHeadV1(
                EvidenceFixtures.ID, EvidenceFixtures.OWNER, 1);
        var record = EvidenceFixtures.storageReadyRecord();
        assertVector("state.head", head.encode());
        assertVector("state.record", record.encode());
        assertVector("event.available", EvidenceAvailableEventV1.fromRecord(record).encode());
        assertVector("query.latest",
                new EvidenceGetRequestV1(EvidenceFixtures.ID, 0).encode());
        assertVector("query.not-found", EvidenceGetResponseV1.notFound().encode());
    }

    @Test
    void keysAndScopesMatchLiteralGoldenVectors() {
        assertVector("key.id-hash", EvidenceKeys.idHash(EvidenceFixtures.ID));
        assertVector("key.head", EvidenceKeys.headKey(EvidenceFixtures.ID));
        assertVector("key.record", EvidenceKeys.recordKey(EvidenceFixtures.ID, 1));
        assertVector("key.kafka", EvidenceKeys.kafkaKey(EvidenceFixtures.ID, 1));
        assertThat(EvidenceKeys.effectScope(
                EvidenceFixtures.ID, 1, EvidenceEffectOperation.OBJECT))
                .isEqualTo(vectors.getProperty("scope.object"));
    }

    @Test
    void roleApprovalConsumptionMatchesPublishedGoldenVectors() {
        byte[] action = new byte[32];
        byte[] messageId = new byte[32];
        Arrays.fill(action, (byte) 0x31);
        Arrays.fill(messageId, (byte) 0x41);
        EvidenceApprovalConsumptionV1 receipt = new EvidenceApprovalConsumptionV1(
                "approval-42", "release-42", action,
                "evidence-release", 3, 99, messageId);

        assertVector("role-approval.consumption", receipt.encode());
        assertVector("role-approval.consumption-key",
                RoleEvidenceKeys.approvalConsumption("approval-42"));
        assertThat(vectors.getProperty("role-approval.consumption.cddl-root"))
                .isEqualTo("evidence-approval-consumption-v1");
    }

    private static void assertVector(String name, byte[] value) {
        assertThat(HexFormat.of().formatHex(value)).isEqualTo(vectors.getProperty(name));
    }
}
