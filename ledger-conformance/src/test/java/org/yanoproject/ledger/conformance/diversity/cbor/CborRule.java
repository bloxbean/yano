package org.yanoproject.ledger.conformance.diversity.cbor;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.Relay;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.yaci.core.model.serializers.AuxDataSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.StakeCredentialSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionBodySerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionInputSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionOutputSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.WitnessesSerializer;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.StrictElements;
import org.yanoproject.runtime.sync.validation.ShelleyHeaderView;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.deserializeOne;

/**
 * How Yano decodes and re-encodes one cardano-cbor-dataset rule (ADR-060 §6).
 *
 * <p>The node decodes a transaction twice: CCL's model decoding ({@code Transaction.deserialize}), which the validation
 * engine runs first and whose model the rules read, and Yano's strict reader ({@link StrictElements}), which applies
 * Haskell's decoding rules. {@link #strict} and {@link #library} are those two decoders for one item; either one
 * rejecting is the node rejecting. Apart, they show which side a difference from Haskell comes from. An item the node
 * only reads inside a transaction, an output, a proposal or a parameter update is placed in a minimal, valid one
 * first.</p>
 *
 * <p>{@link #encoder} re-encodes the decoded model (CCL's, or Yano's for {@code datum_option}). Without one, the rule
 * re-encodes as its original bytes, as Haskell's {@code MemoBytes} does and as the node keeps them.</p>
 *
 * @param name    the corpus rule
 * @param exact   whether the dataset compares the bytes exactly ({@code mkByteExactRule} in its {@code EraSpec.hs});
 *                otherwise both sides are normalized
 * @param strict  Yano's strict reading; rejects by throwing
 * @param library CCL's model decoding, or for headers yaci's (the only header decoding with header validation off,
 *                the default); rejects by throwing
 * @param encoder the re-encoding, from both decodings
 */
record CborRule(String name, boolean exact, Decoder strict, Decoder library, Encoder encoder) {

    @FunctionalInterface
    interface Decoder {
        Object decode(byte[] cbor) throws Exception;
    }

    @FunctionalInterface
    interface Encoder {
        /** @return a {@link DataItem} or the encoded bytes */
        Object encode(Object strict, Object library) throws Exception;
    }

    // Known-good parts of the wrappers.
    static final String HASH28 = "581c" + "00".repeat(28);
    static final String HASH32 = "5820" + "00".repeat(32);
    static final String ADDRESS = "581d60" + "00".repeat(28);
    static final String REWARD_ACCOUNT = "581de0" + "00".repeat(28);
    static final String INPUT = "82" + HASH32 + "00";
    static final String MIN_BODY_ENTRIES = "0081" + INPUT + "0180" + "0200";
    static final String MIN_BODY = "a3" + MIN_BODY_ENTRIES;
    private static final String KES_SIGNATURE = "5901c0" + "00".repeat(448);

    /** @return the rules this harness knows, by name */
    static Map<String, CborRule> all() {
        Map<String, CborRule> base = base();
        return Stream.concat(base.values().stream(), CborPlacedRules.all(base))
                .collect(Collectors.toMap(CborRule::name, Function.identity()));
    }

