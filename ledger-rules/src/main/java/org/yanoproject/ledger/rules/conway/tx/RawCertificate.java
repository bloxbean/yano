package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.raw.FieldType;
import com.bloxbean.cardano.client.transaction.raw.RawDRep;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * One Conway certificate as the witness and certificate rules read it, from the original bytes: its position, its
 * CDDL tag, the credential that must authorise it, and the fields {@code DELEG}, {@code POOL} and {@code GOVCERT}
 * check.
 *
 * <p>Tags ({@code ConwayTxCert} decoder): 0 stake registration (no deposit), 1 stake deregistration, 2 stake
 * delegation, 3 pool registration, 4 pool retirement, 7 registration with deposit, 8 unregistration with refund,
 * 9 vote delegation, 10 stake and vote delegation, 11–13 registration with delegation, 14 committee hot-key
 * authorisation, 15 committee cold-key resignation, 16 DRep registration, 17 DRep unregistration, 18 DRep update.
 * The genesis-delegation and MIR tags (5, 6) do not exist in Conway.</p>
 *
 * @param index      the position in the certificate list
 * @param tag        the certificate's CDDL tag
 * @param credential the certificate's credential (element 1) for tags 0–2 and 7–18 (the cold credential for 14 and
 *                   15, the DRep credential for 16–18); null for pool certificates
 * @param poolId     the pool's key hash (operator) for tags 3 and 4, else null
 * @param poolOwners the pool owners' key hashes for tag 3, else empty
 * @param hot        the committee hot credential for tag 14, else null
 * @param delegatee  the delegation target for tags 2 and 9–13, else null
 * @param coin       the stated deposit (tags 7, 11–13, 16) or refund (8, 17), else null
 * @param epoch      the retirement epoch for tag 4 (a {@code Word64} {@code EpochNo}), else null
 * @param pool       the pool parameters the rules check for tag 3, else null
 */
