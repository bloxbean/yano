package org.yanoproject.ledger.rules.view;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.view.model.CredentialKey;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/** Shared test data for view, overlay and effects tests. */
public final class Fixtures {

    public static final BigInteger KEY_DEPOSIT = BigInteger.valueOf(2_000_000);
    public static final BigInteger POOL_DEPOSIT = BigInteger.valueOf(500_000_000);
    public static final BigInteger DREP_DEPOSIT = BigInteger.valueOf(500_000_000);
    public static final BigInteger GOV_ACTION_DEPOSIT = BigInteger.valueOf(100_000_000_000L);
    public static final int GOV_ACTION_LIFETIME = 6;
    public static final int DREP_ACTIVITY = 20;
    public static final long EPOCH = 100;
    /** A testnet enterprise key-hash address. */
    public static final String ADDRESS = new Address(HexUtil.decodeHexString("60" + hash28(0x22))).toBech32();

    private Fixtures() {
    }

    /** @return a 28-byte hash made of one repeated byte, hex */
    public static String hash28(int b) {
        return String.format("%02x", b).repeat(28);
    }

    /** @return a 32-byte hash made of one repeated byte, hex */
    public static String hash32(int b) {
        return String.format("%02x", b).repeat(32);
    }

    public static CredentialKey keyCred(int b) {
        return CredentialKey.key(hash28(b));
    }

    /** @return a testnet key-hash reward address for {@code keyCred(b)}, hex */
    public static String rewardAddressHex(int b) {
        return "e0" + hash28(b);
    }

    public static ProtocolParams protocolParams() {
        return ProtocolParams.builder()
                .keyDeposit(KEY_DEPOSIT.toString())
                .poolDeposit(POOL_DEPOSIT.toString())
                .drepDeposit(DREP_DEPOSIT)
                .govActionDeposit(GOV_ACTION_DEPOSIT)
                .govActionLifetime(GOV_ACTION_LIFETIME)
                .drepActivity(DREP_ACTIVITY)
                .protocolMajorVer(10)
                .protocolMinorVer(0)
                .build();
    }

    public static ValidationEnv env() {
        return env(10);
    }

    public static ValidationEnv env(int protocolMajor) {
        return new ValidationEnv(EPOCH * 432_000 + 10, EPOCH, protocolMajor, 0, NetworkId.TESTNET,
                new SlotConfig(1000, 0, 0), new byte[32]);
    }

    public static TransactionOutput output(long lovelace) {
        return new TransactionOutput(ADDRESS, Value.builder().coin(BigInteger.valueOf(lovelace)).build());
    }

    public static PoolRegistration poolRegistration(int operator, int vrf) {
        return PoolRegistration.builder()
                .operator(HexUtil.decodeHexString(hash28(operator)))
                .vrfKeyHash(HexUtil.decodeHexString(hash32(vrf)))
                .pledge(BigInteger.ZERO)
                .cost(BigInteger.valueOf(340_000_000))
                .margin(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(100)))
                .rewardAccount(rewardAddressHex(operator))
                .poolOwners(Set.of(hash28(operator)))
                .relays(List.of())
                .build();
    }
}
