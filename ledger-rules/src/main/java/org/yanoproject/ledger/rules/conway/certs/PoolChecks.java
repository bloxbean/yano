package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.PredicateCheck;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;

import java.math.BigInteger;

/**
 * Shelley {@code POOL} as Conway runs it ({@code poolTransition}, Shelley/Rules/Pool.hs:209-323), one scope per
 * certificate kind:
 *
 * <ul>
 *   <li>{@code RegPool} (:225-306): {@code WrongNetworkPOOL} (the reward account's network,
 *       {@code hardforkAlonzoValidatePoolAccountAddressNetID}), {@code PoolMedataHashTooBig} (a metadata hash of more
 *       than 32 bytes, {@code restrictPoolMetadataHash}), {@code StakePoolCostTooLowPOOL}, then from protocol version
 *       11 ({@code hardforkConwayDisallowDuplicatedVRFKeys}, added by the protocol version 11 delta)
 *       {@code VRFKeyHashAlreadyRegistered}.</li>
 *   <li>{@code RetirePool} (:307-323): {@code StakePoolNotRegisteredOnKeyPOOL}, then
 *       {@code StakePoolRetirementWrongEpochPOOL} unless {@code cEpoch < e ≤ cEpoch + eMax}.</li>
 * </ul>
 *
 * <p>Pools are those of the running state: a pool registered earlier in the transaction is re-registered by a
 * second registration, and its VRF key hash is taken.</p>
 */
public final class PoolChecks {

    /** {@code hashSize ([] @HASH)}: blake2b-256. */
    static final int MAX_METADATA_HASH_SIZE = 32;

    private PoolChecks() {
    }

    /** :231-243: the reward account's network. */
    public static final class WrongNetwork extends PredicateCheck<CertSubject> {

        public WrongNetwork() {
            super(ConwayPredicate.WRONG_NETWORK_POOL);
        }

        @Override
        protected String detail(CertSubject s) {
            RawCertificate.PoolParams params = s.raw().pool();
            int network = s.network();
            return params.rewardAccountNetwork() == network ? null
                    : "Mismatch {mismatchSupplied = " + networkName(params.rewardAccountNetwork())
                    + ", mismatchExpected = " + networkName(network) + "} " + s.poolId();
        }
    }

    /** :245-250 ({@code restrictPoolMetadataHash}): a metadata hash of at most 32 bytes. */
    public static final class MetadataHashTooBig extends PredicateCheck<CertSubject> {

        public MetadataHashTooBig() {
            super(ConwayPredicate.POOL_METADATA_HASH_TOO_BIG);
        }

        @Override
        protected String detail(CertSubject s) {
            RawCertificate.PoolParams params = s.raw().pool();
            return params.metadataHashSize() == null || params.metadataHashSize() <= MAX_METADATA_HASH_SIZE ? null
                    : s.poolId() + " " + params.metadataHashSize();
        }
    }

    /** :252-261: the pool cost at least {@code ppMinPoolCost}. */
    public static final class CostTooLow extends PredicateCheck<CertSubject> {

        public CostTooLow() {
            super(ConwayPredicate.STAKE_POOL_COST_TOO_LOW);
        }

        @Override
        protected String detail(CertSubject s) {
            RawCertificate.PoolParams params = s.raw().pool();
            BigInteger minPoolCost = s.params().minPoolCost();
            return params.cost().compareTo(minPoolCost) >= 0 ? null : "Mismatch {mismatchSupplied = Coin "
                    + params.cost() + ", mismatchExpected = Coin " + minPoolCost + "}";
        }
    }

    /**
     * :265-267 (new pool), 279-282 (re-registration), {@code hardforkConwayDisallowDuplicatedVRFKeys}: a new pool must
     * use a VRF key hash no registered pool holds (active or future parameters, {@code psVRFKeyHashes}); a
     * re-registration may keep its active VRF key hash, otherwise the same rule applies — including to a VRF key hash
     * this pool's own earlier re-registration in the epoch recorded ({@code sppVrf == spsVrf || Map.notMember sppVrf
     * psVRFKeyHashes}).
     */
    public static final class VrfKeyHashAlreadyRegistered extends PredicateCheck<CertSubject> {

        public VrfKeyHashAlreadyRegistered() {
            super(ConwayPredicate.VRF_KEY_HASH_ALREADY_REGISTERED);
        }

        @Override
        protected String detail(CertSubject s) {
            String vrf = HexUtil.encodeHexString(s.raw().pool().vrfKeyHash());
            boolean keepsActive = s.pool().isPresent() && vrf.equals(s.pool().get().vrfKeyHashHex());
            boolean taken = s.state().poolByVrfKeyHash(vrf).orElseThrowUnavailable().isPresent();
            return keepsActive || !taken ? null : s.poolId() + " VRFVerKeyHash " + vrf;
        }
    }

    /** :308: the retired pool must be registered. */
    public static final class NotRegisteredOnKey extends PredicateCheck<CertSubject> {

        public NotRegisteredOnKey() {
            super(ConwayPredicate.STAKE_POOL_NOT_REGISTERED_ON_KEY);
        }

        @Override
        protected String detail(CertSubject s) {
            return s.pool().isPresent() ? null : "KeyHash " + s.poolId().hashHex();
        }
    }

    /**
     * :309-322: {@code cEpoch < e ≤ cEpoch + eMax}. Haskell schedules the retirement in {@code psRetiring} even for an
     * unregistered pool; nothing in {@code CERTS} or {@code GOV} reads {@code psRetiring} for a pool that does not
     * exist, so the running state keeps no entry for it ({@code CERT.applyCertificate}).
     */
    public static final class RetirementWrongEpoch extends PredicateCheck<CertSubject> {

        public RetirementWrongEpoch() {
            super(ConwayPredicate.STAKE_POOL_RETIREMENT_WRONG_EPOCH);
        }

        @Override
        protected String detail(CertSubject s) {
            long currentEpoch = s.ctx().env().currentEpoch();
            long limit = currentEpoch + s.params().eMax();
            long epoch = s.raw().epoch();
            return currentEpoch < epoch && epoch <= limit ? null
                    : "Mismatch {mismatchSupplied = EpochNo " + epoch + ", mismatchExpected = EpochNo "
                    + currentEpoch + "} Mismatch {mismatchSupplied = EpochNo " + epoch
                    + ", mismatchExpected = EpochNo " + limit + "}";
        }
    }

    private static String networkName(int network) {
        return network == 1 ? "Mainnet" : "Testnet";
    }
}
