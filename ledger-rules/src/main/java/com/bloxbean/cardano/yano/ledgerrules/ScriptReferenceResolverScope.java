package com.bloxbean.cardano.yano.ledgerrules;

import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-scoped reference-script view used while validating one transaction.
 *
 * <p>The ledger validator's {@code ScriptSupplier} is constructed once, while
 * mempool and block-local UTXOs vary per validation. This scope bridges those
 * lifetimes without exposing mempool indexes or retaining transaction data.</p>
 */
public final class ScriptReferenceResolverScope {
    private static final ThreadLocal<Map<String, byte[]>> CURRENT = new ThreadLocal<>();

    private ScriptReferenceResolverScope() {
    }

    public static Scope open(Iterable<Utxo> resolvedInputs) {
        Map<String, byte[]> scripts = new HashMap<>();
        if (resolvedInputs != null) {
            for (Utxo utxo : resolvedInputs) {
                if (utxo == null || utxo.referenceScriptHash() == null
                        || utxo.scriptRef() == null) continue;
                try {
                    scripts.put(normalize(utxo.referenceScriptHash()),
                            java.util.HexFormat.of().parseHex(utxo.scriptRef()));
                } catch (IllegalArgumentException ignored) {
                    // Malformed script bytes are handled by normal validation;
                    // do not let scope construction mask its typed result.
                }
            }
        }
        Map<String, byte[]> previous = CURRENT.get();
        CURRENT.set(Map.copyOf(scripts));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Optional<byte[]> resolve(String scriptHash) {
        if (scriptHash == null) return Optional.empty();
        Map<String, byte[]> scripts = CURRENT.get();
        if (scripts == null) return Optional.empty();
        byte[] bytes = scripts.get(normalize(scriptHash));
        return bytes != null ? Optional.of(Arrays.copyOf(bytes, bytes.length)) : Optional.empty();
    }

    private static String normalize(String scriptHash) {
        return scriptHash.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
