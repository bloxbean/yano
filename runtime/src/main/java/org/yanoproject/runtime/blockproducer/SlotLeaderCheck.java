package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.vrf.cardano.CardanoLeaderCheck;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Slot leader eligibility check for Ouroboros Praos.
 * Wraps VRF computation and threshold comparison.
 */
public class SlotLeaderCheck {

    /** Decimal digits of the ledger's {@code FixedPoint} ({@code Digits34}, BaseTypes.hs:265-272). */
    private static final int FIXED_POINT_DIGITS = 34;

    private final byte[] vrfSkey;
    private final BigDecimal activeSlotCoeff;
    private final BlockSigner blockSigner;

    /**
     * @param vrfSkey          64-byte VRF secret key
     * @param activeSlotCoeff  active slot coefficient (e.g. 0.05 for mainnet)
     * @param blockSigner      shared BlockSigner instance
     */
    public SlotLeaderCheck(byte[] vrfSkey, BigDecimal activeSlotCoeff, BlockSigner blockSigner) {
        this.vrfSkey = vrfSkey;
        this.activeSlotCoeff = activeSlotCoeff;
        this.blockSigner = blockSigner;
    }

    /**
     * The relative stake as the Haskell leader check uses it: the pool distribution's exact ratio
     * {@code poolStake %. totalActiveStake} (cardano-ledger-core State/SnapShots.hs:198) converted by
     * {@code fromRational} to {@code FixedPoint} in {@code checkLeaderNatValue} (cardano-protocol
     * TPraos/BlockHeader.hs:400), which floors to 34 decimal digits.
     *
     * @return the stake ratio, zero when either value is missing or the total is not positive
     */
    public static BigDecimal relativeStake(BigInteger poolStake, BigInteger totalStake) {
        if (poolStake == null || totalStake == null || totalStake.signum() <= 0 || poolStake.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(poolStake).divide(new BigDecimal(totalStake), FIXED_POINT_DIGITS, RoundingMode.FLOOR);
    }

    /**
     * Check whether we are the slot leader for the given slot.
     * If eligible, returns the VRF result (to be reused in block building).
     * If not eligible, returns null.
     *
     * @param slot       the slot to check
     * @param epochNonce the current epoch nonce (32 bytes)
     * @param sigma      the pool's relative stake (0..1)
     * @return VrfSignResult if eligible, null if not
     */
    public BlockSigner.VrfSignResult checkAndProve(long slot, byte[] epochNonce, BigDecimal sigma) {
        BlockSigner.VrfSignResult vrfResult = blockSigner.computeVrf(vrfSkey, slot, epochNonce);
        byte[] leaderValue = CardanoLeaderCheck.vrfLeaderValue(vrfResult.output());
        boolean isLeader = CardanoLeaderCheck.checkLeaderValue(leaderValue, sigma, activeSlotCoeff);
        return isLeader ? vrfResult : null;
    }
}
