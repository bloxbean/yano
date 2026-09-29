package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.view.model.GovActionId;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A proposal procedure {@code [deposit, reward_account, gov_action, anchor]} read from the original bytes, with every
 * field the witness rules ({@code proposingScriptsNeeded}, Conway/UTxO.hs:96-106) and {@code GOV}
 * (Conway/Rules/Gov.hs:483-566) check.
 *
 * <pre>
 * gov_action = [0, gov_action_id / null, protocol_param_update, policy_hash / null]   ; parameter change
 *            / [1, gov_action_id / null, [major, minor]]                              ; hard-fork initiation
 *            / [2, {* reward_account => coin}, policy_hash / null]                     ; treasury withdrawals
 *            / [3, gov_action_id / null]                                               ; no confidence
 *            / [4, gov_action_id / null, set&lt;credential&gt;, {* credential => epoch}, unit_interval] ; update committee
 *            / [5, gov_action_id / null, [anchor, script_hash / null]]                 ; new constitution
 *            / [6]                                                                     ; info
 * </pre>
 *
 * <p>Decoded as Haskell's {@code DecCBOR (ProposalProcedure era)} and {@code DecCBOR (GovAction era)} at decoder
 * versions 9–11 (Procedures.hs:522-535, 875-941); anything else is a {@link TxDecodingException}: the deposit a
 * {@code Word64} coin, the return account an account address (header {@code & 0xEE == 0xE0}, 29 bytes,
 * {@code decodeAccountAddress}), a {@code GovActionId} {@code [txid (32 bytes), Word16]}, the parameter update per
 * {@link RawParamUpdate}, the protocol version {@code [Word32 major ≤ 12, Word32 minor]} ({@code decodeProtVer}:
 * the major at most {@code succVersion (ProtVerHigh ConwayEra)} = 12, BaseTypes.hs:253-262), maps and sets without
 * duplicates ({@code decodeMap}/{@code decodeSet} from version 9, Decoder.hs:801-976; a set optionally tagged 258),
 * committee expiry epochs {@code Word64}, the quorum a {@code UnitInterval}, script hashes 28 bytes, anchors per
 * {@link BoundedFields}.</p>
 */
public final class RawProposal {

    /** Governance action tags. */
    public static final int PARAMETER_CHANGE = 0;
    public static final int HARD_FORK_INITIATION = 1;
    public static final int TREASURY_WITHDRAWALS = 2;
    public static final int NO_CONFIDENCE = 3;
    public static final int UPDATE_COMMITTEE = 4;
    public static final int NEW_CONSTITUTION = 5;
    public static final int INFO = 6;

    private static final BigInteger WORD32_MAX = BigInteger.valueOf(0xFFFF_FFFFL);
    /** {@code MaxVersion} (cardano-ledger-binary Version.hs:63): {@code succVersion} of it is {@code Nothing}. */
    public static final int MAX_VERSION = 13;
    /** {@code succVersion (ProtVerHigh ConwayEra)}: Conway's highest major version is 11. */
    public static final int MAX_DECODABLE_MAJOR = 12;

    /**
     * Haskell's {@code Ord AccountAddress} (derived: network — {@code Testnet} before {@code Mainnet} — then the
     * credential, {@code ScriptHashObj} before {@code KeyHashObj}, then the hash bytes): the order of
     * {@code Map AccountAddress} keys.
     */
    public static final Comparator<byte[]> ACCOUNT_ORDER = Comparator
            .comparingInt((byte[] a) -> a[0] & 0x01)
            .thenComparingInt(a -> (a[0] & 0x10) != 0 ? 0 : 1)
            .thenComparing((a, b) -> Arrays.compareUnsigned(a, 1, a.length, b, 1, b.length));

    /**
     * A protocol version.
     *
     * @param major the major version
     * @param minor the minor version
     */
    public record ProtVer(long major, long minor) implements Comparable<ProtVer> {

        /**
         * Haskell {@code pvCanFollow} (Shelley/PParams.hs:299-307): the next major with minor 0, or the same major
         * with the next minor ({@code Word32} arithmetic, so it wraps).
         */
        public boolean canFollow(ProtVer previous) {
            return (previous.major < MAX_VERSION && major == previous.major + 1 && minor == 0)
                    || (major == previous.major && minor == ((previous.minor + 1) & 0xFFFF_FFFFL));
        }

