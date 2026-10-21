package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
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
 * @param epoch      the retirement epoch for tag 4, else null
 * @param pool       the pool parameters the rules check for tag 3, else null
 */
public record RawCertificate(int index, int tag, RawCredential credential, byte[] poolId, List<byte[]> poolOwners,
                             RawCredential hot, Delegatee delegatee, BigInteger coin, Long epoch, PoolParams pool) {

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
            List<CborSpan> fields = StrictCbor.array(item);
            if (fields.isEmpty()) {
                throw new TxDecodingException("a DRep is an empty array");
            }
            long kind = StrictCbor.unsignedLong(fields.get(0));
            int expected;
            if (kind <= 1) {
                expected = 2;
            } else if (kind <= 3) {
                expected = 1;
            } else {
                throw new TxDecodingException("unknown DRep kind " + kind);
            }
            if (fields.size() != expected) {
                throw new TxDecodingException("a DRep of kind " + kind + " has " + expected + " elements");
            }
            RawCredential credential = kind <= 1
                    ? new RawCredential(kind == 1, StrictCbor.definiteBytes(fields.get(1))) : null;
            return new DRep((int) kind, credential);
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

    /**
     * Reads one certificate. The fields the rules do not read are still decoded with their Haskell bounds
     * ({@link BoundedFields}: anchors, the pool margin, relays and metadata URL).
     */
    static RawCertificate read(CborSpan item, int index) {
        List<CborSpan> f = StrictCbor.array(item);
        if (f.isEmpty()) {
            throw new TxDecodingException("a certificate is an empty array");
        }
        long tag = StrictCbor.unsignedLong(f.get(0));
        int expected = switch ((int) Math.min(tag, 19)) {
            case 0, 1 -> 2;
            case 2, 7, 8, 9, 14, 15, 17, 18, 4 -> 3;
            case 10, 11, 12, 16 -> 4;
            case 13 -> 5;
            case 3 -> 10;
            default -> throw new TxDecodingException("certificate tag " + tag + " is not a Conway certificate");
        };
        if (f.size() != expected) {
            throw new TxDecodingException("certificate tag " + tag + " has " + f.size() + " elements, expected "
                    + expected);
        }
        RawCredential credential = null;
        RawCredential hot = null;
        byte[] poolId = null;
        List<byte[]> owners = new ArrayList<>();
        byte[] targetPool = null;
        DRep drep = null;
        BigInteger coin = null;
        Long epoch = null;
        PoolParams pool = null;
        switch ((int) tag) {
            case 0, 1 -> credential = RawCredential.read(f.get(1));
            case 2 -> {
                credential = RawCredential.read(f.get(1));
                targetPool = keyHash(StrictCbor.definiteBytes(f.get(2)), "delegatee pool");
            }
            case 7, 8, 17 -> {
                credential = RawCredential.read(f.get(1));
                coin = StrictCbor.unsigned(f.get(2));
            }
            case 9 -> {
                credential = RawCredential.read(f.get(1));
                drep = DRep.read(f.get(2));
            }
            case 10 -> {
                credential = RawCredential.read(f.get(1));
                targetPool = keyHash(StrictCbor.definiteBytes(f.get(2)), "delegatee pool");
                drep = DRep.read(f.get(3));
            }
            case 11 -> {
                credential = RawCredential.read(f.get(1));
                targetPool = keyHash(StrictCbor.definiteBytes(f.get(2)), "delegatee pool");
                coin = StrictCbor.unsigned(f.get(3));
            }
            case 12 -> {
                credential = RawCredential.read(f.get(1));
                drep = DRep.read(f.get(2));
                coin = StrictCbor.unsigned(f.get(3));
            }
            case 13 -> {
                credential = RawCredential.read(f.get(1));
                targetPool = keyHash(StrictCbor.definiteBytes(f.get(2)), "delegatee pool");
                drep = DRep.read(f.get(3));
                coin = StrictCbor.unsigned(f.get(4));
            }
            case 14 -> {
                credential = RawCredential.read(f.get(1));
                hot = RawCredential.read(f.get(2));
            }
            case 15, 18 -> {
                credential = RawCredential.read(f.get(1));
                BoundedFields.anchorOrNull(f.get(2), "certificate");
            }
            case 16 -> {
                credential = RawCredential.read(f.get(1));
                coin = StrictCbor.unsigned(f.get(2));
                BoundedFields.anchorOrNull(f.get(3), "certificate");
            }
            case 3 -> {
                // pool_params, flattened: operator, vrf, pledge, cost, margin, reward account, owners, relays,
                // metadata (decodeStakePoolParamsFlat)
                poolId = keyHash(StrictCbor.definiteBytes(f.get(1)), "pool operator");
                byte[] vrf = StrictCbor.definiteBytes(f.get(2));
                if (vrf.length != VRF_KEY_HASH_LENGTH) {
                    throw new TxDecodingException("VRF key hash of " + vrf.length + " bytes");
                }
                StrictCbor.unsigned(f.get(3)); // pledge: a Word64 coin
                BigInteger cost = StrictCbor.unsigned(f.get(4));
                BoundedFields.unitInterval(f.get(5), "pool margin");
                byte[] rewardAccount = StrictCbor.definiteBytes(f.get(6));
                // decodeAccountAddress (Address.hs:938-955): header & 0xEE == 0xE0, then a 28-byte hash
                if (rewardAccount.length != 29 || (rewardAccount[0] & 0xee) != 0xe0) {
                    throw new TxDecodingException("pool reward account is not an account address");
                }
                for (CborSpan owner : StrictCbor.set(f.get(7))) {
                    owners.add(keyHash(StrictCbor.definiteBytes(owner), "pool owner"));
                }
                for (CborSpan relay : StrictCbor.array(f.get(8))) {
                    BoundedFields.relay(relay);
                }
                Integer metadataHashSize = null;
                if (!f.get(9).isNull()) {
                    List<CborSpan> metadata = StrictCbor.array(f.get(9), 2, "pool metadata is a two-element array");
                    BoundedFields.url(metadata.get(0), "pool metadata");
                    metadataHashSize = StrictCbor.definiteBytes(metadata.get(1)).length;
                }
                pool = new PoolParams(vrf, cost, rewardAccount, metadataHashSize);
            }
            default -> {
                // 4: pool retirement
                poolId = keyHash(StrictCbor.definiteBytes(f.get(1)), "pool id");
                epoch = StrictCbor.unsignedLong(f.get(2));
            }
        }
        Delegatee delegatee = targetPool != null || drep != null ? new Delegatee(targetPool, drep) : null;
        return new RawCertificate(index, (int) tag, credential, poolId, owners, hot, delegatee, coin, epoch, pool);
    }

    private static byte[] keyHash(byte[] value, String what) {
        if (value.length != RawCredential.HASH_LENGTH) {
            throw new TxDecodingException(what + " key hash of " + value.length + " bytes");
        }
        return value;
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
