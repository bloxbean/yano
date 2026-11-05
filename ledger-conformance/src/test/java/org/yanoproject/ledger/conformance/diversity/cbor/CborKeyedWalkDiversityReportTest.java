package org.yanoproject.ledger.conformance.diversity.cbor;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;
import org.yanoproject.ledger.rules.conway.tx.StrictElements;

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
            String readerRejects = rejection(StrictElements::transaction, tx);
            boolean structureRejects = rejection(StrictElements::walkStructure, tx) != null;
            boolean walkRejects = rejection(StrictElements::walk, tx) != null;
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