        @Override
        public int compareTo(ProtVer other) {
            return major != other.major ? Long.compare(major, other.major) : Long.compare(minor, other.minor);
        }

        @Override
        public String toString() {
            return "ProtVer {pvMajor = " + major + ", pvMinor = " + minor + "}";
        }
    }

    /**
     * A treasury withdrawal of a proposal.
     *
     * @param account the account address (29 bytes)
     * @param amount  the coin
     */
    public record Withdrawal(byte[] account, BigInteger amount) {

        public Withdrawal {
            account = account.clone();
        }

        @Override
        public byte[] account() {
            return account.clone();
        }

        /** @return the account's network, 0 (testnet) or 1 (mainnet) */
        public int network() {
            return account[0] & 0x01;
        }

        @Override
        public String toString() {
            return HexUtil.encodeHexString(account) + " -> " + amount;
        }
    }

    private final int index;
    private final BigInteger deposit;
    private final byte[] returnAccount;
    private final int actionTag;
    private final GovActionId prevActionId;
    private final RawParamUpdate paramUpdate;
    private final ProtVer protocolVersion;
    private final List<Withdrawal> withdrawals;
    private final byte[] policyHash;
    private final SortedSet<RawCredential> committeeRemovals;
    private final SortedMap<RawCredential, BigInteger> committeeAdditions;

    private RawProposal(Builder b) {
        this.index = b.index;
        this.deposit = Objects.requireNonNull(b.deposit, "deposit");
        this.returnAccount = Objects.requireNonNull(b.returnAccount, "returnAccount").clone();
        this.actionTag = b.actionTag;
        this.prevActionId = b.prevActionId;
        this.paramUpdate = b.paramUpdate;
        this.protocolVersion = b.protocolVersion;
        this.withdrawals = List.copyOf(b.withdrawals);
        this.policyHash = b.policyHash != null ? b.policyHash.clone() : null;
        this.committeeRemovals = Collections.unmodifiableSortedSet(new TreeSet<>(b.committeeRemovals));
        this.committeeAdditions = Collections.unmodifiableSortedMap(new TreeMap<>(b.committeeAdditions));
    }

    /** @return the position in the proposal list ({@code GovActionIx}) */
    public int index() {
        return index;
    }

    /** @return the deposit ({@code pProcDeposit}) */
    public BigInteger deposit() {
        return deposit;
    }

    /** @return the deposit return account address ({@code pProcReturnAddr}), 29 bytes */
    public byte[] returnAccount() {
        return returnAccount.clone();
    }

    /** @return the return account's network, 0 (testnet) or 1 (mainnet) */
    public int returnAccountNetwork() {
        return returnAccount[0] & 0x01;
    }

    /** @return the governance action's tag 0–6 */
    public int actionTag() {
        return actionTag;
    }

    /** @return the parent in the purpose's lineage, or null ({@code SNothing}, or an action without a lineage) */
    public GovActionId prevActionId() {
        return prevActionId;
    }

    /** @return the parameter update of a {@code ParameterChange}, else null */
    public RawParamUpdate paramUpdate() {
        return paramUpdate;
    }

    /** @return the proposed protocol version of a {@code HardForkInitiation}, else null */
    public ProtVer protocolVersion() {
        return protocolVersion;
    }

    /** @return the withdrawals of a {@code TreasuryWithdrawals}, in {@code Map AccountAddress} order; else empty */
    public List<Withdrawal> withdrawals() {
        return withdrawals;
    }

    /** @return the guardrails script hash of a parameter change or treasury withdrawals, or null */
    public byte[] policyHash() {
        return policyHash != null ? policyHash.clone() : null;
    }

    /** @return the members an {@code UpdateCommittee} removes, in {@code Set} order; else empty */
    public SortedSet<RawCredential> committeeRemovals() {
        return committeeRemovals;
    }

    /** @return the members an {@code UpdateCommittee} adds with their expiry epochs, in {@code Map} order; else empty */
    public SortedMap<RawCredential, BigInteger> committeeAdditions() {
        return committeeAdditions;
    }

