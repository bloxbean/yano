package org.yanoproject.ledger.conformance.mutation;

import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A single-fault edit of a valid base transaction (ADR-056 §8, mutation matrix).
 *
 * @param id           a stable id, e.g. {@code fee-too-small}
 * @param covers       the Haskell {@code RULE.Constructor} the mutant must be rejected with
 * @param haskellFailures when Haskell necessarily reports this fault with more than one constructor, its whole
 *                     failure list in Haskell's order, containing {@code covers} (for no collateral:
 *                     {@code NoCollateralInputs, InsufficientCollateral}: Babbage {@code feesOK} parts 5 and 7, in the
 *                     order of Haskell's {@code LEDGER} list, see {@code RuleFrame}); empty
 *                     for a single fault
 * @param base         the base transaction the edit applies to
 * @param description  what the edit does
 * @param edit         the edit, applied to a copy of the base spec before it is rebuilt and signed again
 * @param amaruReports when Amaru is known to name this fault differently from Haskell (a recorded divergence,
 *                     ADR-056 "Phase 3a results"), the {@code RULE.Constructor} Amaru reports, or
 *                     {@link #AMARU_ACCEPTS} when Amaru accepts the mutant; null otherwise
 * @param protocolMajor the protocol major version of the world the mutant is built and validated in (10; 11 for
 *                     the constructors that exist only from 11; 9, the bootstrap phase, for the bootstrap-only
 *                     constructors and faults judged differently there)
 */
public record Mutation(String id, String covers, List<String> haskellFailures, Base base, String description,
                       Consumer<TxSpec> edit, String amaruReports, int protocolMajor) {

    /** {@link #amaruReports()} for a mutant Amaru accepts although Haskell rejects it (a recorded divergence). */
    public static final String AMARU_ACCEPTS = "Valid";

    /**
     * {@link #amaruReports()} for a protocol-version-9 mutant: Amaru validates protocol version 10 and later only
     * (ADR-056 invariant 6), so it cannot confirm the fault; the mutant's Haskell evidence is cited in its source.
     */
    public static final String AMARU_REFUSES_PV9 = "ENGINE.EraNotSupported";

    /** The valid base transactions. */
    public enum Base {
        /** One key-locked input, one payment, change; signed by {@code dev-42}. */
        SIMPLE,
        /** Also spends an always-succeeds PlutusV3 UTxO with one redeemer and one collateral input. */
        SCRIPT
    }

    public Mutation(String id, String covers, List<String> haskellFailures, Base base, String description,
                    Consumer<TxSpec> edit) {
        this(id, covers, haskellFailures, base, description, edit, null, 10);
    }

    public Mutation(String id, String covers, List<String> haskellFailures, Base base, String description,
                    Consumer<TxSpec> edit, String amaruReports) {
        this(id, covers, haskellFailures, base, description, edit, amaruReports, 10);
    }

    /** @return this mutation in the protocol version 11 world */
    public Mutation atProtocolVersion11() {
        return new Mutation(id, covers, haskellFailures, base, description, edit, amaruReports, 11);
    }

    /** @return this mutation in the protocol version 9 (bootstrap) world, which Amaru refuses */
    public Mutation atProtocolVersion9() {
        return new Mutation(id, covers, haskellFailures, base, description, edit, AMARU_REFUSES_PV9, 9);
    }

    /** @return this mutation with a recorded Amaru divergence */
    public Mutation withAmaruReports(String constructor) {
        return new Mutation(id, covers, haskellFailures, base, description, edit, constructor, protocolMajor);
    }

    public Mutation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(covers, "covers");
        haskellFailures = List.copyOf(haskellFailures);
        if (!haskellFailures.isEmpty() && !haskellFailures.contains(covers)) {
            throw new IllegalArgumentException(id + ": haskellFailures must contain " + covers);
        }
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(edit, "edit");
        if (protocolMajor < 9 || protocolMajor > 11) {
            throw new IllegalArgumentException(id + ": the mutation worlds are protocol versions 9, 10 and 11");
        }
    }

    /** @return the case id, {@code mutant:<id>} */
    public String caseId() {
        return "mutant:" + id;
    }
}
