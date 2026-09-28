package org.yanoproject.ledger.rules.fixtures.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.yanoproject.ledger.rules.LedgerRuleName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The machine-readable catalogue of Conway leaf predicate-failure constructors (ADR-056 §8, coverage matrix):
 * {@code conway-constructors.json}, generated from the pinned table in
 * {@code adr/reports/adr-056-haskell-pinned-revisions.md} (section 3d-table, static/dynamic labels from 3e) at
 * cardano-ledger {@code f649f975}.
 *
 * <p>Wrapper constructors ({@code ConwayUtxowFailure}, {@code CertFailure}, …) are not listed. The catalogue
 * keeps the three constructors that cannot occur at protocol version 10 or 11
 * ({@code DisallowedProposalDuringBootstrap}, {@code DisallowedVotesDuringBootstrap}: PV 9 only;
 * {@code OutputTooSmallUTxO}: unreachable in Conway) with {@code reachable = false}, so the table stays
 * complete; {@link #inScope()} leaves them out.</p>
 */
public final class ConwayConstructorCatalogue {

    private static final String RESOURCE = "/org/yanoproject/ledger/rules/fixtures/conformance/conway-constructors.json";

    /**
     * One constructor.
     *
     * @param rule        the Haskell rule that reports it (as {@link LedgerRuleName})
     * @param family      the rule family it is implemented in ({@code MEMPOOL} for {@code ConwayMempoolFailure},
     *                    which is a {@code LEDGER} constructor raised by the mempool rule); otherwise the rule
     * @param constructor the constructor name
     * @param fields      the constructor's fields, as in the pinned table
     * @param pvMin       the first protocol version it can occur at
     * @param pvMax       the last protocol version it can occur at, or null when still active
     * @param reachable   false when it cannot occur at protocol version 10 or later
     * @param phase       1, or 2 for {@code ValidationTagMismatch}
     * @param check       {@code static} (skipped on re-application) or {@code dynamic}
     * @param pvGate      the protocol-version gate as written in the table (empty when ungated)
     * @param notes       the table's notes (Haskell source locations)
     */
    public record Entry(LedgerRuleName rule, String family, String constructor, String fields, int pvMin,
                        Integer pvMax, boolean reachable, int phase, String check, String pvGate, String notes) {

        /** @return {@code RULE.Constructor} */
        public String qualifiedName() {
            return rule.name() + "." + constructor;
        }

        /** @return whether the constructor can occur at protocol version 10 or 11 */
        public boolean inScope() {
            return reachable && pvMin <= 11 && (pvMax == null || pvMax >= 10);
        }

        /** @return the PV range, e.g. {@code 10–11}, {@code 10}, {@code 11+} */
        public String pvRange() {
            if (!reachable) {
                return pvMin == 9 ? "9 only" : "unreachable";
            }
            if (pvMax == null) {
                return pvMin + "+";
            }
            return pvMin == pvMax ? String.valueOf(pvMin) : pvMin + "–" + pvMax;
        }
    }

    private static final ConwayConstructorCatalogue INSTANCE = load();

    private final String cardanoLedger;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private ConwayConstructorCatalogue(String cardanoLedger, List<Entry> entries) {
        this.cardanoLedger = cardanoLedger;
        this.entries = List.copyOf(entries);
        Map<String, Entry> index = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (index.put(entry.qualifiedName(), entry) != null) {
                throw new IllegalStateException("duplicate constructor " + entry.qualifiedName());
            }
        }
        this.byName = Collections.unmodifiableMap(index);
    }

    /** @return the catalogue on the classpath */
    public static ConwayConstructorCatalogue get() {
        return INSTANCE;
    }

    private static ConwayConstructorCatalogue load() {
        try (InputStream in = ConwayConstructorCatalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            List<Entry> entries = new ArrayList<>();
            for (JsonNode c : root.get("constructors")) {
                entries.add(new Entry(LedgerRuleName.valueOf(c.get("rule").asText()), c.get("family").asText(),
                        c.get("constructor").asText(), c.path("fields").asText(""), c.get("pvMin").asInt(),
                        c.hasNonNull("pvMax") ? c.get("pvMax").asInt() : null, c.get("reachable").asBoolean(),
                        c.get("phase").asInt(), c.get("check").asText(), c.path("pvGate").asText(""),
                        c.path("notes").asText("")));
            }
            return new ConwayConstructorCatalogue(root.path("cardanoLedger").asText(""), entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the pinned cardano-ledger revision the catalogue was taken from */
    public String cardanoLedger() {
        return cardanoLedger;
    }

    /** @return every constructor, in table order */
    public List<Entry> all() {
        return entries;
    }

    /** @return the constructors reachable at protocol version 10 or 11 */
    public List<Entry> inScope() {
        return entries.stream().filter(Entry::inScope).toList();
    }

    /** @param qualifiedName {@code RULE.Constructor} */
    public Optional<Entry> find(String qualifiedName) {
        return Optional.ofNullable(byName.get(qualifiedName));
    }

    /** @return whether {@code RULE.Constructor} is in the catalogue */
    public boolean contains(String qualifiedName) {
        return byName.containsKey(qualifiedName);
    }
}
