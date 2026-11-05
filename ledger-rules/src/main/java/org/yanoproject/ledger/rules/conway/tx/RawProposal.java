package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.raw.RawGovAction;
import com.bloxbean.cardano.client.transaction.raw.RawGovActionId;
import com.bloxbean.cardano.client.transaction.raw.RawProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.raw.RawProtocolVersion;
import com.bloxbean.cardano.client.transaction.raw.RawWithdrawal;
import com.bloxbean.cardano.client.transaction.raw.TxBodyField;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.view.model.GovActionId;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * <p>Read through CCL's view ({@link com.bloxbean.cardano.client.transaction.raw.RawProposal}) of a proposal that
 * {@link HaskellPolicy} has walked: Haskell's {@code DecCBOR (ProposalProcedure era)} and {@code DecCBOR (GovAction
 * era)} at decoder versions 9–11 (Procedures.hs:559-570, 927-946), with the parameter update per
 * {@link RawParamUpdate}.</p>
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

    /** {@code MaxVersion} (cardano-ledger-binary Version.hs:63): {@code succVersion} of it is {@code Nothing}. */
    public static final int MAX_VERSION = 13;

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
    private final CborSpan paramUpdateSpan;
    private final ProtVer protocolVersion;
    private final List<Withdrawal> withdrawals;
    private final byte[] policyHash;
    private final SortedSet<RawCredential> committeeRemovals;
    private final SortedMap<RawCredential, BigInteger> committeeAdditions;
    private final BigInteger[] quorum;
    private final byte[] constitutionScript;

    private RawProposal(Builder b) {
        this.index = b.index;
        this.deposit = Objects.requireNonNull(b.deposit, "deposit");
        this.returnAccount = Objects.requireNonNull(b.returnAccount, "returnAccount").clone();
        this.actionTag = b.actionTag;
        this.prevActionId = b.prevActionId;
        this.paramUpdate = b.paramUpdate;
        this.paramUpdateSpan = b.paramUpdateSpan;
        this.protocolVersion = b.protocolVersion;
        this.withdrawals = List.copyOf(b.withdrawals);
        this.policyHash = b.policyHash != null ? b.policyHash.clone() : null;
        this.committeeRemovals = Collections.unmodifiableSortedSet(new TreeSet<>(b.committeeRemovals));
        this.committeeAdditions = Collections.unmodifiableSortedMap(new TreeMap<>(b.committeeAdditions));
        this.quorum = b.quorum;
        this.constitutionScript = b.constitutionScript;
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

    /**
     * @return a {@code ParameterChange}'s {@code protocol_param_update} as encoded in the transaction, else null (a
     *         script context translates the update from it: {@code ToPlutusData PParamsUpdate})
     */
    public CborSpan paramUpdateSpan() {
        return paramUpdateSpan;
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

    /** @return an {@code UpdateCommittee}'s quorum, the reduced {@code [numerator, denominator]}; else null */
    public BigInteger[] quorum() {
        return quorum != null ? quorum.clone() : null;
    }

    /** @return a {@code NewConstitution}'s guardrails script hash, or null (none, or another action) */
    public byte[] constitutionScript() {
        return constitutionScript != null ? constitutionScript.clone() : null;
    }

    /** Reads a proposal procedure, as one item of a transaction's. */
    static RawProposal read(CborSpan item, int index) {
        HaskellPolicy.CONWAY.walk(TxBodyField.PROPOSAL_PROCEDURES.spec().record(), item);
        return accepted(com.bloxbean.cardano.client.transaction.raw.RawProposal.of(item, Era.Conway), index);
    }

    /** @return the proposal that CCL's reader read, which {@link HaskellPolicy} has accepted */
    static RawProposal accepted(com.bloxbean.cardano.client.transaction.raw.RawProposal proposal, int index) {
        Builder b = new Builder();
        b.index = index;
        b.deposit = proposal.deposit();
        b.returnAccount = proposal.rewardAccount();
        RawGovAction action = proposal.govAction();
        b.actionTag = action.kind().tag();
        switch (action.kind()) {
            case PARAMETER_CHANGE -> {
                b.prevActionId = previous(action);
                RawProtocolParamUpdate update = action.protocolParamUpdate();
                b.paramUpdate = RawParamUpdate.accepted(update);
                b.paramUpdateSpan = update.span();
                b.policyHash = action.policyHash().orElse(null);
            }
            case HARD_FORK_INITIATION -> {
                b.prevActionId = previous(action);
                RawProtocolVersion version = action.protocolVersion();
                b.protocolVersion = new ProtVer(version.major(), version.minor().longValueExact());
            }
            case TREASURY_WITHDRAWALS -> {
                TreeMap<byte[], BigInteger> sorted = new TreeMap<>(ACCOUNT_ORDER);
                for (RawWithdrawal withdrawal : action.withdrawals()) {
                    sorted.put(withdrawal.rewardAccount(), withdrawal.coin());
                }
                sorted.forEach((account, amount) -> b.withdrawals.add(new Withdrawal(account, amount)));
                b.policyHash = action.policyHash().orElse(null);
            }
            case NO_CONFIDENCE -> b.prevActionId = previous(action);
            case UPDATE_COMMITTEE -> {
                b.prevActionId = previous(action);
                action.membersToRemove().forEach(member -> b.committeeRemovals.add(RawCredential.of(member)));
                action.membersToAdd().forEach(member -> b.committeeAdditions.put(RawCredential.of(member.getKey()),
                        new BigInteger(Long.toUnsignedString(member.getValue()))));
                UnitInterval quorum = action.quorum();
                BigInteger gcd = quorum.getNumerator().gcd(quorum.getDenominator());
                b.quorum = new BigInteger[]{quorum.getNumerator().divide(gcd), quorum.getDenominator().divide(gcd)};
            }
            case NEW_CONSTITUTION -> {
                b.prevActionId = previous(action);
                b.constitutionScript = action.constitution().scriptHash().orElse(null);
            }
            default -> {
                // InfoAction: [6]
            }
        }
        return new RawProposal(b);
    }

    private static GovActionId previous(RawGovAction action) {
        return action.previousActionId().map(RawProposal::govActionId).orElse(null);
    }

    /** @return a governance action id that CCL's reader read, which {@link HaskellPolicy} has accepted */
    static GovActionId govActionId(RawGovActionId id) {
        return new GovActionId(HexUtil.encodeHexString(id.transactionId()), id.index());
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
        private CborSpan paramUpdateSpan;
        private BigInteger[] quorum;
        private byte[] constitutionScript;
    }
}
