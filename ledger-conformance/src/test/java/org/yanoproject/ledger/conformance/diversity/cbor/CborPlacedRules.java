package org.yanoproject.ledger.conformance.diversity.cbor;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.Relay;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.StrictElements;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.yanoproject.ledger.conformance.diversity.cbor.CborRule.*;

/**
 * The dataset's rules for parts of a larger item. The node never reads such a part alone, so a sample is placed in a
 * minimal, valid item and both decoders read that. A valid sample then has to come back out: the part is taken from
 * the re-encoded CCL model, at the path it was placed, so a value the model drops or changes fails.
 *
 * <p>A part of a header, of auxiliary data or of a block has no re-encoding to read: the node keeps those items' bytes
 * ({@link CborRule#kept}).</p>
 */
final class CborPlacedRules {

    /** A path step: the first key of a map. */
    private static final Object KEY = new Object();
    /** A path step: the first value of a map. */
    private static final Object VALUE = new Object();

    private static final int BYTES = 2;
    private static final int ARRAY = 4;
    private static final int MAP = 5;
    private static final int TAG = 6;

    private static final String HASH64 = "5840" + "00".repeat(64);
    private static final String ANCHOR = "8260" + HASH32;
    private static final String INFO_ACTION = "8106";
    private static final String PROPOSAL = "8400" + REWARD_ACCOUNT;
    /** {@code [operator, vrf, pledge, cost, margin, reward account]}, in two parts around {@code vrf}. */
    private static final String POOL_START = "8a03" + HASH28;
    private static final String POOL_AFTER_VRF = "0000d81e820001" + REWARD_ACCOUNT;
    /** {@code owners, relays} */
    private static final String POOL_OWNERS_RELAYS = "81" + HASH28 + "80";
    private static final String VRF_CERT = "82" + HASH64 + "5850" + "00".repeat(80);
    private static final String HEADER_BODY_START = "8a";
    /** {@code slot, prev_hash, issuer_vkey} */
    private static final String HEADER_SLOT_TO_ISSUER = "00f6" + HASH32;
    /** {@code vrf_result, block_body_size, block_body_hash} */
    private static final String HEADER_VRF_TO_BODY_HASH = VRF_CERT + "00" + HASH32;
    private static final String HEADER_PROTOCOL_VERSION = "820a00";

    private CborPlacedRules() {
    }

    /** @param base the rules these can share decoders with */
    static Stream<CborRule> all(Map<String, CborRule> base) {
        return Stream.of(bodyParts(), witnessParts(), certificateParts(), relayParts(), valueParts(), proposalParts(),
                paramParts(), plutusParts(), otherParts(base), headerParts(), auxiliaryDataParts())
                .flatMap(List::stream);
    }

    private static List<CborRule> bodyParts() {
        return List.of(
                inBody("addr_keyhash", "0e81", "", 14, 0),
                inBody("auxiliary_data_hash", "07", "", 7),
                inBody("certificates", "04", "", 4),
                inBodyInLedgerOrder("mint", "09", body -> MultiAsset.serializeInLedgerOrder(body.getMint())),
                inBody("network_id", "0f", "", 15),
                inBody("nonzero_int64", "09a1" + HASH28 + "a140", "", 9, VALUE, VALUE),
                inBody("proposal_procedures", "14", "", 20),
                inBody("script_data_hash", "0b", "", 11),
                inBody("slot", "03", "", 3),
                inBody("voter", "13a1", "a1" + INPUT + "82" + "00f6", 19, KEY),
                inBody("voting_procedures", "13", "", 19),
                inBodyInLedgerOrder("withdrawals", "05", body -> Withdrawal.serializeInLedgerOrder(body.getWithdrawals())),
                placedInBody("coin", "a30081" + INPUT + "018002", "", 2),
                placedInBody("hash32", "a3008182", "00" + "01800200", 0, 0, 0),
                placedInBody("transaction_id", "a3008182", "00" + "01800200", 0, 0, 0));
    }

    private static List<CborRule> witnessParts() {
        return List.of(
                inWitnessSet("bootstrap_witness", "a10281", "", 2, 0),
                inWitnessSet("plutus_v1_script", "a10381", "", 3, 0),
                inWitnessSet("plutus_v2_script", "a10681", "", 6, 0),
                inWitnessSet("plutus_v3_script", "a10781", "", 7, 0),
                inWitnessSet("signature", "a1008182" + HASH32, "", 0, 0, 1),
                inWitnessSet("vkey", "a1008182", HASH64, 0, 0, 0),
                inWitnessSet("vkeywitness", "a10081", "", 0, 0));
    }

