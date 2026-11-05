package org.yanoproject.ledger.conformance.diversity.cbor;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.FieldSpec;
import com.bloxbean.cardano.client.transaction.raw.FormatPolicy;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.client.transaction.raw.RecordSpec;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;
import org.yanoproject.ledger.rules.conway.tx.HaskellPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0003 M1's keyed-record proof over the dataset: CCL's walk with {@code HaskellPolicy} beside the strict reader, on
 * the rules that hold a body and a witness set. A walk must never reject what the reader accepts; what only the reader
 * rejects is what the policy does not hold yet, printed by the reader's reason (not reported).
 */
class CborKeyedWalkDiversityReportTest {

    private static final Map<String, UnaryOperator<byte[]>> RULES = Map.of(
            "transaction", cbor -> cbor,
            "transaction_body", cbor -> CborRule.tx(cbor, CborRule.hex("a0"), null),
            "transaction_witness_set", cbor -> CborRule.tx(CborRule.hex(CborRule.MIN_BODY), cbor, null));

    /** {@link HaskellPolicy} with only its rules on structure: no rule on a value's type. */
    private static final FormatPolicy STRUCTURE = new FormatPolicy() {
        @Override
        public void enter(RecordSpec record, CborSpan container, Era era) {
            HaskellPolicy.CONWAY.enter(record, container, era);
        }

        @Override
        public void entry(RecordSpec record, FieldSpec field, CborSpan key, CborSpan value, int occurrence, Era era) {
            HaskellPolicy.CONWAY.entry(record, field, key, value, occurrence, era);
        }

        @Override
        public void missing(RecordSpec record, FieldSpec field, CborSpan container, Era era) {
            HaskellPolicy.CONWAY.missing(record, field, container, era);
        }
    };

    @Test
    void comparesTheWalkWithTheReader() throws IOException {
        Path conway = NodeDiversity.requireCorpus(CborDecodingDiversityReportTest.DATASET).resolve("conway");
        int beyondTheReader = 0;
        for (String rule : new TreeMap<>(RULES).keySet()) {
            beyondTheReader += compare(rule, conway.resolve(rule));
        }
        assertThat(beyondTheReader).as("samples a walk rejects and the strict reader accepts").isZero();
    }

    private static int compare(String rule, Path samples) throws IOException {
        int total = 0;
        int reader = 0;
        int structure = 0;
        int walk = 0;
        int beyondTheReader = 0;
        Map<String, Integer> outside = new TreeMap<>();
        for (Path sample : inputs(samples)) {
            byte[] tx = RULES.get(rule).apply(Files.readAllBytes(sample));
            total++;
            String readerRejects = rejection(CborRule::transaction, tx);
            boolean structureRejects = rejection(cbor -> RawTx.walk(cbor, Era.Conway, STRUCTURE), tx) != null;
            boolean walkRejects = rejection(cbor -> RawTx.walk(cbor, Era.Conway, HaskellPolicy.CONWAY), tx) != null;
            reader += readerRejects != null ? 1 : 0;
            structure += structureRejects ? 1 : 0;
            walk += walkRejects ? 1 : 0;
            if (readerRejects == null && (structureRejects || walkRejects)) {
                beyondTheReader++;
                System.out.printf("cbor-walk beyond the reader: %s%n", samples.relativize(sample));
            }
            if (readerRejects != null && !walkRejects) {
                outside.merge(readerRejects, 1, Integer::sum);
            }
        }
        System.out.printf("cbor-walk %-24s samples %d, reader rejects %d, structure rejects %d, walk rejects %d%n",
                rule, total, reader, structure, walk);
        outside.forEach((reason, count) -> System.out.printf("cbor-walk %-24s %5d only the reader: %s%n", rule, count,
                reason));
        return beyondTheReader;
    }

    /** @return why the read rejected the transaction, its numbers and hex removed, or null if it accepted */
    private static String rejection(Consumer<byte[]> read, byte[] tx) {
        try {
            read.accept(tx);
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()).replaceAll("[0-9a-f]{8,}", "#").replaceAll("[0-9]+", "#");
        }
    }

    private static List<Path> inputs(Path samples) throws IOException {
        try (Stream<Path> files = Files.walk(samples)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".input.cbor")).sorted().toList();
        }
    }
}
