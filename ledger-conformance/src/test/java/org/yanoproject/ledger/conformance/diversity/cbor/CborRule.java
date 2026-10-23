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
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
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

/**
 * How Yano decodes and re-encodes one cardano-cbor-dataset rule (ADR-060 §6).
 *
 * <p>The node decodes a transaction twice: CCL's model decoding ({@code Transaction.deserialize}), which the validation
 * engine runs first and whose model the rules read, and Yano's strict reader ({@link StrictElements}), which applies
 * Haskell's decoding rules. {@link #strict} and {@link #ccl} are those two decoders for one item; either one rejecting
 * is the node rejecting. Apart, they show which side a difference from Haskell comes from. An item the node only reads
 * inside a transaction, an output, a proposal or a parameter update is placed in a minimal, valid one first.</p>
 *
 * <p>{@link #encoder} re-encodes the decoded model (CCL's, or Yano's for {@code datum_option}); a hashed rule
 * ({@link #hashed}) re-encodes as its original bytes, as Haskell's {@code MemoBytes} does and as the node keeps
 * them.</p>
 *
 * @param name    the corpus rule
 * @param hashed  whether the item's bytes are a hash preimage (no normalization; the bytes must be reproduced)
 * @param strict  Yano's strict reading; rejects by throwing
 * @param ccl     CCL's model decoding; rejects by throwing (accepts everything for headers, which no CCL model reads)
 * @param encoder the re-encoding, from both decodings
 */
record CborRule(String name, boolean hashed, Decoder strict, Decoder ccl, Encoder encoder) {

    @FunctionalInterface
    interface Decoder {
        Object decode(byte[] cbor) throws Exception;
    }

    @FunctionalInterface
    interface Encoder {
        /** @return a {@link DataItem} or the encoded bytes */
        Object encode(Object strict, Object ccl) throws Exception;
    }

    // Known-good parts of the wrappers.
    private static final String HASH28 = "581c" + "00".repeat(28);
    private static final String HASH32 = "5820" + "00".repeat(32);
    private static final String ADDRESS = "581d60" + "00".repeat(28);
    private static final String REWARD_ACCOUNT = "581de0" + "00".repeat(28);
    private static final String INPUT = "82" + HASH32 + "00";
    private static final String MIN_BODY_ENTRIES = "0081" + INPUT + "0180" + "0200";
    private static final String MIN_BODY = "a3" + MIN_BODY_ENTRIES;
    private static final String KES_SIGNATURE = "5901c0" + "00".repeat(448);

