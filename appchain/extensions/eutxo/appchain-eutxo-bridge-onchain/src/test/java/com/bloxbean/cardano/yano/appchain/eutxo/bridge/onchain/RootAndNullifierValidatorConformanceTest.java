package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

class RootAndNullifierValidatorConformanceTest extends ContractTest {
    private static final byte[] ROOT_POLICY = fill(28, 41);
    private static final byte[] NULLIFIER_POLICY = fill(28, 42);
    private static final byte[] ROOT_SCRIPT = fill(28, 43);
    private static final byte[] NULLIFIER_SCRIPT = fill(28, 44);
    private static final byte[] PROOF_VAULT_SCRIPT = fill(28, 45);
    private static final byte[] MEMBER_1 = fill(32, 1);
    private static final byte[] MEMBER_2 = fill(32, 2);
    private static Program rootProgram;
    private static Program nullifierProgram;

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void rootAdvanceRequiresThresholdAndPreservesTheProfile() {
        PlutusData current = rootDatum(7, 42, 0, MEMBER_1, MEMBER_2);
        PlutusData next = rootDatum(7, 43, 0, MEMBER_1, MEMBER_2);

        assertSuccess(evaluate(
                rootProgram(), rootContext(current, next, true, true)));
        assertFailure(evaluate(
                rootProgram(), rootContext(current, next, true, false)));
        assertFailure(evaluate(
                rootProgram(),
                rootContext(
                        current,
                        rootDatum(7, 43, 0, MEMBER_1, fill(32, 3)),
                        true,
                        true)));
        assertFailure(evaluate(
                rootProgram(),
                rootContext(
                        current,
                        rootDatum(7, 41, 0, MEMBER_1, MEMBER_2),
                        true,
                        true)));
        assertFailure(evaluate(
                rootProgram(),
                rootContext(current, next, true, true, new byte[]{1})));
    }

    @Test
    void rootMigrationAndNullifierMigrationAreExplicit() {
        PlutusData currentRoot =
                rootDatum(7, 42, 0, MEMBER_1, MEMBER_2);
        PlutusData migratedRoot =
                rootDatum(8, 1, 1, MEMBER_1, MEMBER_2);
        assertSuccess(evaluate(
                rootProgram(),
                rootContext(currentRoot, migratedRoot, false, true)));

        PlutusData currentNullifier = nullifierDatum(7, 3, 0);
        PlutusData migratedNullifier = nullifierDatum(8, 0, 1);
        assertFailure(evaluate(
                nullifierProgram(),
                nullifierContext(
                        currentNullifier,
                        migratedNullifier,
                        PlutusData.constr(1),
                        false)));
        assertSuccess(evaluate(
                nullifierProgram(),
                nullifierContext(
                        currentNullifier,
                        migratedNullifier,
                        PlutusData.constr(1),
                        true)));
        assertFailure(evaluate(
                nullifierProgram(),
                nullifierContext(
                        currentNullifier,
                        migratedNullifier,
                        PlutusData.constr(1),
                        true,
                        false,
                        false,
                        true)));
    }

    @Test
    void nullifierAcceptsOnlyTheExactNextClaimSequence() {
        PlutusData current = nullifierDatum(7, 3, 0);
        PlutusData next = nullifierDatum(7, 4, 0);

        assertSuccess(evaluate(
                nullifierProgram(),
                nullifierContext(
                        current, next, proofRedeemer(7, 3), false)));
        assertFailure(evaluate(
                nullifierProgram(),
                nullifierContext(
                        current, next, proofRedeemer(7, 2), false)));
        assertFailure(evaluate(
                nullifierProgram(),
                nullifierContext(
                        current,
                        nullifierDatum(7, 5, 0),
                        proofRedeemer(7, 3),
                        false)));
        assertFailure(evaluate(
                nullifierProgram(),
                nullifierContext(
                        current, next, proofRedeemer(7, 3),
                        false, true)));
        assertFailure(evaluate(
                nullifierProgram(),
                nullifierContext(
                        current, next, proofRedeemer(7, 3),
                        false, false, false)));
    }

    private Program rootProgram() {
        if (rootProgram == null) {
            rootProgram = compileValidator(FederatedRootValidator.class)
                    .program()
                    .applyParams(
                            PlutusData.bytes(ROOT_POLICY),
                            PlutusData.bytes(new byte[0]));
        }
        return rootProgram;
    }

    private Program nullifierProgram() {
        if (nullifierProgram == null) {
            nullifierProgram =
                    compileValidator(NullifierStateValidator.class)
                            .program()
                            .applyParams(
                                    PlutusData.bytes(NULLIFIER_POLICY),
                                    PlutusData.bytes(new byte[0]),
                                    PlutusData.bytes(PROOF_VAULT_SCRIPT),
                                    PlutusData.bytes(ROOT_SCRIPT),
                                    PlutusData.bytes(ROOT_POLICY),
                                    PlutusData.bytes(new byte[0]));
        }
        return nullifierProgram;
    }

    private PlutusData rootContext(
            PlutusData current,
            PlutusData next,
            boolean advance,
            boolean secondSignature
    ) {
        return rootContext(
                current, next, advance, secondSignature, new byte[0]);
    }

