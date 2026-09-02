package com.bloxbean.cardano.yano.compat.contract;

import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

/**
 * Smallest useful BLS12-381 spending validator: the redeemer carries a compressed
 * G1 point, the datum carries the compressed point the locker expects it to double
 * to, and the script passes only when {@code point + point == expected}.
 *
 * <p>It exists to exercise the BLS12-381 Plutus builtins end to end — that is what
 * pulls the {@code blst} JNI library into a running node. In a GraalVM native image
 * the library only loads if its {@code .so}/{@code .dylib} is registered as an image
 * resource, so a node that is missing that configuration fails here and nowhere else.
 * Three builtins are touched ({@code uncompress}, {@code G1_add}, {@code G1_equal});
 * the cost is a few million CPU units, far below any tx limit.</p>
 *
 * <p>The zeroj use-case validators are the realistic BLS contracts, but every one of
 * them is a full Groth16 or PlonK verifier and needs a proving stack to produce a
 * passing witness. For a smoke test that is machinery without benefit: the failure
 * being detected is the JNI library load, which any single BLS builtin triggers.</p>
 *
 * <p>Wrong expected point ⇒ script failure. That negative case is what proves the
 * builtins really ran rather than the validator merely returning true.</p>
 */
@SpendingValidator
public class BlsDoublingValidator {

    /** Written at lock time: the compressed G1 point the redeemer must double to. */
    record BlsDatum(byte[] expected) {}

    /** Supplied at spend time: a compressed G1 point (48 bytes). */
    record BlsRedeemer(byte[] point) {}

    @Entrypoint
    public static boolean validate(BlsDatum datum, BlsRedeemer redeemer, ScriptContext ctx) {
        byte[] point = Builtins.bls12_381_G1_uncompress(redeemer.point());
        byte[] doubled = Builtins.bls12_381_G1_add(point, point);
        byte[] expected = Builtins.bls12_381_G1_uncompress(datum.expected());
        return Builtins.bls12_381_G1_equal(doubled, expected);
    }
}
