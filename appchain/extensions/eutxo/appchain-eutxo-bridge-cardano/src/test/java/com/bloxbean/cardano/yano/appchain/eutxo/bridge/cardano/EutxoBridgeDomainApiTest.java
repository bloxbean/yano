package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.api.plugin.domain.L1TransactionBuilderService;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelQueryService;
import com.bloxbean.cardano.yano.api.plugin.domain.PrivilegedSystemMessageService;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBridgeInfo;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoBridgeDomainApiTest {
    private static final String CHAIN = "payments";
    private static final String DEPOSITOR = EutxoKeyWallet.fromSeed(
            java.util.HexFormat.of().parseHex("01".repeat(32))).address();
    private static final String VAULT =
            "addr_test1wpxg9ntn83pztkpw09lfkvv4uurd7pxztlx7yg0zqr0frdcuc9zzj";

    @Test
    void depositUsesOnlyTheReviewedHostBuilderAndCommittedBridgeQuery()
            throws Exception {
        AtomicReference<L1TransactionBuilderService.PaymentPlan> plan =
                new AtomicReference<>();
        L1TransactionBuilderService builder = new L1TransactionBuilderService() {
            @Override public long tipSlot() { return 5_000; }
            @Override public SpendableInput selectSpendableInput(String sourceAddress) {
                assertThat(sourceAddress).isEqualTo(DEPOSITOR);
                return new SpendableInput("11".repeat(32), 0, 50_000_000);
            }
            @Override public UnsignedTransaction buildPayment(PaymentPlan value) {
                plan.set(value);
                return new UnsignedTransaction(
                        new byte[]{1, 2, 3}, "22".repeat(32), 170_000, value.ttlSlot());
            }
        };
        DomainQueryService queries = new DomainQueryService() {
            @Override public List<String> chainIds() { return List.of(CHAIN); }
            @Override public AppQueryResult query(
                    String chainId, String path, byte[] request
            ) {
                assertThat(path).isEqualTo(EutxoQueryCodec.BRIDGE_INFO_PATH);
                byte[] payload = new EutxoBridgeInfo(
                        true, VAULT, "44".repeat(28), "", 0,
                        BigInteger.ONE, true).encode();
                return new AppQueryResult(
                        chainId, "eutxo-ledger", 2, new byte[32], payload);
            }
        };
        EutxoBridgeDomainApi api = new EutxoBridgeDomainApi(new DomainApiContext(
                Map.of("max-deposit-lovelace", "100000000"), queries,
                LocalReadModelQueryService.unavailable(),
                PrivilegedSystemMessageService.unavailable(), builder));
        byte[] body = new ObjectMapper().writeValueAsBytes(Map.of(
                "depositorAddress", DEPOSITOR,
                "lovelace", 8_000_000));

        var result = api.handle(new DomainApiRequest(
                "bridge-deposit-build", DomainHttpMethod.POST,
                "chains/payments/bridge/deposit/build",
                Map.of("chain_id", CHAIN), Map.of(), body));

        assertThat(result.status()).isEqualTo(200);
        assertThat(new String(result.body())).contains(
                "\"unsignedTxCborHex\":\"010203\"",
                "\"transactionId\":\"" + "22".repeat(32) + "\"");
        assertThat(plan.get().destinationAddress()).isEqualTo(VAULT);
        assertThat(plan.get().lovelace()).isEqualTo(8_000_000);
        assertThat(plan.get().ttlSlot()).isEqualTo(12_200);
        assertThat(plan.get().inlineDatum()).isNotEmpty();
    }
}