    /** @return the rules for items the node reads as they are, or with one wrapper */
    static Map<String, CborRule> base() {
        return Stream.of(
                inTransaction("auxiliary_data", cbor -> tx(hex(MIN_BODY), hex("a0"), cbor)),
                kept("block", cbor -> block(cbor, StrictElements::transaction),
                        cbor -> block(cbor, Transaction::deserialize)),
                model("certificate", reads(StrictElements::certificate),
                        cbor -> Certificate.deserialize((Array) item(cbor)), (s, c) -> ((Certificate) c).serialize()),
                model("cost_models", reads(cbor -> StrictElements.paramUpdate(cat("a112", cbor))),
                        cbor -> CostMdls.deserialize(item(cbor)), (s, c) -> ((CostMdls) c).serialize()),
                model("credential", reads(StrictElements::credential),
                        cbor -> StakeCredential.deserialize((Array) item(cbor)),
                        (s, c) -> ((StakeCredential) c).serialize()),
                model("datum_option", cbor -> StrictElements.output(datumOutput(cbor)),
                        cbor -> TransactionOutput.deserialize(item(datumOutput(cbor))),
                        (s, c) -> datumOption((RawOutput) s)),
                model("drep", reads(StrictElements::drep), cbor -> DRep.deserialize(item(cbor)),
                        (s, c) -> ((DRep) c).serialize()),
                model("gov_action",
                        reads(cbor -> StrictElements.proposal(cat("8400" + REWARD_ACCOUNT, cbor, "8260" + HASH32))),
                        cbor -> GovAction.deserialize((Array) item(cbor)), (s, c) -> ((GovAction) c).serialize()),
                hashed("header", CborRule::header, CborRule::yaciHeader),
                kept("header_body", cbor -> header(headerOf(cbor)), cbor -> yaciHeader(headerOf(cbor))),
                hashed("native_script", reads(StrictElements::nativeScript),
                        cbor -> NativeScript.deserialize((Array) item(cbor))),
                hashed("plutus_data", reads(StrictElements::plutusData), PlutusData::deserialize),
                model("proposal_procedure", reads(StrictElements::proposal),
                        cbor -> ProposalProcedure.deserialize(item(cbor)),
                        (s, c) -> ((ProposalProcedure) c).serialize()),
                model("protocol_param_update", reads(StrictElements::paramUpdate),
                        cbor -> ProtocolParamUpdate.deserialize(item(cbor)),
                        (s, c) -> ((ProtocolParamUpdate) c).serialize()),
                model("redeemer", cbor -> StrictElements.transaction(redeemerTx(cbor)), cbor -> {
                    Transaction.deserialize(redeemerTx(cbor));
                    return Redeemer.deserializePreConway((Array) item(cbor));
                }, (s, c) -> ((Redeemer) c).serializePreConway()),
                inTransaction("redeemers", cbor -> tx(hex(MIN_BODY), cat("a105", cbor), null)),
                model("relay", reads(StrictElements::relay), CborRule::relay, (s, c) -> ((Relay) c).serialize()),
                model("script", reads(StrictElements::script),
                        cbor -> ((Array) item(cbor)).getDataItems().getFirst().equals(new UnsignedInteger(0))
                                ? NativeScript.deserializeScriptRef(cbor) : PlutusScript.deserializeScriptRef(cbor),
                        (s, c) -> c instanceof NativeScript n ? n.scriptRefBytes()
                                : ((PlutusScript) c).scriptRefBytes()),
                kept("transaction", StrictElements::transaction, Transaction::deserialize),
                inTransaction("transaction_body", cbor -> tx(cbor, hex("a0"), null)),
                model("transaction_input", cbor -> StrictElements.transaction(inputTx(cbor)), cbor -> {
                    Transaction.deserialize(inputTx(cbor));
                    return TransactionInput.deserialize((Array) item(cbor));
                }, (s, c) -> ((TransactionInput) c).serialize()),
                model("transaction_output", StrictElements::output, cbor -> TransactionOutput.deserialize(item(cbor)),
                        (s, c) -> ((TransactionOutput) c).serializeInLedgerOrder()),
                inTransaction("transaction_witness_set", cbor -> tx(hex(MIN_BODY), cbor, null)),
                model("value", reads(StrictElements::value), cbor -> Value.deserialize(item(cbor)),
                        (s, c) -> ((Value) c).serializeInLedgerOrder()),
                model("voting_procedure", cbor -> StrictElements.transaction(voteTx(cbor)), cbor -> {
                    Transaction.deserialize(voteTx(cbor));
                    return VotingProcedure.deserialize((Array) item(cbor));
                }, (s, c) -> ((VotingProcedure) c).serialize())
        ).collect(Collectors.toMap(CborRule::name, Function.identity()));
    }

    /**
     * @return yaci's decoder for the rules it has one for, apart from headers, which {@link #library} already decodes
     *         with yaci. Sync decodes with yaci by default, so this gives the sync path a number. It is a diagnostic:
     *         the report measures transaction admission, which does not use these. As in sync, an item yaci recovers
     *         from (a block, witness set or auxiliary data with a datum or metadata it cannot parse) counts as
     *         accepted.
     */
    static Map<String, Decoder> yaciDecoders() {
        return Map.of(
                "auxiliary_data", AuxDataSerializer.INSTANCE::deserialize,
                "block", cbor -> BlockSerializer.INSTANCE.deserialize(cat("8207", cbor)),
                "certificate", cbor -> CertificateSerializer.INSTANCE.deserializeDI(deserializeOne(cbor)),
                "credential", cbor -> StakeCredentialSerializer.INSTANCE.deserializeDI(deserializeOne(cbor)),
                "transaction_body",
                cbor -> TransactionBodySerializer.INSTANCE.deserializeDI(deserializeOne(cbor), cbor),
                "transaction_input", cbor -> TransactionInputSerializer.INSTANCE.deserializeDI(deserializeOne(cbor)),
                "transaction_output", cbor -> TransactionOutputSerializer.INSTANCE.deserializeDI(deserializeOne(cbor)),
                "transaction_witness_set", WitnessesSerializer.INSTANCE::deserialize);
    }

    /** One item as both decoders read it. */
    record Decoded(byte[] cbor, Object strict, Object library) {
    }

    /** @return the item when both decoders accept it; an exception is a rejection */
    Decoded decode(byte[] cbor) throws Exception {
        return new Decoded(cbor, strict.decode(cbor), library.decode(cbor));
    }

    /**
     * @return the re-encoding of an accepted item. Not the canonical form, which would sort a Plutus {@code Data} map:
     *         that is an association list, whose order Haskell keeps. An exception here is an encoder failure, not a
     *         rejection.
     */
    byte[] encode(Decoded item) throws Exception {
        if (encoder == null) {
            return item.cbor();
        }
        Object encoded = encoder.encode(item.strict(), item.library());
        return encoded instanceof byte[] bytes ? bytes : CborSerializationUtil.serialize((DataItem) encoded, false);
    }

