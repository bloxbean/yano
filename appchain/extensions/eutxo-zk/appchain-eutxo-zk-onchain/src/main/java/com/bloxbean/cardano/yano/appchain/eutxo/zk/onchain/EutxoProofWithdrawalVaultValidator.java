package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Restricted Z4 proof-withdrawal vault.
 *
 * <p>Each proof-advanced validity root authorizes one aggregate lovelace
 * withdrawal to the deployment-fixed destination. The vault thread records
 * the consumed root height and sequence, preventing replay.</p>
 */
@SpendingValidator
public final class EutxoProofWithdrawalVaultValidator {
    @Param
    static byte[] vaultThreadPolicyId;

    @Param
    static byte[] vaultThreadAssetName;

    @Param
    static byte[] rootThreadPolicyId;

    @Param
    static byte[] rootThreadAssetName;

    @Param
    static byte[] rootScriptHash;

    @Param
    static byte[] fundsVaultScriptHash;

    @Param
    static PlutusData payoutAddress;

    @Param
    static byte[] migrationAuthority;

    @Param
    static byte[] migrationTargetScriptHash;

    record VaultState(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger nextSequence,
            BigInteger lastRootHeight,
            BigInteger generation
    ) {
    }

    record Action(
            BigInteger kind,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger sequence,
            BigInteger rootHeight,
            BigInteger withdrawalLovelace
    ) {
    }

    record Root(
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger height,
            BigInteger withdrawalLovelace,
            BigInteger generation
    ) {
    }

    private EutxoProofWithdrawalVaultValidator() {
    }

    @Entrypoint
    public static boolean validate(
            VaultState current,
            Action action,
            ScriptContext context
    ) {
        if (!stateShapeValid(current) || !stateValid(current)) {
            return false;
        }
        if (action.kind().signum() == 0) {
            return withdraw(current, action, context);
        }
        return action.kind().equals(BigInteger.ONE)
                && migrate(current, context);
    }

    private static boolean withdraw(
            VaultState current,
            Action action,
            ScriptContext context
    ) {
        Optional<Root> root = acceptedRoot(context);
        Optional<TxInInfo> ownInput = ContextsLib.findOwnInput(context);
        Optional<TxOut> fundsInput = fundsVaultInput(context);
        Optional<TxOut> fundsOutput = fundsVaultOutput(context);
        JulcList<TxOut> continuing =
                ContextsLib.getContinuingOutputs(context);
        if (root.isEmpty() || ownInput.isEmpty()
                || fundsInput.isEmpty() || fundsOutput.isEmpty()
                || !hasVaultThread(ownInput.get().resolved())
                || !singleVaultThreadTransition(context)
                || continuing.size() != 1
                || !hasVaultThread(continuing.head())
                || !nextStateValid(
                continuing.head().datum(), current, action)) {
            return false;
        }
        Root accepted = root.get();
        if (!Builtins.equalsByteString(
                action.chainId(), current.chainId())
                || !Builtins.equalsByteString(
                accepted.chainId(), current.chainId())
                || !action.bridgeEpoch().equals(
                current.bridgeEpoch())
                || !accepted.bridgeEpoch().equals(
                current.bridgeEpoch())
                || !accepted.generation().equals(
                current.generation())
                || !action.sequence().equals(
                current.nextSequence())
                || !action.rootHeight().equals(accepted.height())
                || action.rootHeight().compareTo(
                current.lastRootHeight()) <= 0
                || action.withdrawalLovelace().signum() <= 0
                || !action.withdrawalLovelace().equals(
                accepted.withdrawalLovelace())) {
            return false;
        }
        BigInteger payoutCount = BigInteger.ZERO;
        for (TxOut output : context.txInfo().outputs()) {
            if (Builtins.equalsData(
                    output.address().toPlutusData(), payoutAddress)
                    && ValuesLib.lovelaceOf(output.value()).equals(
                    action.withdrawalLovelace())) {
                payoutCount = payoutCount.add(BigInteger.ONE);
            }
        }
        return payoutCount.equals(BigInteger.ONE)
                && ValuesLib.eq(
                ownInput.get().resolved().value(),
                continuing.head().value())
                && exactVaultValueTransition(
                fundsInput.get(),
                fundsOutput.get(),
                action.withdrawalLovelace());
    }

