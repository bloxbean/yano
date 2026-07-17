package com.bloxbean.cardano.yano.appchain.composite.stock;

import com.bloxbean.cardano.yano.appchain.composite.CompositeStateKeys;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalOutcome;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.stdlib.ApprovalsStateMachine;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody;
import com.bloxbean.cardano.yano.appchain.stdlib.KvRegistryStateMachine;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeStockConformanceTest {
    private static final String CHAIN = "composite-conformance";
    private static final byte[] REGISTRY_KEY = "product-42".getBytes(StandardCharsets.US_ASCII);
    private static final SubmitEvidenceCommandV1 EVIDENCE = evidence();
    private static final EvidenceReleaseCommandV1 RELEASE = new EvidenceReleaseCommandV1(
            "release-42", REGISTRY_KEY, "approval-42", "product-42", filled(0x66),
            "doc://certificate", EVIDENCE.encode());

    @Test
    void replayRestartAndSnapshotKeepWorkflowResultsRootsAndEffectsByteDeterministic() {
        for (long restartAt : List.of(1L, 2L, 3L)) {
            StateMachineConformance.Result result = StateMachineConformance.builder(
                            new com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachineProvider())
                    .chainId(CHAIN)
                    .settings(Map.of(
                            "effects.enabled", "true",
                            "effects.max-per-block", "8",
                            "effects.max-payload-bytes", "8192",
                            "machines.evidence-registry.storage-expiry-blocks", "1",
                            "machines.composite.preset", "evidence-v1"))
                    .blocks(4)
                    .messagesPerBlock(2)
                    .runs(2)
                    .restartAtHeight(restartAt)
                    .snapshotAtHeight(restartAt)
                    .messageGenerator((height, index, random) -> message(height, index))
                    .stateProbe("evidence-record", CompositeStateKeys.componentKey(
                            "evidence", EvidenceKeys.recordKey("sample-42", 1)))
                    .run();

            assertThat(result.deterministic()).as(result.describeDivergence()).isTrue();
            Map<Long, StateMachineConformance.HeightOutcome> baseline =
                    result.outcomesPerRun().getFirst();
            assertThat(baseline.get(1L).effectHashes()).isEmpty();
            assertThat(baseline.get(2L).effectHashes()).hasSize(2);
            assertThat(baseline.get(3L).effectHashes()).isEmpty();
            assertThat(baseline.get(4L).effectHashes()).isEmpty();

            EvidenceRecordV1 terminalAtThree = evidenceRecord(baseline.get(3L));
            assertThat(terminalAtThree.objectTerminal().outcome())
                    .isEqualTo(EvidenceTerminalOutcome.FAILED);
            assertThat(terminalAtThree.objectTerminal().resultHeight()).isEqualTo(3);
            assertThat(terminalAtThree.ipfsTerminal().outcome())
                    .isEqualTo(EvidenceTerminalOutcome.EXPIRED);
            assertThat(terminalAtThree.ipfsTerminal().resultHeight()).isEqualTo(3);
            assertThat(evidenceRecord(baseline.get(4L))).isEqualTo(terminalAtThree);
        }
    }

    private static EvidenceRecordV1 evidenceRecord(
            StateMachineConformance.HeightOutcome outcome) {
        String encoded = outcome.stateValues().get("evidence-record");
        assertThat(encoded).isNotNull().isNotEqualTo("<absent>");
        return EvidenceRecordV1.decode(HexFormat.of().parseHex(encoded));
    }

    private static StateMachineConformance.CorpusMessage message(long height, int index) {
        if (height == 1) {
            return index == 0
                    ? command(CompositeStockPresets.REGISTRY_TOPIC,
                    KvRegistryStateMachine.put(REGISTRY_KEY, bytes("passport")))
                    : command(CompositeStockPresets.APPROVALS_TOPIC,
                    ApprovalsStateMachine.propose("approval-42", EVIDENCE.encode(), 1, 0));
        }
        if (height == 2) {
            return index == 0
                    ? command(CompositeStockPresets.APPROVALS_TOPIC,
                    ApprovalsStateMachine.approve("approval-42"))
                    : command(EvidenceReleaseWorkflow.TOPIC, RELEASE.encode());
        }
        if (height == 3) {
            return index == 0
                    ? command(FxResultBody.TOPIC, new FxResultBody(
                    FxResultBody.BODY_VERSION, 2, 0, EffectOutcome.FAILED,
                    bytes("OBJECT_REJECTED"), filled(0x77)).encode())
                    : command(EvidenceReleaseWorkflow.TOPIC, RELEASE.encode());
        }
        return command(EvidenceReleaseWorkflow.TOPIC, RELEASE.encode());
    }

    private static StateMachineConformance.CorpusMessage command(String topic, byte[] body) {
        return new StateMachineConformance.CorpusMessage(topic, body);
    }

    private static SubmitEvidenceCommandV1 evidence() {
        ObjectPutCommandV1 object = new ObjectPutCommandV1(
                "archive", "staging/certificate.bin", "evidence/certificate.bin",
                DigestAlgorithm.SHA_256, filled(0x11), 32,
                "application/octet-stream", null);
        IpfsPinCommandV1 ipfs = new IpfsPinCommandV1("local", CanonicalCid.fromText(
                "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi"),
                true, "demo-single");
        return new SubmitEvidenceCommandV1("sample-42", 1, object.encode(), filled(0x22),
                ipfs.encode(), filled(0x33), "primary", "evidence-ready", filled(0x55));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