    /** @return the rules this harness knows, by name */
    static Map<String, CborRule> all() {
        return Stream.of(
                inTransaction("auxiliary_data", cbor -> tx(MIN_BODY, "a0", cbor)),
                hashed("block", cbor -> block(cbor, StrictElements::transaction),
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
                hashed("header", CborRule::header, cbor -> null),
                hashed("header_body", cbor -> header(cat("82", cbor, KES_SIGNATURE)), cbor -> null),
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
                inTransaction("redeemers", cbor -> tx(MIN_BODY, cat("a105", cbor), null)),
                model("relay", reads(StrictElements::relay), CborRule::relay, (s, c) -> ((Relay) c).serialize()),
                model("script", reads(StrictElements::script),
                        cbor -> ((Array) item(cbor)).getDataItems().getFirst().equals(new UnsignedInteger(0))
                                ? NativeScript.deserializeScriptRef(cbor) : PlutusScript.deserializeScriptRef(cbor),
                        (s, c) -> c instanceof NativeScript n ? n.scriptRefBytes()
                                : ((PlutusScript) c).scriptRefBytes()),
                inTransaction("transaction", cbor -> cbor),
                inTransaction("transaction_body", cbor -> tx(cbor, "a0", null)),
                model("transaction_input", cbor -> StrictElements.transaction(inputTx(cbor)), cbor -> {
                    Transaction.deserialize(inputTx(cbor));
                    return TransactionInput.deserialize((Array) item(cbor));
                }, (s, c) -> ((TransactionInput) c).serialize()),
                model("transaction_output", StrictElements::output, cbor -> TransactionOutput.deserialize(item(cbor)),
                        (s, c) -> ((TransactionOutput) c).serialize()),
                inTransaction("transaction_witness_set", cbor -> tx(MIN_BODY, cbor, null)),
                model("value", reads(StrictElements::value), cbor -> Value.deserialize(item(cbor)),
                        (s, c) -> ((Value) c).serialize()),
                model("voting_procedure", cbor -> StrictElements.transaction(voteTx(cbor)), cbor -> {
                    Transaction.deserialize(voteTx(cbor));
                    return VotingProcedure.deserialize((Array) item(cbor));
                }, (s, c) -> ((VotingProcedure) c).serialize())
        ).collect(Collectors.toMap(CborRule::name, Function.identity()));
    }

    /** @return the re-encoding when both decoders accept the item; an exception is a rejection */
    byte[] decodeAndEncode(byte[] cbor) throws Exception {
        Object strictItem = strict.decode(cbor);
        Object cclItem = ccl.decode(cbor);
        if (hashed) {
            return cbor;
        }
        Object encoded = encoder.encode(strictItem, cclItem);
        return encoded instanceof byte[] bytes ? bytes : CborSerializationUtil.serialize((DataItem) encoded);
    }

    private static CborRule model(String name, Decoder strict, Decoder ccl, Encoder encoder) {
        return new CborRule(name, false, strict, ccl, encoder);
    }

    private static CborRule hashed(String name, Decoder strict, Decoder ccl) {
        return new CborRule(name, true, strict, ccl, (s, c) -> {
            throw new IllegalStateException("a hashed rule re-encodes as its original bytes");
        });
    }

    /** A hashed rule read inside (or as) a transaction: the strict reader and CCL's decoding of that transaction. */
    private static CborRule inTransaction(String name, Wrapper wrapper) {
        return hashed(name, cbor -> StrictElements.transaction(wrapper.wrap(cbor)),
                cbor -> Transaction.deserialize(wrapper.wrap(cbor)));
    }

    /** A strict reader that only accepts or rejects. */
    @FunctionalInterface
    private interface Reader {
        void read(byte[] cbor) throws Exception;
    }

    private static Decoder reads(Reader reader) {
        return cbor -> {
            reader.read(cbor);
            return null;
        };
    }

    @FunctionalInterface
    private interface Wrapper {
        byte[] wrap(byte[] cbor);
    }

    /** CCL reads a relay only inside a pool registration. */
    private static Relay relay(byte[] relay) throws Exception {
        return PoolRegistration.deserialize(item(cat("8a03" + HASH28 + HASH32 + "0000d81e820001" + REWARD_ACCOUNT
                + "8081", relay, "f6"))).getRelays().getFirst();
    }

    private static byte[] datumOutput(byte[] datumOption) {
        return cat("a300" + ADDRESS + "0100" + "02", datumOption);
    }

    private static byte[] redeemerTx(byte[] redeemer) {
        return tx(MIN_BODY, cat("a10581", redeemer), null);
    }

    private static byte[] inputTx(byte[] input) {
        return tx(cat("a30081", input, "01800200"), "a0", null);
    }

    private static byte[] voteTx(byte[] votingProcedure) {
        return tx(cat("a4" + MIN_BODY_ENTRIES + "13a1" + "8200" + HASH28 + "a1" + INPUT, votingProcedure), "a0", null);
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
    private static Object header(byte[] cbor) throws Exception {
        byte[] wrapped = CborSerializationUtil.serialize(new ByteString(cbor));
        return ShelleyHeaderView.from(BlockHeaderSerializer.INSTANCE.deserialize(wrapped), wrapped);
    }

    /**
     * {@code datum_option} from Yano's reading of it: {@code [0, hash]}, or {@code [1, #6.24(bytes)]} around the inline
     * datum's original bytes, which Yano keeps (as Haskell's {@code MemoBytes} does) and hashes.
     */
    private static DataItem datumOption(RawOutput output) {
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

    private static DataItem item(byte[] cbor) {
        return CborSerializationUtil.deserialize(cbor);
    }

    /** {@code [body, witness_set, true, auxiliary_data / null]} */
    private static byte[] tx(String body, String witnessSet, byte[] auxData) {
        return tx(hex(body), hex(witnessSet), auxData);
    }

    private static byte[] tx(byte[] body, String witnessSet, byte[] auxData) {
        return tx(body, hex(witnessSet), auxData);
    }

    private static byte[] tx(String body, byte[] witnessSet, byte[] auxData) {
        return tx(hex(body), witnessSet, auxData);
    }

    private static byte[] tx(byte[] body, byte[] witnessSet, byte[] auxData) {
        return cat(cat(hex("84"), body), witnessSet, auxData == null ? hex("f5f6") : cat(hex("f5"), auxData));
    }

    private static byte[] cat(String prefix, byte[] item) {
        return cat(hex(prefix), item);
    }

    private static byte[] cat(String prefix, byte[] item, String suffix) {
        return cat(hex(prefix), item, hex(suffix));
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : List.of(parts)) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