    private static List<CborRule> certificateParts() {
        return List.of(
                inCertificate("committee_cold_credential", "830e", "8200" + HASH28, 1),
                inCertificate("committee_hot_credential", "830e8200" + HASH28, "", 2),
                inCertificate("drep_credential", "8410", "00f6", 1),
                inCertificate("epoch", "8304" + HASH28, "", 2),
                inCertificate("pool_keyhash", "8304", "00", 1),
                only(ARRAY, inCertificate("pool_metadata", POOL_START + HASH32 + POOL_AFTER_VRF + POOL_OWNERS_RELAYS,
                        "", 9)),
                inCertificate("vrf_keyhash", POOL_START, POOL_AFTER_VRF + POOL_OWNERS_RELAYS + "f6", 2));
    }

    private static List<CborRule> relayParts() {
        return List.of(
                inRelay("dns_name", "8301f6", "", 2),
                only(BYTES, inRelay("ipv4", "8400f6", "f6", 2)),
                only(BYTES, inRelay("ipv6", "8400f6f6", "", 3)));
    }

    private static List<CborRule> valueParts() {
        return List.of(
                inValue("%multiasset<positive_coin>", "8200", "", 1),
                inValue("asset_name", "8200a1" + HASH28 + "a1", "01", 1, VALUE, KEY),
                inValue("policy_id", "8200a1", "a14001", 1, KEY),
                inValue("positive_coin", "8200a1" + HASH28 + "a140", "", 1, VALUE, VALUE));
    }

    private static List<CborRule> proposalParts() {
        return List.of(
                inProposal("anchor", PROPOSAL + INFO_ACTION, "", 3),
                inProposal("constitution", PROPOSAL + "8305f6", ANCHOR, 2, 2),
                only(ARRAY, inProposal("gov_action_id", PROPOSAL + "8203", ANCHOR, 2, 1)),
                only(BYTES, inProposal("guardrails_script_hash", PROPOSAL + "8302a0", ANCHOR, 2, 2)),
                inProposal("major_protocol_version", PROPOSAL + "8301f682", "00" + ANCHOR, 2, 2, 0),
                inProposal("protocol_version", PROPOSAL + "8301f6", ANCHOR, 2, 2),
                inProposal("reward_account", "8400", INFO_ACTION + ANCHOR, 1),
                inProposal("url", PROPOSAL + INFO_ACTION + "82", HASH32, 3, 0));
    }

    private static List<CborRule> paramParts() {
        return List.of(
                inParamUpdate("drep_voting_thresholds", "a1181a", 26),
                inParamUpdate("ex_unit_prices", "a113", 19),
                inParamUpdate("nonnegative_interval", "a109", 9),
                inParamUpdate("pool_voting_thresholds", "a11819", 25),
                inParamUpdate("unit_interval", "a10a", 10));
    }

    private static List<CborRule> plutusParts() {
        return List.of(data("%constr<plutus_data>", ConstrPlutusData.class), data("big_int", BigIntPlutusData.class),
                data("bounded_bytes", BytesPlutusData.class));
    }

    /** One alternative of {@code plutus_data}: the decoders take them all, so another one is a rejection here. */
    private static CborRule data(String name, Class<? extends PlutusData> alternative) {
        return model(name, reads(StrictElements::plutusData), cbor -> alternative.cast(PlutusData.deserialize(cbor)),
                (s, c) -> ((PlutusData) c).serialize());
    }

    private static List<CborRule> otherParts(Map<String, CborRule> base) {
        return List.of(
                sameAs("stake_credential", base.get("credential")),
                sameAs("alonzo_transaction_output", base.get("transaction_output")),
                sameAs("babbage_transaction_output", base.get("transaction_output")),
                inCredential("hash28"),
                inCredential("script_hash"),
                model("address", cbor -> StrictElements.output(addressOutput(cbor)),
                        cbor -> TransactionOutput.deserialize(item(addressOutput(cbor))),
                        (s, c) -> at(((TransactionOutput) c).serialize(), 0)),
                model("data", cbor -> StrictElements.output(datumOutput(cat("8201", cbor))),
                        cbor -> TransactionOutput.deserialize(item(datumOutput(cat("8201", cbor)))),
                        (s, c) -> at(datumOption((RawOutput) s), 1)),
                model("ex_units", cbor -> StrictElements.transaction(redeemerTx(cat("84000000", cbor))), cbor -> {
                    byte[] redeemer = cat("84000000", cbor);
                    Transaction.deserialize(redeemerTx(redeemer));
                    return Redeemer.deserializePreConway((Array) item(redeemer));
                }, (s, c) -> at(((Redeemer) c).serializePreConway(), 3)),
                model("language", reads(cbor -> StrictElements.paramUpdate(cat("a112a1", cbor, "80"))),
                        CborPlacedRules::language, (s, c) -> at(((CostMdls) c).serialize(), KEY)),
                model("vote", cbor -> StrictElements.transaction(voteTx(cat("82", cbor, "f6"))), cbor -> {
                    byte[] procedure = cat("82", cbor, "f6");
                    Transaction.deserialize(voteTx(procedure));
                    return VotingProcedure.deserialize((Array) item(procedure));
                }, (s, c) -> at(((VotingProcedure) c).serialize(), 0)));
    }

