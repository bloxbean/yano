package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.l1view.L1EpochStateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class PriorCompiledRuntimeCompatibilityTest {
    @TempDir Path temporary;

    @Test
    void pre19ClientLinksOriginalNamesToExtractedRuntimeAndPersistsAcrossRestart() throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/app-ledger/compat/pre19-runtime-client.jar")) {
            assertThat(input).isNotNull();
            fixture = input.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture)))
                .isEqualTo("430fcfb2ea3d53ed6cc7d4b0d5e3c17eedd619620d5f6e1d8036c68a0192c787");
        assertThat(AppChainSubsystem.class.getSuperclass().getName())
                .isEqualTo("org.yanoproject.runtime.appchain.AppLedgerRuntime");
        assertThat(AppChainManager.class.getSuperclass().getName())
                .isEqualTo("org.yanoproject.runtime.appchain.AppLedgerManager");
        Path jar = temporary.resolve("pre19-runtime-client.jar");
        Files.write(jar, fixture);
        SimpleEventBus events = new SimpleEventBus();
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> client = loader.loadClass("compat.Pre19RuntimeClient");
            assertThat(client.getClassLoader()).isSameAs(loader);
            var method = client.getMethod("exercise", AppChainConfig.class, String.class,
                    EventBus.class, Logger.class,
                    ChainBlockReader.class, L1EpochStateProvider.class, TxEvaluationGateway.class);
            Object result;
            try {
                result = method.invoke(null, config(), temporary.resolve("ledger").toString(), events,
                        LoggerFactory.getLogger(getClass()), inert(ChainBlockReader.class),
                        inert(L1EpochStateProvider.class), inert(TxEvaluationGateway.class));
            } catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof Error error) throw error;
                if (wrapped.getCause() instanceof Exception exception) throw exception;
                throw wrapped;
            }
            assertThat(result).isEqualTo(List.of("nested-fee-record", "constructors-and-cardano-wiring",
                    "manager-protocol-and-gateway-api", "inherited-finality-and-query-api",
                    "inherited-restart-persistence", "inherited-close"));
        } finally {
            events.close();
        }
    }

    private static <T> T inert(Class<T> contract) {
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, arguments) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    throw new AssertionError("Unexpected fixture host invocation: " + method);
                }));
    }

    private static AppChainConfig config() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 91);
        String member = HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        return AppChainConfig.builder("pre19-runtime-client")
                .signingKeyHex(HexFormat.of().formatHex(seed))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .threshold(1)
                .blockIntervalMs(50)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
    }
}
