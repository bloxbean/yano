package org.yanoproject.ledger.conformance.diversity.cbor;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.raw.FieldType;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.client.transaction.raw.RawInput;
import com.bloxbean.cardano.client.transaction.raw.RawOutput;
import com.bloxbean.cardano.client.transaction.raw.RecordSpec;
import com.bloxbean.cardano.client.transaction.raw.TxBodyField;
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
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionBodySerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionInputSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionOutputSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.WitnessesSerializer;
import org.yanoproject.ledger.rules.conway.tx.HaskellPolicy;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How Yano decodes and re-encodes one cardano-cbor-dataset rule (ADR-060 §6).
 *
 * <p>Whether an item decodes is {@link #strict}'s alone (ADR 0003 D9): CCL's reader with {@link HaskellPolicy}, read
 * by the rule's own type or record where it has one ({@link #reads}), so the report measures one reader and one policy.
 * A rule whose checks belong to its parent (a collection, a part of a transaction or of a header) is read in a minimal,
 * valid parent.</p>
 *
 * <p>{@link #library} is CCL's model of an accepted item, which {@link #encoder} re-encodes: a model's writer, as
 * Haskell's re-encoding sorts and folds what the original bytes keep. Without an encoder, the rule re-encodes as its
 * original bytes, as Haskell's {@code MemoBytes} does and as the node keeps them. On its own, the model's decoding is a
 * diagnostic: which items CCL's models and Yano's policy differ on.</p>
 *
 * @param name    the corpus rule
 * @param exact   whether the dataset compares the bytes exactly ({@code mkByteExactRule} in its {@code EraSpec.hs});
 *                otherwise both sides are normalized
 * @param strict  Yano's reading; rejects by throwing
 * @param library CCL's model decoding, or for headers yaci's (the only header decoding with header validation off,
 *                the default); rejects by throwing
 * @param encoder the re-encoding of the model
 */
record CborRule(String name, boolean exact, Decoder strict, Decoder library, Encoder encoder) {

    @FunctionalInterface
    interface Decoder {
        Object decode(byte[] cbor) throws Exception;
    }

    @FunctionalInterface
    interface Encoder {
        /** @return a {@link DataItem} or the encoded bytes */
        Object encode(Object model) throws Exception;
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
    /** A proposal procedure, an item of the body's set of them. */
    private static final RecordSpec PROPOSAL = TxBodyField.PROPOSAL_PROCEDURES.spec().record();

    /** @return the rules this harness knows, by name */
    static Map<String, CborRule> all() {
        Map<String, CborRule> base = base();
        return Stream.concat(base.values().stream(), CborPlacedRules.all(base))
                .collect(Collectors.toMap(CborRule::name, Function.identity()));
    }

    /** @return the rules for items the node reads as they are, or with one wrapper */
    static Map<String, CborRule> base() {
        return Stream.of(
                hashed("auxiliary_data", reads(FieldType.OPTIONAL_AUXILIARY_DATA),
                        cbor -> Transaction.deserialize(tx(hex(MIN_BODY), hex("a0"), cbor))),
                kept("block", cbor -> block(cbor, CborRule::transaction),
                        cbor -> block(cbor, Transaction::deserialize)),
                model("certificate", reads(CertKind.RECORD), cbor -> Certificate.deserialize((Array) item(cbor)),
                        c -> ((Certificate) c).serialize()),
                model("cost_models", reads(FieldType.COST_MODELS), cbor -> CostMdls.deserialize(item(cbor)),
                        c -> ((CostMdls) c).serialize()),
                model("credential", reads(FieldType.CREDENTIAL),
                        cbor -> StakeCredential.deserialize((Array) item(cbor)),
                        c -> ((StakeCredential) c).serialize()),
                kept("datum_option", reads(FieldType.DATUM_OPTION),
                        cbor -> TransactionOutput.deserialize(item(datumOutput(cbor)))),
                model("drep", reads(FieldType.DREP), cbor -> DRep.deserialize(item(cbor)), c -> ((DRep) c).serialize()),
                model("gov_action", reads(FieldType.GOV_ACTION), cbor -> GovAction.deserialize((Array) item(cbor)),
                        c -> ((GovAction) c).serialize()),
                hashed("header", CborRule::header, CborRule::yaciHeader),
                kept("header_body", cbor -> header(headerOf(cbor)), cbor -> yaciHeader(headerOf(cbor))),
                hashed("native_script", reads(FieldType.NATIVE_SCRIPT),
                        cbor -> NativeScript.deserialize((Array) item(cbor))),
                hashed("plutus_data", reads(FieldType.PLUTUS_DATA), PlutusData::deserialize),
                model("proposal_procedure", reads(PROPOSAL), cbor -> ProposalProcedure.deserialize(item(cbor)),
                        c -> ((ProposalProcedure) c).serialize()),
                model("protocol_param_update", reads(FieldType.PROTOCOL_PARAM_UPDATE),
                        cbor -> ProtocolParamUpdate.deserialize(item(cbor)),
                        c -> ((ProtocolParamUpdate) c).serialize()),
                model("redeemer", inTransaction(CborRule::redeemerTx),
                        cbor -> Redeemer.deserializePreConway((Array) item(cbor)),
                        c -> ((Redeemer) c).serializePreConway()),
                inTransaction("redeemers", cbor -> tx(hex(MIN_BODY), cat("a105", cbor), null)),
                model("relay", reads(FieldType.RELAY_LIST.record()), CborRule::relay, c -> ((Relay) c).serialize()),
                model("script", cbor -> RawScript.readScript(CborSpan.of(cbor)),
                        cbor -> ((Array) item(cbor)).getDataItems().getFirst().equals(new UnsignedInteger(0))
                                ? NativeScript.deserializeScriptRef(cbor) : PlutusScript.deserializeScriptRef(cbor),
                        c -> c instanceof NativeScript n ? n.scriptRefBytes() : ((PlutusScript) c).scriptRefBytes()),
                kept("transaction", CborRule::transaction, Transaction::deserialize),
                inTransaction("transaction_body", cbor -> tx(cbor, hex("a0"), null)),
                model("transaction_input", reads(TxBodyField.INPUTS.spec().record()),
                        cbor -> TransactionInput.deserialize((Array) item(cbor)),
                        c -> ((TransactionInput) c).serialize()),
                model("transaction_output", reads(FieldType.TRANSACTION_OUTPUT),
                        cbor -> TransactionOutput.deserialize(item(cbor)),
                        c -> ((TransactionOutput) c).serializeInLedgerOrder()),
                inTransaction("transaction_witness_set", cbor -> tx(hex(MIN_BODY), cbor, null)),
                model("value", reads(FieldType.VALUE), cbor -> Value.deserialize(item(cbor)),
                        c -> ((Value) c).serializeInLedgerOrder()),
                model("voting_procedure", reads(FieldType.VOTING_PROCEDURE),
                        cbor -> VotingProcedure.deserialize((Array) item(cbor)), c -> ((VotingProcedure) c).serialize())
        ).collect(Collectors.toMap(CborRule::name, Function.identity()));
    }

    /**
     * @return yaci's decoder for the rules it has one for, apart from headers, which {@link #library} already decodes
     *         with yaci. Sync decodes with yaci by default, so this gives the sync path a number. It is a diagnostic:
     *         the report measures transaction admission, which does not use these. As in sync, an item yaci recovers
     *         from (a block, witness set or auxiliary data with a datum or metadata it cannot parse) counts as
     *         accepted. Each is the reader sync uses: a credential has none, as yaci reads one only in its certificate.
     */
    static Map<String, Decoder> yaciDecoders() {
        return Map.of(
                "auxiliary_data", AuxDataSerializer.INSTANCE::deserialize,
                "block", cbor -> BlockSerializer.INSTANCE.deserialize(cat("8207", cbor)),
                "certificate", cbor -> CertificateSerializer.INSTANCE.read(CborSpan.of(cbor)),
                "transaction_body", cbor -> TransactionBodySerializer.INSTANCE.read(cbor, Era.Conway),
                "transaction_input", cbor -> TransactionInputSerializer.INSTANCE.read(RawInput.of(CborSpan.of(cbor))),
                "transaction_output",
                cbor -> TransactionOutputSerializer.INSTANCE.read(RawOutput.tolerant(CborSpan.of(cbor))),
                "transaction_witness_set", WitnessesSerializer.INSTANCE::deserialize);
    }

    /** Yano's reading of an item: an exception is a rejection. */
    void read(byte[] cbor) throws Exception {
        strict.decode(cbor);
    }

    /**
     * @return the re-encoding of an accepted item. Not the canonical form, which would sort a Plutus {@code Data} map:
     *         that is an association list, whose order Haskell keeps. An exception here, the model's decoding
     *         included, is an encoder failure, not a rejection.
     */
    byte[] encode(byte[] cbor) throws Exception {
        if (encoder == null) {
            return cbor;
        }
        Object encoded = encoder.encode(library.decode(cbor));
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

    /** A hashed rule read inside (or as) a transaction: Yano's reading and CCL's decoding of that transaction. */
    private static CborRule inTransaction(String name, Wrapper wrapper) {
        return hashed(name, inTransaction(wrapper), cbor -> Transaction.deserialize(wrapper.wrap(cbor)));
    }

    /** Yano's reading of a transaction, which a rule whose checks are a transaction's is placed in. */
    static Decoder inTransaction(Wrapper wrapper) {
        return cbor -> transaction(wrapper.wrap(cbor));
    }

    /**
     * {@code transaction}. The node also decodes it with CCL ({@code Transaction.deserialize}) and passes that model
     * along; the reader keeps it for structure only and decides nothing on it, so it is left out here.
     */
    static RawTransaction transaction(byte[] cbor) {
        return RawTransaction.parse(cbor, null);
    }

    /** The reader of a rule that is one type: {@link HaskellPolicy} on CCL's walk of it. */
    static Decoder reads(FieldType type) {
        return cbor -> {
            HaskellPolicy.CONWAY.walk(type, CborSpan.of(cbor));
            return null;
        };
    }

    /** The reader of a rule that is one record of CCL's tables. */
    static Decoder reads(RecordSpec record) {
        return cbor -> {
            HaskellPolicy.CONWAY.walk(record, CborSpan.of(cbor));
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

    /** A Conway block, as shadow sync reads one: CCL's raw view, then every transaction with {@code transaction}. */
    private static Object block(byte[] cbor, Decoder transaction) throws Exception {
        RawBlock block = RawBlock.of(cbor, Era.Conway);
        for (int i = 0; i < block.txCount(); i++) {
            transaction.decode(block.txBytes(i));
        }
        return block;
    }

    /** A header as upstream header validation reads one, when a node runs it: {@link HaskellPolicy#header}. */
    static Object header(byte[] cbor) {
        return HaskellPolicy.header(CborSpan.of(cbor));
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
