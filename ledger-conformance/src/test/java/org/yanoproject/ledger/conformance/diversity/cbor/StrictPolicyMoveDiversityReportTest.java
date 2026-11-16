package org.yanoproject.ledger.conformance.diversity.cbor;

import com.bloxbean.cardano.client.exception.CborRuntimeException;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0005 J1, before Yano's copy of the policy is deleted (M4): Yano's strict reading of every Conway sample, and of
 * seeded byte-level mutations of each, against the reading of CCL's harness (ledger-cbor's {@code HaskellPolicy}),
 * accept or reject. The only allowed differences are J1's corrections: the {@code block} rule, whose strict read now
 * checks the header and reads a bare block only, and the {@code script} rule, which now refuses a Conway guard.
 * Every difference is written to {@code build/node-diversity/strict-move-differential.txt}.
 */
class StrictPolicyMoveDiversityReportTest {

    private static final int MUTATIONS_PER_SAMPLE = 4;
    private static final long SEED = 0x0ad0_0005_51e1L;
    /** J1's corrections, by rule. */
    private static final Set<String> CORRECTED = Set.of("block", "script");

    @Test
    void cclsStrictReadsDecideAsYanosDid() throws IOException {
        Path conway = NodeDiversity.requireCorpus(CborDecodingDiversityReportTest.DATASET).resolve("conway");
        Map<String, CborRule> yano = CborRule.all();
        Map<String, com.bloxbean.cardano.client.ledger.conformance.CborRule> ccl =
                com.bloxbean.cardano.client.ledger.conformance.CborRule.conway();
        assertThat(ccl.keySet()).isEqualTo(yano.keySet());

        Random random = new Random(SEED);
        List<String> differences = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        long compared = 0;
        try (Stream<Path> files = Files.walk(conway)) {
            for (Path sample : files.filter(p -> p.toString().endsWith(".input.cbor")).sorted().toList()) {
                String path = conway.relativize(sample).toString().replace('\\', '/');
                String rule = path.substring(0, path.indexOf('/'));
                byte[] input = Files.readAllBytes(sample);
                List<byte[]> inputs = new ArrayList<>();
                inputs.add(input);
                for (int i = 0; i < MUTATIONS_PER_SAMPLE; i++) {
                    inputs.add(mutate(input, random));
                }
                for (int i = 0; i < inputs.size(); i++) {
                    String before = yanos(yano.get(rule), inputs.get(i));
                    String after = ccls(ccl.get(rule), inputs.get(i));
                    compared++;
                    if (!before.equals(after)) {
                        String line = path + "#" + i + " yano=" + before + " ccl=" + after
                                + (inputs.get(i).length <= 512 ? " " + HexFormat.of().formatHex(inputs.get(i)) : "");
                        differences.add(line);
                        if (!CORRECTED.contains(rule)) {
                            unexpected.add(line);
                        }
                    }
                }
            }
        }
        Path out = Path.of(System.getProperty("node.diversity.report.dir", "build/node-diversity"))
                .resolve("strict-move-differential.txt");
        Files.createDirectories(out.getParent());
        List<String> report = new ArrayList<>();
        report.add("# ADR 0005 J1: Yano's strict reads vs CCL's harness, " + compared + " inputs (samples and "
                + MUTATIONS_PER_SAMPLE + " mutations each, seed " + Long.toHexString(SEED) + "), "
                + differences.size() + " differences, " + unexpected.size() + " outside J1's corrections");
        report.addAll(differences);
        Files.write(out, report);
        System.out.println(report.get(0));
        assertThat(unexpected).as("differences outside J1's corrections (all in " + out + ")").isEmpty();
    }

    /** A for accepted, R for rejected, C for a crash (an Error), as Yano's report counts. */
    private static String yanos(CborRule rule, byte[] input) {
        try {
            rule.strict().decode(input);
            return "A";
        } catch (Exception e) {
            return "R";
        } catch (Error e) {
            return "C";
        }
    }

    /** A, R for a CborRuntimeException (a rejection, ADR 0005 D10), E for anything else thrown. */
    private static String ccls(com.bloxbean.cardano.client.ledger.conformance.CborRule rule, byte[] input) {
        try {
            rule.strict().decode(input);
            return "A";
        } catch (CborRuntimeException e) {
            return "R";
        } catch (Exception | Error e) {
            return "E:" + e.getClass().getSimpleName();
        }
    }

    private static byte[] mutate(byte[] input, Random random) {
        if (input.length == 0) {
            return new byte[]{(byte) random.nextInt(256)};
        }
        byte[] out;
        int at = random.nextInt(input.length);
        switch (random.nextInt(5)) {
            case 0 -> {
                out = input.clone();
                out[at] = (byte) random.nextInt(256);
            }
            case 1 -> {
                out = input.clone();
                out[at] ^= (byte) (1 << random.nextInt(8));
            }
            case 2 -> {
                out = new byte[input.length + 1];
                System.arraycopy(input, 0, out, 0, at);
                out[at] = (byte) random.nextInt(256);
                System.arraycopy(input, at, out, at + 1, input.length - at);
            }
            case 3 -> {
                out = new byte[input.length - 1];
                System.arraycopy(input, 0, out, 0, at);
                System.arraycopy(input, at + 1, out, at, input.length - at - 1);
            }
            default -> {
                out = new byte[at];
                System.arraycopy(input, 0, out, 0, at);
            }
        }
        return out;
    }
}
