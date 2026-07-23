package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalConfirmation;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcceptedVaultDepositObserverTest {
    private static final byte[] VAULT_HASH = fill(28, 7);
    private static final String VAULT_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromScript(VAULT_HASH), Networks.testnet()).getAddress();
    private static final String OWNER = EutxoTestWallet.fromSeed(fill(32, 2)).address();

    @Test
    void exactAcceptedVaultOutputProducesOneCanonicalClaim() {
        EutxoVaultDatum datum = datum();
        AcceptedVaultDepositObserver observer = observer();
        Block block = block(List.of(output(VAULT_ADDRESS, 50, datum.encode())));

        List<L1Observation> observations = observer.observe(100, fill(32, 9), block);

        assertThat(observations).singleElement().satisfies(observation -> {
            EutxoDepositClaim claim = EutxoDepositClaim.decode(observation.claim());
            assertThat(claim.chainId()).isEqualTo("payments-eutxo");
            assertThat(claim.acceptedOutpoint())
                    .isEqualTo(new EutxoOutpoint("11".repeat(32), 0));
            assertThat(claim.stagingOutpoint()).isEqualTo(datum.stagingOutpoint());
            assertThat(claim.l2Address()).isEqualTo(OWNER);
            assertThat(claim.mirroredOutpoint()).isEqualTo(
                    EutxoDepositClaim.decode(claim.encode()).mirroredOutpoint());
        });
    }

    @Test
    void stagingOutputsAreIgnoredAndMultipleVaultOutputsFailClosed() {
        AcceptedVaultDepositObserver observer = observer();
        EutxoVaultDatum datum = datum();
        assertThat(observer.observe(
                100,
                fill(32, 9),
                block(List.of(output(OWNER, 50, datum.encode())))))
                .isEmpty();
        assertThatThrownBy(() -> observer.observe(
                100,
                fill(32, 9),
                block(List.of(
                        output(VAULT_ADDRESS, 20, datum.encode()),
                        output(VAULT_ADDRESS, 30, datum.encode())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only one");
    }

    @Test
    void acceptanceBuilderPreservesTheFullDepositAndPaysFeesExternally() {
        EutxoVaultDatum datum = datum();
        EutxoOutpoint feeOutpoint = new EutxoOutpoint("22".repeat(32), 1);
        com.bloxbean.cardano.client.transaction.spec.TransactionBody body =
                DepositAcceptanceBuilder.build(
                        datum.stagingOutpoint(),
                        BigInteger.valueOf(100),
                        feeOutpoint,
                        BigInteger.valueOf(12),
                        BigInteger.valueOf(2),
                        VAULT_ADDRESS,
                        OWNER,
                        datum,
                        500);

        assertThat(body.getInputs()).hasSize(2);
        assertThat(body.getInputs().getFirst().getTransactionId())
                .isEqualTo(datum.stagingOutpoint().transactionId());
        assertThat(body.getInputs().get(1).getTransactionId())
                .isEqualTo(feeOutpoint.transactionId());
        assertThat(body.getOutputs()).hasSize(2);
        assertThat(body.getOutputs().getFirst()).satisfies(output -> {
                    assertThat(output.getAddress()).isEqualTo(VAULT_ADDRESS);
                    assertThat(output.getValue().getCoin()).isEqualTo(BigInteger.valueOf(100));
                    assertThat(EutxoVaultDatum.decode(output.getInlineDatum().serializeToBytes()))
                            .isEqualTo(datum);
                });
        assertThat(body.getOutputs().get(1).getValue().getCoin())
                .isEqualTo(BigInteger.TEN);
        assertThat(body.getTtl()).isEqualTo(999);
    }

    @Test
    void rollbackBelowACreditedDepositRequiresAHalt() {
        assertThat(BridgeRollbackGuard.assess(90, 100).halt()).isTrue();
        assertThat(BridgeRollbackGuard.assess(100, 100).halt()).isFalse();
    }

    @Test
    void settlementMarkerProducesAnExactWithdrawalConfirmation() {
        EutxoSettlementDatum settlement = new EutxoSettlementDatum(
                1,
                "payments-eutxo",
                3,
                "55".repeat(32),
                OWNER,
                BigInteger.valueOf(20));
        WithdrawalConfirmationObserver observer =
                new WithdrawalConfirmationObserver("bridge-withdrawals", Map.of(
                        "chain-id", "payments-eutxo",
                        "bridge-epoch", "3",
                        "vault-address", VAULT_ADDRESS));
        Block settlementBlock = block(List.of(
                output(OWNER, 20, null),
                output(VAULT_ADDRESS, 30, settlement.encode())));

        assertThat(observer.observe(101, fill(32, 8), settlementBlock))
                .singleElement()
                .satisfies(observation -> {
                    EutxoWithdrawalConfirmation confirmation =
                            EutxoWithdrawalConfirmation.decode(observation.claim());
                    assertThat(confirmation.claimId()).isEqualTo("55".repeat(32));
                    assertThat(confirmation.destinationAddress()).isEqualTo(OWNER);
                    assertThat(confirmation.lovelace()).isEqualTo(BigInteger.valueOf(20));
                    assertThat(confirmation.continuingVaultLovelace())
                            .isEqualTo(BigInteger.valueOf(30));
                });
    }

    @Test
    void settlementWithoutExactPayoutFailsClosedAndDepositObserverIgnoresIt() {
        EutxoSettlementDatum settlement = new EutxoSettlementDatum(
                1,
                "payments-eutxo",
                3,
                "55".repeat(32),
                OWNER,
                BigInteger.valueOf(20));
        WithdrawalConfirmationObserver withdrawalObserver =
                new WithdrawalConfirmationObserver("bridge-withdrawals", Map.of(
                        "chain-id", "payments-eutxo",
                        "bridge-epoch", "3",
                        "vault-address", VAULT_ADDRESS));
        Block mismatched = block(List.of(
                output(OWNER, 19, null),
                output(VAULT_ADDRESS, 30, settlement.encode())));

        assertThatThrownBy(() ->
                withdrawalObserver.observe(101, fill(32, 8), mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no exact payout");
        assertThat(observer().observe(101, fill(32, 8), mismatched)).isEmpty();
    }

    private static AcceptedVaultDepositObserver observer() {
        return new AcceptedVaultDepositObserver("bridge-deposits", Map.of(
                "chain-id", "payments-eutxo",
                "vault-address", VAULT_ADDRESS,
                "vault-script-hash", HexFormat.of().formatHex(VAULT_HASH),
                "max-lovelace", "1000000"));
    }

    private static EutxoVaultDatum datum() {
        return new EutxoVaultDatum(
                1,
                "payments-eutxo",
                OWNER,
                fill(32, 3),
                new EutxoOutpoint("44".repeat(32), 1),
                1_000);
    }

    private static TransactionOutput output(String address, long lovelace, byte[] datum) {
        var builder = TransactionOutput.builder()
                .address(address)
                .amounts(List.of(Amount.builder()
                        .unit("lovelace")
                        .quantity(BigInteger.valueOf(lovelace))
                        .build()));
        if (datum != null) {
            builder.inlineDatum(HexFormat.of().formatHex(datum));
        }
        return builder.build();
    }

    private static Block block(List<TransactionOutput> outputs) {
        return Block.builder()
                .transactionBodies(List.of(TransactionBody.builder()
                        .txHash("11".repeat(32))
                        .outputs(outputs)
                        .build()))
                .build();
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
