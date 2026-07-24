package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoProofWithdrawalVaultValidatorTest extends ContractTest {
    private static final byte[] VAULT_POLICY = fill(28, 51);
    private static final byte[] ROOT_POLICY = fill(28, 52);
    private static final byte[] VAULT_SCRIPT = fill(28, 53);
    private static final byte[] ROOT_SCRIPT = fill(28, 54);
    private static final byte[] MIGRATION_AUTHORITY = fill(28, 55);
    private static final byte[] MIGRATION_TARGET = fill(28, 56);
    private static final Address PAYOUT = new Address(
            new Credential.PubKeyCredential(
                    new PubKeyHash(fill(28, 57))),
            Optional.empty());
    private static final BigInteger WITHDRAWAL = BigInteger.valueOf(30);
    private static final BigInteger ROOT_HEIGHT = BigInteger.valueOf(7);
    private static final BigInteger VAULT_INPUT = BigInteger.valueOf(100);

    @Test
    void proofFinalizedRootPermissionlesslyReleasesExactlyOnce() {
        Program program = program();
        assertSuccess(evaluate(
                program,
                withdrawalContext(6, 7, 30, 30, 1, false)));
        assertFailure(evaluate(
                program,
                withdrawalContext(7, 7, 30, 30, 1, false)));
        assertFailure(evaluate(
                program,
                withdrawalContext(6, 6, 30, 30, 1, false)));
        assertFailure(evaluate(
                program,
                withdrawalContext(6, 7, 31, 30, 1, false)));
        assertFailure(evaluate(
                program,
                withdrawalContext(6, 7, 30, 30, 2, false)));
        assertFailure(evaluate(
                program,
                withdrawalContext(6, 7, 30, 30, 1, true)));
    }

    @Test
    void vaultMigrationIsAuthorizedAndPreservesValueAndCursor() {
        Program program = program();
        assertSuccess(evaluate(
                program, migrationContext(true, true)));
        assertFailure(evaluate(
                program, migrationContext(false, true)));
        assertFailure(evaluate(
                program, migrationContext(true, false)));
    }

    @Test
    void releasePinnedJulcCompilesWithdrawalValidator() {
        assertThat(compileValidator(
                EutxoProofWithdrawalVaultValidator.class)
                .program()).isNotNull();
    }

    private Program program() {
        return compileValidator(
                EutxoProofWithdrawalVaultValidator.class)
                .program()
                .applyParams(
                        PlutusData.bytes(VAULT_POLICY),
                        PlutusData.bytes(new byte[0]),
                        PlutusData.bytes(ROOT_POLICY),
                        PlutusData.bytes(new byte[0]),
                        PlutusData.bytes(ROOT_SCRIPT),
                        PAYOUT.toPlutusData(),
                        PlutusData.bytes(MIGRATION_AUTHORITY),
                        PlutusData.bytes(MIGRATION_TARGET));
    }

    private PlutusData withdrawalContext(
            long lastRootHeight,
            long rootHeight,
            long actionAmount,
            long rootAmount,
            int payoutCount,
            boolean competingVaultInput
    ) {
        PlutusData current = vaultState(
                0, 0, lastRootHeight, 0);
        PlutusData next = vaultState(
                0, 1, rootHeight, 0);
        PlutusData root = validityRoot(rootHeight, rootAmount, 0);
        PlutusData action = PlutusData.constr(
                0,
                PlutusData.integer(0),
                PlutusData.bytes(chain()),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(rootHeight),
                PlutusData.integer(actionAmount));
        TxOutRef ownRef = ref(1);
        Value inputValue = threadedValue(
                VAULT_INPUT, VAULT_POLICY);
        Value nextValue = threadedValue(
                VAULT_INPUT.subtract(BigInteger.valueOf(actionAmount)),
                VAULT_POLICY);
        var builder = spendingContext(ownRef, current)
                .redeemer(action)
                .input(new TxInInfo(
                        ownRef,
                        output(
                                scriptAddress(VAULT_SCRIPT),
                                inputValue,
                                current)))
                .referenceInput(new TxInInfo(
                        ref(2),
                        output(
                                scriptAddress(ROOT_SCRIPT),
                                threadedValue(
                                        BigInteger.valueOf(2_000_000),
                                        ROOT_POLICY),
                                root)))
                .output(output(
                        scriptAddress(VAULT_SCRIPT),
                        nextValue,
                        next));
        for (int index = 0; index < payoutCount; index++) {
            builder.output(new TxOut(
                    PAYOUT,
                    Value.lovelace(BigInteger.valueOf(rootAmount)),
                    new OutputDatum.NoOutputDatum(),
                    Optional.empty()));
        }
        if (competingVaultInput) {
            builder.input(new TxInInfo(
                    ref(3),
                    output(
                            scriptAddress(VAULT_SCRIPT),
                            threadedValue(BigInteger.TEN, VAULT_POLICY),
                            current)));
        }
        return builder.buildPlutusData();
    }

    private PlutusData migrationContext(
            boolean signed,
            boolean preserved
    ) {
        PlutusData current = vaultState(0, 2, 7, 0);
        PlutusData next = vaultState(
                1, 2, preserved ? 7 : 8, 1);
        PlutusData action = PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes(chain()),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(0));
        TxOutRef ownRef = ref(4);
        Value value = threadedValue(
                VAULT_INPUT, VAULT_POLICY);
        var builder = spendingContext(ownRef, current)
                .redeemer(action)
                .input(new TxInInfo(
                        ownRef,
                        output(
                                scriptAddress(VAULT_SCRIPT),
                                value,
                                current)))
                .output(output(
                        scriptAddress(MIGRATION_TARGET),
                        value,
                        next));
        if (signed) {
            builder.signer(MIGRATION_AUTHORITY);
        }
        return builder.buildPlutusData();
    }

    private static PlutusData vaultState(
            long epoch,
            long nextSequence,
            long lastRootHeight,
            long generation
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes(chain()),
                PlutusData.integer(epoch),
                PlutusData.integer(nextSequence),
                PlutusData.integer(lastRootHeight),
                PlutusData.integer(generation));
    }

    private static PlutusData validityRoot(
            long height,
            long withdrawal,
            long generation
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes(chain()),
                PlutusData.integer(0),
                PlutusData.integer(height),
                PlutusData.integer(11),
                PlutusData.integer(12),
                PlutusData.integer(13),
                PlutusData.integer(withdrawal),
                PlutusData.integer(generation));
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

    private static Value threadedValue(
            BigInteger lovelace,
            byte[] policy
    ) {
        return Value.lovelace(lovelace)
                .merge(Value.singleton(
                        new PolicyId(policy),
                        new TokenName(new byte[0]),
                        BigInteger.ONE));
    }

    private static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    private static TxOutRef ref(int value) {
        return new TxOutRef(
                new TxId(fill(32, value)), BigInteger.ZERO);
    }

    private static byte[] chain() {
        return "payments".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
