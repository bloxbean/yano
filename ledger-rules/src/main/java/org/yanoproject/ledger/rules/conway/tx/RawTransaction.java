package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.view.model.GovActionId;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * A Conway transaction read from its <em>original</em> bytes (ADR-056 Phase 3a).
 *
 * <p>Everything the rules hash or size comes from byte ranges of the received transaction, never from a
 * re-serialisation: the transaction id ({@link #txId()}, blake2b-256 of the body bytes), the size
 * ({@link #size()}), each output's encoded size, the witness-set fields (redeemers, datums) and the auxiliary
 * data. Scalar body fields, inputs, outputs, withdrawals and the mint are decoded here too, so the checks see
 * exactly what Haskell decodes (for example whether a validity bound is present at all). The decoded CCL
 * {@link Transaction} is kept for the structure the rules do not need byte-exact (certificates, proposals,
 * votes).</p>
 *
 * <p>Definite and indefinite encodings and tag-258 sets are accepted wherever Conway accepts them; redeemers
 * may be the list form or the Conway map form.</p>
 */
public final class RawTransaction {

    /** Haskell's derived {@code Ord GovActionId}: the transaction id's bytes, then the index. */
    public static final Comparator<GovActionId> GOV_ACTION_ID_ORDER =
            Comparator.comparing(GovActionId::txHashHex).thenComparingInt(GovActionId::index);

    /** Body keys (Conway CDDL {@code transaction_body}). */
    public static final int BODY_INPUTS = 0;
    public static final int BODY_OUTPUTS = 1;
    public static final int BODY_FEE = 2;
    public static final int BODY_TTL = 3;
    public static final int BODY_CERTS = 4;
    public static final int BODY_WITHDRAWALS = 5;
    public static final int BODY_AUX_DATA_HASH = 7;
    public static final int BODY_VALIDITY_START = 8;
    public static final int BODY_MINT = 9;
    public static final int BODY_SCRIPT_DATA_HASH = 11;
    public static final int BODY_COLLATERAL = 13;
    public static final int BODY_REQUIRED_SIGNERS = 14;
    public static final int BODY_NETWORK_ID = 15;
    public static final int BODY_COLLATERAL_RETURN = 16;
    public static final int BODY_TOTAL_COLLATERAL = 17;
    public static final int BODY_REFERENCE_INPUTS = 18;
    public static final int BODY_VOTING_PROCEDURES = 19;
    public static final int BODY_PROPOSAL_PROCEDURES = 20;
    public static final int BODY_CURRENT_TREASURY_VALUE = 21;
    public static final int BODY_DONATION = 22;

    /** Witness-set keys. */
    public static final int WITNESS_VKEYS = 0;
    public static final int WITNESS_NATIVE_SCRIPTS = 1;
    public static final int WITNESS_BOOTSTRAP = 2;
    public static final int WITNESS_PLUTUS_V1 = 3;
    public static final int WITNESS_DATUMS = 4;
    public static final int WITNESS_REDEEMERS = 5;
    public static final int WITNESS_PLUTUS_V2 = 6;
    public static final int WITNESS_PLUTUS_V3 = 7;

    private static final int SET_TAG = 258;
    /** The keys Conway's body decoder knows (Conway/TxBody.hs:190-252). */
    private static final Set<Integer> BODY_KEYS = Set.of(0, 1, 2, 3, 4, 5, 7, 8, 9, 11, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22);
    private static final int TX_ID_LENGTH = 32;
    private static final int VKEY_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 64;

    /** A withdrawal: the reward account bytes and the amount. */
    public record Withdrawal(byte[] rewardAccount, BigInteger amount) {
        public Withdrawal {
            rewardAccount = rewardAccount.clone();
        }

        @Override
        public byte[] rewardAccount() {
            return rewardAccount.clone();
        }

        /** @return the reward account's network id (header low nibble) */
        public int network() {
            return rewardAccount[0] & 0x01;
        }

        @Override
        public String toString() {
            return HexUtil.encodeHexString(rewardAccount) + " -> " + amount;
        }
    }

    private final byte[] txCbor;
    private final CborSlice body;
    private final CborSlice witnessSet;
    private final CborSlice auxData;
    private final boolean isValid;
    private final byte[] txId;
    private final Map<Integer, CborSlice> bodyFields;
    private final Map<Integer, CborSlice> witnessFields;
    private final List<TxInRef> inputs;
    private final List<TxInRef> collateralInputs;
    private final List<TxInRef> referenceInputs;
    private final List<RawOutput> outputs;
    private final RawOutput collateralReturn;
    private final BigInteger fee;
    private final BigInteger ttl;
    private final BigInteger validityStart;
    private final List<Withdrawal> withdrawals;
    private final Map<String, Map<String, BigInteger>> mint;
    private final Integer networkId;
    private final BigInteger totalCollateral;
    private final BigInteger currentTreasuryValue;
    private final BigInteger donation;
    private final int proposalCount;
    private final List<RawRedeemer> redeemers;
    private final List<VKeyWitness> vkeyWitnesses;
    private final List<BootstrapWitness> bootstrapWitnesses;
    private final List<RawScript> witnessScripts;
    private final List<byte[]> datumHashes;
    private final List<RawCertificate> certificates;
    private final List<RawVoter> voters;
    private final Map<RawVoter, SortedMap<GovActionId, Integer>> votes;
    private final List<RawProposal> proposals;
    private final List<byte[]> requiredSigners;
    private final byte[] auxDataHash;
    private final byte[] scriptDataHash;
    private final RawAuxData auxDataContent;
    private final Transaction decoded;

    private RawTransaction(Builder b) {
        this.txCbor = b.txCbor;
        this.body = b.body;
        this.witnessSet = b.witnessSet;
        this.auxData = b.auxData;
        this.isValid = b.isValid;
        this.txId = Blake2bUtil.blake2bHash256(body.copy(txCbor));
        this.bodyFields = Collections.unmodifiableMap(b.bodyFields);
        this.witnessFields = Collections.unmodifiableMap(b.witnessFields);
        this.inputs = List.copyOf(b.inputs);
        this.collateralInputs = List.copyOf(b.collateralInputs);
        this.referenceInputs = List.copyOf(b.referenceInputs);
        this.outputs = List.copyOf(b.outputs);
        this.collateralReturn = b.collateralReturn;
        this.fee = b.fee;
        this.ttl = b.ttl;
        this.validityStart = b.validityStart;
        this.withdrawals = List.copyOf(b.withdrawals);
        this.mint = b.mint;
        this.networkId = b.networkId;
        this.totalCollateral = b.totalCollateral;
        this.currentTreasuryValue = b.currentTreasuryValue;
        this.donation = b.donation;
        this.proposalCount = b.proposalCount;
        this.redeemers = List.copyOf(b.redeemers);
        this.vkeyWitnesses = List.copyOf(b.vkeyWitnesses);
        this.bootstrapWitnesses = List.copyOf(b.bootstrapWitnesses);
        this.witnessScripts = List.copyOf(b.witnessScripts);
        this.datumHashes = List.copyOf(b.datumHashes);
        this.certificates = List.copyOf(b.certificates);
        this.voters = List.copyOf(b.voters);
        this.votes = Collections.unmodifiableMap(new TreeMap<>(b.votes));
        this.proposals = List.copyOf(b.proposals);
        this.requiredSigners = List.copyOf(b.requiredSigners);
        this.auxDataHash = b.auxDataHash;
        this.scriptDataHash = b.scriptDataHash;
        this.auxDataContent = b.auxDataContent;
        this.decoded = b.decoded;
    }

    /**
     * Reads a transaction {@code [body, witness_set, is_valid, auxiliary_data / null]}.
     *
     * @param txCbor  the transaction exactly as received (copied)
     * @param decoded the CCL decoding of the same bytes, kept for structure
     * @throws TxDecodingException when the bytes are not a well-formed Conway transaction
     */
    public static RawTransaction parse(byte[] txCbor, Transaction decoded) {
        Objects.requireNonNull(txCbor, "txCbor");
        Builder b = new Builder(txCbor.clone(), decoded);
        b.read();
        return new RawTransaction(b);
    }

    public byte[] txCbor() {
        return txCbor.clone();
    }

    /** @return the decoded CCL transaction (structure only; never re-serialise it) */
    public Transaction decoded() {
        return decoded;
    }

    /** @return blake2b-256 of the original body bytes */
    public byte[] txId() {
        return txId.clone();
    }

    public String txIdHex() {
        return HexUtil.encodeHexString(txId);
    }

    public CborSlice body() {
        return body;
    }

    public CborSlice witnessSet() {
        return witnessSet;
    }

    /** @return the auxiliary data's bytes, or null when the transaction has none ({@code null}) */
    public CborSlice auxData() {
        return auxData;
    }

    /** @return the {@code is_valid} flag (Haskell {@code IsPhase2Valid}) */
    public boolean isValid() {
        return isValid;
    }

    /** @return a copy of an item's bytes */
    public byte[] bytes(CborSlice slice) {
        return slice.copy(txCbor);
    }

    /**
     * Haskell's transaction size ({@code sizeTxF}, Conway/Tx.hs:86 → {@code sizeAlonzoTxF}, Alonzo/Tx.hs:324-331):
     * the length of {@code toCBORForSizeComputation} (Alonzo/Tx.hs:432-443), a three-element list header, then
     * the body, the witness set and the auxiliary data (or {@code null}) with their stored bytes; the
     * {@code is_valid} flag is not counted. Same as {@code YanoTransactionSizeValidator.haskellSize}
     * (scalus-bridge).
     */
    public int size() {
        return 1 + body.length() + witnessSet.length() + (auxData == null ? 1 : auxData.length());
    }

    /** @return the body's fields as byte ranges of the original bytes, by key */
    public Map<Integer, CborSlice> bodyFields() {
        return bodyFields;
    }

    /** @return the witness set's fields as byte ranges of the original bytes, by key */
    public Map<Integer, CborSlice> witnessFields() {
        return witnessFields;
    }

    /** @return the spending inputs, in encoded order */
    public List<TxInRef> inputs() {
        return inputs;
    }

    /** @return the spending inputs as Haskell's {@code Set TxIn} */
    public SortedSet<TxInRef> inputSet() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(inputs));
    }

    public List<TxInRef> collateralInputs() {
        return collateralInputs;
    }

    public SortedSet<TxInRef> collateralSet() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(collateralInputs));
    }

    public List<TxInRef> referenceInputs() {
        return referenceInputs;
    }

    public SortedSet<TxInRef> referenceSet() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(referenceInputs));
    }

    /** @return spending ∪ collateral ∪ reference inputs ({@code allInputsTxBodyF}, Babbage) */
    public SortedSet<TxInRef> allInputs() {
        TreeSet<TxInRef> all = new TreeSet<>(inputs);
        all.addAll(collateralInputs);
        all.addAll(referenceInputs);
        return Collections.unmodifiableSortedSet(all);
    }

    public List<RawOutput> outputs() {
        return outputs;
    }

    /** @return the collateral return, or null */
    public RawOutput collateralReturn() {
        return collateralReturn;
    }

    /** @return the outputs followed by the collateral return ({@code allSizedOutputsTxBodyF}, Babbage/TxBody.hs:241-250) */
    public List<RawOutput> allOutputs() {
        if (collateralReturn == null) {
            return outputs;
        }
        List<RawOutput> all = new ArrayList<>(outputs);
        all.add(collateralReturn);
        return List.copyOf(all);
    }

    public BigInteger fee() {
        return fee;
    }

    /** @return the validity interval's upper bound (key 3), or null when absent */
    public BigInteger ttl() {
        return ttl;
    }

    /** @return the validity interval's lower bound (key 8), or null when absent */
    public BigInteger validityStart() {
        return validityStart;
    }

    public List<Withdrawal> withdrawals() {
        return withdrawals;
    }

    /** @return the mint field, policy → asset → signed quantity (empty when absent) */
    public Map<String, Map<String, BigInteger>> mint() {
        return mint;
    }

    /** @return the body's network id (0 or 1), or null when absent */
    public Integer networkId() {
        return networkId;
    }

    /** @return the declared total collateral, or null */
    public BigInteger totalCollateral() {
        return totalCollateral;
    }

    /** @return the declared current treasury value, or null */
    public BigInteger currentTreasuryValue() {
        return currentTreasuryValue;
    }

    /** @return the treasury donation (zero when absent, as Haskell's default) */
    public BigInteger donation() {
        return donation;
    }

    public int proposalCount() {
        return proposalCount;
    }

    /**
     * @return the redeemers as Haskell's {@code Redeemers} map holds them: keyed by (tag, index), sorted; in both the
     *         list and the map form a later duplicate replaces an earlier one ({@code Map.fromList}; the map form
     *         reverses its accumulator first, Alonzo/TxWits.hs:567-580)
     */
    public List<RawRedeemer> redeemers() {
        return redeemers;
    }

    /** @return whether the witness set carries at least one redeemer */
    public boolean hasRedeemers() {
        return !redeemers.isEmpty();
    }

    /** @return the vkey witnesses (witness set key 0), in encoded order */
    public List<VKeyWitness> vkeyWitnesses() {
        return vkeyWitnesses;
    }

    /** @return the bootstrap witnesses (witness set key 2), in encoded order */
    public List<BootstrapWitness> bootstrapWitnesses() {
        return bootstrapWitnesses;
    }

    /** @return the witness scripts: native (key 1), then Plutus V1, V2, V3 (keys 3, 6, 7), in encoded order */
    public List<RawScript> witnessScripts() {
        return witnessScripts;
    }

    /** @return the hashes of the witness set's datums (key 4), blake2b-256 of each datum's original bytes */
    public List<byte[]> datumHashes() {
        return datumHashes.stream().map(byte[]::clone).toList();
    }

    /** @return the certificates (body key 4), in order */
    public List<RawCertificate> certificates() {
        return certificates;
    }

    /** @return the voters of the voting procedures (body key 19), in Haskell's {@code Ord Voter} order */
    public List<RawVoter> voters() {
        return voters;
    }

    /**
     * @return each voter's votes (body key 19): the actions voted on, with the vote (0 no, 1 yes, 2 abstain); voters
     *         in Haskell's {@code Ord Voter} order and each voter's actions in {@code Ord GovActionId} order (transaction
     *         id bytes, then index)
     */
    public Map<RawVoter, SortedMap<GovActionId, Integer>> votes() {
        return votes;
    }

    /** @return the proposal procedures (body key 20), in order */
    public List<RawProposal> proposals() {
        return proposals;
    }

    /** @return the required signers (body key 14), in encoded order */
    public List<byte[]> requiredSigners() {
        return requiredSigners.stream().map(byte[]::clone).toList();
    }

    /** @return the body's auxiliary-data hash (key 7), or null */
    public byte[] auxDataHash() {
        return auxDataHash != null ? auxDataHash.clone() : null;
    }

    /** @return the body's script integrity hash (key 11), or null */
    public byte[] scriptDataHash() {
        return scriptDataHash != null ? scriptDataHash.clone() : null;
    }

    /** @return the decoded auxiliary data, or null when the transaction has none */
    public RawAuxData auxDataContent() {
        return auxDataContent;
    }

    /** Parser state. */
    private static final class Builder {
        private final byte[] txCbor;
        private final Transaction decoded;
        private CborSlice body;
        private CborSlice witnessSet;
        private CborSlice auxData;
        private boolean isValid;
        private final Map<Integer, CborSlice> bodyFields = new TreeMap<>();
        private final Map<Integer, CborSlice> witnessFields = new TreeMap<>();
        private List<TxInRef> inputs = List.of();
        private List<TxInRef> collateralInputs = List.of();
        private List<TxInRef> referenceInputs = List.of();
        private final List<RawOutput> outputs = new ArrayList<>();
        private RawOutput collateralReturn;
        private BigInteger fee;
        private BigInteger ttl;
        private BigInteger validityStart;
        private final List<Withdrawal> withdrawals = new ArrayList<>();
        private Map<String, Map<String, BigInteger>> mint = Map.of();
        private Integer networkId;
        private BigInteger totalCollateral;
        private BigInteger currentTreasuryValue;
        private BigInteger donation = BigInteger.ZERO;
        private int proposalCount;
        private final List<RawRedeemer> redeemers = new ArrayList<>();
        private final List<VKeyWitness> vkeyWitnesses = new ArrayList<>();
        private final List<BootstrapWitness> bootstrapWitnesses = new ArrayList<>();
        private final List<RawScript> witnessScripts = new ArrayList<>();
        private final List<byte[]> datumHashes = new ArrayList<>();
        private final List<RawCertificate> certificates = new ArrayList<>();
        private final List<RawVoter> voters = new ArrayList<>();
        private final Map<RawVoter, SortedMap<GovActionId, Integer>> votes = new TreeMap<>();
        private final List<RawProposal> proposals = new ArrayList<>();
        private final List<byte[]> requiredSigners = new ArrayList<>();
        private byte[] auxDataHash;
        private byte[] scriptDataHash;
        private RawAuxData auxDataContent;

        Builder(byte[] txCbor, Transaction decoded) {
            this.txCbor = txCbor;
            this.decoded = decoded;
        }

        void read() {
            if (txCbor.length == 0) {
                throw new TxDecodingException("empty transaction bytes");
            }
            CborReader tx = new CborReader(txCbor);
            long length = tx.readArrayHeader();
            if (length != 4 && length != CborReader.INDEFINITE) {
                throw new TxDecodingException("a Conway transaction has 4 elements, found " + length);
            }
            body = tx.readItem();
            witnessSet = tx.readItem();
            isValid = tx.readBoolean();
            if (tx.peekNull()) {
                tx.readNull();
                auxData = null;
            } else {
                auxData = tx.readItem();
            }
            if (length == CborReader.INDEFINITE && tx.hasNext(length, 4)) {
                throw new TxDecodingException("a Conway transaction has 4 elements");
            }
            if (!tx.atEnd()) {
                throw new TxDecodingException("trailing bytes after the transaction");
            }
            readBody();
            readWitnessSet();
            if (auxData != null) {
                auxDataContent = RawAuxData.decode(auxData.copy(txCbor));
            }
        }

        /**
         * Conway's body decoder at protocol version 9+ ({@code ConwayTxBodyRaw} {@code DecCBOR},
         * Conway/TxBody.hs:186-260): a sparse keyed map with keys 0–5, 7–9, 11, 13–22 (any other key is
         * {@code invalidField}), no duplicate key, keys 0–2 required, and the set-like fields 4, 5, 9, 13, 14, 18,
         * 19, 20 non-empty when present ({@code fieldGuarded}); the donation must be non-zero
         * ({@code decodePositiveCoin}).
         */
        private void readBody() {
            CborReader reader = new CborReader(txCbor, body);
            long entries = reader.readMapHeader();
            for (long i = 0; reader.hasNext(entries, i); i++) {
                long key = reader.readUnsignedLong();
                if (!BODY_KEYS.contains((int) key) || key > 22) {
                    throw new TxDecodingException("unknown transaction body key " + key);
                }
                CborSlice value = reader.readItem();
                if (bodyFields.put((int) key, value) != null) {
                    throw new TxDecodingException("duplicate transaction body key " + key);
                }
            }
            for (int required : new int[]{BODY_INPUTS, BODY_OUTPUTS, BODY_FEE}) {
                if (!bodyFields.containsKey(required)) {
                    throw new TxDecodingException("transaction body has no key " + required);
                }
            }
            inputs = readInputs(bodyFields.get(BODY_INPUTS), false);
            collateralInputs = optional(BODY_COLLATERAL)
                    ? readInputs(bodyFields.get(BODY_COLLATERAL), true) : List.of();
            referenceInputs = optional(BODY_REFERENCE_INPUTS)
                    ? readInputs(bodyFields.get(BODY_REFERENCE_INPUTS), true) : List.of();
            readOutputs();
            fee = field(BODY_FEE).readUnsigned();
            ttl = optional(BODY_TTL) ? field(BODY_TTL).readUnsigned() : null;
            validityStart = optional(BODY_VALIDITY_START) ? field(BODY_VALIDITY_START).readUnsigned() : null;
            if (optional(BODY_WITHDRAWALS)) {
                // Withdrawals: a map without duplicate keys (decodeMap at version 9, Decoder.hs:810-830) of
                // account addresses (decodeAccountAddressT, Address.hs:938-955: header & 0xEE == 0xE0, 28-byte hash).
                CborReader w = field(BODY_WITHDRAWALS);
                long count = w.readMapHeader();
                Set<String> seen = new HashSet<>();
                for (long i = 0; w.hasNext(count, i); i++) {
                    byte[] account = w.readDefiniteBytes();
                    if (account.length != 29 || (account[0] & 0xee) != 0xe0) {
                        throw new TxDecodingException("withdrawal key is not an account address");
                    }
                    if (!seen.add(HexUtil.encodeHexString(account))) {
                        throw new TxDecodingException("duplicate withdrawal key " + HexUtil.encodeHexString(account));
                    }
                    withdrawals.add(new Withdrawal(account, w.readUnsigned()));
                }
                nonEmpty(withdrawals.isEmpty(), "Withdrawals");
            }
            if (optional(BODY_MINT)) {
                mint = MultiAssets.read(field(BODY_MINT), true);
                nonEmpty(mint.isEmpty(), "Mint");
            }
            if (optional(BODY_NETWORK_ID)) {
                long network = field(BODY_NETWORK_ID).readUnsignedLong();
                if (network > 1) {
                    throw new TxDecodingException("network id " + network);
                }
                networkId = (int) network;
            }
            if (optional(BODY_AUX_DATA_HASH)) {
                auxDataHash = hash(field(BODY_AUX_DATA_HASH).readDefiniteBytes(), 32, "auxiliary data hash");
            }
            if (optional(BODY_SCRIPT_DATA_HASH)) {
                scriptDataHash = hash(field(BODY_SCRIPT_DATA_HASH).readDefiniteBytes(), 32, "script integrity hash");
            }
            if (optional(BODY_REQUIRED_SIGNERS)) {
                CborReader r = field(BODY_REQUIRED_SIGNERS);
                r.skipTag(SET_TAG);
                long count = r.readArrayHeader();
                Set<String> seen = new HashSet<>();
                int n = 0;
                for (; r.hasNext(count, n); n++) {
                    byte[] signer = hash(r.readDefiniteBytes(), 28, "required signer");
                    if (!seen.add(HexUtil.encodeHexString(signer))) {
                        throw new TxDecodingException("duplicate required signer");
                    }
                    requiredSigners.add(signer);
                }
                nonEmpty(n == 0, "Required Signer Hashes");
            }
            if (optional(BODY_COLLATERAL_RETURN)) {
                CborReader r = field(BODY_COLLATERAL_RETURN);
                collateralReturn = RawOutput.read(r, txCbor, Math.min(outputs.size(), 0xFFFF), true);
            }
            totalCollateral = optional(BODY_TOTAL_COLLATERAL) ? field(BODY_TOTAL_COLLATERAL).readUnsigned() : null;
            currentTreasuryValue = optional(BODY_CURRENT_TREASURY_VALUE)
                    ? field(BODY_CURRENT_TREASURY_VALUE).readUnsigned() : null;
            donation = optional(BODY_DONATION) ? field(BODY_DONATION).readUnsigned() : BigInteger.ZERO;
            if (optional(BODY_DONATION) && donation.signum() == 0) {
                throw new TxDecodingException("TxBody: 'Treasury Donation' must be non-zero when supplied");
            }
            if (optional(BODY_CERTS)) {
                // An OSet: decodeOSet rejects two equal certificates (decodeSetLikeEnforceNoDuplicates).
                CborReader c = field(BODY_CERTS);
                c.skipTag(SET_TAG);
                long count = c.readArrayHeader();
                Set<String> seen = new HashSet<>();
                for (int i = 0; c.hasNext(count, i); i++) {
                    int start = c.position();
                    RawCertificate cert = RawCertificate.read(c, i);
                    if (!seen.add(key(new CborSlice(start, c.position()), cert.tag() == 3 ? 7 : -1, -1))) {
                        throw new TxDecodingException("duplicate certificate " + i);
                    }
                    certificates.add(cert);
                }
                nonEmpty(certificates.isEmpty(), "Certificates");
            }
            if (optional(BODY_VOTING_PROCEDURES)) {
                // voting_procedures = {+ voter => {+ gov_action_id => voting_procedure}}; decodeMap rejects
                // duplicate voters from version 9.
                CborReader v = field(BODY_VOTING_PROCEDURES);
                long count = v.readMapHeader();
                TreeSet<RawVoter> seen = new TreeSet<>();
                for (long i = 0; v.hasNext(count, i); i++) {
                    RawVoter voter = RawVoter.read(v);
                    if (!seen.add(voter)) {
                        throw new TxDecodingException("duplicate voter " + voter);
                    }
                    // {+ gov_action_id => voting_procedure}, voting_procedure = [vote, anchor / null]: a non-empty map
                    // without duplicate action ids (Procedures.hs:408-416), the vote 0–2 (decodeEnumBounded), the
                    // anchor with its bounds.
                    long actions = v.readMapHeader();
                    TreeMap<GovActionId, Integer> ids = new TreeMap<>(GOV_ACTION_ID_ORDER);
                    for (long j = 0; v.hasNext(actions, j); j++) {
                        GovActionId id = govActionId(v);
                        if (ids.containsKey(id)) {
                            throw new TxDecodingException("duplicate governance action id " + id + " for " + voter);
                        }
                        long fields = v.readArrayHeader();
                        if (fields != 2 && fields != CborReader.INDEFINITE) {
                            throw new TxDecodingException("a voting procedure has 2 elements");
                        }
                        long vote = v.readUnsignedLong();
                        if (vote > 2) {
                            throw new TxDecodingException("unknown vote " + vote);
                        }
                        ids.put(id, (int) vote);
                        BoundedFields.anchorOrNull(v, "vote");
                        if (fields == CborReader.INDEFINITE && v.hasNext(fields, 2)) {
                            throw new TxDecodingException("a voting procedure has 2 elements");
                        }
                    }
                    if (ids.isEmpty()) {
                        throw new TxDecodingException("VotingProcedures require votes, but Voter: " + voter
                                + " didn't have any");
                    }
                    votes.put(voter, Collections.unmodifiableSortedMap(ids));
                }
                nonEmpty(seen.isEmpty(), "VotingProcedures");
                voters.addAll(seen);
            }
            if (optional(BODY_PROPOSAL_PROCEDURES)) {
                // An OSet too: two equal proposal procedures do not decode.
                CborReader p = field(BODY_PROPOSAL_PROCEDURES);
                p.skipTag(SET_TAG);
                long count = p.readArrayHeader();
                Set<String> seen = new HashSet<>();
                for (int i = 0; p.hasNext(count, i); i++) {
                    int start = p.position();
                    RawProposal proposal = RawProposal.read(p, i);
                    // [deposit, account, gov_action, anchor]; an update-committee action [4, prev, set, map, q]
                    // holds a set at position 2
                    if (!seen.add(key(new CborSlice(start, p.position()), -1, proposal.actionTag() == 4 ? 2 : -1))) {
                        throw new TxDecodingException("duplicate proposal procedure " + i);
                    }
                    proposals.add(proposal);
                }
                proposalCount = proposals.size();
                nonEmpty(proposalCount == 0, "ProposalProcedures");
            }
        }

        /**
         * The equality key of a decoded certificate or proposal ({@link CborCanonical}): its canonical encoding,
         * with an untagged set sorted too — at position {@code setAt} of the item (a pool registration's owners), or
         * at position {@code actionSetAt} of the proposal's governance action (an update committee's removals).
         */
        private String key(CborSlice slice, int setAt, int actionSetAt) {
            if (setAt < 0 && actionSetAt < 0) {
                return HexUtil.encodeHexString(CborCanonical.of(txCbor, slice));
            }
            StringBuilder key = new StringBuilder();
            CborReader r = new CborReader(txCbor, slice);
            long n = r.readArrayHeader();
            for (int i = 0; r.hasNext(n, i); i++) {
                CborSlice element = r.readItem();
                if (i == setAt) {
                    key.append(HexUtil.encodeHexString(CborCanonical.set(txCbor, element)));
                } else if (i == 2 && actionSetAt >= 0) {
                    CborReader a = new CborReader(txCbor, element);
                    long m = a.readArrayHeader();
                    for (int j = 0; a.hasNext(m, j); j++) {
                        CborSlice part = a.readItem();
                        key.append(HexUtil.encodeHexString(j == actionSetAt ? CborCanonical.set(txCbor, part)
                                : CborCanonical.of(txCbor, part))).append(',');
                    }
                } else {
                    key.append(HexUtil.encodeHexString(CborCanonical.of(txCbor, element)));
                }
                key.append('|');
            }
            return key.toString();
        }

        /** {@code gov_action_id = [transaction_id, Word16]}. */
        private static GovActionId govActionId(CborReader reader) {
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

        private static void nonEmpty(boolean empty, String field) {
            if (empty) {
                throw new TxDecodingException("TxBody: '" + field + "' must be non-empty when supplied");
            }
        }

        private static byte[] hash(byte[] value, int length, String what) {
            if (value.length != length) {
                throw new TxDecodingException(what + " of " + value.length + " bytes, expected " + length);
            }
            return value;
        }

        private boolean optional(int key) {
            return bodyFields.containsKey(key);
        }

        private CborReader field(int key) {
            return new CborReader(txCbor, bodyFields.get(key));
        }

        private List<TxInRef> readInputs(CborSlice slice, boolean nonEmpty) {
            CborReader reader = new CborReader(txCbor, slice);
            reader.skipTag(SET_TAG);
            long count = reader.readArrayHeader();
            List<TxInRef> result = new ArrayList<>();
            TreeSet<TxInRef> seen = new TreeSet<>();
            for (long i = 0; reader.hasNext(count, i); i++) {
                long elements = reader.readArrayHeader();
                if (elements != 2 && elements != CborReader.INDEFINITE) {
                    throw new TxDecodingException("a transaction input is a two-element array");
                }
                byte[] id = reader.readDefiniteBytes();
                if (id.length != TX_ID_LENGTH) {
                    throw new TxDecodingException("transaction id of " + id.length + " bytes");
                }
                long index = reader.readUnsignedLong();
                if (index > 0xFFFF) {
                    throw new TxDecodingException("output index " + index + " exceeds Word16");
                }
                if (elements == CborReader.INDEFINITE && reader.hasNext(elements, 2)) {
                    throw new TxDecodingException("a transaction input is a two-element array");
                }
                TxInRef in = new TxInRef(HexUtil.encodeHexString(id), (int) index);
                if (!seen.add(in)) {
                    throw new TxDecodingException("duplicate input " + in);
                }
                result.add(in);
            }
            if (nonEmpty && result.isEmpty()) {
                throw new TxDecodingException("TxBody: an input set field must be non-empty when supplied");
            }
            return result;
        }

        private void readOutputs() {
            CborReader reader = field(BODY_OUTPUTS);
            long count = reader.readArrayHeader();
            for (int i = 0; reader.hasNext(count, i); i++) {
                outputs.add(RawOutput.read(reader, txCbor, i, false));
            }
        }

        private void readWitnessSet() {
            CborReader reader = new CborReader(txCbor, witnessSet);
            long entries = reader.readMapHeader();
            for (long i = 0; reader.hasNext(entries, i); i++) {
                long key = reader.readUnsignedLong();
                // AlonzoTxWits: keys 0-7, anything else is invalidField (Alonzo/TxWits.hs:650-675)
                if (key > WITNESS_PLUTUS_V3) {
                    throw new TxDecodingException("unknown witness set key " + key);
                }
                CborSlice value = reader.readItem();
                if (witnessFields.put((int) key, value) != null) {
                    throw new TxDecodingException("duplicate witness set key " + key);
                }
            }
            // Version 9+: vkey and bootstrap witnesses, native scripts and datums are non-empty lists or tag-258
            // sets (addrWitsSetDecoder, nativeScriptsDecoder, TxDatsRaw: decodeNonEmptyList; TxWits.hs:613-700, 334-352).
            for (int key : new int[]{WITNESS_VKEYS, WITNESS_NATIVE_SCRIPTS, WITNESS_BOOTSTRAP, WITNESS_DATUMS}) {
                if (witnessFields.containsKey(key)) {
                    CborReader list = new CborReader(txCbor, witnessFields.get(key));
                    list.skipTag(SET_TAG);
                    long length = list.readArrayHeader();
                    if (length == 0 || (length == CborReader.INDEFINITE && !list.hasNext(length, 0))) {
                        throw new TxDecodingException("witness set key " + key + " is an empty list");
                    }
                }
            }
            if (witnessFields.containsKey(WITNESS_VKEYS)) {
                forEachWitness(WITNESS_VKEYS, w -> vkeyWitnesses.add(new VKeyWitness(
                        checkLength(w.readDefiniteBytes(), VKEY_LENGTH, "vkey"),
                        checkLength(w.readDefiniteBytes(), SIGNATURE_LENGTH, "vkey witness signature"))));
            }
            if (witnessFields.containsKey(WITNESS_BOOTSTRAP)) {
                // The chain code is checked to be 32 bytes only from protocol version 12 (Keys/Bootstrap.hs:72-78).
                forEachWitness(WITNESS_BOOTSTRAP, w -> bootstrapWitnesses.add(new BootstrapWitness(
                        checkLength(w.readDefiniteBytes(), VKEY_LENGTH, "bootstrap witness vkey"),
                        checkLength(w.readDefiniteBytes(), SIGNATURE_LENGTH, "bootstrap witness signature"),
                        w.readDefiniteBytes(),
                        w.readDefiniteBytes())));
            }
            if (witnessFields.containsKey(WITNESS_NATIVE_SCRIPTS)) {
                // nativeScriptsDecoder at version 9: a non-empty list (duplicates collapse in Map.fromList).
                CborReader list = new CborReader(txCbor, witnessFields.get(WITNESS_NATIVE_SCRIPTS));
                list.skipTag(SET_TAG);
                long count = list.readArrayHeader();
                for (long i = 0; list.hasNext(count, i); i++) {
                    RawScript script = new RawScript(RawScript.NATIVE, list.copy(list.readItem()));
                    script.timelock();
                    witnessScripts.add(script);
                }
            }
            readPlutusScripts(WITNESS_PLUTUS_V1, RawScript.PLUTUS_V1);
            readPlutusScripts(WITNESS_PLUTUS_V2, RawScript.PLUTUS_V2);
            readPlutusScripts(WITNESS_PLUTUS_V3, RawScript.PLUTUS_V3);
            if (witnessFields.containsKey(WITNESS_DATUMS)) {
                CborReader list = new CborReader(txCbor, witnessFields.get(WITNESS_DATUMS));
                list.skipTag(SET_TAG);
                long count = list.readArrayHeader();
                for (long i = 0; list.hasNext(count, i); i++) {
                    byte[] datum = list.copy(list.readItem());
                    PlutusData.validate(datum); // DecCBOR (PlutusData era) = Cborg.decode (Plutus/Data.hs:99-103)
                    datumHashes.add(Hashes.blake2b256(datum));
                }
            }
            if (witnessFields.containsKey(WITNESS_REDEEMERS)) {
                readRedeemers(new CborReader(txCbor, witnessFields.get(WITNESS_REDEEMERS)));
            }
        }

        /**
         * {@code scriptDecoderV9} (Alonzo/TxWits.hs:741-751): a list or tag-258 set of Plutus binaries, not empty,
         * with no two scripts of the same hash ({@code decodeMapLikeEnforceNoDuplicates}).
         */
        private void readPlutusScripts(int key, int language) {
            if (!witnessFields.containsKey(key)) {
                return;
            }
            CborReader list = new CborReader(txCbor, witnessFields.get(key));
            list.skipTag(SET_TAG);
            long count = list.readArrayHeader();
            Set<String> seen = new HashSet<>();
            int n = 0;
            for (; list.hasNext(count, n); n++) {
                RawScript script = new RawScript(language, list.readDefiniteBytes());
                if (!seen.add(script.hashHex())) {
                    throw new TxDecodingException("duplicate PlutusV" + language + " script " + script.hashHex());
                }
                witnessScripts.add(script);
            }
            if (n == 0) {
                throw new TxDecodingException("Empty list of scripts is not allowed");
            }
        }

        /**
         * Haskell decodes VKeys and signatures at their fixed sizes; any other length is a decoding failure
         * ({@code VKey}/{@code SignedDSIGN} {@code DecCBOR}; Amaru scenarios 00077, 00078, 00080).
         */
        private static byte[] checkLength(byte[] value, int expected, String what) {
            if (value.length != expected) {
                throw new TxDecodingException(what + " of " + value.length + " bytes, expected " + expected);
            }
            return value;
        }

        private void forEachWitness(int key, Consumer<CborReader> body) {
            CborReader reader = new CborReader(txCbor, witnessFields.get(key));
            reader.skipTag(SET_TAG);
            long count = reader.readArrayHeader();
            for (long i = 0; reader.hasNext(count, i); i++) {
                long elements = reader.readArrayHeader();
                int start = reader.position();
                body.accept(reader);
                if (elements == CborReader.INDEFINITE && reader.hasNext(elements, 99)) {
                    throw new TxDecodingException("witness at " + start + " has extra elements");
                }
            }
        }

        /**
         * {@code RedeemersRaw} at version 9+ (Alonzo/TxWits.hs:548-598): the map form or the list form, never empty
         * ({@code "Expected redeemers map to be non-empty"}, {@code decodeNonEmptyList}); both end in
         * {@code Map.fromList} of the entries in encoded order (the map form reverses its accumulator first), so a
         * later duplicate key replaces an earlier one. A key is a {@code Word8} tag 0–5 and a {@code Word32}
         * index.
         */
        private void readRedeemers(CborReader reader) {
            TreeMap<Long, RawRedeemer> byKey = new TreeMap<>();
            int count = 0;
            if (reader.peekMajor() == 5) {
                long entries = reader.readMapHeader();
                for (long i = 0; reader.hasNext(entries, i); i++) {
                    long keyLength = reader.readArrayHeader();
                    if (keyLength != 2 && keyLength != CborReader.INDEFINITE) {
                        throw new TxDecodingException("a redeemer key is [tag, index]");
                    }
                    long tag = reader.readUnsignedLong();
                    long index = reader.readUnsignedLong();
                    if (keyLength == CborReader.INDEFINITE && reader.hasNext(keyLength, 2)) {
                        throw new TxDecodingException("a redeemer key is [tag, index]");
                    }
                    long valueLength = reader.readArrayHeader();
                    if (valueLength != 2 && valueLength != CborReader.INDEFINITE) {
                        throw new TxDecodingException("a redeemer value is [data, ex_units]");
                    }
                    CborSlice data = reader.readItem();
                    PlutusData.validate(reader.copy(data)); // data: plutus-core's decodeData
                    RawRedeemer redeemer = readExUnits(reader, tag, index, data);
                    if (valueLength == CborReader.INDEFINITE && reader.hasNext(valueLength, 2)) {
                        throw new TxDecodingException("a redeemer value is [data, ex_units]");
                    }
                    byKey.put(key(tag, index), redeemer);
                    count++;
                }
            } else {
                long length0 = reader.readArrayHeader();
                for (long i = 0; reader.hasNext(length0, i); i++) {
                    long length = reader.readArrayHeader();
                    if (length != 4 && length != CborReader.INDEFINITE) {
                        throw new TxDecodingException("a redeemer is [tag, index, data, ex_units]");
                    }
                    long tag = reader.readUnsignedLong();
                    long index = reader.readUnsignedLong();
                    CborSlice data = reader.readItem();
                    PlutusData.validate(reader.copy(data)); // data: plutus-core's decodeData
                    RawRedeemer redeemer = readExUnits(reader, tag, index, data);
                    if (length == CborReader.INDEFINITE && reader.hasNext(length, 4)) {
                        throw new TxDecodingException("a redeemer is [tag, index, data, ex_units]");
                    }
                    byKey.put(key(tag, index), redeemer);
                    count++;
                }
            }
            if (count == 0) {
                throw new TxDecodingException("Expected redeemers to be non-empty");
            }
            redeemers.addAll(byKey.values());
        }

        private static long key(long tag, long index) {
            if (tag > 5) {
                throw new TxDecodingException("redeemer tag " + tag);
            }
            if (index > 0xFFFF_FFFFL) {
                throw new TxDecodingException("redeemer index " + index + " exceeds Word32");
            }
            return RawRedeemer.key((int) tag, index);
        }

        private static RawRedeemer readExUnits(CborReader reader, long tag, long index, CborSlice data) {
            long length = reader.readArrayHeader();
            BigInteger mem = reader.readUnsigned();
            BigInteger steps = reader.readUnsigned();
            if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
                throw new TxDecodingException("ex_units is [mem, steps]");
            }
            return new RawRedeemer((int) tag, index, mem, steps, data);
        }
    }
}