public record RawCertificate(int index, int tag, RawCredential credential, byte[] poolId, List<byte[]> poolOwners,
                             RawCredential hot, Delegatee delegatee, BigInteger coin, BigInteger epoch,
                             PoolParams pool) {

    public static final int STAKE_REGISTRATION = 0;
    public static final int STAKE_DEREGISTRATION = 1;
    public static final int STAKE_DELEGATION = 2;
    public static final int POOL_REGISTRATION = 3;
    public static final int POOL_RETIREMENT = 4;
    public static final int REG = 7;
    public static final int UNREG = 8;
    public static final int VOTE_DELEG = 9;
    public static final int STAKE_VOTE_DELEG = 10;
    public static final int STAKE_REG_DELEG = 11;
    public static final int VOTE_REG_DELEG = 12;
    public static final int STAKE_VOTE_REG_DELEG = 13;
    public static final int AUTH_COMMITTEE_HOT = 14;
    public static final int RESIGN_COMMITTEE_COLD = 15;
    public static final int REG_DREP = 16;
    public static final int UNREG_DREP = 17;
    public static final int UPDATE_DREP = 18;

    /** The VRF key hash length ({@code VRFVerKeyHash}, blake2b-256). */
    public static final int VRF_KEY_HASH_LENGTH = 32;

    /**
     * A DRep ({@code drep = [0, addr_keyhash] / [1, script_hash] / [2] / [3]}).
     *
     * @param credential the DRep credential, or null for the two predefined DReps
     * @param kind       0 key hash, 1 script hash, 2 {@code DRepAlwaysAbstain}, 3 {@code DRepAlwaysNoConfidence}
     */
    public record DRep(int kind, RawCredential credential) {

        public DRep {
            if ((kind <= 1) != (credential != null)) {
                throw new IllegalArgumentException("a credential is required exactly for DRep kinds 0 and 1");
            }
        }

        static DRep read(CborSpan item) {
            HaskellPolicy.CONWAY.walk(FieldType.DREP.record(), item);
            return of(RawDRep.of(item));
        }

        private static DRep of(RawDRep drep) {
            return switch (drep.kind()) {
                case KEY_HASH -> new DRep(0, new RawCredential(false, drep.hash()));
                case SCRIPT_HASH -> new DRep(1, new RawCredential(true, drep.hash()));
                case ABSTAIN -> new DRep(2, null);
                case NO_CONFIDENCE -> new DRep(3, null);
            };
        }

        @Override
        public String toString() {
            return switch (kind) {
                case 0, 1 -> "DRepCredential (" + credential + ")";
                case 2 -> "DRepAlwaysAbstain";
                default -> "DRepAlwaysNoConfidence";
            };
        }
    }

    /**
     * A delegation target ({@code Delegatee}: {@code DelegStake}, {@code DelegVote}, {@code DelegStakeVote}).
     *
     * @param pool the stake pool, or null
     * @param drep the DRep, or null
     */
    public record Delegatee(byte[] pool, DRep drep) {

        public Delegatee {
            if (pool == null && drep == null) {
                throw new IllegalArgumentException("a delegatee names a pool, a DRep or both");
            }
            pool = pool != null ? pool.clone() : null;
        }

        @Override
        public byte[] pool() {
            return pool != null ? pool.clone() : null;
        }

        @Override
        public String toString() {
            String p = pool != null ? "pool " + HexUtil.encodeHexString(pool) : null;
            return p != null && drep != null ? p + ", " + drep : p != null ? p : drep.toString();
        }
    }

    /**
     * The pool parameters of a registration that {@code POOL} checks.
     *
     * @param vrfKeyHash       the VRF key hash (32 bytes)
     * @param cost             the fixed cost
     * @param rewardAccount    the reward account bytes (an account address)
     * @param metadataHashSize the size of the metadata hash, or null without metadata
     */
    public record PoolParams(byte[] vrfKeyHash, BigInteger cost, byte[] rewardAccount, Integer metadataHashSize) {

        public PoolParams {
            vrfKeyHash = vrfKeyHash.clone();
            Objects.requireNonNull(cost, "cost");
            rewardAccount = rewardAccount.clone();
        }

        @Override
        public byte[] vrfKeyHash() {
            return vrfKeyHash.clone();
        }

        @Override
        public byte[] rewardAccount() {
            return rewardAccount.clone();
        }

        /** @return the reward account's network id (header low nibble) */
        public int rewardAccountNetwork() {
            return rewardAccount[0] & 0x0f;
        }
    }

    public RawCertificate {
        poolId = poolId != null ? poolId.clone() : null;
        poolOwners = poolOwners.stream().map(byte[]::clone).toList();
    }

    @Override
    public byte[] poolId() {
        return poolId != null ? poolId.clone() : null;
    }

    @Override
    public List<byte[]> poolOwners() {
        return poolOwners.stream().map(byte[]::clone).toList();
    }

    /**
     * {@code getScriptWitnessConwayTxCert} (Conway/TxCert.hs:763-784): the script hash that must authorise the
     * certificate. A registration without a deposit (tag 0) needs none during Conway; pool certificates are
     * authorised by keys only.
     *
     * @return the credential's script hash, or null
     */
    public byte[] scriptWitness() {
        if (tag == 0 || credential == null || !credential.script()) {
            return null;
        }
        return credential.hash();
    }

    /**
     * {@code getVKeyWitnessConwayTxCert} (Conway/TxCert.hs:786-804): the key hash that must sign — the credential's
     * key hash, or the pool id for pool certificates; none for a registration without a deposit (tag 0).
     *
     * @return the key hash, or null
     */
    public byte[] vkeyWitness() {
        if (tag == 3 || tag == 4) {
            return poolId();
        }
        if (tag == 0 || credential == null || credential.script()) {
            return null;
        }
        return credential.hash();
    }

    /** Reads one certificate: {@link HaskellPolicy} decides whether it decodes, CCL's reader reads it. */
    static RawCertificate read(CborSpan item, int index) {
        HaskellPolicy.CONWAY.walk(CertKind.RECORD, item);
        return accepted(item, index);
    }

    /** The certificate at {@code index}, which {@link HaskellPolicy} has accepted. */
    static RawCertificate accepted(CborSpan item, int index) {
        var cert = com.bloxbean.cardano.client.transaction.raw.RawCertificate.of(item);
        CertKind kind = cert.kind();
        RawCredential credential = switch (kind) {
            case POOL_REGISTRATION, POOL_RETIREMENT -> null;
            default -> RawCredential.of(cert.credential());
        };
        RawCredential hot = kind == CertKind.AUTH_COMMITTEE_HOT_CERT ? RawCredential.of(cert.hotCredential()) : null;
        byte[] targetPool = switch (kind) {
            case STAKE_DELEGATION, STAKE_VOTE_DELEG_CERT, STAKE_REG_DELEG_CERT, STAKE_VOTE_REG_DELEG_CERT ->
                    cert.poolKeyHash();
            default -> null;
        };
        DRep drep = switch (kind) {
            case VOTE_DELEG_CERT, STAKE_VOTE_DELEG_CERT, VOTE_REG_DELEG_CERT, STAKE_VOTE_REG_DELEG_CERT ->
                    DRep.of(cert.drep());
            default -> null;
        };
        BigInteger coin = switch (kind) {
            case REG_CERT, UNREG_CERT, STAKE_REG_DELEG_CERT, VOTE_REG_DELEG_CERT, STAKE_VOTE_REG_DELEG_CERT,
                 REG_DREP_CERT, UNREG_DREP_CERT -> cert.coin();
            default -> null;
        };
        Delegatee delegatee = targetPool != null || drep != null ? new Delegatee(targetPool, drep) : null;
        boolean registration = kind == CertKind.POOL_REGISTRATION;
        boolean retirement = kind == CertKind.POOL_RETIREMENT;
        return new RawCertificate(index, kind.tag(), credential, registration || retirement ? cert.poolKeyHash() : null,
                registration ? cert.poolOwners() : List.of(), hot, delegatee, coin,
                retirement ? new BigInteger(Long.toUnsignedString(cert.epoch())) : null,
                registration ? new PoolParams(cert.vrfKeyHash(), cert.cost(), cert.rewardAccount(),
                        cert.poolMetadata().map(metadata -> metadata.hash().length).orElse(null)) : null);
    }

    @Override
    public String toString() {
        return "certificate " + index + " (tag " + tag + (credential != null ? ", " + credential : "") + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawCertificate other && index == other.index && tag == other.tag
                && Objects.equals(credential, other.credential);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, tag, credential);
    }
}