    private static boolean migrate(
            VaultState current,
            ScriptContext context
    ) {
        if (Builtins.lengthOfByteString(migrationAuthority) != 28
                || Builtins.lengthOfByteString(
                migrationTargetScriptHash) != 28
                || !ContextsLib.signedBy(
                context.txInfo(), migrationAuthority)) {
            return false;
        }
        JulcList<TxOut> targets = ContextsLib.scriptOutputsAt(
                context.txInfo(), migrationTargetScriptHash);
        Optional<TxInInfo> ownInput = ContextsLib.findOwnInput(context);
        return ownInput.isPresent()
                && hasVaultThread(ownInput.get().resolved())
                && singleVaultThreadTransition(context)
                && targets.size() == 1
                && hasVaultThread(targets.head())
                && migratedStateValid(
                targets.head().datum(), current)
                && ValuesLib.eq(
                ownInput.get().resolved().value(),
                targets.head().value());
    }

    static boolean stateShapeValid(VaultState state) {
        PlutusData fields = Builtins.sndPair(
                Builtins.unConstrData(state));
        PlutusData f1 = Builtins.tailList(fields);
        PlutusData f2 = Builtins.tailList(f1);
        PlutusData f3 = Builtins.tailList(f2);
        PlutusData f4 = Builtins.tailList(f3);
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData f6 = Builtins.tailList(f5);
        return Builtins.constrTag(state) == 0
                && Builtins.nullList(f6);
    }

    static boolean stateValid(VaultState state) {
        return state.version().equals(BigInteger.ONE)
                && Builtins.lengthOfByteString(state.chainId()) >= 1
                && Builtins.lengthOfByteString(state.chainId()) <= 128
                && state.bridgeEpoch().signum() >= 0
                && state.nextSequence().signum() >= 0
                && state.lastRootHeight().signum() >= 0
                && state.generation().signum() >= 0;
    }

