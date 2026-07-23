package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoContractCodecTest {

    @Test
    void recordReceiptAndQueryListsRoundTripCanonically() {
        EutxoRecord record = new EutxoRecord(
                new EutxoOutpoint("01".repeat(32), 7),
                "addr_test1vr0sample",
                HexFormat.of().parseHex("820102"),
                EutxoRecord.Origin.TRANSACTION);
        EutxoReceipt receipt = new EutxoReceipt(
                EutxoReceipt.Status.ACCEPTED,
                "02".repeat(32),
                HexFormat.of().parseHex("03".repeat(32)),
                9,
                2,
                123,
                "",
                "");

        assertThat(EutxoRecord.decode(record.encode())).isEqualTo(record);
        assertThat(EutxoReceipt.decode(receipt.encode())).isEqualTo(receipt);
        assertThat(EutxoQueryCodec.decodeRecords(
                EutxoQueryCodec.records(List.of(record)))).containsExactly(record);
        assertThat(EutxoQueryCodec.decodeOptionalRecord(
                EutxoQueryCodec.optionalRecord(null))).isNull();
        assertThat(EutxoQueryCodec.decodeOptionalReceipt(
                EutxoQueryCodec.optionalReceipt(null))).isNull();
    }

    @Test
    void stateKeysAndOutpointsAreCanonicalAndBounded() {
        EutxoOutpoint outpoint = EutxoOutpoint.parse("ab".repeat(32) + "#12");

        assertThat(outpoint.transactionId()).isEqualTo("ab".repeat(32));
        assertThat(outpoint.index()).isEqualTo(12);
        assertThat(EutxoStateKeys.utxo(outpoint))
                .asString()
                .isEqualTo("eutxo/v1/u/" + outpoint);
        assertThat(EutxoStateKeys.addressIndex("addr_test1x")).hasSizeLessThan(100);
        assertThatThrownBy(() -> EutxoOutpoint.parse("not-an-outpoint"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileDigestIsStableAndChangesOnlyWithProfileSemantics() {
        assertThat(EutxoProfile.V1.digestHex()).isEqualTo(
                "2499d01ee7cb0d09d0d498040c6351accd9da83df31666cd4463d0b1722d1212");
        assertThat(EutxoProfile.V2.digestHex()).isEqualTo(
                "8cd4adb72def2c31dc8551a02f67429ea468bb2024dbe85a1dc7300590c9d1bf");
    }

    @Test
    void bridgeContractsRoundTripAndReserveRejectsInflation() {
        EutxoDepositClaim claim = new EutxoDepositClaim(
                1,
                "payments",
                new EutxoOutpoint("11".repeat(32), 2),
                50,
                fill(32, 1),
                "addr_test1vault",
                "22".repeat(28),
                new byte[]{1},
                "addr_test1owner",
                new byte[]{2},
                fill(32, 3),
                new EutxoOutpoint("44".repeat(32), 0),
                100);
        assertThat(EutxoDepositClaim.decode(claim.encode())).isEqualTo(claim);
        EutxoDepositRecord record =
                new EutxoDepositRecord(claim, claim.mirroredOutpoint(), 7);
        assertThat(EutxoDepositRecord.decode(record.encode())).isEqualTo(record);

        EutxoReserve reserve = EutxoReserve.empty(EutxoReserve.LOVELACE)
                .credit(java.math.BigInteger.TEN);
        assertThat(EutxoReserve.decode(reserve.encode())).isEqualTo(reserve);
        assertThatThrownBy(() -> new EutxoReserve(
                EutxoReserve.LOVELACE,
                java.math.BigInteger.ONE,
                java.math.BigInteger.TEN,
                java.math.BigInteger.ZERO,
                java.math.BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withdrawalContractsRoundTripAndReserveReconcilesAtomically() {
        byte[] nonce = fill(32, 8);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "payments", 4, "addr_test1destination", nonce);
        assertThat(EutxoWithdrawalDatum.decode(datum.encode())).isEqualTo(datum);

        EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                1,
                "payments",
                4,
                new EutxoOutpoint("55".repeat(32), 1),
                "addr_test1destination",
                BigInteger.valueOf(30),
                nonce,
                9);
        assertThat(EutxoWithdrawalClaim.decode(claim.encode())).isEqualTo(claim);
        EutxoWithdrawalRecord pending = EutxoWithdrawalRecord.pending(claim, 9);
        assertThat(EutxoWithdrawalRecord.decode(pending.encode())).isEqualTo(pending);

        EutxoWithdrawalConfirmation confirmation =
                new EutxoWithdrawalConfirmation(
                        1,
                        "payments",
                        4,
                        claim.claimId(),
                        "66".repeat(32),
                        0,
                        claim.destinationAddress(),
                        claim.lovelace(),
                        new EutxoOutpoint("66".repeat(32), 1),
                        BigInteger.valueOf(70),
                        100,
                        fill(32, 9));
        assertThat(EutxoWithdrawalConfirmation.decode(confirmation.encode()))
                .isEqualTo(confirmation);
        EutxoWithdrawalRecord confirmed = pending.confirm(
                confirmation.settlementTransactionId(),
                confirmation.l1Slot(),
                confirmation.l1BlockHash(),
                10);
        assertThat(EutxoWithdrawalRecord.decode(confirmed.encode()))
                .isEqualTo(confirmed);

        EutxoSettlementDatum settlement = new EutxoSettlementDatum(
                1,
                claim.chainId(),
                claim.bridgeEpoch(),
                claim.claimId(),
                claim.destinationAddress(),
                claim.lovelace());
        assertThat(EutxoSettlementDatum.decode(settlement.encode()))
                .isEqualTo(settlement);

        EutxoReserve requested = EutxoReserve.empty(EutxoReserve.LOVELACE)
                .credit(BigInteger.valueOf(100))
                .requestWithdrawal(BigInteger.valueOf(30));
        assertThat(requested.spendableMirrored()).isEqualTo(BigInteger.valueOf(70));
        assertThat(requested.pendingWithdrawals()).isEqualTo(BigInteger.valueOf(30));
        EutxoReserve reconciled = requested.confirmWithdrawal(BigInteger.valueOf(30));
        assertThat(reconciled.stableVault()).isEqualTo(BigInteger.valueOf(70));
        assertThat(reconciled.confirmedWithdrawals()).isEqualTo(BigInteger.valueOf(30));
        reconciled.requireInvariant();
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
