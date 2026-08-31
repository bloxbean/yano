package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.account.RewardType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundaryCredentialKeyTest {
    @Test
    void fixedWidthIdentityRoundTripsAndUsesByteEquality() {
        String hash = "ab".repeat(28);
        var fromAddress = BoundaryCredentialKey.fromAddress("1:" + hash);
        byte[] accountKey = DefaultAccountStateStore.accountKey(1, hash);
        var fromStorageKey = BoundaryCredentialKey.fromKey(accountKey, 1);

        assertThat(fromAddress).isEqualTo(fromStorageKey);
        assertThat(fromAddress.hashCode()).isEqualTo(fromStorageKey.hashCode());
        assertThat(fromAddress.credentialType()).isEqualTo(1);
        assertThat(fromAddress.credentialHash()).isEqualTo(hash);
        assertThat(fromAddress.address()).isEqualTo("1:" + hash);
        assertThat(fromAddress).isNotEqualTo(BoundaryCredentialKey.of(0, hash));
    }

    @Test
    void byteBackedStorageKeysAreIdenticalToHexBuilders() {
        String hash = "cd".repeat(28);
        var credential = BoundaryCredentialKey.of(1, hash);

        assertThat(DefaultAccountStateStore.accountKey(credential))
                .containsExactly(DefaultAccountStateStore.accountKey(1, hash));
        assertThat(DefaultAccountStateStore.accumulatedRewardKey(credential))
                .containsExactly(DefaultAccountStateStore.accumulatedRewardKey(1, hash));
        assertThat(DefaultAccountStateStore.credentialStakeEventKey(
                credential, 123, 4, 5))
                .containsExactly(DefaultAccountStateStore.credentialStakeEventKey(
                        1, hash, 123, 4, 5));
    }

    @Test
    void rawPoolHashCodecsPreserveExistingCborBytes() {
        String poolHash = "ef".repeat(28);
        byte[] snapshot = AccountStateCborCodec.encodeEpochDelegSnapshot(
                poolHash, BigInteger.valueOf(123));

        assertThat(AccountStateCborCodec.decodeEpochDelegSnapshotPoolHash(snapshot))
                .containsExactly(HexUtil.decodeHexString(poolHash));

        var reward = new AccountStateCborCodec.AccumulatedReward(
                8, RewardType.MEMBER.ordinal(), BigInteger.valueOf(456), poolHash);
        assertThat(AccountStateCborCodec.encodeAccumulatedReward(
                8, RewardType.MEMBER.ordinal(), BigInteger.valueOf(456),
                HexUtil.decodeHexString(poolHash)))
                .containsExactly(AccountStateCborCodec.encodeAccumulatedReward(reward));
    }

    @Test
    void rejectsNonCardanoCredentialHashLengths() {
        assertThatThrownBy(() -> BoundaryCredentialKey.of(0, "aa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("28 bytes");
    }
}
