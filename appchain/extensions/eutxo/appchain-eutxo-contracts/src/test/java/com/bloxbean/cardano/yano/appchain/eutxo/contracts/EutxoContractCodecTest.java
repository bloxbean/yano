package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoContractCodecTest {
    private static final String DESTINATION =
            "addr_test1wzn5ee2qaqvly3hx7e0nk3vhm240n5muq3plhjcnvx9ppjgf62u6a";

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
        assertThat(EutxoQueryCodec.decodeCount(
                EutxoQueryCodec.count(17))).isEqualTo(17);
        assertThat(EutxoQueryCodec.decodeBridgeHalt(
                EutxoQueryCodec.bridgeHalt(
                        "DEEP_ROLLBACK_BELOW_CREDITED_DEPOSIT")))
                .isEqualTo("DEEP_ROLLBACK_BELOW_CREDITED_DEPOSIT");
        assertThatThrownBy(() -> EutxoQueryCodec.bridgeHalt(
                "not canonical"))
                .isInstanceOf(IllegalArgumentException.class);
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
        assertThat(EutxoStateKeys.totalWithdrawalCount(7))
                .isNotEqualTo(EutxoStateKeys.totalWithdrawalCount(8));
        assertThat(EutxoStateKeys.depositIndex(12))
                .asString()
                .endsWith("/00000000000000000012");
        assertThat(EutxoStateKeys.withdrawalIndex(7, 12))
                .asString()
                .isEqualTo("eutxo/v1/bridge/7/withdrawal/index/"
                        + "00000000000000000012");
        assertThatThrownBy(() -> EutxoOutpoint.parse("not-an-outpoint"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileDigestIsStableAndChangesOnlyWithProfileSemantics() {
        assertThat(EutxoProfile.V1.digestHex()).isEqualTo(
                "2499d01ee7cb0d09d0d498040c6351accd9da83df31666cd4463d0b1722d1212");
        assertThat(EutxoProfile.V2.digestHex()).isEqualTo(
                "8cd4adb72def2c31dc8551a02f67429ea468bb2024dbe85a1dc7300590c9d1bf");
        // ADR-UTXO-009: v3 digest also freezes the tier-1 settlement bounds.
        assertThat(EutxoProfile.V3.digestHex()).isEqualTo(
                "71d7d7445118ef5c4761c847836075f3e5011a1336c5e8f2bfb3517ad8598f47");
    }

    @Test
    void withdrawalClaimV2CarriesTheBountyAndKeepsV1BytesAndIdsFrozen() {
        EutxoOutpoint outpoint = new EutxoOutpoint("ab".repeat(32), 1);
        byte[] nonce = new byte[32];
        java.util.Arrays.fill(nonce, (byte) 5);
        EutxoWithdrawalClaim v1 = new EutxoWithdrawalClaim(
                1, "chain", 7, outpoint, "addr_test1_destination",
                java.math.BigInteger.valueOf(25), nonce, 3, 9);
        EutxoWithdrawalClaim v1RoundTrip =
                EutxoWithdrawalClaim.decode(v1.encode());
        assertThat(v1RoundTrip).isEqualTo(v1);
        assertThat(v1RoundTrip.bounty()).isZero();
        // Frozen v1 identity (pre-009 vector): bounty never enters v1 ids.
        assertThat(v1.claimId()).isEqualTo(new EutxoWithdrawalClaim(
                1, "chain", 7, outpoint, "addr_test1_destination",
                java.math.BigInteger.valueOf(25), nonce, 3, 999).claimId());

        EutxoWithdrawalClaim v2 = new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2, "chain", 7, outpoint,
                "addr_test1_destination", java.math.BigInteger.valueOf(8_000_000),
                nonce, 3, 9, java.math.BigInteger.valueOf(2_000_000));
        EutxoWithdrawalClaim v2RoundTrip =
                EutxoWithdrawalClaim.decode(v2.encode());
        assertThat(v2RoundTrip).isEqualTo(v2);
        assertThat(v2.totalLovelace())
                .isEqualTo(java.math.BigInteger.valueOf(10_000_000));
        // The bounty is part of the v2 identity.
        EutxoWithdrawalClaim differentBounty = new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2, "chain", 7, outpoint,
                "addr_test1_destination", java.math.BigInteger.valueOf(8_000_000),
                nonce, 3, 9, java.math.BigInteger.valueOf(2_000_001));
        assertThat(v2.claimId()).isNotEqualTo(differentBounty.claimId());

        assertThatThrownBy(() -> new EutxoWithdrawalClaim(
                1, "chain", 7, outpoint, "addr_test1_destination",
                java.math.BigInteger.valueOf(25), nonce, 3, 9,
                java.math.BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v1 withdrawal claims cannot carry");
    }

    @Test
    void batchSettlementMarkerAndBatchPayloadRoundTrip() {
        java.util.List<String> ids = java.util.List.of(
                "aa".repeat(32), "bb".repeat(32), "cc".repeat(32));
        EutxoBatchSettlementMarker marker =
                new EutxoBatchSettlementMarker(1, ids);
        EutxoBatchSettlementMarker back =
                EutxoBatchSettlementMarker.decode(marker.encode());
        assertThat(back).isEqualTo(marker);
        assertThat(back.count()).isEqualTo(3);
        assertThat(back.claimIds()).containsExactlyElementsOf(ids);
        assertThatThrownBy(() -> new EutxoBatchSettlementMarker(
                1, java.util.List.of("short")))
                .isInstanceOf(IllegalArgumentException.class);

        EutxoSettlementBatch batch = new EutxoSettlementBatch(
                1, "payments", 7, 3, 8, 16);
        EutxoSettlementBatch decoded =
                EutxoSettlementBatch.decode(batch.encode());
        assertThat(decoded).isEqualTo(batch);
        assertThat(decoded.claimCount()).isEqualTo(8);
        assertThatThrownBy(() -> new EutxoSettlementBatch(1, "c", 0, 0, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchWithdrawalConfirmationRoundTripsAndExpandsPerClaim() {
        EutxoBatchWithdrawalConfirmation confirmation =
                new EutxoBatchWithdrawalConfirmation(
                        1, "payments", 7,
                        "ab".repeat(32),
                        // Deliberately unsorted: canonical sorting is applied.
                        List.of(new EutxoOutpoint("99".repeat(32), 0),
                                new EutxoOutpoint("55".repeat(32), 2)),
                        new EutxoOutpoint("cd".repeat(32), 3),
                        BigInteger.valueOf(18_000_000L),
                        123_456L,
                        HexFormat.of().parseHex("ef".repeat(32)),
                        List.of(
                                new EutxoBatchWithdrawalConfirmation.Entry(
                                        "11".repeat(32), 0, DESTINATION,
                                        BigInteger.valueOf(8_000_000L)),
                                new EutxoBatchWithdrawalConfirmation.Entry(
                                        "22".repeat(32), 1, DESTINATION,
                                        BigInteger.valueOf(5_000_000L))));

        EutxoBatchWithdrawalConfirmation back =
                EutxoBatchWithdrawalConfirmation.decode(confirmation.encode());
        assertThat(back).isEqualTo(confirmation);
        assertThat(back.count()).isEqualTo(2);
        // Spent outpoints are canonically sorted regardless of input order.
        assertThat(back.spentOutpoints()).containsExactly(
                new EutxoOutpoint("55".repeat(32), 2),
                new EutxoOutpoint("99".repeat(32), 0));

        // Each entry expands into a self-consistent single-claim confirmation
        // sharing the settlement's L1 identity.
        List<EutxoWithdrawalConfirmation> expanded = back.confirmations();
        assertThat(expanded).hasSize(2);
        assertThat(expanded.get(0).claimId()).isEqualTo("11".repeat(32));
        assertThat(expanded.get(0).payoutIndex()).isZero();
        assertThat(expanded.get(0).lovelace())
                .isEqualTo(BigInteger.valueOf(8_000_000L));
        assertThat(expanded.get(1).claimId()).isEqualTo("22".repeat(32));
        assertThat(expanded.get(1).payoutIndex()).isEqualTo(1);
        assertThat(expanded.get(0).settlementTransactionId())
                .isEqualTo("ab".repeat(32));
        assertThat(expanded.get(0).continuingVaultLovelace())
                .isEqualTo(BigInteger.valueOf(18_000_000L));

        // Non-positional entries are rejected: the claim must mirror the
        // transaction's dense positional payouts.
        assertThatThrownBy(() -> new EutxoBatchWithdrawalConfirmation(
                1, "payments", 7, "ab".repeat(32),
                List.of(new EutxoOutpoint("99".repeat(32), 0)),
                new EutxoOutpoint("cd".repeat(32), 3),
                BigInteger.ZERO, 1L, HexFormat.of().parseHex("ef".repeat(32)),
                List.of(new EutxoBatchWithdrawalConfirmation.Entry(
                        "11".repeat(32), 5, DESTINATION, BigInteger.ONE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positional");

        // No spent inputs = no authenticity anchor: rejected.
        assertThatThrownBy(() -> new EutxoBatchWithdrawalConfirmation(
                1, "payments", 7, "ab".repeat(32),
                List.of(),
                new EutxoOutpoint("cd".repeat(32), 3),
                BigInteger.ZERO, 1L, HexFormat.of().parseHex("ef".repeat(32)),
                List.of(new EutxoBatchWithdrawalConfirmation.Entry(
                        "11".repeat(32), 0, DESTINATION, BigInteger.ONE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spent outpoints");
    }

    @Test
    void bridgeParamsAndGovernanceCommandsRoundTripWithinFrozenBounds() {
        EutxoBridgeParams defaults = EutxoBridgeParams.defaults();
        assertThat(EutxoBridgeParams.decode(defaults.encode())).isEqualTo(defaults);
        assertThat(defaults.resolveBounty(
                java.math.BigInteger.valueOf(10_000_000)))
                .isEqualTo(java.math.BigInteger.valueOf(2_000_000));
        EutxoBridgeParams withBps = new EutxoBridgeParams(
                1, 1_000_000L, 50, 2_000_000L, 8, 100L, 3_600L, 86_400L, 0L);
        // 1 ADA flat + 50bps of 10 ADA = 1.05 ADA.
        assertThat(withBps.resolveBounty(
                java.math.BigInteger.valueOf(10_000_000)))
                .isEqualTo(java.math.BigInteger.valueOf(1_050_000));

        assertThatThrownBy(() -> new EutxoBridgeParams(
                1, EutxoProfile.V3_BOUNTY_CAP_FLAT_LOVELACE + 1, 0,
                2_000_000L, 8, 100L, 3_600L, 86_400L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EutxoBridgeParams(
                1, 0L, EutxoProfile.V3_BOUNTY_CAP_BASIS_POINTS + 1,
                2_000_000L, 8, 100L, 3_600L, 86_400L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EutxoBridgeParams(
                1, 0L, 0, 2_000_000L, 8, 100L, 3_600L,
                EutxoProfile.V3_FALLBACK_DELAY_MIN_SLOTS - 1, 0L))
                .isInstanceOf(IllegalArgumentException.class);

        EutxoBridgeParamsGovernanceV1.Command command =
                new EutxoBridgeParamsGovernanceV1.Command(1, defaults, 5);
        EutxoBridgeParamsGovernanceV1.Command decoded =
                EutxoBridgeParamsGovernanceV1.decode(command.encode());
        assertThat(decoded).isEqualTo(command);
        assertThat(decoded.digestHex()).isEqualTo(command.digestHex());
        assertThatThrownBy(() -> new EutxoBridgeParamsGovernanceV1.Command(
                1, defaults.withEffectiveHeight(4), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EutxoBridgeParamsGovernanceV1.decode(new byte[600]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new String(EutxoStateKeys.bridgeParamsCurrent(),
                java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("eutxo/v1/bridge/params/current");
        assertThat(new String(EutxoStateKeys.bridgeParamsHistory(0),
                java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("eutxo/v1/bridge/params/history/00000000000000000000");
    }

    @Test
    void l2ParameterSnapshotRoundTripsAndBindsEveryIdentity() {
        EutxoL2ParameterSnapshot snapshot =
                new EutxoL2ParameterSnapshot(
                        "payments",
                        EutxoProfile.V1.digestHex(),
                        "aa".repeat(32),
                        "zeroj-jubjub-dev-v1",
                        "bb".repeat(32),
                        EutxoProfile.V1.maxTransactionBytes(),
                        EutxoProfile.V1.maxInputs(),
                        EutxoProfile.V1.maxOutputs(),
                        "");

        assertThat(EutxoL2ParameterSnapshot.decode(snapshot.encode()))
                .isEqualTo(snapshot);
        assertThat(EutxoQueryCodec.decodeL2Parameters(
                EutxoQueryCodec.l2Parameters(snapshot)))
                .isEqualTo(snapshot);
        assertThat(snapshot.digest()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> new EutxoL2ParameterSnapshot(
                snapshot.chainId(),
                snapshot.ledgerProfileDigest(),
                snapshot.validityProfileDigest(),
                snapshot.authorizationProfile(),
                snapshot.authorizationProfileDigest(),
                snapshot.maxTransactionBytes(),
                snapshot.maxInputs(),
                snapshot.maxOutputs(),
                "00".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest mismatch");
    }

    @Test
    void validityDomainRoundTripsAsBodyBoundCardanoMetadata() throws Exception {
        EutxoTransactionDomain domain = new EutxoTransactionDomain(
                "payments-zk",
                "preview",
                EutxoProfile.V1.digestHex(),
                "aa".repeat(32),
                fill(32, 7),
                900);
        Transaction transaction = Transaction.builder()
                .body(TransactionBody.builder().ttl(900).build())
                .build();

        domain.attach(transaction);
        Transaction decoded = Transaction.deserialize(
                transaction.serialize());

        assertThat(EutxoTransactionDomain.from(decoded))
                .isEqualTo(domain);
        assertThat(EutxoTransactionDomain.from(decoded).commitment())
                .isEqualTo(domain.commitment());
        assertThat(decoded.getBody().getAuxiliaryDataHash())
                .isEqualTo(decoded.getAuxiliaryData()
                        .getAuxiliaryDataHash());

        decoded.getBody().getAuxiliaryDataHash()[0] ^= 1;
        assertThatThrownBy(() -> EutxoTransactionDomain.from(decoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash");
        assertThatThrownBy(() -> domain.attach(transaction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty auxiliary-data");
    }

    @Test
    void bridgeContractsRoundTripAndReserveRejectsInflation() {
        EutxoStagingDatum staging = new EutxoStagingDatum(
                EutxoStagingDatum.ABI_VERSION,
                "payments",
                "addr_test1owner",
                fill(32, 9),
                fill(28, 8),
                100);
        assertThat(EutxoStagingDatum.decode(staging.encode()))
                .isEqualTo(staging);

        EutxoDepositClaim claim = new EutxoDepositClaim(
                EutxoDepositClaim.ABI_VERSION,
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
        assertThat(EutxoQueryCodec.decodeDepositRecords(
                EutxoQueryCodec.depositRecords(List.of(record))))
                .containsExactly(record);

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
    void finalizedTransactionSummariesRoundTripWithBoundedPages() {
        EutxoTransactionSummary summary =
                new EutxoTransactionSummary(
                        "11".repeat(32),
                        "22".repeat(32),
                        1,
                        7,
                        1,
                        99,
                        EutxoTransactionSummary.Status.ACCEPTED,
                        "cardano-vkey",
                        List.of(new EutxoTransactionSummary.Entry(
                                new EutxoOutpoint("33".repeat(32), 0),
                                DESTINATION,
                                BigInteger.valueOf(10))),
                        List.of(new EutxoTransactionSummary.Entry(
                                new EutxoOutpoint("11".repeat(32), 0),
                                DESTINATION,
                                BigInteger.valueOf(10))),
                        "");

        assertThat(EutxoTransactionSummary.decode(summary.encode()))
                .isEqualTo(summary);
        assertThat(EutxoTransactionSummary.decodeList(
                EutxoTransactionSummary.encodeList(List.of(summary))))
                .containsExactly(summary);
    }

    @Test
    void withdrawalContractsRoundTripAndReserveReconcilesAtomically() {
        byte[] nonce = fill(32, 8);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "payments", 4, DESTINATION, nonce);
        assertThat(EutxoWithdrawalDatum.decode(datum.encode())).isEqualTo(datum);

        EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                1,
                "payments",
                4,
                new EutxoOutpoint("55".repeat(32), 1),
                DESTINATION,
                BigInteger.valueOf(30),
                nonce,
                2,
                9);
        assertThat(EutxoWithdrawalClaim.decode(claim.encode())).isEqualTo(claim);
        EutxoWithdrawalRecord pending = EutxoWithdrawalRecord.pending(claim, 9);
        assertThat(EutxoWithdrawalRecord.decode(pending.encode())).isEqualTo(pending);
        assertThat(EutxoQueryCodec.decodeWithdrawalRecords(
                EutxoQueryCodec.withdrawalRecords(List.of(pending))))
                .containsExactly(pending);

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

        EutxoSettlementDatum settlement =
                EutxoSettlementDatum.forAddress(
                1,
                claim.chainId(),
                claim.bridgeEpoch(),
                claim.claimId(),
                claim.destinationAddress(),
                claim.lovelace());
        assertThat(EutxoSettlementDatum.decode(settlement.encode()))
                .isEqualTo(settlement);
        assertThatThrownBy(() -> new EutxoSettlementDatum(
                1,
                claim.chainId(),
                claim.bridgeEpoch(),
                claim.claimId(),
                com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData
                        .of(99)
                        .serializeToBytes(),
                claim.lovelace()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination");

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

    @Test
    void proofWithdrawalBindsCanonicalPlutusClaimRootAndSequence() {
        String destination =
                "addr_test1wzn5ee2qaqvly3hx7e0nk3vhm240n5muq3plhjcnvx9ppjgf62u6a";
        EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                1,
                "payments",
                4,
                new EutxoOutpoint("77".repeat(32), 0),
                destination,
                BigInteger.valueOf(30),
                fill(32, 8),
                6,
                9);
        EutxoWithdrawalCommitment commitment =
                EutxoWithdrawalCommitment.fromClaim(claim);
        byte[] key = EutxoStateKeys.withdrawalCommitment(claim.claimId());
        byte[] value = commitment.encode();
        byte[] path = EutxoMpfProof.nibbles(
                com.bloxbean.cardano.client.crypto.Blake2bUtil
                        .blake2bHash256(key));
        byte[] encodedSuffix = EutxoMpfProof.encodeLeafSuffix(path);
        byte[] root = EutxoMpfProof.commitLeaf(
                encodedSuffix,
                com.bloxbean.cardano.client.crypto.Blake2bUtil
                        .blake2bHash256(value));
        EutxoMpfProof proof = new EutxoMpfProof(
                root, key, value, encodedSuffix, List.of(), 9);
        EutxoProofWithdrawal withdrawal = new EutxoProofWithdrawal(
                1, commitment, proof);
        EutxoFederatedRoot accepted = new EutxoFederatedRoot(
                1,
                "payments",
                4,
                9,
                root,
                List.of(fill(32, 1), fill(32, 2), fill(32, 3)),
                2,
                0);
        EutxoNullifierState cursor =
                new EutxoNullifierState(1, "payments", 4, 6, 0);

        assertThat(proof.verify()).isTrue();
        assertThat(withdrawal.encode()).isNotEmpty();
        assertThat(EutxoFederatedRoot.decode(accepted.encode()))
                .isEqualTo(accepted);
        assertThat(EutxoNullifierState.decode(cursor.encode()))
                .isEqualTo(cursor);
        assertThat(accepted.accepts(withdrawal)).isTrue();
        assertThat(cursor.advance(6).nextSettlementSequence()).isEqualTo(7);
        assertThatThrownBy(() -> cursor.advance(7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
