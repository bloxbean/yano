package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppQueryException;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.AppSubmissionRejectedException;
import org.yanoproject.api.appchain.PoolFullException;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local admission is advisory, root-fixed, and happens before pool retention. */
@Timeout(30)
class AppChainSubmissionAdmissionTest {
    private static final byte[] HEIGHT_KEY = {42};
    @TempDir Path directory;
    private AppChainSubsystem node;

    @AfterEach
    void close() throws Exception {
        if (node != null) {
            node.close();
        }
    }

    @Test
    void diffusionOnlyModeDoesNotInventApplicationStateOrCandidateHeight() {
        var machine = new AdmissionMachine();
        start(machine, 60_000, false);
        assertThat(node.submit("reject", new byte[]{1})).isNotBlank();
        assertThat(machine.retained).isNull();
        assertThat(machine.lastCandidate).isZero();
        assertThat(node.tipHeight()).isZero();
        assertThat(node.recentMessages(10)).hasSize(1);
        assertAdmissionTotals(0, 0);
    }

    @Test
    void rejectionDoesNotOccupyPoolOrAppearAsAcceptedTraffic() {
        var machine = new AdmissionMachine();
        start(machine, 60_000);
        assertThatThrownBy(() -> node.submit("reject", new byte[]{1}))
                .isInstanceOf(AppSubmissionRejectedException.class)
                .hasMessage("EVENT_PAYLOAD_TOO_LARGE");
        assertThat(node.recentMessages(10)).isEmpty();
        assertThatThrownBy(() -> machine.retained.stateRoot()).isInstanceOf(AppQueryException.class);
        assertThat(node.submit("accept", new byte[]{1})).isNotBlank();
        assertThatThrownBy(() -> node.submit("accept", new byte[]{2})).isInstanceOf(PoolFullException.class);
        assertAdmissionTotals(1, 0);
        // Reading diagnostics is not a new admission decision.
        assertAdmissionTotals(1, 0);
    }

    @Test
    void pluginProseAndPluginSymbolsCannotCreateMetricKeys() {
        start(new AdmissionMachine(), 60_000);
        for (String topic : new String[]{"prose", "symbol"}) {
            assertThatThrownBy(() -> node.submit(topic, new byte[]{1}))
                    .isInstanceOf(AppSubmissionRejectedException.class);
        }
        assertAdmissionTotals(2, 0);
        assertThat(node.recentMessages(10)).isEmpty();
    }

    @Test
    void callbackFailuresAreNotBusinessRejectionsAndNeverExposePrivateText() {
        var machine = new AdmissionMachine();
        start(machine, 60_000);
        assertThatThrownBy(() -> node.submit("throws", new byte[]{1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("App-chain application admission is unavailable")
                .hasNoCause();
        assertThat(node.recentMessages(10)).isEmpty();
        assertThat(node.submit("accept", new byte[]{1})).isNotBlank();
        assertAdmissionTotals(0, 1);
    }

    @Test
    void nullCallbackResultCountsAsUnavailableOnce() {
        start(new AdmissionMachine(), 60_000);
        assertThatThrownBy(() -> node.submit("null", new byte[]{1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("App-chain application admission is unavailable");
        assertAdmissionTotals(0, 1);
    }

    private void assertAdmissionTotals(long rejected, long failed) {
        Map<String, Object> status = node.status();
        assertThat(status.get("admissionRejections"))
                .isEqualTo(Map.of("APPLICATION_REJECTED", rejected));
        assertThat(status.get("admissionUnavailable"))
                .isEqualTo(Map.of("CALLBACK_FAILED", failed, "STATE_UNAVAILABLE", 0L));
    }

    @Test
    void postGenesisAdmissionUsesCandidateHeightAndItsCommittedState() throws Exception {
        var machine = new AdmissionMachine();
        start(machine, 100);
        String first = node.submit("accept", new byte[]{1});
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (node.messageHeight(HexUtil.decodeHexString(first)).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(node.messageHeight(HexUtil.decodeHexString(first))).isPresent();
        // The machine's stateless validate always rejects. Its candidate-aware
        // hook checks that persisted predecessor height and candidate agree.
        assertThat(node.submit("after-genesis", new byte[]{2})).isNotBlank();
        assertThat(machine.lastCandidate).isGreaterThan(1);
    }

    private void start(AppStateMachine machine, long interval) {
        start(machine, interval, true);
    }

    private void start(AppStateMachine machine, long interval, boolean sequencing) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 91);
        String member = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        AppChainConfig config = AppChainConfig.builder("admission-chain")
                .signingKeyHex(HexUtil.encodeHexString(seed)).memberKeysHex(Set.of(member))
                .proposerKeyHex(sequencing ? member : null).threshold(1).blockIntervalMs(interval)
                .maxBlockMessages(1).poolMaxMessages(1)
                .stateCommitmentIdentity(TestStateCommitments.MPF).build();
        node = new AppChainSubsystem(config, 42, null, machine, directory.resolve("ledger").toString(),
                LoggerFactory.getLogger(AppChainSubmissionAdmissionTest.class));
        node.start();
    }

    private static final class AdmissionMachine implements AppStateMachine {
        private volatile AppStateReader retained;
        private volatile long lastCandidate;

        @Override public String id() { return "admission-test"; }

        @Override public AdmissionResult validate(AppMessage message) {
            return AdmissionResult.reject("STATELESS_VALIDATION_FORBIDDEN");
        }

        @Override
        public AdmissionResult validateForBlock(AppMessage message, long height, AppStateReader state) {
            retained = state;
            lastCandidate = height;
            long previous = state.get(HEIGHT_KEY).map(bytes -> ByteBuffer.wrap(bytes).getLong()).orElse(0L);
            if (previous != height - 1) {
                return AdmissionResult.reject("INCOHERENT_CANDIDATE");
            }
            return switch (message.getTopic()) {
                case "reject" -> AdmissionResult.reject("EVENT_PAYLOAD_TOO_LARGE");
                case "prose" -> AdmissionResult.reject("private body / token=secret\ninvalid field");
                case "symbol" -> AdmissionResult.reject("PLUGIN_CHOSEN_SYMBOL");
                case "null" -> null;
                case "throws" -> throw new IllegalArgumentException("private payload / token=secret");
                case "after-genesis" -> height > 1 ? AdmissionResult.accept()
                        : AdmissionResult.reject("WRONG_GENERATION");
                default -> AdmissionResult.accept();
            };
        }

        @Override
        public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
            writer.put(HEIGHT_KEY, ByteBuffer.allocate(Long.BYTES).putLong(context.block().height()).array());
        }
    }
}
