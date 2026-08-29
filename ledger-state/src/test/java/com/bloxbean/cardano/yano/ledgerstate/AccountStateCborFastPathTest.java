package com.bloxbean.cardano.yano.ledgerstate;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountStateCborFastPathTest {
    private static final String POOL_HASH = "ab".repeat(28);

    @Test
    void stakeAccountFastPathMatchesGenericDecoderAcrossIntegerWidths() {
        for (BigInteger value : representativeUnsignedValues()) {
            byte[] encoded = AccountStateCborCodec.encodeStakeAccount(value, value);

            assertThat(AccountStateCborCodec.decodeStakeAccount(encoded))
                    .isEqualTo(AccountStateCborCodec.decodeStakeAccountGeneric(encoded));
        }
    }

    @Test
    void epochDelegationFastPathsMatchGenericDecoderWithAndWithoutAmount() {
        for (BigInteger amount : representativeUnsignedValues()) {
            byte[] encoded = AccountStateCborCodec.encodeEpochDelegSnapshot(POOL_HASH, amount);

            assertThat(AccountStateCborCodec.decodeEpochDelegSnapshot(encoded))
                    .isEqualTo(AccountStateCborCodec.decodeEpochDelegSnapshotGeneric(encoded));
            assertThat(AccountStateCborCodec.decodeEpochDelegSnapshotPoolHash(encoded))
                    .containsExactly(AccountStateCborCodec.decodeEpochDelegSnapshotPoolHashGeneric(encoded));
        }
    }

    @Test
    void poolMajorStakeFastPathMatchesGenericDecoderAcrossIntegerWidths() {
        for (BigInteger value : representativeUnsignedValues()) {
            byte[] encoded = AccountStateCborCodec.encodePoolMajorStake(value);

            assertThat(AccountStateCborCodec.decodePoolMajorStake(encoded))
                    .isEqualTo(AccountStateCborCodec.decodePoolMajorStakeGeneric(encoded));
        }
    }

    @Test
    void stakeEventFastPathMatchesGenericDecoder() {
        for (int event : new int[]{
                AccountStateCborCodec.EVENT_REGISTRATION,
                AccountStateCborCodec.EVENT_DEREGISTRATION}) {
            byte[] encoded = AccountStateCborCodec.encodeStakeEvent(event);

            assertThat(AccountStateCborCodec.decodeStakeEvent(encoded))
                    .isEqualTo(AccountStateCborCodec.decodeStakeEventGeneric(encoded));
        }
    }

    @Test
    void validNonCanonicalAndReorderedMapsRemainBackwardCompatible() {
        byte[] reorderedStakeAccount = new byte[]{(byte) 0xa2, 0x01, 0x02, 0x00, 0x01};
        byte[] nonCanonicalStakeAccount = new byte[]{
                (byte) 0xa2, 0x00, 0x18, 0x01, 0x01, 0x18, 0x02};

        assertThat(AccountStateCborCodec.decodeStakeAccount(reorderedStakeAccount))
                .isEqualTo(AccountStateCborCodec.decodeStakeAccountGeneric(reorderedStakeAccount));
        assertThat(AccountStateCborCodec.decodeStakeAccount(nonCanonicalStakeAccount))
                .isEqualTo(AccountStateCborCodec.decodeStakeAccountGeneric(nonCanonicalStakeAccount));
    }

    @Test
    void malformedInputsStillFailClosed() {
        byte[] truncatedStakeAccount = new byte[]{(byte) 0xa2, 0x00, 0x01, 0x01};
        byte[] wrongStakeAccountShape = new byte[]{(byte) 0x82, 0x01, 0x02};
        byte[] truncatedSnapshot = new byte[]{(byte) 0xa1, 0x00, 0x58, 0x1c, 0x01};
        byte[] malformedPoolMajor = new byte[]{0x1b, 0x00};
        byte[] truncatedStakeEvent = new byte[]{(byte) 0xa1, 0x00};

        assertThatThrownBy(() -> AccountStateCborCodec.decodeStakeAccount(truncatedStakeAccount));
        assertThatThrownBy(() -> AccountStateCborCodec.decodeStakeAccount(wrongStakeAccountShape));
        assertThatThrownBy(() -> AccountStateCborCodec.decodeEpochDelegSnapshot(truncatedSnapshot));
        assertThatThrownBy(() -> AccountStateCborCodec.decodePoolMajorStake(malformedPoolMajor));
        assertThatThrownBy(() -> AccountStateCborCodec.decodeStakeEvent(truncatedStakeEvent));
    }

    @Test
    void randomizedCanonicalFixturesMatchGenericDecoder() {
        Random random = new Random(98);
        for (int i = 0; i < 2_000; i++) {
            BigInteger reward = new BigInteger(63, random);
            BigInteger deposit = new BigInteger(63, random);
            byte[] account = AccountStateCborCodec.encodeStakeAccount(reward, deposit);
            byte[] snapshot = AccountStateCborCodec.encodeEpochDelegSnapshot(POOL_HASH, reward);
            byte[] poolMajor = AccountStateCborCodec.encodePoolMajorStake(deposit);

            assertThat(AccountStateCborCodec.decodeStakeAccount(account))
                    .isEqualTo(AccountStateCborCodec.decodeStakeAccountGeneric(account));
            assertThat(AccountStateCborCodec.decodeEpochDelegSnapshot(snapshot))
                    .isEqualTo(AccountStateCborCodec.decodeEpochDelegSnapshotGeneric(snapshot));
            assertThat(AccountStateCborCodec.decodeEpochDelegSnapshotPoolHash(snapshot))
                    .containsExactly(AccountStateCborCodec.decodeEpochDelegSnapshotPoolHashGeneric(snapshot));
            assertThat(AccountStateCborCodec.decodePoolMajorStake(poolMajor))
                    .isEqualTo(AccountStateCborCodec.decodePoolMajorStakeGeneric(poolMajor));
        }
    }

    private static BigInteger[] representativeUnsignedValues() {
        return new BigInteger[]{
                BigInteger.ZERO,
                BigInteger.ONE,
                BigInteger.valueOf(23),
                BigInteger.valueOf(24),
                BigInteger.valueOf(255),
                BigInteger.valueOf(256),
                BigInteger.valueOf(65_535),
                BigInteger.valueOf(65_536),
                new BigInteger("4294967295"),
                new BigInteger("4294967296"),
                new BigInteger("45000000000000000"),
                new BigInteger("18446744073709551615")
        };
    }
}
