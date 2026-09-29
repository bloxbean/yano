package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Shelley {@code POOL} as Conway runs it ({@code poolTransition}, Shelley/Rules/Pool.hs:209-323).
 *
 * <ul>
 *   <li>{@code RegPool} (:225-306): {@code WrongNetworkPOOL} (the reward account's network,
 *       {@code hardforkAlonzoValidatePoolAccountAddressNetID}), {@code PoolMedataHashTooBig} (a metadata hash of more
 *       than 32 bytes, {@code restrictPoolMetadataHash}), {@code StakePoolCostTooLowPOOL}, then from protocol version
 *       11 ({@code hardforkConwayDisallowDuplicatedVRFKeys}) {@code VRFKeyHashAlreadyRegistered}: a new pool must use
 *       a VRF key hash no registered pool holds (active or future parameters, {@code psVRFKeyHashes}); a
 *       re-registration may keep its active VRF key hash, otherwise the same rule applies — including to a VRF key
 *       hash this pool's own earlier re-registration in the epoch recorded (Haskell's
 *       {@code sppVrf == spsVrf || Map.notMember sppVrf psVRFKeyHashes}, :279-282).</li>
 *   <li>{@code RetirePool} (:307-323): {@code StakePoolNotRegisteredOnKeyPOOL}, then
 *       {@code StakePoolRetirementWrongEpochPOOL} unless {@code cEpoch < e ≤ cEpoch + eMax}.</li>
 * </ul>
 *
 * <p>Pools are those of the running state: a pool registered earlier in the transaction is re-registered by a
 * second registration, and its VRF key hash is taken.</p>
 */
final class PoolRule {

    /** {@code hashSize ([] @HASH)}: blake2b-256. */
    static final int MAX_METADATA_HASH_SIZE = 32;

    private PoolRule() {
    }

    /** @return whether the certificate changes the state */
    static boolean apply(RuleFrame frame, RawCertificate cert, LedgerView state) {
        TransitionContext ctx = frame.context();
        PoolId id = PoolId.of(cert.poolId());
        Optional<PoolState> registered = state.pool(id).orElseThrowUnavailable();
        ConwayParams pp = CertState.params(ctx);
        if (cert.tag() == RawCertificate.POOL_REGISTRATION) {
            RawCertificate.PoolParams params = cert.pool();
            int network = CertState.network(ctx);
            ctx.check(frame, ConwayPredicate.WRONG_NETWORK_POOL, () -> params.rewardAccountNetwork() == network ? null
                    : "Mismatch {mismatchSupplied = " + networkName(params.rewardAccountNetwork())
                    + ", mismatchExpected = " + networkName(network) + "} " + id);
            ctx.check(frame, ConwayPredicate.POOL_METADATA_HASH_TOO_BIG,
                    () -> params.metadataHashSize() == null || params.metadataHashSize() <= MAX_METADATA_HASH_SIZE
                            ? null : id + " " + params.metadataHashSize());
            BigInteger minPoolCost = pp.minPoolCost();
            ctx.check(frame, ConwayPredicate.STAKE_POOL_COST_TOO_LOW, () -> params.cost().compareTo(minPoolCost) >= 0
                    ? null : "Mismatch {mismatchSupplied = Coin " + params.cost() + ", mismatchExpected = Coin "
                    + minPoolCost + "}");
            String vrf = HexUtil.encodeHexString(params.vrfKeyHash());
            ctx.check(frame, ConwayPredicate.VRF_KEY_HASH_ALREADY_REGISTERED, () -> {
                boolean keepsActive = registered.isPresent() && vrf.equals(registered.get().vrfKeyHashHex());
                boolean taken = state.poolByVrfKeyHash(vrf).orElseThrowUnavailable().isPresent();
                return keepsActive || !taken ? null : id + " VRFVerKeyHash " + vrf;
            });
            return true;
        }
        if (cert.tag() == RawCertificate.POOL_RETIREMENT) {
            ctx.check(frame, ConwayPredicate.STAKE_POOL_NOT_REGISTERED_ON_KEY,
                    () -> registered.isPresent() ? null : "KeyHash " + id.hashHex());
            long currentEpoch = ctx.env().currentEpoch();
            long limit = currentEpoch + pp.eMax();
            long epoch = cert.epoch();
            ctx.check(frame, ConwayPredicate.STAKE_POOL_RETIREMENT_WRONG_EPOCH,
                    () -> currentEpoch < epoch && epoch <= limit ? null
                            : "Mismatch {mismatchSupplied = EpochNo " + epoch + ", mismatchExpected = EpochNo "
                            + currentEpoch + "} Mismatch {mismatchSupplied = EpochNo " + epoch
                            + ", mismatchExpected = EpochNo " + limit + "}");
            // Haskell schedules the retirement in psRetiring even for an unregistered pool; nothing in CERTS or GOV
            // reads psRetiring for a pool that does not exist, so the running state keeps no entry for it.
            return registered.isPresent();
        }
        throw new IllegalArgumentException(cert + " is not a POOL certificate");
    }

    private static String networkName(int network) {
        return network == 1 ? "Mainnet" : "Testnet";
    }
}
