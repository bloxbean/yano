package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoBridgeResourceTest {
    private static final String CHAIN = "payment-chain-l1bridge";
    private static final byte[] SEED = sha256("eutxo-bridge-resource-test");
    private static final EutxoKeyWallet DEPOSITOR = EutxoKeyWallet.fromSeed(SEED);
    private static final String VAULT =
            "addr_test1wpxg9ntn83pztkpw09lfkvv4uurd7pxztlx7yg0zqr0frdcuc9zzj";

    private final EutxoBridgeResource.BridgeSettings settings =
            new EutxoBridgeResource.BridgeSettings(
                    VAULT,
                    "4c82cd733c4225d82e797e9b3195e706df04c25fcde221e200de91b7",
                    "addr_test1vrpz48l78va55y3ewuv7p6narrtgsw2ajq3ns9xx945e0vsmpxjls",
                    1,
                    BigInteger.valueOf(100_000_000L),
                    false,
                    2);

    private EutxoBridgeResource resource(List<Utxo> utxos) {
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(
                    String address, Integer count, Integer page, OrderEnum order) {
                return DEPOSITOR.address().equals(address)
                        && (page == null || page == 0) ? utxos : List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return utxos.stream()
                        .filter(value -> value.getTxHash().equals(txHash)
                                && value.getOutputIndex() == outputIndex)
                        .findFirst();
            }
        };
        return new EutxoBridgeResource(
                CHAIN, settings, supplier, EutxoBridgeResourceTest::params,
                () -> 5_000L);
    }

    private static Utxo utxo(int index, long lovelace) {
        return Utxo.builder()
                .txHash("11".repeat(32))
                .outputIndex(index)
                .address(DEPOSITOR.address())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))))
                .build();
    }

    @Test
    void buildsUnsignedDepositWithInlineDatumAndAssemblesCip30Witnesses()
            throws Exception {
        EutxoBridgeResource resource = resource(List.of(utxo(0, 50_000_000L)));
        Map<?, ?> info = (Map<?, ?>) resource.info().getEntity();
        assertThat(info.get("vaultAddress")).isEqualTo(VAULT);

        Response response = resource.depositBuild(
                new EutxoBridgeResource.DepositBuildRequest(
                        DEPOSITOR.address(), 8_000_000L, null));
        Map<?, ?> fields = (Map<?, ?>) response.getEntity();
        assertThat(fields.get("l2OwnerAddress")).isEqualTo(DEPOSITOR.address());
        assertThat((long) fields.get("ttlSlot")).isEqualTo(12_200L);

        byte[] unsignedCbor = HexFormat.of().parseHex(
                (String) fields.get("unsignedTxCborHex"));
        Transaction unsigned = Transaction.deserialize(unsignedCbor);
        assertThat(unsigned.getBody().getFee()).isPositive();
        var vaultOutputs = unsigned.getBody().getOutputs().stream()
                .filter(output -> VAULT.equals(output.getAddress()))
                .toList();
        assertThat(vaultOutputs).hasSize(1);
        assertThat(vaultOutputs.getFirst().getValue().getCoin())
                .isEqualTo(BigInteger.valueOf(8_000_000L));
        assertThat(HexFormat.of().formatHex(
                CborSerializationUtil.serialize(
                        vaultOutputs.getFirst().getInlineDatum().serialize())))
                .isEqualTo(fields.get("datumHex"));
        // Change (minus fee) returns to the depositor.
        assertThat(unsigned.getBody().getOutputs().stream()
                .filter(output -> DEPOSITOR.address().equals(output.getAddress()))
                .count()).isEqualTo(1);

        // CIP-30 signTx returns ONLY a witness set; emulate it by signing and
        // extracting the witnesses.
        Transaction signed = TransactionSigner.INSTANCE.sign(
                unsigned, new SecretKey(DEPOSITOR.signingKey().getCborHex()));
        String witnessSetHex = HexFormat.of().formatHex(
                CborSerializationUtil.serialize(
                        signed.getWitnessSet().serialize()));
        Response assembled = resource.depositAssemble(
                new EutxoBridgeResource.DepositAssembleRequest(
                        (String) fields.get("unsignedTxCborHex"), witnessSetHex));
        Map<?, ?> assembledFields = (Map<?, ?>) assembled.getEntity();
        Transaction complete = Transaction.deserialize(HexFormat.of().parseHex(
                (String) assembledFields.get("signedTxCborHex")));
        assertThat(complete.getWitnessSet().getVkeyWitnesses()).hasSize(1);
        assertThat(assembledFields.get("transactionId"))
                .isEqualTo(fields.get("transactionId"));
    }

    @Test
    void rejectsBadRequestsBeforeTouchingTheLedger() {
        EutxoBridgeResource resource = resource(List.of(utxo(0, 50_000_000L)));
        assertThatThrownBy(() -> resource.depositBuild(
                new EutxoBridgeResource.DepositBuildRequest(
                        DEPOSITOR.address(), 200_000_000L, null)))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> resource.depositBuild(
                new EutxoBridgeResource.DepositBuildRequest(
                        DEPOSITOR.address(), 8_000_000L,
                        settings.withdrawalAddress())))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> resource.depositBuild(
                new EutxoBridgeResource.DepositBuildRequest(
                        DEPOSITOR.address(), 500_000L, null)))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("400");
    }

    @Test
    void reportsMissingFundsAsConflict() {
        EutxoBridgeResource resource = resource(List.of());
        assertThatThrownBy(() -> resource.depositBuild(
                new EutxoBridgeResource.DepositBuildRequest(
                        DEPOSITOR.address(), 8_000_000L, null)))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("409");
    }

    private static ProtocolParams params() {
        return ProtocolParams.builder()
                .minFeeA(44)
                .minFeeB(155381)
                .maxTxSize(16384)
                .coinsPerUtxoSize("4310")
                .maxValSize("5000")
                .keyDeposit("2000000")
                .poolDeposit("500000000")
                .priceMem(new BigDecimal("0.0577"))
                .priceStep(new BigDecimal("0.0000721"))
                .collateralPercent(new BigDecimal("150"))
                .maxCollateralInputs(3)
                .build();
    }

    private static byte[] sha256(String seedText) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(
                    seedText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
