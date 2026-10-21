package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.ada;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.balanced;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.run;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.spec;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.FRESH_VRF;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.MIN_POOL_COST;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.NETWORK;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.POOL_77_VRF;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.POOL_BB_VRF;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.poolRegistration;

/**
 * Shelley {@code POOL} as Conway runs it (Shelley/Rules/Pool.hs:209-323). World: {@code dev-77}'s pool (VRF
 * {@code 71…}) and {@code dev-bb}'s pool (VRF {@code b1…}) registered; epoch 0, {@code eMax} 18, {@code minPoolCost}
 * 340 ADA, pool deposit 500 ADA. A re-registration pays no deposit.
 */
class PoolRuleTest {

    private static PoolRegistration reregister77(byte[] vrf, BigInteger cost, String metadataHash) {
        return poolRegistration(TestKey.DEV_77, vrf, cost, NETWORK, metadataHash);
    }

    private static PoolRegistration newPool(TestKey operator, byte[] vrf) {
        return poolRegistration(operator, vrf, MIN_POOL_COST, NETWORK, null);
    }

    @Test
    @Covers("POOL.StakePoolNotRegisteredOnKeyPOOL")
    void onlyARegisteredPoolRetires() {
        assertThat(run(spec(new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()), 1))))
                .containsExactly("POOL.StakePoolNotRegisteredOnKeyPOOL");
        assertThat(run(spec(new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_77.keyHash()), 1), TestKey.DEV_77)))
                .containsExactly("Valid");
    }

    @Test
    @Covers("POOL.StakePoolRetirementWrongEpochPOOL")
    void theRetirementEpochIsAfterTheCurrentEpochAndWithinEMax() {
        byte[] pool = HexUtil.decodeHexString(TestKey.DEV_77.keyHash());
        // cEpoch < e <= cEpoch + eMax, with cEpoch = 0 and eMax = 18
        assertThat(run(spec(new PoolRetirement(pool, 0), TestKey.DEV_77)))
                .containsExactly("POOL.StakePoolRetirementWrongEpochPOOL");
        assertThat(run(spec(new PoolRetirement(pool, 18), TestKey.DEV_77))).containsExactly("Valid");
        assertThat(run(spec(new PoolRetirement(pool, 19), TestKey.DEV_77)))
                .containsExactly("POOL.StakePoolRetirementWrongEpochPOOL");
        // Both failures, in execution order.
        assertThat(run(spec(new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()), 19))))
                .containsExactly("POOL.StakePoolNotRegisteredOnKeyPOOL", "POOL.StakePoolRetirementWrongEpochPOOL");
    }

    @Test
    @Covers("POOL.StakePoolCostTooLowPOOL")
    void theCostIsAtLeastMinPoolCost() {
        assertThat(run(spec(reregister77(POOL_77_VRF, MIN_POOL_COST.subtract(BigInteger.ONE), null), TestKey.DEV_77)))
                .containsExactly("POOL.StakePoolCostTooLowPOOL");
        assertThat(run(spec(reregister77(POOL_77_VRF, MIN_POOL_COST, null), TestKey.DEV_77))).containsExactly("Valid");
        assertThat(run(spec(reregister77(POOL_77_VRF, BigInteger.ZERO, null), TestKey.DEV_77)))
                .containsExactly("POOL.StakePoolCostTooLowPOOL");
    }

    @Test
    @Covers("POOL.WrongNetworkPOOL")
    void theRewardAccountIsOnTheLedgersNetwork() {
        var mainnet = spec(poolRegistration(TestKey.DEV_77, POOL_77_VRF, MIN_POOL_COST, Networks.mainnet(), null),
                TestKey.DEV_77);
        assertThat(run(mainnet)).containsExactly("POOL.WrongNetworkPOOL");
    }

    @Test
    @Covers("POOL.PoolMedataHashTooBig")
    void theMetadataHashIsAtMost32Bytes() {
        assertThat(run(spec(reregister77(POOL_77_VRF, MIN_POOL_COST, "ab".repeat(33)), TestKey.DEV_77)))
                .containsExactly("POOL.PoolMedataHashTooBig");
        assertThat(run(spec(reregister77(POOL_77_VRF, MIN_POOL_COST, "ab".repeat(32)), TestKey.DEV_77)))
                .containsExactly("Valid");
        assertThat(run(spec(reregister77(POOL_77_VRF, MIN_POOL_COST, "ab".repeat(20)), TestKey.DEV_77)))
                .containsExactly("Valid");
    }

    @Test
    void registrationFailuresKeepHaskellsOrder() {
        // WrongNetworkPOOL, PoolMedataHashTooBig, StakePoolCostTooLowPOOL, then (PV 11) VRFKeyHashAlreadyRegistered
        var all = spec(poolRegistration(TestKey.DEV_77, POOL_BB_VRF, BigInteger.ONE, Networks.mainnet(),
                "cd".repeat(40)), TestKey.DEV_77);
        assertThat(run(all, 10)).containsExactly("POOL.WrongNetworkPOOL", "POOL.PoolMedataHashTooBig",
                "POOL.StakePoolCostTooLowPOOL");
        assertThat(run(all, 11)).containsExactly("POOL.WrongNetworkPOOL", "POOL.PoolMedataHashTooBig",
                "POOL.StakePoolCostTooLowPOOL", "POOL.VRFKeyHashAlreadyRegistered");
    }

    @Test
    @Covers("POOL.VRFKeyHashAlreadyRegistered")
    void fromProtocolVersion11AVrfKeyHashBelongsToOnePool() {
        // A new pool with dev-bb's VRF key hash: allowed before 11 (hardforkConwayDisallowDuplicatedVRFKeys).
        var taken = balanced(spec(newPool(TestKey.DEV_42, POOL_BB_VRF)), ada(-500));
        assertThat(run(taken, 10)).containsExactly("Valid");
        assertThat(run(taken, 11)).containsExactly("POOL.VRFKeyHashAlreadyRegistered");
        assertThat(run(balanced(spec(newPool(TestKey.DEV_42, FRESH_VRF)), ada(-500)), 11)).containsExactly("Valid");

        // A re-registration may keep its own active VRF key hash, or take a fresh one, but not another pool's.
        assertThat(run(spec(reregister77(POOL_77_VRF, MIN_POOL_COST, null), TestKey.DEV_77), 11))
                .containsExactly("Valid");
        assertThat(run(spec(reregister77(FRESH_VRF, MIN_POOL_COST, null), TestKey.DEV_77), 11))
                .containsExactly("Valid");
        assertThat(run(spec(reregister77(POOL_BB_VRF, MIN_POOL_COST, null), TestKey.DEV_77), 11))
                .containsExactly("POOL.VRFKeyHashAlreadyRegistered");
    }

    @Test
    void vrfKeyHashesTakenEarlierInTheSameTransaction() {
        // Two new pools with the same fresh VRF key hash: the second finds it in psVRFKeyHashes.
        List<Certificate> twoPools = List.of(newPool(TestKey.DEV_42, FRESH_VRF), newPool(TestKey.DEV_AA, FRESH_VRF));
        var twice = balanced(spec(twoPools, TestKey.DEV_AA), ada(-1000));
        assertThat(run(twice, 10)).containsExactly("Valid");
        assertThat(run(twice, 11)).containsExactly("POOL.VRFKeyHashAlreadyRegistered");

        // A pool re-registering twice in one epoch with the same new VRF key hash: the first re-registration recorded
        // it and it is not the active one, so Haskell's check (sppVrf == spsVrf || notMember sppVrf psVRFKeyHashes,
        // Pool.hs:279-282) fails the second.
        List<Certificate> reregisterTwice = List.of(reregister77(FRESH_VRF, MIN_POOL_COST, null),
                reregister77(FRESH_VRF, MIN_POOL_COST.add(BigInteger.ONE), null));
        assertThat(run(spec(reregisterTwice, TestKey.DEV_77), 11)).containsExactly("POOL.VRFKeyHashAlreadyRegistered");
        // Going back to the active VRF key hash is allowed.
        List<Certificate> andBack = List.of(reregister77(FRESH_VRF, MIN_POOL_COST, null),
                reregister77(POOL_77_VRF, MIN_POOL_COST.add(BigInteger.ONE), null));
        assertThat(run(spec(andBack, TestKey.DEV_77), 11)).containsExactly("Valid");
        // The VRF key hash a re-registration left is free again for a new pool in the same transaction.
        List<Certificate> handOver = List.of(reregister77(FRESH_VRF, MIN_POOL_COST, null),
                reregister77(POOL_77_VRF.clone(), MIN_POOL_COST.add(BigInteger.ONE), null),
                newPool(TestKey.DEV_42, FRESH_VRF));
        assertThat(run(balanced(spec(handOver, TestKey.DEV_77), ada(-500)), 11)).containsExactly("Valid");
    }

    @Test
    void aNewPoolPaysTheDepositAndCanRetireInTheSameTransaction() {
        List<Certificate> registerAndRetire = List.of(newPool(TestKey.DEV_42, FRESH_VRF),
                new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()), 5));
        assertThat(run(balanced(spec(registerAndRetire), MutationWorld.POOL_DEPOSIT.negate()))).containsExactly("Valid");
        List<Certificate> retireFirst = List.of(new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()), 5),
                newPool(TestKey.DEV_42, FRESH_VRF));
        assertThat(run(balanced(spec(retireFirst), MutationWorld.POOL_DEPOSIT.negate())))
                .containsExactly("POOL.StakePoolNotRegisteredOnKeyPOOL");
    }
}
