package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
class AuthenticatedMapRuntimeTest {
    private static final Logger log = LoggerFactory.getLogger(AuthenticatedMapRuntimeTest.class);
    private static final byte[] SIGNING_KEY = repeated(0x5a);

    @TempDir
    Path tempDir;

    private final List<AppChainSubsystem> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AppChainSubsystem node : nodes) {
            try {
                node.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void serviceLoadedMachineCommitsProofsQueriesHistorySnapshotAndRestart() throws Exception {
        String chainId = "authenticated-map-runtime";
        String member = HexUtil.encodeHexString(
                KeyGenUtil.getPublicKeyFromPrivateKey(SIGNING_KEY));
        AppChainConfig unsignedGenesisConfig = config(chainId, member, java.util.Map.of());
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapGenesisFactory.mpf(
                unsignedGenesisConfig, repeated(0x33), 32, 32_768,
                List.of(new AuthenticatedMapContract.CollectionDescriptor(
                        "products", AuthenticatedMapContract.AUTH_OWNER,
                        true, 64, 4096)),
                List.of());
        AppChainConfig config = config(
                chainId, member, AuthenticatedMapGenesisFactory.settings(genesis));
        Path ledgerBase = tempDir.resolve("ledger");

        AppChainSubsystem first = start(config, ledgerBase);
        byte[] applicationKey = "sku-1".getBytes(StandardCharsets.US_ASCII);
        byte[] canonicalKey = CompositeStateKeys.componentKey(
                AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey("products", applicationKey));
        AuthenticatedMapContract.Command mutation = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "products", applicationKey,
                        "metadata-v1".getBytes(StandardCharsets.US_ASCII)));
        String messageId = first.submit(AuthenticatedMapContract.DEFAULT_TOPIC,
                AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                                AuthenticatedMapAuthorizationContract.MapActionV1.basic(
                                        mutation, List.of(AuthenticatedMapContract.AUTH_OWNER)),
                                List.of())));
        awaitTrue("authenticated-map put finalized",
                () -> first.stateValue(canonicalKey).isPresent());

        long finalizedHeight = first.tipHeight();
        byte[] rootBeforeRestart = first.stateRoot();
        assertThat(first.stateProofEnvelope(canonicalKey)).isPresent();
        assertThat(first.stateProofEnvelopeAtHeight(finalizedHeight, canonicalKey))
                .isPresent();
        assertThat(first.stateProofEnvelopeAtHeight(finalizedHeight, canonicalKey)
                .orElseThrow().proof().snapshot().stateRoot()).isEqualTo(rootBeforeRestart);

        AppQueryResult pointQuery = first.query(
                AuthenticatedMapContract.POINT_QUERY_PATH,
                AuthenticatedMapContract.encodePointQuery(
                        AuthenticatedMapContract.PointQuery.current(
                                "products", applicationKey)));
        AuthenticatedMapContract.PointResult point =
                AuthenticatedMapContract.decodePointResult(pointQuery.payload());
        assertThat(point.entry().value())
                .isEqualTo("metadata-v1".getBytes(StandardCharsets.US_ASCII));
        assertThat(point.stateRoot()).isEqualTo(pointQuery.stateRoot());

        AppQueryResult receiptQuery = first.query(
                AuthenticatedMapContract.RECEIPT_QUERY_PATH,
                AuthenticatedMapContract.encodeReceiptQuery(
                        new AuthenticatedMapContract.ReceiptQuery(
                                HexUtil.decodeHexString(messageId))));
        assertThat(AuthenticatedMapContract.decodeReceiptResult(
                receiptQuery.payload()).receipt().status())
                .isEqualTo(AuthenticatedMapContract.RECEIPT_APPLIED);

        Path snapshot = tempDir.resolve("snapshot");
        assertThat(first.snapshot(snapshot.toString())).isEqualTo(finalizedHeight);
        first.stop();
        nodes.remove(first);

        AppChainSubsystem restarted = start(config, ledgerBase);
        assertThat(restarted.tipHeight()).isEqualTo(finalizedHeight);
        assertThat(restarted.stateRoot()).isEqualTo(rootBeforeRestart);
        assertThat(restarted.stateValue(canonicalKey)).isPresent();
        assertThat(restarted.stateProofEnvelopeAtHeight(finalizedHeight, canonicalKey))
                .isPresent();
        assertThat(snapshot.resolve("snapshot-manifest.json")).exists();
    }

    private AppChainConfig config(String chainId, String member,
                                  java.util.Map<String, String> settings) {
        return AppChainConfig.builder(chainId)
                .signingKeyHex(HexUtil.encodeHexString(SIGNING_KEY))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .threshold(1)
                .blockIntervalMs(200)
                .stateMachineId(AuthenticatedMapStateMachine.ID)
                .pluginSettings(settings)
                .build();
    }

    private AppChainSubsystem start(AppChainConfig config, Path ledgerBase) {
        AppChainSubsystem node = new AppChainSubsystem(
                config, 42, null, null, ledgerBase.toString(), null, log);
        nodes.add(node);
        node.start();
        return node;
    }

    private static void awaitTrue(String description, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static byte[] repeated(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