    private static Optional<Root> acceptedRoot(
            ScriptContext context
    ) {
        Optional<Root> result = Optional.empty();
        BigInteger count = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().referenceInputs()) {
            if (ValuesLib.assetOf(
                    input.resolved().value(),
                    rootThreadPolicyId,
                    rootThreadAssetName).equals(BigInteger.ONE)
                    && atScriptAddress(
                    input.resolved(), rootScriptHash)) {
                count = count.add(BigInteger.ONE);
                result = root(input.resolved().datum());
            }
        }
        return count.equals(BigInteger.ONE)
                ? result : Optional.empty();
    }

    private static Optional<Root> root(OutputDatum datum) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return rootData(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<Root> rootData(PlutusData value) {
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version =
                Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chain =
                Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch =
                Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger height =
                Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData f6 = Builtins.tailList(f5);
        PlutusData f7 = Builtins.tailList(f6);
        BigInteger withdrawal =
                Builtins.unIData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        BigInteger generation =
                Builtins.unIData(Builtins.headList(f8));
        if (Builtins.constrTag(value) != 0
                || !version.equals(BigInteger.ONE)
                || !Builtins.nullList(Builtins.tailList(f8))
                || Builtins.lengthOfByteString(chain) < 1
                || Builtins.lengthOfByteString(chain) > 128
                || epoch.signum() < 0
                || height.signum() < 0
                || withdrawal.signum() < 0
                || generation.signum() < 0) {
            return Optional.empty();
        }
        return Optional.of(new Root(
                chain, epoch, height, withdrawal, generation));
    }

    private static boolean nextStateValid(
            OutputDatum datum,
            VaultState current,
            Action action
    ) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return stateDataValid(
                    inline.datum(), current, action, false);
        }
        return false;
    }

    private static boolean migratedStateValid(
            OutputDatum datum,
            VaultState current
    ) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return stateDataValid(
                    inline.datum(), current,
                    new Action(
                            BigInteger.ONE,
                            current.chainId(),
                            current.bridgeEpoch(),
                            current.nextSequence(),
                            current.lastRootHeight(),
                            BigInteger.ZERO),
                    true);
        }
        return false;
    }

    private static boolean stateDataValid(
            PlutusData value,
            VaultState current,
            Action action,
            boolean migration
    ) {
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version =
                Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chain =
                Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch =
                Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger sequence =
                Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        BigInteger rootHeight =
                Builtins.unIData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        BigInteger generation =
                Builtins.unIData(Builtins.headList(f5));
        if (Builtins.constrTag(value) != 0
                || !version.equals(BigInteger.ONE)
                || !Builtins.nullList(Builtins.tailList(f5))
                || !Builtins.equalsByteString(
                chain, current.chainId())) {
            return false;
        }
        if (migration) {
            return epoch.equals(
                    current.bridgeEpoch().add(BigInteger.ONE))
                    && sequence.equals(current.nextSequence())
                    && rootHeight.equals(current.lastRootHeight())
                    && generation.equals(
                    current.generation().add(BigInteger.ONE));
        }
        return epoch.equals(current.bridgeEpoch())
                && sequence.equals(
                current.nextSequence().add(BigInteger.ONE))
                && sequence.equals(
                action.sequence().add(BigInteger.ONE))
                && rootHeight.equals(action.rootHeight())
                && generation.equals(current.generation());
    }

    private static boolean exactVaultValueTransition(
            TxOut input,
            TxOut continuing,
            BigInteger withdrawal
    ) {
        return ValuesLib.eq(
                input.value(),
                ValuesLib.add(
                        continuing.value(),
                        ValuesLib.singleton(
                                Builtins.emptyByteString(),
                                Builtins.emptyByteString(),
                                withdrawal)));
    }

    private static Optional<TxOut> fundsVaultInput(
            ScriptContext context
    ) {
        Optional<TxOut> result = Optional.empty();
        BigInteger count = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().inputs()) {
            if (atScriptAddress(
                    input.resolved(), fundsVaultScriptHash)) {
                count = count.add(BigInteger.ONE);
                result = Optional.of(input.resolved());
            }
        }
        return count.equals(BigInteger.ONE)
                ? result : Optional.empty();
    }

    private static Optional<TxOut> fundsVaultOutput(
            ScriptContext context
    ) {
        Optional<TxOut> result = Optional.empty();
        BigInteger count = BigInteger.ZERO;
        for (TxOut output : context.txInfo().outputs()) {
            if (atScriptAddress(output, fundsVaultScriptHash)) {
                count = count.add(BigInteger.ONE);
                result = Optional.of(output);
            }
        }
        return count.equals(BigInteger.ONE)
                ? result : Optional.empty();
    }

    private static boolean hasVaultThread(TxOut output) {
        return ValuesLib.assetOf(
                output.value(),
                vaultThreadPolicyId,
                vaultThreadAssetName).equals(BigInteger.ONE);
    }

    private static boolean singleVaultThreadTransition(
            ScriptContext context
    ) {
        BigInteger inputs = BigInteger.ZERO;
        BigInteger outputs = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().inputs()) {
            if (hasVaultThread(input.resolved())) {
                inputs = inputs.add(BigInteger.ONE);
            }
        }
        for (TxOut output : context.txInfo().outputs()) {
            if (hasVaultThread(output)) {
                outputs = outputs.add(BigInteger.ONE);
            }
        }
        return inputs.equals(BigInteger.ONE)
                && outputs.equals(BigInteger.ONE);
    }

    private static boolean atScriptAddress(
            TxOut output,
            byte[] scriptHash
    ) {
        PlutusData credential =
                Builtins.headList(Builtins.constrFields(
                        output.address().toPlutusData()));
        PlutusData fields = Builtins.constrFields(credential);
        return Builtins.constrTag(credential) == 1
                && !Builtins.nullList(fields)
                && Builtins.nullList(Builtins.tailList(fields))
                && Builtins.equalsByteString(
                Builtins.unBData(Builtins.headList(fields)),
                scriptHash);
    }
}
