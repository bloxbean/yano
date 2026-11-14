package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawExUnits;
import com.bloxbean.cardano.client.transaction.raw.RawInput;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.client.transaction.raw.RawTxBody;
import com.bloxbean.cardano.client.transaction.raw.RawVotingProcedures;
import com.bloxbean.cardano.client.transaction.raw.RawWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.view.model.GovActionId;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A Conway transaction read from its <em>original</em> bytes (ADR-056 Phase 3a).
 *
 * <p>The bytes are read with CCL's raw view ({@link RawTx}): the body, the witness set, the auxiliary data and
 * every field are spans of the received transaction, so everything the rules hash or size comes from the original
 * bytes, never from a re-serialisation: the transaction id ({@link #txId()}, blake2b-256 of the body bytes), the size
 * ({@link #size()}), each output's encoded size, the witness-set fields (redeemers, datums) and the auxiliary data.
 * Over those spans the transaction is walked by CCL with {@link HaskellPolicy} and read through CCL's views, so the
 * checks see exactly what Haskell decodes (for example whether a validity bound is present at all) and bytes Haskell
 * would not decode are a {@link TxDecodingException}. The decoded CCL {@link Transaction} is kept for the structure the
 * rules do not need byte-exact.</p>
 *
 * <p>Definite and indefinite encodings and tag-258 sets are accepted wherever Conway accepts them; redeemers
 * may be the list form or the Conway map form.</p>
 */
public final class RawTransaction {

    /** Haskell's derived {@code Ord GovActionId}: the transaction id's bytes, then the index. */
    public static final Comparator<GovActionId> GOV_ACTION_ID_ORDER =
            Comparator.comparing(GovActionId::txHashHex).thenComparingInt(GovActionId::index);

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
    private final RawTx tx;
    private final byte[] txId;
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
    private final List<RawScript> auxScripts;
    private final Transaction decoded;

    private RawTransaction(Builder b) {
        this.txCbor = b.txCbor;
        this.tx = b.tx;
        this.txId = tx.txId();
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
        this.auxScripts = List.copyOf(b.auxScripts);
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
        try {
            b.read();
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
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

    /** @return CCL's view of the transaction's original bytes */
    public RawTx rawTx() {
        return tx;
    }

    /** @return the body, as encoded */
    public CborSpan body() {
        return tx.body();
    }

    /** @return the witness set, as encoded */
    public CborSpan witnessSet() {
        return tx.witnessSet();
    }

    /** @return the auxiliary data, as encoded, or null when the transaction has none ({@code null}) */
    public CborSpan auxData() {
        return tx.auxData().orElse(null);
    }

    /** @return the {@code is_valid} flag (Haskell {@code IsPhase2Valid}) */
    public boolean isValid() {
        return tx.isValid();
    }

    /**
     * Haskell's transaction size ({@code sizeTxF}, Conway/Tx.hs:86 → {@code sizeAlonzoTxF}, Alonzo/Tx.hs:324-331):
     * the length of {@code toCBORForSizeComputation} (Alonzo/Tx.hs:432-443), a three-element list header, then
     * the body, the witness set and the auxiliary data (or {@code null}) with their stored bytes; the
     * {@code is_valid} flag is not counted. Same as {@code YanoTransactionSizeValidator.haskellSize}
     * (scalus-bridge).
     */
    public int size() {
        CborSpan auxData = auxData();
        return 1 + body().length() + witnessSet().length() + (auxData == null ? 1 : auxData.length());
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

    /** @return the auxiliary data's scripts ({@code RawTx.auxScripts()}), in encoded order; empty without any */
    public List<RawScript> auxScripts() {
        return auxScripts;
    }

    /** Parser state. */
    private static final class Builder {
        /** The era the transaction is read in: its decoder ({@link HaskellPolicy#forEra}) and CCL's views. */
        private static final Era ERA = Era.Conway;

        private final byte[] txCbor;
        private final Transaction decoded;
        private RawTx tx;
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
        private final List<RawScript> auxScripts = new ArrayList<>();
        private byte[] auxDataHash;
        private byte[] scriptDataHash;

        Builder(byte[] txCbor, Transaction decoded) {
            this.txCbor = txCbor;
            this.decoded = decoded;
        }

        /** {@code [body, witness_set, is_valid, auxiliary_data / null]}, read with CCL's view. */
        void read() {
            if (txCbor.length == 0) {
                throw new TxDecodingException("empty transaction bytes");
            }
            tx = RawTx.of(txCbor);
            HaskellPolicy.forEra(ERA).walk(tx);
            readBody();
            readWitnessSet();
            tx.auxData(ERA).ifPresent(auxData -> auxData.scripts()
                    .forEach(script -> auxScripts.add(RawScript.accepted(script))));
        }

        /**
         * The body through CCL's view, which {@link HaskellPolicy} has walked (Conway's body decoder at protocol
         * version 9+, {@code ConwayTxBodyRaw} {@code DecCBOR}, Conway/TxBody.hs:180-265).
         */
        private void readBody() {
            RawTxBody body = tx.body(ERA);
            inputs = inputs(body.inputs());
            collateralInputs = body.collateral().map(Builder::inputs).orElse(List.of());
            referenceInputs = body.referenceInputs().map(Builder::inputs).orElse(List.of());
            for (var output : body.outputs()) {
                outputs.add(RawOutput.accepted(output, outputs.size(), false));
            }
            fee = body.fee().orElseThrow();
            ttl = unsigned(body.ttl());
            validityStart = unsigned(body.validityStart());
            body.withdrawals().forEach(w -> withdrawals.add(new Withdrawal(w.rewardAccount(), w.coin())));
            mint = MultiAssets.of(body.mint());
            OptionalInt network = body.networkId();
            networkId = network.isPresent() ? network.getAsInt() : null;
            auxDataHash = body.auxDataHash().orElse(null);
            scriptDataHash = body.scriptDataHash().orElse(null);
            body.requiredSigners().ifPresent(requiredSigners::addAll);
            collateralReturn = body.collateralReturn()
                    .map(output -> RawOutput.accepted(output, Math.min(outputs.size(), 0xFFFF), true)).orElse(null);
            totalCollateral = body.totalCollateral().orElse(null);
            currentTreasuryValue = body.currentTreasury().orElse(null);
            donation = body.donation().orElse(BigInteger.ZERO);
            for (var certificate : body.certificates()) {
                certificates.add(RawCertificate.accepted(certificate, certificates.size()));
            }
            body.votingProcedures().ifPresent(this::readVotes);
            body.proposalProcedures().ifPresent(views -> views
                    .forEach(view -> proposals.add(RawProposal.accepted(view, proposals.size()))));
            proposalCount = proposals.size();
        }

        private static BigInteger unsigned(OptionalLong value) {
            return value.isPresent() ? new BigInteger(Long.toUnsignedString(value.getAsLong())) : null;
        }

        private static List<TxInRef> inputs(List<RawInput> views) {
            List<TxInRef> result = new ArrayList<>(views.size());
            for (RawInput input : views) {
                result.add(new TxInRef(HexUtil.encodeHexString(input.transactionId()), input.index()));
            }
            return result;
        }

        /** Each voter's votes, voters in {@code Ord Voter} order and each one's actions in {@code Ord GovActionId}. */
        private void readVotes(RawVotingProcedures view) {
            for (RawVotingProcedures.Votes voter : view.voters()) {
                TreeMap<GovActionId, Integer> ids = new TreeMap<>(GOV_ACTION_ID_ORDER);
                voter.votes().forEach(vote -> ids.put(RawProposal.govActionId(vote.getKey()), vote.getValue().vote()));
                votes.put(RawVoter.of(voter.voter()), Collections.unmodifiableSortedMap(ids));
            }
            voters.addAll(votes.keySet());
        }

        /**
         * The witness set through CCL's view, which {@link HaskellPolicy} has walked ({@code AlonzoTxWitsRaw} at
         * protocol version 9+, Alonzo/TxWits.hs:603-700).
         */
        private void readWitnessSet() {
            RawWitnessSet witnesses = tx.witnessSet(ERA);
            witnesses.vkeyWitnesses().ifPresent(views -> views
                    .forEach(w -> vkeyWitnesses.add(new VKeyWitness(w.vkey(), w.signature()))));
            witnesses.bootstrapWitnesses().ifPresent(views -> views.forEach(w -> bootstrapWitnesses
                    .add(new BootstrapWitness(w.publicKey(), w.signature(), w.chainCode(), w.attributes()))));
            witnesses.scripts().forEach(script -> witnessScripts.add(RawScript.accepted(script)));
            witnesses.datums().ifPresent(views -> views.forEach(datum -> datumHashes.add(datum.hash())));
            witnesses.redeemers().ifPresent(this::readRedeemers);
        }

        /**
         * Haskell's {@code Redeemers}, of either form, is {@code Map.fromList} of the entries in encoded order (the
         * map form reverses its accumulator first, Alonzo/TxWits.hs:552-590), so a later duplicate key replaces an
         * earlier one.
         */
        private void readRedeemers(List<com.bloxbean.cardano.client.transaction.raw.RawRedeemer> views) {
            TreeMap<Long, RawRedeemer> byKey = new TreeMap<>();
            for (var view : views) {
                RawExUnits units = view.units();
                RawRedeemer redeemer = new RawRedeemer(view.tag(), view.index(), units.mem(), units.steps(),
                        view.data());
                byKey.put(redeemer.key(), redeemer);
            }
            redeemers.addAll(byKey.values());
        }
    }
}