    /** Reads a proposal procedure at the reader's position. */
    static RawProposal read(CborReader reader, int index) {
        Builder b = new Builder();
        b.index = index;
        long length = reader.readArrayHeader();
        if (length != 4 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a proposal procedure has 4 elements");
        }
        b.deposit = reader.readUnsigned();
        b.returnAccount = accountAddress(reader, "proposal return account");
        long actionLength = reader.readArrayHeader();
        long tag = reader.readUnsignedLong();
        int expected = switch ((int) Math.min(tag, 7)) {
            case PARAMETER_CHANGE -> 4;
            case HARD_FORK_INITIATION, TREASURY_WITHDRAWALS -> 3;
            case NO_CONFIDENCE -> 2;
            case UPDATE_COMMITTEE -> 5;
            case NEW_CONSTITUTION -> 3;
            case INFO -> 1;
            default -> throw new TxDecodingException("unknown governance action tag " + tag);
        };
        if (actionLength != CborReader.INDEFINITE && actionLength != expected) {
            throw new TxDecodingException("governance action tag " + tag + " has " + actionLength + " elements");
        }
        b.actionTag = (int) tag;
        switch (b.actionTag) {
            case PARAMETER_CHANGE -> {
                b.prevActionId = govActionIdOrNull(reader);
                b.paramUpdate = RawParamUpdate.read(reader);
                b.policyHash = scriptHashOrNull(reader, "guardrails policy hash");
            }
            case HARD_FORK_INITIATION -> {
                b.prevActionId = govActionIdOrNull(reader);
                b.protocolVersion = protVer(reader);
            }
            case TREASURY_WITHDRAWALS -> {
                b.withdrawals.addAll(treasuryWithdrawals(reader));
                b.policyHash = scriptHashOrNull(reader, "guardrails policy hash");
            }
            case NO_CONFIDENCE -> b.prevActionId = govActionIdOrNull(reader);
            case UPDATE_COMMITTEE -> {
                b.prevActionId = govActionIdOrNull(reader);
                committeeRemovals(reader, b.committeeRemovals);
                committeeAdditions(reader, b.committeeAdditions);
                BoundedFields.unitInterval(reader, "committee quorum");
            }
            case NEW_CONSTITUTION -> {
                b.prevActionId = govActionIdOrNull(reader);
                constitution(reader);
            }
            default -> {
                // InfoAction: [6]
            }
        }
        if (actionLength == CborReader.INDEFINITE && reader.hasNext(actionLength, expected)) {
            throw new TxDecodingException("governance action tag " + tag + " has extra elements");
        }
        BoundedFields.anchor(reader, "proposal");
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 4)) {
            throw new TxDecodingException("a proposal procedure has 4 elements");
        }
        return new RawProposal(b);
    }

    /** {@code decodeAccountAddress}: 29 bytes, header {@code 111s 000n} (Address.hs:938-955). */
    static byte[] accountAddress(CborReader reader, String what) {
        byte[] account = reader.readDefiniteBytes();
        if (account.length != 29 || (account[0] & 0xee) != 0xe0) {
            throw new TxDecodingException(what + " is not an account address");
        }
        return account;
    }

    /** {@code gov_action_id / null}: {@code [transaction_id, Word16]}. */
    private static GovActionId govActionIdOrNull(CborReader reader) {
        if (reader.peekNull()) {
            reader.readNull();
            return null;
        }
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a governance action id has 2 elements");
        }
        byte[] txId = reader.readDefiniteBytes();
        if (txId.length != 32) {
            throw new TxDecodingException("a governance action id's transaction id of " + txId.length + " bytes");
        }
        long ix = reader.readUnsignedLong();
        if (ix > 0xFFFF) {
            throw new TxDecodingException("a governance action index exceeds Word16: " + ix);
        }
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException("a governance action id has 2 elements");
        }
        return new GovActionId(HexUtil.encodeHexString(txId), (int) ix);
    }

    private static byte[] scriptHashOrNull(CborReader reader, String what) {
        if (reader.peekNull()) {
            reader.readNull();
            return null;
        }
        byte[] hash = reader.readDefiniteBytes();
        if (hash.length != RawCredential.HASH_LENGTH) {
            throw new TxDecodingException("a " + what + " is 28 bytes");
        }
        return hash;
    }

    /** {@code decodeProtVer @ConwayEra}: {@code [major, minor]}, the major at most 12. */
    private static ProtVer protVer(CborReader reader) {
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a protocol version has 2 elements");
        }
        BigInteger major = reader.readUnsigned();
        BigInteger minor = reader.readUnsigned();
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException("a protocol version has 2 elements");
        }
        if (minor.compareTo(WORD32_MAX) > 0) {
            throw new TxDecodingException("a protocol minor version exceeds Word32: " + minor);
        }
        if (major.compareTo(BigInteger.valueOf(MAX_DECODABLE_MAJOR)) > 0) {
            throw new TxDecodingException("Protocol version " + major + " exceeds the maximum expected version of "
                    + MAX_DECODABLE_MAJOR);
        }
        return new ProtVer(major.longValue(), minor.longValue());
    }

    private static List<Withdrawal> treasuryWithdrawals(CborReader reader) {
        long count = reader.readMapHeader();
        TreeMap<byte[], BigInteger> sorted = new TreeMap<>(ACCOUNT_ORDER);
        for (long i = 0; reader.hasNext(count, i); i++) {
            byte[] account = accountAddress(reader, "treasury withdrawal account");
            BigInteger amount = reader.readUnsigned();
            if (sorted.put(account, amount) != null) {
                throw new TxDecodingException("duplicate treasury withdrawal account "
                        + HexUtil.encodeHexString(account));
            }
        }
        List<Withdrawal> result = new ArrayList<>(sorted.size());
        sorted.forEach((account, amount) -> result.add(new Withdrawal(account, amount)));
        return result;
    }

    private static void committeeRemovals(CborReader reader, SortedSet<RawCredential> into) {
        reader.skipTag(258);
        long count = reader.readArrayHeader();
        for (long i = 0; reader.hasNext(count, i); i++) {
            RawCredential credential = RawCredential.read(reader);
            if (!into.add(credential)) {
                throw new TxDecodingException("duplicate committee member to remove " + credential);
            }
        }
    }

    private static void committeeAdditions(CborReader reader, SortedMap<RawCredential, BigInteger> into) {
        long count = reader.readMapHeader();
        for (long i = 0; reader.hasNext(count, i); i++) {
            RawCredential credential = RawCredential.read(reader);
            BigInteger epoch = reader.readUnsigned();
            if (into.put(credential, epoch) != null) {
                throw new TxDecodingException("duplicate committee member to add " + credential);
            }
        }
    }

    /** {@code constitution = [anchor, script_hash / null]}. */
    private static void constitution(CborReader reader) {
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a constitution has 2 elements");
        }
        BoundedFields.anchor(reader, "constitution");
        scriptHashOrNull(reader, "constitution guardrails script hash");
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException("a constitution has 2 elements");
        }
    }

    /** @return Haskell's {@code showGovActionType} */
    public String actionTypeName() {
        return switch (actionTag) {
            case PARAMETER_CHANGE -> "ParameterChange";
            case HARD_FORK_INITIATION -> "HardForkInitiation";
            case TREASURY_WITHDRAWALS -> "TreasuryWithdrawals";
            case NO_CONFIDENCE -> "NoConfidence";
            case UPDATE_COMMITTEE -> "UpdateCommittee";
            case NEW_CONSTITUTION -> "NewConstitution";
            default -> "InfoAction";
        };
    }

    @Override
    public String toString() {
        return "proposal " + index + " (" + actionTypeName()
                + (policyHash != null ? ", policy " + HexUtil.encodeHexString(policyHash) : "") + ")";
    }

    private static final class Builder {
        private int index;
        private BigInteger deposit;
        private byte[] returnAccount;
        private int actionTag;
        private GovActionId prevActionId;
        private RawParamUpdate paramUpdate;
        private ProtVer protocolVersion;
        private final List<Withdrawal> withdrawals = new ArrayList<>();
        private byte[] policyHash;
        private final SortedSet<RawCredential> committeeRemovals = new TreeSet<>();
        private final SortedMap<RawCredential, BigInteger> committeeAdditions = new TreeMap<>();
    }
}
