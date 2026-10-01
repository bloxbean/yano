package org.yanoproject.ledger.scripteval.phase2;

import com.bloxbean.cardano.client.util.HexUtil;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.ProtocolVersion;
import org.julclang.vm.java.JavaVmProvider;
import org.yanoproject.ledger.rules.conway.utxow.PlutusScriptDecoder;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The julc CEK machine ({@code julc-vm-java}'s {@link JavaVmProvider}) as the ledger runs Plutus: a script decoded as
 * a program of its language at the protocol version, applied to its arguments, evaluated with the redeemer's declared
 * ExUnits as the budget and the protocol parameters' cost model; a PlutusV3 script must return {@code ()}
 * (plutus-ledger-api {@code InvalidReturnValue}, Common/Eval.hs:300-308).
 *
 * <p>A {@link JavaVmProvider} stores its cost model in the instance, and julc's {@code JulcTransactionEvaluator}
 * reconfigures one shared provider per call, which is not thread-safe. Here each (language, protocol version, cost
 * model) gets its own provider, configured once before it is published and never reconfigured; evaluation itself
 * creates a fresh machine per call. Thread-safe.</p>
 */
final class JulcMachine {

    /** One evaluation. */
    record Outcome(boolean success, long mem, long steps, List<String> logs, String error) {
    }

    private record ProviderKey(int language, int major, int minor, List<Long> costModel) {
    }

    private record ProgramKey(String hash, int language, int major) {
    }

    private static final int MAX_PROVIDERS = 64;
    private static final int MAX_PROGRAMS = 512;
    private static final EvalOptions OPTIONS = new EvalOptions(null, false, false);

    private final Map<ProviderKey, JavaVmProvider> providers = new ConcurrentHashMap<>();
    private final Map<ProgramKey, Program> programs = new ConcurrentHashMap<>();

    /**
     * Haskell {@code deserialiseScript} with julc's decoder: the {@code PlutusBinary} decodes as a program of
     * {@code language} with the protocol version's decoding limits. Builtin availability is
     * {@code PlutusScriptDecoder}'s ({@code UTXOW}), and the Plutus Core version is checked when a script runs
     * ({@code ScriptCollection#plutusCoreVersionError}), not here.
     */
    boolean isWellFormed(int language, byte[] script, int protocolMajor) {
        try {
            decode(language, script, target(language, protocolMajor, 0));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Runs a script.
     *
     * @param scriptHash    the script hash (hex), for the decoded-program cache
     * @param language      1, 2 or 3
     * @param script        the {@code PlutusBinary}
     * @param costModel     the language's cost model from the protocol parameters, in the ledger's order
     * @param args          the script arguments
     * @param mem           the declared memory budget
     * @param steps         the declared CPU budget
     */
    Outcome run(String scriptHash, int language, byte[] script, int protocolMajor, int protocolMinor,
                List<Long> costModel, List<PlutusData> args, long mem, long steps) {
        LedgerEvaluationTarget target = target(language, protocolMajor, protocolMinor);
        Program program;
        try {
            program = program(scriptHash, language, script, target);
        } catch (RuntimeException e) {
            // decodePlutusRunnable failing for a needed script (a reference script of a resolved input; witnesses are
            // checked in phase one) is a script failure.
            return new Outcome(false, 0, 0, List.of(), "the script does not decode: " + e.getMessage());
        }
        JavaVmProvider provider = provider(language, protocolMajor, protocolMinor, costModel);
        EvalResult result = provider.evaluateWithArgs(program, target, args, new ExBudget(steps, mem), OPTIONS);
        return switch (result) {
            case EvalResult.Success success -> {
                ExBudget used = success.consumed();
                if (used.memoryUnits() > mem || used.cpuSteps() > steps) {
                    yield new Outcome(false, used.memoryUnits(), used.cpuSteps(), success.traces(),
                            "budget exceeded: used " + used + ", declared mem=" + mem + " steps=" + steps);
                }
                if (language == 3 && !isUnit(success.resultTerm())) {
                    yield new Outcome(false, used.memoryUnits(), used.cpuSteps(), success.traces(),
                            "InvalidReturnValue: a PlutusV3 script must return BuiltinUnit");
                }
                yield new Outcome(true, used.memoryUnits(), used.cpuSteps(), success.traces(), null);
            }
            case EvalResult.Failure failure -> new Outcome(false, failure.consumed().memoryUnits(),
                    failure.consumed().cpuSteps(), failure.traces(), failure.error());
            case EvalResult.BudgetExhausted exhausted -> new Outcome(false, exhausted.consumed().memoryUnits(),
                    exhausted.consumed().cpuSteps(), exhausted.traces(), "budget exhausted (declared mem=" + mem
                    + " steps=" + steps + ")");
        };
    }

    private Program program(String scriptHash, int language, byte[] script, LedgerEvaluationTarget target) {
        ProgramKey key = new ProgramKey(scriptHash, language, target.protocolVersion().major());
        Program cached = programs.get(key);
        if (cached != null) {
            return cached;
        }
        Program program = decode(language, script, target);
        if (programs.size() >= MAX_PROGRAMS) {
            programs.clear();
        }
        programs.put(key, program);
        return program;
    }

    /** A V1/V2 script is decoded from its leading CBOR item ({@link PlutusScriptDecoder#leadingItem}). */
    private static Program decode(int language, byte[] script, LedgerEvaluationTarget target) {
        byte[] binary = language == 3 ? script : PlutusScriptDecoder.leadingItem(script);
        return JulcScriptAdapter.toProgram(HexUtil.encodeHexString(cborBytes(binary)), target);
    }

    private JavaVmProvider provider(int language, int major, int minor, List<Long> costModel) {
        ProviderKey key = new ProviderKey(language, major, minor, List.copyOf(costModel));
        JavaVmProvider cached = providers.get(key);
        if (cached != null) {
            return cached;
        }
        if (providers.size() >= MAX_PROVIDERS) {
            providers.clear();
        }
        return providers.computeIfAbsent(key, k -> {
            JavaVmProvider provider = new JavaVmProvider();
            long[] values = new long[k.costModel().size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = k.costModel().get(i);
            }
            provider.setCostModelParams(values, language(k.language()), k.major(), k.minor());
            return provider;
        });
    }

    private static boolean isUnit(Term term) {
        return term instanceof Term.Const constant && constant.value() instanceof Constant.UnitConst;
    }

    static LedgerEvaluationTarget target(int language, int major, int minor) {
        return new LedgerEvaluationTarget(language(language), new ProtocolVersion(major, minor));
    }

    static PlutusLanguage language(int language) {
        return switch (language) {
            case 1 -> PlutusLanguage.PLUTUS_V1;
            case 2 -> PlutusLanguage.PLUTUS_V2;
            case 3 -> PlutusLanguage.PLUTUS_V3;
            default -> throw new IllegalArgumentException("not a Plutus language: " + language);
        };
    }

    /** Wraps bytes in a definite CBOR byte string (julc's transport wrapper around the {@code PlutusBinary}). */
    static byte[] cborBytes(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 9);
        int length = payload.length;
        if (length < 24) {
            out.write(0x40 | length);
        } else if (length < 0x100) {
            out.write(0x58);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x59);
            out.write(length >>> 8);
            out.write(length);
        } else {
            out.write(0x5a);
            out.write(length >>> 24);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
        }
        out.writeBytes(payload);
        return out.toByteArray();
    }
}
