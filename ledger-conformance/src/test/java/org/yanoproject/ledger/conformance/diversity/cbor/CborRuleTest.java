package org.yanoproject.ledger.conformance.diversity.cbor;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.ledger.conformance.diversity.cbor.CborRule.ADDRESS;
import static org.yanoproject.ledger.conformance.diversity.cbor.CborRule.HASH28;
import static org.yanoproject.ledger.conformance.diversity.cbor.CborRule.HASH32;
import static org.yanoproject.ledger.conformance.diversity.cbor.CborRule.INPUT;
import static org.yanoproject.ledger.conformance.diversity.cbor.CborRule.REWARD_ACCOUNT;

/**
 * Positive controls for the rules the corpus only tests with items that must be rejected ({@code block},
 * {@code header}, {@code header_body}, {@code transaction}, {@code transaction_body}, {@code transaction_witness_set}):
 * a broken wrapper would reject everything and still score. Each is fed a valid item taken from a real Conway block
 * (runtime's {@code wallet/eras/conway2.block}).
 */
class CborRuleTest {

    private static final Map<String, CborRule> RULES = CborRule.all();

    @Test
    void wrappedRulesAcceptValidItems() throws IOException {
        byte[] envelope = HexFormat.of().parseHex(Files.readString(
                Path.of(System.getProperty("node.diversity.conwayBlock"))).trim());
        byte[] blockBytes = CborSpan.of(envelope).items().get(1).bytes();
        RawBlock block = RawBlock.of(blockBytes, Era.Conway);
        assertThat(block.txCount()).isPositive();
        byte[] header = block.header().bytes();
        byte[] tx = block.txBytes(0);
        List<CborSpan> txItems = CborSpan.of(tx).items();

        accepts("block", blockBytes);
        accepts("header", header);
        accepts("header_body", CborSpan.of(header).items().getFirst().bytes());
        accepts("transaction", tx);
        accepts("transaction_body", txItems.get(0).bytes());
        accepts("transaction_witness_set", txItems.get(1).bytes());
        accepts("auxiliary_data", HexFormat.of().parseHex("a0"));
    }

    @Test
    void plutusMapKeepsItsOrderWhenReEncoded() throws Exception {
        byte[] redeemer = HexFormat.of().parseHex("840000a203040102820101");
        CborRule rule = RULES.get("redeemer");

        assertThat(rule.encode(rule.decode(redeemer))).isEqualTo(redeemer);
    }

    @Test
    void encoderFailureIsNotARejection(@TempDir Path dir) throws IOException {
        CborRule rule = new CborRule("rule", false, cbor -> cbor, cbor -> cbor, (strict, library) -> {
            throw new IllegalStateException("encoder failure");
        });
        Path sample = Files.write(dir.resolve("sample.input.cbor"), new byte[]{0});

        assertThat(CborDecodingDiversityReportTest.check(rule, false, sample))
                .contains("decode: succeeded when expected to fail");
    }