    private static List<CborRule> headerParts() {
        String beforeVrfVkey = HEADER_BODY_START + "00" + HEADER_SLOT_TO_ISSUER;
        String beforeCertificate = beforeVrfVkey + HASH32 + HEADER_VRF_TO_BODY_HASH;
        String certificateEnd = "0000" + HASH64;
        String certificate = "84" + HASH32 + certificateEnd;
        return List.of(
                inHeaderBody("block_number", HEADER_BODY_START, HEADER_SLOT_TO_ISSUER + HASH32 + HEADER_VRF_TO_BODY_HASH
                        + certificate + HEADER_PROTOCOL_VERSION),
                inHeaderBody("kes_period", beforeCertificate + "84" + HASH32 + "00", HASH64 + HEADER_PROTOCOL_VERSION),
                inHeaderBody("kes_vkey", beforeCertificate + "84", certificateEnd + HEADER_PROTOCOL_VERSION),
                inHeaderBody("operational_cert", beforeCertificate, HEADER_PROTOCOL_VERSION),
                inHeaderBody("vrf_vkey", beforeVrfVkey, HEADER_VRF_TO_BODY_HASH + certificate
                        + HEADER_PROTOCOL_VERSION));
    }

    private static List<CborRule> auxiliaryDataParts() {
        return List.of(
                only(ARRAY, inAuxiliaryData("auxiliary_data_array", "", "")),
                only(TAG, inAuxiliaryData("auxiliary_data_map", "", "")),
                inAuxiliaryData("auxiliary_scripts", "82a0", ""),
                only(MAP, inAuxiliaryData("metadata", "", "")),
                inAuxiliaryData("metadatum", "a100", ""));
    }

    private static CborRule inBody(String name, String keyAndPrefix, String suffix, Object... path) {
        return placedInBody(name, "a4" + MIN_BODY_ENTRIES + keyAndPrefix, suffix, path);
    }

    /** The sample inside a transaction body: {@code prefix} and {@code suffix} make the rest of the body. */
    private static CborRule placedInBody(String name, String prefix, String suffix, Object... path) {
        Wrapper wrapper = cbor -> tx(cat(prefix, cbor, suffix), hex("a0"), null);
        return model(name, cbor -> StrictElements.transaction(wrapper.wrap(cbor)),
                cbor -> Transaction.deserialize(wrapper.wrap(cbor)),
                (s, c) -> at(((Transaction) c).getBody().serialize(), path));
    }

    /** A body writes CIP-21's order for hardware wallets, so the part is written on its own in the ledger's order. */
    private static CborRule inBodyInLedgerOrder(String name, String prefix, LedgerOrderWriter writer) {
        Wrapper wrapper = cbor -> tx(cat("a4" + MIN_BODY_ENTRIES + prefix, cbor), hex("a0"), null);
        return model(name, cbor -> StrictElements.transaction(wrapper.wrap(cbor)),
                cbor -> Transaction.deserialize(wrapper.wrap(cbor)),
                (s, c) -> writer.write(((Transaction) c).getBody()));
    }

    @FunctionalInterface
    private interface LedgerOrderWriter {
        DataItem write(com.bloxbean.cardano.client.transaction.spec.TransactionBody body) throws Exception;
    }

    private static CborRule inWitnessSet(String name, String prefix, String suffix, Object... path) {
        Wrapper wrapper = cbor -> tx(hex(MIN_BODY), cat(prefix, cbor, suffix), null);
        return model(name, cbor -> StrictElements.transaction(wrapper.wrap(cbor)),
                cbor -> Transaction.deserialize(wrapper.wrap(cbor)),
                (s, c) -> at(((Transaction) c).getWitnessSet().serialize(), path));
    }

    private static CborRule inCertificate(String name, String prefix, String suffix, Object... path) {
        return placed(name, prefix, suffix, StrictElements::certificate,
                cbor -> Certificate.deserialize((Array) item(cbor)), c -> ((Certificate) c).serialize(), path);
    }