    static CborRule model(String name, Decoder strict, Decoder library, Encoder encoder) {
        return new CborRule(name, false, strict, library, encoder);
    }

    /** The dataset compares the bytes exactly, and the node keeps them. */
    private static CborRule hashed(String name, Decoder strict, Decoder library) {
        return new CborRule(name, true, strict, library, null);
    }

    /** The node keeps the bytes, and the dataset compares them normalized. */
    static CborRule kept(String name, Decoder strict, Decoder library) {
        return new CborRule(name, false, strict, library, null);
    }

    /** A hashed rule read inside (or as) a transaction: the strict reader and CCL's decoding of that transaction. */
    private static CborRule inTransaction(String name, Wrapper wrapper) {
        return hashed(name, cbor -> StrictElements.transaction(wrapper.wrap(cbor)),
                cbor -> Transaction.deserialize(wrapper.wrap(cbor)));
    }

    /** A strict reader that only accepts or rejects. */
    @FunctionalInterface
    interface Reader {
        void read(byte[] cbor) throws Exception;
    }

    static Decoder reads(Reader reader) {
        return cbor -> {
            reader.read(cbor);
            return null;
        };
    }

    @FunctionalInterface
    interface Wrapper {
        byte[] wrap(byte[] cbor);
    }

    /** CCL reads a relay only inside a pool registration. */
    static Relay relay(byte[] relay) throws Exception {
        return PoolRegistration.deserialize(item(cat("8a03" + HASH28 + HASH32 + "0000d81e820001" + REWARD_ACCOUNT
                + "8081", relay, "f6"))).getRelays().getFirst();
    }

    static byte[] datumOutput(byte[] datumOption) {
        return cat("a300" + ADDRESS + "0100" + "02", datumOption);
    }

    static byte[] redeemerTx(byte[] redeemer) {
        return tx(hex(MIN_BODY), cat("a10581", redeemer), null);
    }

    private static byte[] inputTx(byte[] input) {
        return tx(cat("a30081", input, "01800200"), hex("a0"), null);
    }

    static byte[] voteTx(byte[] votingProcedure) {
        return tx(cat("a4" + MIN_BODY_ENTRIES + "13a1" + "8200" + HASH28 + "a1" + INPUT, votingProcedure), hex("a0"),
                null);
    }

    /** A Conway block, as shadow sync reads one: CCL's raw view, then every transaction with {@code transaction}. */
    private static Object block(byte[] cbor, Decoder transaction) throws Exception {
        RawBlock block = RawBlock.of(cbor, Era.Conway);
        for (int i = 0; i < block.txCount(); i++) {
            transaction.decode(block.txBytes(i));
        }
        return block;
    }

    /**
     * A header as chain sync receives one (wrapped in a byte string): yaci's decoding, then the header checks of
     * upstream header validation ({@link ShelleyHeaderView}), which a node runs when header validation is on.
     */
    static Object header(byte[] cbor) throws Exception {
        byte[] wrapped = wrapped(cbor);
        return ShelleyHeaderView.from(BlockHeaderSerializer.INSTANCE.deserialize(wrapped), wrapped);
    }

    /** A header as chain sync decodes one with header validation off: yaci's decoding alone. */
    static Object yaciHeader(byte[] cbor) throws Exception {
        return BlockHeaderSerializer.INSTANCE.deserialize(wrapped(cbor));
    }

    private static byte[] wrapped(byte[] header) throws Exception {
        return CborSerializationUtil.serialize(new ByteString(header));
    }

    /** {@code [header_body, kes_signature]} */
    static byte[] headerOf(byte[] headerBody) {
        return cat(hex("82"), headerBody, hex(KES_SIGNATURE));
    }

    /**
     * {@code datum_option} from Yano's reading of it: {@code [0, hash]}, or {@code [1, #6.24(bytes)]} around the inline
     * datum's original bytes, which Yano keeps (as Haskell's {@code MemoBytes} does) and hashes.
     */
    static DataItem datumOption(RawOutput output) {
        Array option = new Array();
        if (output.datumHash() != null) {
            option.add(new UnsignedInteger(0));
            option.add(new ByteString(output.datumHash()));
        } else {
            option.add(new UnsignedInteger(1));
            ByteString inline = new ByteString(output.inlineDatum());
            inline.setTag(24);
            option.add(inline);
        }
        return option;
    }

    static DataItem item(byte[] cbor) {
        return CborSerializationUtil.deserialize(cbor);
    }

    /** {@code [body, witness_set, true, auxiliary_data / null]} */
    static byte[] tx(byte[] body, byte[] witnessSet, byte[] auxData) {
        return cat(hex("84"), body, witnessSet, auxData == null ? hex("f5f6") : cat(hex("f5"), auxData));
    }

    static byte[] cat(String prefix, byte[] item) {
        return cat(hex(prefix), item);
    }

    static byte[] cat(String prefix, byte[] item, String suffix) {
        return cat(hex(prefix), item, hex(suffix));
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : List.of(parts)) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
