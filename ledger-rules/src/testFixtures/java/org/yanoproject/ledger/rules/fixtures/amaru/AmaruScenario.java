package org.yanoproject.ledger.rules.fixtures.amaru;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One of Amaru's implementation-generic transaction scenarios
 * ({@code crates/amaru-ledger/tests/data/transaction/scenarios/*.json} at the pinned tag), converted
 * for Yano's engines by {@link AmaruScenarioLoader}.
 *
 * <p>An engine test validates {@link #txCbor()} against {@link #view()} and {@link #env()} and
 * compares the outcome with {@link #expected()}. {@link #network()}, {@link #constants()} and
 * {@link #state()} carry what the view model does not (era history, global parameters, the
 * Haskell-hardcoded constants a few scenarios move, and the fixture state in file order with its
 * original bytes and certificate pointers) for engines and golden tests that need them.</p>
 *
 * @param name           file name without {@code .json}, e.g. {@code 00005-pass-registered-...}
 * @param path           the fixture file
 * @param title          the fixture's title
 * @param txCbor         the transaction bytes, exactly as in the fixture
 * @param view           the initial state as a {@link InMemoryLedgerView}
 * @param env            the validation environment at the fixture's point
 * @param protocolParams the fixture's protocol parameters, as the view holds them
 * @param expected       the expected verdict, with the Haskell rule and constructor
 * @param network        network magic, era history and global parameters
 * @param constants      the Haskell-hardcoded values the fixture moves away from their defaults
 * @param state          the fixture state in file order, with original bytes
 */
public record AmaruScenario(String name, Path path, String title, byte[] txCbor, InMemoryLedgerView view,
                            ValidationEnv env, ProtocolParams protocolParams, Expected expected,
                            Network network, LedgerConstants constants, State state) {

    public AmaruScenario {
        Objects.requireNonNull(name, "name");
        txCbor = Objects.requireNonNull(txCbor, "txCbor").clone();
    }

    @Override
    public byte[] txCbor() {
        return txCbor.clone();
    }

    @Override
    public String toString() {
        return name;
    }

    /** The fixture's {@code expected} field. */
    public sealed interface Expected permits Expected.Pass, Expected.DecodingFailure, Expected.Predicate {

        /** {@code "Pass"}. */
        record Pass() implements Expected {
        }

        /** {@code {"decoding_failure": true}}: the transaction must not decode. */
        record DecodingFailure() implements Expected {
        }

        /**
         * {@code {"predicate": …}}, mapped to Haskell through {@link AmaruCorpusNames}.
         *
         * @param corpusName  Amaru's predicate name as written in the fixture
         * @param rule        the Haskell rule at ADR-056's pinned cardano-ledger revision
         * @param constructor the Haskell predicate-failure constructor
         * @param phase       phase 2 for {@code ValidationTagMismatch}, otherwise phase 1
         * @param description the fixture's {@code description} ({@code PassedUnexpectedly} /
         *                    {@code FailedUnexpectedly} for {@code ValidationTagMismatch}), or null
         */
        record Predicate(String corpusName, LedgerRuleName rule, String constructor, LedgerFailure.Phase phase,
                         String description) implements Expected {
            /** @return {@code RULE.Constructor} */
            public String qualifiedName() {
                return rule.name() + "." + constructor;
            }
        }
    }

    /**
     * Network magic, era history and global parameters (Amaru's {@code GlobalParameters} for the
     * fixture's named network).
     */
    public record Network(String name, long networkMagic, long stabilityWindow, List<EraSummary> eras,
                          GlobalParameters globalParameters) {
        public Network {
            eras = List.copyOf(eras);
        }
    }

    /**
     * @param start          the era's start
     * @param end            the era's end, or null when open-ended
     * @param epochSizeSlots slots per epoch
     * @param slotLengthMs   slot length in milliseconds
     * @param eraTag         Amaru's {@code EraName} number: 1 Byron … 7 Conway, 8 Dijkstra
     */
    public record EraSummary(EraBound start, EraBound end, long epochSizeSlots, long slotLengthMs, int eraTag) {
    }

    /** @param timeMs time since system start, in milliseconds */
    public record EraBound(long timeMs, long slot, long epoch) {
    }

    /** Amaru's {@code GlobalParameters} (the genesis values it does not read from the ledger state). */
    public record GlobalParameters(long securityParam, long epochLengthScaleFactor, long activeSlotCoeffInverse,
                                   BigInteger maxLovelaceSupply, long slotsPerKesPeriod, int maxKesEvolution,
                                   long systemStartMs) {
    }

    /**
     * Values Haskell hardcodes instead of storing them in the protocol parameters, when the fixture
     * moves them; each field is null when the fixture keeps Haskell's value (204800, 1048576, 25600,
     * 12/10). Production hosts never send them (INTERFACE.md, request key 7).
     */
    public record LedgerConstants(Long maxRefScriptSizePerTx, Long maxRefScriptSizePerBlock,
                                  Long refScriptCostStride, BigInteger refScriptCostMultiplierNumerator,
                                  BigInteger refScriptCostMultiplierDenominator) {

        public static final LedgerConstants NONE = new LedgerConstants(null, null, null, null, null);

        public boolean isNone() {
            return equals(NONE);
        }
    }

    /** A certificate pointer: slot, transaction index, certificate index. */
    public record CertPointer(long slot, long transactionIndex, long certificateIndex) {
    }

    /** A UTxO entry with the output exactly as the fixture encodes it. */
    public record RawUtxo(Outpoint outpoint, byte[] outputCbor) {
        public RawUtxo {
            outputCbor = outputCbor.clone();
        }

        @Override
        public byte[] outputCbor() {
            return outputCbor.clone();
        }
    }

    /** An account with its delegation pointers (null when not delegated). */
    public record RawAccount(CredentialKey credential, BigInteger deposit, BigInteger rewards, PoolId pool,
                             CertPointer poolPointer, DRepTarget drep, CertPointer drepPointer) {
    }

    public record RawDRep(CredentialKey credential, BigInteger deposit, CertPointer registeredAt, long validUntil) {
    }

    /**
     * @param hot        the authorised hot credential, or null
     * @param resigned   whether the member resigned
     * @param validUntil the term's last epoch, or null when the member holds no term
     */
    public record RawCommitteeMember(CredentialKey cold, CredentialKey hot, boolean resigned, Long validUntil) {
    }

    /** @param kind the fixture's lineage string, e.g. {@code HardFork(10.0)} */
    public record RawProposal(GovActionId id, String kind, long validUntil) {
    }

    /**
     * The fixture's {@code initial_state} and {@code point}, in file order.
     *
     * @param guardrailScriptHash hex, or null
     */
    public record State(List<RawUtxo> utxo, List<RawAccount> accounts, List<PoolId> pools, List<RawDRep> dreps,
                        List<RawCommitteeMember> committee, List<RawProposal> proposals, EnactedRoots roots,
                        long dormantEpochs, BigInteger treasury, String guardrailScriptHash, long slot,
                        long transactionIndex) {
        public State {
            utxo = List.copyOf(utxo);
            accounts = List.copyOf(accounts);
            pools = List.copyOf(pools);
            dreps = List.copyOf(dreps);
            committee = List.copyOf(committee);
            proposals = List.copyOf(proposals);
        }
    }
}
