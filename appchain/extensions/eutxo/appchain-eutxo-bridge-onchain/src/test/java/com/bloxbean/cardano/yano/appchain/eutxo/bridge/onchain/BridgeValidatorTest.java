package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeValidatorTest {
    @Test
    void stagingDatumShapeIsStrictlyBounded() {
        DepositStagingValidator.StagingDatum valid =
                new DepositStagingValidator.StagingDatum(
                        BigInteger.ONE,
                        new byte[]{1},
                        new byte[]{2},
                        new byte[32],
                        new byte[32],
                        BigInteger.ZERO,
                        new byte[28],
                        BigInteger.TEN);
        assertThat(DepositStagingValidator.shapeValid(valid)).isTrue();
        assertThat(DepositStagingValidator.shapeValid(
                new DepositStagingValidator.StagingDatum(
                        BigInteger.TWO,
                        valid.chainId(),
                        valid.l2Owner(),
                        valid.nonce(),
                        valid.stagingTransactionId(),
                        valid.stagingIndex(),
                        valid.depositorKeyHash(),
                        valid.refundDeadline())))
                .isFalse();
    }

    @Test
    void vaultSettlementDatumBindsClaimAndAmount() {
        byte[] claimId = filled(7, 32);
        OutputDatum datum = new OutputDatum.OutputDatumInline(PlutusData.constr(2,
                PlutusData.integer(BigInteger.ONE),
                PlutusData.bytes(new byte[]{1}),
                PlutusData.integer(BigInteger.ZERO),
                PlutusData.bytes(claimId),
                PlutusData.bytes(new byte[]{2}),
                PlutusData.integer(BigInteger.TEN)));

        assertThat(VaultValidator.settlement(datum)).hasValueSatisfying(settlement -> {
            assertThat(settlement.claimId()).isEqualTo(claimId);
            assertThat(settlement.lovelace()).isEqualTo(BigInteger.TEN);
        });
        assertThat(VaultValidator.settlement(new OutputDatum.NoOutputDatum())).isEmpty();
    }

    @Test
    void acceptedVaultDatumMustPreserveTheStagingIntentExactly() {
        DepositStagingValidator.StagingDatum staging =
                new DepositStagingValidator.StagingDatum(
                        BigInteger.ONE,
                        new byte[]{1},
                        new byte[]{2},
                        filled(3, 32),
                        filled(4, 32),
                        BigInteger.valueOf(5),
                        filled(6, 28),
                        BigInteger.TEN);
        OutputDatum exact = new OutputDatum.OutputDatumInline(vaultDatum(staging, staging.l2Owner()));
        OutputDatum redirected = new OutputDatum.OutputDatumInline(vaultDatum(staging, new byte[]{9}));

        assertThat(DepositStagingValidator.acceptedDatumMatches(exact, staging)).isTrue();
        assertThat(DepositStagingValidator.acceptedDatumMatches(redirected, staging)).isFalse();
        assertThat(DepositStagingValidator.acceptedDatumMatches(new OutputDatum.NoOutputDatum(), staging))
                .isFalse();
    }

    private static PlutusData vaultDatum(
            DepositStagingValidator.StagingDatum staging,
            byte[] owner
    ) {
        return PlutusData.constr(0,
                PlutusData.integer(staging.version()),
                PlutusData.bytes(staging.chainId()),
                PlutusData.bytes(owner),
                PlutusData.bytes(staging.nonce()),
                PlutusData.bytes(staging.stagingTransactionId()),
                PlutusData.integer(staging.stagingIndex()),
                PlutusData.integer(staging.refundDeadline()));
    }

    private static byte[] filled(int value, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
