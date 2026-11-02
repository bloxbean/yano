package org.yanoproject.ledger.conformance.diversity.cbor;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

        assertThat(CborDecodingDiversityReportTest.check(rule, "invalid-zap-0", sample))
                .contains("decode: succeeded when expected to fail");
    }

    private static void accepts(String rule, byte[] item) {
        CborRule cborRule = RULES.get(rule);
        assertThatCode(() -> cborRule.encode(cborRule.decode(item))).as(rule).doesNotThrowAnyException();
    }
}
