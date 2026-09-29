package org.yanoproject.tx;

import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle.RecordedOutcome;
import org.yanoproject.ledger.rules.shadow.Verdict;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Replays shadow replay bundles ({@link ShadowDumpBundle}, written by admission shadows and by shadow sync, ADR-056
 * §7 and Phase 7a) through an engine, against exactly the ledger-view reads the bundle recorded.
 *
 * <p>Command line (the java engine, Plutus by Scalus):</p>
 * <pre>
 * java -cp app/build/yano.jar org.yanoproject.tx.ShadowBundleReplay &lt;bundle.json | directory&gt;...
 * </pre>
 * Prints one line per bundle: the recorded verdicts (the chain's or admission's, and the engine's) and the replayed
 * one. Exit code 0 when every replay reproduces the recorded engine verdict, 1 otherwise, 2 on a usage error. In a
 * test, {@link #replay(Path, LedgerValidationEngine)} is the same check.
 */
public final class ShadowBundleReplay {

    /**
     * One replayed bundle.
     *
     * @param file       the bundle
     * @param txHash     the transaction id
     * @param reference  the recorded reference verdict (the chain for shadow sync, the admission engine otherwise)
     * @param recorded   the recorded engine verdict
     * @param replayed   the verdict of the replay
     * @param outcome    the replay's outcome
     */
    public record Result(Path file, String txHash, Verdict reference, Verdict recorded, Verdict replayed,
                         TxValidationOutcome outcome) {

        /** @return true when the replay gives the recorded engine verdict (the finding reproduces) */
        public boolean reproduced() {
            return Verdict.compare(recorded, replayed).isEmpty();
        }

        /** @return true when the replay agrees with the reference (the finding no longer reproduces as a finding) */
        public boolean agreesWithReference() {
            return Verdict.compare(reference, replayed).isEmpty();
        }

        @Override
        public String toString() {
            return txHash + " reference=" + reference.label() + " recorded=" + recorded.label() + " replayed="
                    + replayed.label() + (reproduced() ? " (reproduced)" : " (NOT reproduced)") + " [" + file + "]";
        }
    }

    private ShadowBundleReplay() {
    }

    /** Replays one bundle through {@code engine}. */
    public static Result replay(Path file, LedgerValidationEngine engine) {
        Objects.requireNonNull(engine, "engine");
        ShadowDumpBundle bundle = ShadowDumpBundle.read(file);
        TxValidationOutcome outcome = engine.validate(bundle.replayRequest());
        RecordedOutcome replayed = RecordedOutcome.of(engine.name(), outcome);
        return new Result(file, bundle.txHash(), bundle.admission().verdict(), bundle.shadow().verdict(),
                replayed.verdict(), outcome);
    }

    /** @return the bundles in {@code path}: the file itself, or the {@code *.json} files of a directory */
    public static List<Path> bundles(Path path) {
        if (!Files.isDirectory(path)) {
            return List.of(path);
        }
        try (Stream<Path> files = Files.list(path)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + path, e);
        }
    }

    /** @return the java engine with the Scalus phase-2 evaluator, as shadow sync runs it */
    public static LedgerValidationEngine javaEngine() {
        return new JavaLedgerValidationEngine(new ScalusScriptPhaseEvaluator());
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: ShadowBundleReplay <bundle.json | directory>...");
            System.exit(2);
        }
        LedgerValidationEngine engine = javaEngine();
        List<Result> results = new ArrayList<>();
        for (String arg : args) {
            for (Path bundle : bundles(Path.of(arg))) {
                Result result = replay(bundle, engine);
                results.add(result);
                System.out.println(result);
                if (result.outcome() instanceof TxValidationOutcome.Invalid invalid) {
                    invalid.failures().forEach(f -> System.out.println("    " + f.qualifiedName() + ": " + f.detail()));
                }
            }
        }
        long reproduced = results.stream().filter(Result::reproduced).count();
        System.out.println(reproduced + "/" + results.size() + " reproduced");
        System.exit(reproduced == results.size() ? 0 : 1);
    }
}