    /**
     * A valid item for each rule of a part of a larger item: it must get past both decoders in its placement and come
     * back out of the re-encoding, or a placement or path is wrong. Part of the check a broken placement would fail
     * silently otherwise: a rejected sample would look like a decoder difference.
     */
    @Test
    void placedRulesReturnWhatWasPlaced() throws Exception {
        String h64 = "5840" + "00".repeat(64);
        String rational = "d81e820102";
        String url = "6161";
        String anchor = "82" + url + HASH32;
        String proposal = "8400" + REWARD_ACCOUNT + "8106" + anchor;
        Map<String, String> items = Map.ofEntries(
                Map.entry("addr_keyhash", HASH28),
                Map.entry("auxiliary_data_hash", HASH32),
                Map.entry("certificates", "d90102818304" + HASH28 + "00"),
                Map.entry("mint", "a1" + HASH28 + "a14001"),
                Map.entry("network_id", "00"),
                Map.entry("nonzero_int64", "01"),
                Map.entry("proposal_procedures", "d9010281" + proposal),
                Map.entry("script_data_hash", HASH32),
                Map.entry("slot", "01"),
                Map.entry("voter", "8200" + HASH28),
                Map.entry("voting_procedures", "a18200" + HASH28 + "a1" + INPUT + "8200f6"),
                Map.entry("withdrawals", "a1" + REWARD_ACCOUNT + "00"),
                Map.entry("coin", "01"),
                Map.entry("hash32", HASH32),
                Map.entry("transaction_id", HASH32),
                Map.entry("bootstrap_witness", "84" + HASH32 + h64 + HASH32 + "41a0"),
                Map.entry("plutus_v1_script", "4100"),
                Map.entry("plutus_v2_script", "4100"),
                Map.entry("plutus_v3_script", "4100"),
                Map.entry("signature", h64),
                Map.entry("vkey", HASH32),
                Map.entry("vkeywitness", "82" + HASH32 + h64),
                Map.entry("committee_cold_credential", "8201" + HASH28),
                Map.entry("committee_hot_credential", "8201" + HASH28),
                Map.entry("drep_credential", "8200" + HASH28),
                Map.entry("epoch", "01"),
                Map.entry("pool_keyhash", HASH28),
                Map.entry("pool_metadata", anchor),
                Map.entry("vrf_keyhash", HASH32),
                Map.entry("dns_name", url),
                Map.entry("ipv4", "4401020304"),
                Map.entry("ipv6", "50" + "01".repeat(16)),
                Map.entry("%multiasset<positive_coin>", "a1" + HASH28 + "a14001"),
                Map.entry("asset_name", "4161"),
                Map.entry("policy_id", HASH28),
                Map.entry("positive_coin", "01"),
                Map.entry("anchor", anchor),
                Map.entry("constitution", "82" + anchor + "f6"),
                Map.entry("gov_action_id", "82" + HASH32 + "00"),
                Map.entry("guardrails_script_hash", HASH28),
                Map.entry("major_protocol_version", "0a"),
                Map.entry("protocol_version", "820a00"),
                Map.entry("reward_account", REWARD_ACCOUNT),
                Map.entry("url", url),
                Map.entry("drep_voting_thresholds", "8a" + rational.repeat(10)),
                Map.entry("ex_unit_prices", "82" + rational.repeat(2)),
                Map.entry("nonnegative_interval", rational),
                Map.entry("pool_voting_thresholds", "85" + rational.repeat(5)),
                Map.entry("unit_interval", rational),
                Map.entry("%constr<plutus_data>", "d87980"),
                Map.entry("big_int", "01"),
                Map.entry("bounded_bytes", "4161"),
                Map.entry("stake_credential", "8200" + HASH28),
                Map.entry("alonzo_transaction_output", "82" + ADDRESS + "01"),
                Map.entry("babbage_transaction_output", "a200" + ADDRESS + "0101"),
                Map.entry("hash28", HASH28),
                Map.entry("script_hash", HASH28),
                Map.entry("address", ADDRESS),
                Map.entry("data", "d818" + "4101"),
                Map.entry("ex_units", "820102"),
                Map.entry("language", "00"),
                Map.entry("vote", "01"),
                Map.entry("block_number", "01"),
                Map.entry("kes_period", "01"),
                Map.entry("kes_vkey", HASH32),
                Map.entry("operational_cert", "84" + HASH32 + "0000" + h64),
                Map.entry("vrf_vkey", HASH32),
                Map.entry("auxiliary_data_array", "82a080"),
                Map.entry("auxiliary_data_map", "d90103a0"),
                Map.entry("auxiliary_scripts", "80"),
                Map.entry("metadata", "a0"),
                Map.entry("metadatum", "01"));

        assertThat(items.keySet()).containsExactlyInAnyOrderElementsOf(
                CborPlacedRules.all(CborRule.base()).map(CborRule::name).toList());
        Map<String, String> different = new TreeMap<>();
        for (Map.Entry<String, String> entry : items.entrySet()) {
            CborRule rule = RULES.get(entry.getKey());
            byte[] item = HexFormat.of().parseHex(entry.getValue());
            byte[] encoded = rule.encode(rule.decode(item));
            if (!Arrays.equals(CborNormalizer.normalize(encoded), CborNormalizer.normalize(item))) {
                different.put(entry.getKey(), HexFormat.of().formatHex(encoded));
            }
        }
        // CCL writes an
        // output that came as a map as an array. This fails once CCL changes, and the rule then leaves this set.
        assertThat(different.keySet()).as(different.toString())
                .containsExactly("babbage_transaction_output");
    }

    @Test
    void placedRuleRejectsAWrongItem() {
        assertThatThrownBy(() -> RULES.get("hash32").decode(HexFormat.of().parseHex(HASH28)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void manualSamplesKeepTheirExpectation() {
        assertThat(CborDecodingDiversityReportTest.isValid("valid")).isTrue();
        assertThat(CborDecodingDiversityReportTest.isValid("manual-valid")).isTrue();
        assertThat(CborDecodingDiversityReportTest.isValid("manual-invalid")).isFalse();
        assertThat(CborDecodingDiversityReportTest.isValid("invalid-zap-0")).isFalse();
    }

    private static void accepts(String rule, byte[] item) {
        CborRule cborRule = RULES.get(rule);
        assertThatCode(() -> cborRule.encode(cborRule.decode(item))).as(rule).doesNotThrowAnyException();
    }
}
