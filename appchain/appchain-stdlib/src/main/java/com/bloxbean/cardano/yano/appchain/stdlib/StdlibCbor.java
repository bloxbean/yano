package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

/** Frozen appchain-stdlib command/value CBOR work bounds (ADR-014 P0.1). */
final class StdlibCbor {
    private static final CborStructurePreflight.Limits COMMAND_LIMITS =
            new CborStructurePreflight.Limits(
                    AppChainConfig.MAX_MESSAGE_BYTES, 4, 32, 16,
                    AppChainConfig.MAX_MESSAGE_BYTES);
    private static final CborStructurePreflight.Limits NESTED_VALUE_LIMITS =
            new CborStructurePreflight.Limits(
                    AppChainConfig.MAX_MESSAGE_BYTES, 32, 100_000, 50_000,
                    AppChainConfig.MAX_MESSAGE_BYTES);
    private static final CborStructurePreflight.Limits PERSISTED_ENTRY_LIMITS =
            new CborStructurePreflight.Limits(
                    AppChainConfig.MAX_MESSAGE_BYTES, 4, 128, 64,
                    AppChainConfig.MAX_MESSAGE_BYTES);

    private StdlibCbor() {
    }

    static void requireCommand(byte[] bytes) {
        if (!CborStructurePreflight.accepts(bytes, COMMAND_LIMITS)) {
            throw new IllegalArgumentException("invalid bounded stdlib command CBOR");
        }
    }

    static boolean acceptsNestedValue(byte[] bytes) {
        return CborStructurePreflight.accepts(bytes, NESTED_VALUE_LIMITS);
    }

    static void requirePersistedEntry(byte[] bytes) {
        if (!CborStructurePreflight.accepts(bytes, PERSISTED_ENTRY_LIMITS)) {
            throw new IllegalArgumentException("invalid bounded stdlib state-entry CBOR");
        }
    }
}
