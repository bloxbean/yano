package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A registered stake pool (Haskell {@code StakePoolState} plus its {@code psFutureStakePoolParams}
 * and {@code psRetiring} entries).
 *
 * <p>A re-registration within an epoch does not change the active parameters. Haskell stores the new
 * parameters as <em>future</em> parameters, adopted at the next epoch boundary, cancels any pending
 * retirement and keeps the deposit ({@code Shelley/Rules/Pool.hs:277-305}).</p>
 *
 * @param id            the pool id
 * @param deposit       the deposit paid at first registration
 * @param vrfKeyHashHex the VRF key hash of the active parameters, lowercase hex
 * @param retiringEpoch the epoch a pending retirement takes effect, or {@code null}
 * @param params        the active registration parameters, or {@code null} if the backing store
 *                      does not keep them
 * @param futureParams  parameters from a re-registration in the current epoch, or {@code null}
 */
public record PoolState(PoolId id, BigInteger deposit, String vrfKeyHashHex, Long retiringEpoch,
                        PoolRegistration params, PoolRegistration futureParams) {

    public PoolState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(deposit, "deposit");
        vrfKeyHashHex = HexStrings.normalize(vrfKeyHashHex, "vrf key hash", HexStrings.HASH32);
    }

    public PoolState withRetiringEpoch(Long epoch) {
        return new PoolState(id, deposit, vrfKeyHashHex, epoch, params, futureParams);
    }

    public PoolState withFutureParams(PoolRegistration future) {
        return new PoolState(id, deposit, vrfKeyHashHex, retiringEpoch, params, future);
    }
}
