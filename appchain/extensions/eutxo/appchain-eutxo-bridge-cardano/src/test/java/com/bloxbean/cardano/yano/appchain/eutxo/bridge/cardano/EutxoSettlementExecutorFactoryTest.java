package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.api.appchain.effects.AppChainEffectContext;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.l1view.BridgeDiffusionHandler;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009 SP-M6: the non-owner member's wiring. A chain configured with
 * {@code effects.executor.enabled=true} fails activation when a factory
 * yields zero products, so every member must produce one — but only the
 * pinned owner may own {@code l1.settlement}.
 */
class EutxoSettlementExecutorFactoryTest {

    @Test
    void nonOwnerMemberRegistersCosignAndOwnsNoEffectType() throws Exception {
        AtomicReference<BridgeDiffusionHandler> registered = new AtomicReference<>();
        List<AppEffectExecutor> executors = new EutxoSettlementExecutorFactory()
                .create("payment-chain-settlement", config(false),
                        context(registered));

        // Co-sign is registered on a non-owner: that is why it must start.
        assertThat(registered.get()).isNotNull();
        assertThat(executors).hasSize(1);
        AppEffectExecutor executor = executors.getFirst();
        assertThat(executor.effectTypes()).isEmpty();
        assertThat(executor.supports("l1.settlement")).isFalse();
        executor.close();
    }

    @Test
    void ownerMemberOwnsTheSettlementEffect() throws Exception {
        AtomicReference<BridgeDiffusionHandler> registered = new AtomicReference<>();
        List<AppEffectExecutor> executors = new EutxoSettlementExecutorFactory()
                .create("payment-chain-settlement", config(true),
                        context(registered));

        assertThat(registered.get()).isNotNull();
        assertThat(executors).hasSize(1);
        assertThat(executors.getFirst().effectTypes()).isNotEmpty();
        executors.getFirst().close();
    }

    /** Config-only creation cannot wire the node-coupled stack. */
    @Test
    void declinesWithoutContext() {
        assertThat(new EutxoSettlementExecutorFactory()
                .create("payment-chain-settlement", config(true))).isEmpty();
        assertThat(new EutxoSettlementExecutorFactory()
                .create("payment-chain-settlement", config(true), null)).isEmpty();
    }

    // ------------------------------------------------------------------

    private static Map<String, String> config(boolean owner) {
        Map<String, String> config = new HashMap<>();
        config.put("owner", String.valueOf(owner));
        config.put("vault-address", "addr_test1wp5kwkyukh0h6vyj6g2vc250gnjy4hhswhwfauv0m349mugukf2cf");
        config.put("shard-address", "addr_test1wzf40zzylfag4jxuqky9r4m65eslvjh2mw8q8gwawk5w77qzyg439");
        config.put("root-address", "addr_test1wqr20zh95ngrteg2n5kcafcrc6ykz5c97wvtjkrnvj7mp7cwnfhhg");
        config.put("root-unit", "00".repeat(28) + "59616e6f536574746c65526f6f74");
        config.put("shard-thread-policy-id", "11".repeat(28));
        config.put("operator-address", "addr_test1vq2p8rmdp8p70auqjg0vzu59793575jqegfuwejrk5mu42czwm8wp");
        config.put("operator-seed", "22".repeat(32));
        config.put("vault-script", "46450101002499");
        config.put("shard-script", "46450101002499");
        return config;
    }

    private static AppChainEffectContext context(
            AtomicReference<BridgeDiffusionHandler> registered) {
        return new AppChainEffectContext() {
            @Override
            public String chainId() {
                return "payment-chain-settlement";
            }

            @Override
            public void diffuse(String topic, byte[] body) {
            }

            @Override
            public SignerProvider memberSigner() {
                return new SignerProvider() {
                    @Override
                    public byte[] sign(byte[] message) {
                        return new byte[64];
                    }

                    @Override
                    public byte[] publicKey() {
                        return new byte[32];
                    }
                };
            }

            @Override
            public java.util.function.Supplier<Set<String>> members() {
                return Set::of;
            }

            @Override
            public java.util.function.IntSupplier threshold() {
                return () -> 2;
            }

            @Override
            public com.bloxbean.cardano.yano.api.appchain.AppQueryResult query(
                    String path, byte[] request) {
                return null;
            }

            @Override
            public java.util.function.Supplier<
                    com.bloxbean.cardano.yano.api.utxo.UtxoState> l1UtxoView() {
                return () -> null;
            }

            @Override
            public java.util.function.Supplier<
                    com.bloxbean.cardano.client.api.model.ProtocolParams> protocolParams() {
                return () -> null;
            }

            @Override
            public com.bloxbean.cardano.yano.api.TxEvaluationGateway txEvaluation() {
                return null;
            }

            @Override
            public String submitTx(byte[] transactionCbor) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void registerBridgeDiffusionHandler(BridgeDiffusionHandler handler) {
                registered.set(handler);
            }
        };
    }
}
