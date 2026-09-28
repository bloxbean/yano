package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.amaru.wire.CborReader;
import org.yanoproject.ledger.rules.TxValidationRequest.Origin;
import org.yanoproject.ledger.rules.TxValidationRequest.Rule;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The request the <em>engine</em> builds from a {@code LedgerView} (key resolution, canonical order,
 * re-encoded outputs) carries the same state as the Rust reference request for the same scenario,
 * restricted to the keys {@code required_keys} named.
 *
 * <p>Compared structurally: slice order, certificate pointers (the engine sends null) and the output
 * envelope (legacy array vs map, key order, lengths) are ignored; the address bytes, the value, the datum
 * (hash, or the inline datum's exact bytes) and the reference script's exact bytes must match. Everything
 * outside the state slices must be identical. Needs the Rust gate's dump
 * ({@code amaru.request.dumps.dir}); skipped without it.</p>
 */
class EngineRequestEquivalenceTest {

    @Test
    void engineRequestsCarryTheReferenceStateForEveryScenario() throws IOException {
        AmaruScenarioLoader loader = AmaruScenarioLoader.fromEnvironment();
        String configured = System.getProperty("amaru.request.dumps.dir", "");
        Path dumps = configured.isBlank() ? null : Path.of(configured);
        assumeTrue(dumps != null && Files.isDirectory(dumps), "no reference request dump");

        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (AmaruScenario scenario : loader.loadAll()) {
            if (scenario.expected() instanceof Expected.DecodingFailure || scenario.env().protocolMajor() < 10) {
                continue; // no state is sent (decoding failure) or the engine refuses the request (invariant 6)
            }
            Path reference = dumps.resolve(scenario.name() + ".full.req.cbor");
            Path keys = dumps.resolve(scenario.name() + ".keys-resp.cbor");
            assumeTrue(Files.isRegularFile(reference) && Files.isRegularFile(keys), "incomplete dump");

            FaultyInstance.Shared shared = new FaultyInstance.Shared();
            try (AmaruTransactionValidator engine = new AmaruTransactionValidator(
                    AmaruEngineConfig.defaults(Phase2Mode.FULL, 1), ScenarioSupport.network(scenario), null,
                    ScenarioSupport.constants(scenario), shared.factory())) {
                ScenarioSupport.validate(engine, scenario, Rule.LEDGER, Origin.SYNC);
            }
            assertThat(shared.requests).as(scenario.name()).hasSize(1);
            String problem = compare(Files.readAllBytes(reference), Files.readAllBytes(keys),
                    shared.requests.getFirst());
            if (problem != null) {
                mismatches.add(scenario.name() + ": " + problem);
            }
            compared++;
        }
        System.out.println("Engine requests equivalent to the reference: " + (compared - mismatches.size()) + " of "
                + compared);
        assertThat(mismatches).isEmpty();
        assertThat(compared).isEqualTo(AmaruScenarioLoader.EXPECTED_SCENARIOS - 4);
    }

    private static String compare(byte[] referenceRequest, byte[] keysResponse, byte[] engineRequest) {
        Map<?, ?> reference = (Map<?, ?>) CborReader.decode(referenceRequest);
        Map<?, ?> engine = (Map<?, ?>) CborReader.decode(engineRequest);
        Map<?, ?> keys = (Map<?, ?>) CborReader.decode(keysResponse);
        if (!reference.keySet().equals(engine.keySet())) {
            return "request keys " + engine.keySet() + " vs reference " + reference.keySet();
        }
        for (long key = 0; key <= 12; key++) {
            if (!show(reference.get(key)).equals(show(engine.get(key)))) {
                return "key " + key + ": " + show(engine.get(key)) + " vs reference " + show(reference.get(key));
            }
        }
        Set<String> inputs = shown((List<?>) keys.get(1L));
        Set<String> accounts = shown((List<?>) keys.get(2L));
        Set<String> pools = shown((List<?>) keys.get(3L));
        Set<String> dreps = shown((List<?>) keys.get(4L));

        String[] problems = {
            slice("utxo", reference.get(13L), engine.get(13L), e -> show(((List<?>) e).get(0)), inputs,
                    e -> show(((List<?>) e).get(0)) + "=" + output((byte[]) ((List<?>) e).get(1))),
            slice("accounts", reference.get(14L), engine.get(14L), e -> show(((List<?>) e).get(0)), accounts,
                    EngineRequestEquivalenceTest::accountWithoutPointers),
            slice("pools", reference.get(15L), engine.get(15L), EngineRequestEquivalenceTest::show, pools,
                    EngineRequestEquivalenceTest::show),
            slice("dreps", reference.get(16L), engine.get(16L), e -> show(((List<?>) e).get(0)), dreps,
                    e -> { List<?> d = (List<?>) e; return show(List.of(d.get(0), d.get(1), d.get(3))); }),
            slice("committee", reference.get(17L), engine.get(17L), e -> "", null, EngineRequestEquivalenceTest::show),
            slice("proposals", reference.get(18L), engine.get(18L), e -> "", null, EngineRequestEquivalenceTest::show)
        };
        for (String problem : problems) {
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private interface Shower {
        String show(Object entry);
    }

    /** The reference slice restricted to {@code required} (null: whole), against the engine's, as sets. */
    private static String slice(String name, Object referenceSlice, Object engineSlice, Shower key,
                                Set<String> required, Shower entry) {
        Set<String> expected = new TreeSet<>();
        for (Object e : (List<?>) referenceSlice) {
            if (required == null || required.contains(key.show(e))) {
                expected.add(entry.show(e));
            }
        }
        Set<String> actual = new TreeSet<>();
        for (Object e : (List<?>) engineSlice) {
            actual.add(entry.show(e));
        }
        return expected.equals(actual) ? null : name + ": engine " + actual + " vs reference " + expected;
    }

    private static String accountWithoutPointers(Object entry) {
        List<?> a = (List<?>) entry;
        Object pool = a.get(3) == null ? null : ((List<?>) a.get(3)).get(0);
        Object drep = a.get(4) == null ? null : ((List<?>) a.get(4)).get(0);
        return show(Arrays.asList(a.get(0), a.get(1), a.get(2), pool, drep));
    }

    /** An output as address, value, datum and reference script, ignoring the envelope. */
    static String output(byte[] cbor) {
        Object decoded = CborReader.decode(cbor);
        Object address;
        Object value;
        String datum = "none";
        String script = "none";
        if (decoded instanceof List<?> legacy) {
            address = legacy.get(0);
            value = legacy.get(1);
            if (legacy.size() > 2) {
                datum = "hash:" + show(legacy.get(2));
            }
        } else {
            Map<?, ?> map = (Map<?, ?>) decoded;
            address = map.get(0L);
            value = map.get(1L);
            if (map.get(2L) instanceof List<?> option) {
                datum = (Long) option.get(0) == 0 ? "hash:" + show(option.get(1))
                        : "inline:" + show(((CborReader.Tagged) option.get(1)).value());
            }
            if (map.get(3L) instanceof CborReader.Tagged tagged) {
                script = show(tagged.value());
            }
        }
        return "addr=" + show(address) + " value=" + value(value) + " datum=" + datum + " script=" + script;
    }

    private static String value(Object value) {
        if (value instanceof List<?> withAssets) {
            Map<String, Map<String, String>> assets = new TreeMap<>();
            ((Map<?, ?>) withAssets.get(1)).forEach((policy, tokens) -> {
                Map<String, String> names = new TreeMap<>();
                ((Map<?, ?>) tokens).forEach((name, amount) -> names.put(show(name), String.valueOf(amount)));
                assets.put(show(policy), names);
            });
            return withAssets.get(0) + assets.toString();
        }
        return String.valueOf(value);
    }

    private static Set<String> shown(List<?> items) {
        Set<String> result = new TreeSet<>();
        if (items != null) {
            items.forEach(i -> result.add(show(i)));
        }
        return result;
    }

    /** A canonical text form: byte strings (and byte-string map keys) as hex. */
    static String show(Object value) {
        return switch (value) {
            case null -> "null";
            case byte[] bytes -> HexUtil.encodeHexString(bytes);
            case String text when !text.isEmpty() && text.chars().anyMatch(c -> c > 0x7e || c < 0x20) ->
                    HexUtil.encodeHexString(text.getBytes(StandardCharsets.ISO_8859_1));
            case List<?> list -> list.stream().map(EngineRequestEquivalenceTest::show).toList().toString();
            case Map<?, ?> map -> {
                Map<String, String> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(show(k), show(v)));
                yield sorted.toString();
            }
            case CborReader.Tagged tagged -> tagged.tag() + "(" + show(tagged.value()) + ")";
            default -> value.toString();
        };
    }
}
