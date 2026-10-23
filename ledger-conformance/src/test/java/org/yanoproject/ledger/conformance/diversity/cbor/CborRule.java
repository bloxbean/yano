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
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How Yano decodes and re-encodes one cardano-cbor-dataset rule (ADR-060 §6).
 *
 * <p>{@link #decoder} does what the node does with such an item: CCL's model decoding, which the node runs on every
 * transaction ({@code Transaction.deserialize}), and Yano's strict reader ({@link StrictElements}); either one
 * rejecting is a rejection. An item the node only reads inside a transaction, an output, a proposal or a parameter
 * update is placed in a minimal, valid one first. {@link #encoder} re-encodes the decoded model (CCL's, or Yano's for
 * {@code datum_option}); a hashed rule ({@link #hashed}) re-encodes as its original bytes, as Haskell's
 * {@code MemoBytes} does and as the node keeps them.</p>
 *
 * @param name    the corpus rule
 * @param hashed  whether the item's bytes are a hash preimage (no normalization; the bytes must be reproduced)
 * @param decoder rejects by throwing; returns what {@code encoder} re-encodes
 * @param encoder the re-encoding of a decoded item
 */
record CborRule(String name, boolean hashed, Decoder decoder, Encoder encoder) {

    @FunctionalInterface
    interface Decoder {
        Object decode(byte[] cbor) throws Exception;
    }

    @FunctionalInterface
    interface Encoder {
        byte[] encode(Object decoded) throws Exception;
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
                hashed("auxiliary_data", cbor -> StrictElements.transaction(tx(MIN_BODY, "a0", cbor))),
                hashed("block", CborRule::block),
                model("certificate", cbor -> {
                    StrictElements.certificate(cbor);
                    return Certificate.deserialize((Array) item(cbor));
                }, c -> ((Certificate) c).serialize()),
                model("cost_models", cbor -> {
                    StrictElements.paramUpdate(cat("a112", cbor));
                    return CostMdls.deserialize(item(cbor));
                }, c -> ((CostMdls) c).serialize()),
                model("credential", cbor -> {
                    StrictElements.credential(cbor);
                    return StakeCredential.deserialize((Array) item(cbor));
                }, c -> ((StakeCredential) c).serialize()),
                model("datum_option", cbor -> {
                    byte[] output = cat("a300" + ADDRESS + "0100" + "02", cbor);
                    TransactionOutput.deserialize(item(output));
                    return StrictElements.output(output);
                }, CborRule::datumOption),
                model("drep", cbor -> {
                    StrictElements.drep(cbor);
                    return DRep.deserialize(item(cbor));
                }, c -> ((DRep) c).serialize()),
                model("gov_action", cbor -> {
                    StrictElements.proposal(cat("8400" + REWARD_ACCOUNT, cbor, "8260" + HASH32));
                    return GovAction.deserialize((Array) item(cbor));
                }, c -> ((GovAction) c).serialize()),
                hashed("header", CborRule::header),
                hashed("header_body", cbor -> header(cat("82", cbor, KES_SIGNATURE))),
                hashed("native_script", cbor -> {
                    StrictElements.nativeScript(cbor);
                    return NativeScript.deserialize((Array) item(cbor));
                }),
                hashed("plutus_data", cbor -> {
                    StrictElements.plutusData(cbor);
                    return PlutusData.deserialize(cbor);
                }),
                model("proposal_procedure", cbor -> {
                    StrictElements.proposal(cbor);
                    return ProposalProcedure.deserialize(item(cbor));
                }, c -> ((ProposalProcedure) c).serialize()),
                model("protocol_param_update", cbor -> {
                    StrictElements.paramUpdate(cbor);
                    return ProtocolParamUpdate.deserialize(item(cbor));
                }, c -> ((ProtocolParamUpdate) c).serialize()),
                model("redeemer", cbor -> {
                    StrictElements.transaction(tx(MIN_BODY, cat("a10581", cbor), null));
                    return Redeemer.deserializePreConway((Array) item(cbor));
                }, c -> ((Redeemer) c).serializePreConway()),
                hashed("redeemers", cbor -> StrictElements.transaction(tx(MIN_BODY, cat("a105", cbor), null))),
                model("relay", cbor -> {
                    StrictElements.relay(cbor);
                    return PoolRegistration.deserialize(item(cat("8a03" + HASH28 + HASH32 + "0000d81e820001"
                            + REWARD_ACCOUNT + "8081", cbor, "f6"))).getRelays().getFirst();
                }, c -> ((com.bloxbean.cardano.client.transaction.spec.cert.Relay) c).serialize()),
                model("script", cbor -> {
                    StrictElements.script(cbor);
                    return ((Array) item(cbor)).getDataItems().getFirst().equals(new UnsignedInteger(0))
                            ? NativeScript.deserializeScriptRef(cbor) : PlutusScript.deserializeScriptRef(cbor);
                }, c -> c instanceof NativeScript n ? n.scriptRefBytes() : ((PlutusScript) c).scriptRefBytes()),
                hashed("transaction", StrictElements::transaction),
                hashed("transaction_body", cbor -> StrictElements.transaction(tx(cbor, "a0", null))),
                model("transaction_input", cbor -> {
                    StrictElements.transaction(tx(cat("a30081", cbor, "01800200"), "a0", null));
                    return TransactionInput.deserialize((Array) item(cbor));
                }, c -> ((TransactionInput) c).serialize()),
                model("transaction_output", cbor -> {
                    StrictElements.output(cbor);
                    return TransactionOutput.deserialize(item(cbor));
                }, c -> ((TransactionOutput) c).serialize()),
                hashed("transaction_witness_set", cbor -> StrictElements.transaction(tx(MIN_BODY, cbor, null))),
                model("value", cbor -> {
                    StrictElements.value(cbor);
                    return Value.deserialize(item(cbor));
                }, c -> ((Value) c).serialize()),
                model("voting_procedure", cbor -> {
                    StrictElements.transaction(tx(cat("a4" + MIN_BODY_ENTRIES + "13a1" + "8200" + HASH28 + "a1" + INPUT,
                            cbor), "a0", null));
                    return VotingProcedure.deserialize((Array) item(cbor));
                }, c -> ((VotingProcedure) c).serialize())
        ).collect(Collectors.toMap(CborRule::name, Function.identity()));
    }

    /** @return the re-encoding, or empty when the node rejects the item; exceptions are rejections */
    Optional<byte[]> decodeAndEncode(byte[] cbor) throws Exception {
        Object decoded = decoder.decode(cbor);
        return Optional.of(hashed ? cbor : encoder.encode(decoded));
    }

    private static CborRule hashed(String name, Decoder decoder) {
        return new CborRule(name, true, decoder, decoded -> {
            throw new IllegalStateException("a hashed rule re-encodes as its original bytes");
        });
    }

    private static CborRule model(String name, Decoder decoder, DataItemEncoder encoder) {
        return new CborRule(name, false, decoder, decoded -> {
            Object encoded = encoder.encode(decoded);
            return encoded instanceof byte[] bytes ? bytes : CborSerializationUtil.serialize((DataItem) encoded);
        });
    }

    @FunctionalInterface
    private interface DataItemEncoder {
        /** @return a {@link DataItem} or the encoded bytes */
        Object encode(Object decoded) throws Exception;
    }

    /** A Conway block, as shadow sync reads one: CCL's raw view, then every transaction as the node reads it. */
    private static Object block(byte[] cbor) {
        RawBlock block = RawBlock.of(cbor, Era.Conway);
        for (int i = 0; i < block.txCount(); i++) {
            StrictElements.transaction(block.txBytes(i));
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
    private static DataItem datumOption(Object decoded) {
        RawOutput output = (RawOutput) decoded;
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