    private static CborRule inRelay(String name, String prefix, String suffix, Object... path) {
        return placed(name, prefix, suffix, StrictElements::relay, CborRule::relay, c -> ((Relay) c).serialize(), path);
    }

    private static CborRule inValue(String name, String prefix, String suffix, Object... path) {
        return placed(name, prefix, suffix, StrictElements::value, cbor -> Value.deserialize(item(cbor)),
                c -> ((Value) c).serializeInLedgerOrder(), path);
    }

    private static CborRule inProposal(String name, String prefix, String suffix, Object... path) {
        return placed(name, prefix, suffix, StrictElements::proposal, cbor -> ProposalProcedure.deserialize(item(cbor)),
                c -> ((ProposalProcedure) c).serialize(), path);
    }

    private static CborRule inParamUpdate(String name, String prefix, int key) {
        return placed(name, prefix, "", StrictElements::paramUpdate,
                cbor -> ProtocolParamUpdate.deserialize(item(cbor)), c -> ((ProtocolParamUpdate) c).serialize(), key);
    }

    private static CborRule inCredential(String name) {
        return placed(name, "8201", "", StrictElements::credential,
                cbor -> StakeCredential.deserialize((Array) item(cbor)), c -> ((StakeCredential) c).serialize(), 1);
    }

    /** The sample inside an item with a strict reader and a model of its own, read from that model at the path. */
    private static CborRule placed(String name, String prefix, String suffix, Reader strict, Decoder library,
                                   Serializer serializer, Object... path) {
        return model(name, reads(cbor -> strict.read(cat(prefix, cbor, suffix))),
                cbor -> library.decode(cat(prefix, cbor, suffix)), (s, c) -> at(serializer.serialize(c), path));
    }

    @FunctionalInterface
    private interface Serializer {
        DataItem serialize(Object model) throws Exception;
    }

    private static CborRule inHeaderBody(String name, String prefix, String suffix) {
        return kept(name, cbor -> header(headerOf(cat(prefix, cbor, suffix))),
                cbor -> yaciHeader(headerOf(cat(prefix, cbor, suffix))));
    }

    private static CborRule inAuxiliaryData(String name, String prefix, String suffix) {
        Wrapper wrapper = cbor -> tx(hex(MIN_BODY), hex("a0"), cat(prefix, cbor, suffix));
        return kept(name, cbor -> StrictElements.transaction(wrapper.wrap(cbor)),
                cbor -> Transaction.deserialize(wrapper.wrap(cbor)));
    }

    /** A cost model keeps a key it does not know, as Haskell's does, but {@code Language} has only the known ones. */
    private static CostMdls language(byte[] cbor) throws Exception {
        BigInteger key = ((UnsignedInteger) item(cbor)).getValue();
        if (Stream.of(Language.values()).noneMatch(language -> BigInteger.valueOf(language.getKey()).equals(key))) {
            throw new IllegalArgumentException("unknown language " + key);
        }
        return CostMdls.deserialize(item(cat("a1", cbor, "80")));
    }

    /**
     * A rule placed where its parent also takes another type, such as a nullable position or another form of auxiliary
     * data. The parent would accept that item, but it is not one of this rule's.
     */
    private static CborRule only(int majorType, CborRule rule) {
        return new CborRule(rule.name(), rule.exact(), only(majorType, rule.strict()), only(majorType, rule.library()),
                rule.encoder());
    }

    private static CborRule.Decoder only(int majorType, CborRule.Decoder decoder) {
        return cbor -> {
            if ((cbor[0] & 0xff) >>> 5 != majorType) {
                throw new IllegalArgumentException("not of major type " + majorType);
            }
            return decoder.decode(cbor);
        };
    }

    /** The same decoders under a second rule name: the dataset tests one Haskell type under both. */
    private static CborRule sameAs(String name, CborRule same) {
        return new CborRule(name, same.exact(), same.strict(), same.library(), same.encoder());
    }

    private static byte[] addressOutput(byte[] address) {
        return cat("a200", address, "0100");
    }

    /** @return the item at the path: an int is an array index or an integer map key, or {@link #KEY}, {@link #VALUE} */
    private static DataItem at(DataItem root, Object... path) {
        DataItem item = root;
        for (Object step : path) {
            item = switch (item) {
                case Array array -> array.getDataItems().get((Integer) step);
                case co.nstant.in.cbor.model.Map map when step == KEY -> map.getKeys().iterator().next();
                case co.nstant.in.cbor.model.Map map when step == VALUE -> map.getValues().iterator().next();
                case co.nstant.in.cbor.model.Map map -> map.get(new UnsignedInteger((Integer) step));
                default -> throw new IllegalArgumentException("no " + step + " in " + item);
            };
        }
        return item;
    }
}