    private PlutusData rootContext(
            PlutusData current,
            PlutusData next,
            boolean advance,
            boolean secondSignature,
            byte[] nextThreadAssetName
    ) {
        TxOutRef ownRef = ref(51);
        Address address = scriptAddress(ROOT_SCRIPT);
        var builder = spendingContext(ownRef, current)
                .redeemer(PlutusData.integer(advance ? 0 : 1))
                .input(new TxInInfo(
                        ownRef,
                        output(address, threadValue(ROOT_POLICY), current)))
                .output(output(
                        address,
                        threadValue(
                                ROOT_POLICY, nextThreadAssetName),
                        next))
                .signer(Blake2bUtil.blake2bHash224(MEMBER_1));
        if (secondSignature) {
            builder.signer(Blake2bUtil.blake2bHash224(MEMBER_2));
        }
        return builder.buildPlutusData();
    }

    private PlutusData nullifierContext(
            PlutusData current,
            PlutusData next,
            PlutusData redeemer,
            boolean spendRoot
    ) {
        boolean proofAction =
                redeemer instanceof PlutusData.ConstrData constr
                        && constr.tag() == 0;
        return nullifierContext(
                current, next, redeemer, spendRoot, false,
                proofAction, false);
    }

    private PlutusData nullifierContext(
            PlutusData current,
            PlutusData next,
            PlutusData redeemer,
            boolean spendRoot,
            boolean divertThread
    ) {
        boolean proofAction =
                redeemer instanceof PlutusData.ConstrData constr
                        && constr.tag() == 0;
        return nullifierContext(
                current, next, redeemer, spendRoot, divertThread,
                proofAction, false);
    }

    private PlutusData nullifierContext(
            PlutusData current,
            PlutusData next,
            PlutusData redeemer,
            boolean spendRoot,
            boolean divertThread,
            boolean spendProofVault
    ) {
        return nullifierContext(
                current,
                next,
                redeemer,
                spendRoot,
                divertThread,
                spendProofVault,
                false);
    }

    private PlutusData nullifierContext(
            PlutusData current,
            PlutusData next,
            PlutusData redeemer,
            boolean spendRoot,
            boolean divertThread,
            boolean spendProofVault,
            boolean mismatchedRootMigration
    ) {
        TxOutRef ownRef = ref(61);
        Address address = scriptAddress(NULLIFIER_SCRIPT);
        var builder = spendingContext(ownRef, current)
                .redeemer(redeemer)
                .input(new TxInInfo(
                        ownRef,
                        output(
                                address,
                                threadValue(NULLIFIER_POLICY),
                                current)))
                .output(output(
                        divertThread
                                ? scriptAddress(fill(28, 99))
                                : address,
                        threadValue(NULLIFIER_POLICY),
                        next));
        if (spendRoot) {
            builder.input(new TxInInfo(
                    ref(62),
                    output(
                            scriptAddress(ROOT_SCRIPT),
                            threadValue(ROOT_POLICY),
                            rootDatum(
                                    7, 42, 0,
                                    MEMBER_1, MEMBER_2))))
                    .output(output(
                            scriptAddress(ROOT_SCRIPT),
                            threadValue(ROOT_POLICY),
                            rootDatum(
                                    mismatchedRootMigration ? 9 : 8,
                                    1,
                                    1,
                                    MEMBER_1,
                                    MEMBER_2)));
        }
        if (spendProofVault) {
            builder.input(new TxInInfo(
                    ref(63),
                    output(
                            scriptAddress(PROOF_VAULT_SCRIPT),
                            Value.lovelace(BigInteger.valueOf(2_000_000)),
                            PlutusData.UNIT)));
        }
        return builder.buildPlutusData();
    }

    private static PlutusData rootDatum(
            long epoch,
            long height,
            long generation,
            byte[] first,
            byte[] second
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes(
                        "payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(epoch),
                PlutusData.integer(height),
                PlutusData.bytes(fill(32, (int) height)),
                PlutusData.list(
                        PlutusData.bytes(first),
                        PlutusData.bytes(second)),
                PlutusData.integer(2),
                PlutusData.integer(generation));
    }

    private static PlutusData nullifierDatum(
            long epoch,
            long sequence,
            long generation
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes(
                        "payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(epoch),
                PlutusData.integer(sequence),
                PlutusData.integer(generation));
    }

    private static PlutusData proofRedeemer(long epoch, long sequence) {
        PlutusData claim = PlutusData.constr(
                3,
                PlutusData.integer(1),
                PlutusData.bytes(
                        "payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(epoch),
                PlutusData.integer(sequence),
                PlutusData.bytes(fill(32, 71)),
                PlutusData.UNIT,
                PlutusData.integer(1));
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                claim,
                PlutusData.bytes(new byte[]{1}),
                PlutusData.bytes(fill(32, 2)),
                PlutusData.bytes(new byte[]{(byte) 0xFF}),
                PlutusData.list());
    }

    private static TxOut output(
            Address address,
            Value value,
            PlutusData datum
    ) {
        return new TxOut(
                address,
                value,
                new OutputDatum.OutputDatumInline(datum),
                Optional.empty());
    }

    private static Value threadValue(byte[] policy) {
        return threadValue(policy, new byte[0]);
    }

    private static Value threadValue(
            byte[] policy,
            byte[] assetName
    ) {
        return Value.lovelace(BigInteger.valueOf(2_000_000))
                .merge(Value.singleton(
                        new PolicyId(policy),
                        new TokenName(assetName),
                        BigInteger.ONE));
    }

    private static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    private static TxOutRef ref(int value) {
        return new TxOutRef(
                new TxId(fill(32, value)),
                BigInteger.ZERO);
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] concatenate(byte[] left, byte[] right) {
        byte[] joined =
                java.util.Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return joined;
    }
}
