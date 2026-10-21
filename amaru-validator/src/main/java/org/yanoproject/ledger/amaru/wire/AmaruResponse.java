package org.yanoproject.ledger.amaru.wire;

import java.util.Map;

/** A {@code validate} response (INTERFACE.md, {@code validate_response}). */
public sealed interface AmaruResponse permits AmaruResponse.Ok, AmaruResponse.Invalid, AmaruResponse.Error {

    /** The transaction is valid (and, in full mode, its {@code is_valid} flag is right). */
    record Ok() implements AmaruResponse {
    }

    /**
     * A ledger rejection.
     *
     * @param phase       0 decode, 1 phase one, 2 phase two
     * @param rule        {@code UTXO UTXOW UTXOS LEDGER CERTS DELEG POOL GOVCERT GOV}, or {@code DECODE}
     * @param constructor the Haskell predicate-failure constructor, or null where Haskell has none
     * @param amaruError  Amaru's variant path, e.g. {@code PhaseOne.Certificates.StakePoolUnknown}
     * @param detail      Amaru's message
     * @param tagMismatch {@code PassedUnexpectedly} / {@code FailedUnexpectedly} for
     *                    {@code ValidationTagMismatch}, otherwise null
     */
    record Invalid(int phase, String rule, String constructor, String amaruError, String detail,
                   String tagMismatch) implements AmaruResponse {
    }

    /** The request could not be processed; says nothing about the transaction. */
    record Error(String message) implements AmaruResponse {
    }

    /**
     * @throws CborReader.CborException or {@link IllegalArgumentException} when the document is not a
     *                                  valid response
     */
    static AmaruResponse decode(byte[] document) {
        Map<?, ?> map = WireValues.map(CborReader.decode(document), "validate response");
        for (Object key : map.keySet()) {
            if (!(key instanceof Long k) || k < 0 || k > 7) {
                throw new IllegalArgumentException("unknown validate response key " + key);
            }
        }
        long status = WireValues.uint(map.get(0L), "status");
        return switch ((int) status) {
            case 0 -> new Ok();
            case 1 -> {
                long phase = WireValues.uint(map.get(1L), "phase");
                if (phase > 2) {
                    throw new IllegalArgumentException("unknown phase " + phase);
                }
                yield new Invalid((int) phase, WireValues.text(map.get(2L), "rule"),
                        map.get(3L) == null ? null : WireValues.text(map.get(3L), "constructor"),
                        WireValues.text(map.get(4L), "amaru_error"), WireValues.text(map.get(5L), "detail"),
                        map.get(6L) == null ? null : WireValues.text(map.get(6L), "tag_mismatch"));
            }
            case 2 -> new Error(WireValues.text(map.get(7L), "message"));
            default -> throw new IllegalArgumentException("unknown validate status " + status);
        };
    }
}
