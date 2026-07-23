package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
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
 * Singleton settlement cursor. Normal redeemers are proof withdrawals
 * (Constr 0); migration is Constr 1 and must spend the governed root state in
 * the same transaction.
 */
@SpendingValidator
public final class NullifierStateValidator {
    @Param
    static byte[] nullifierThreadPolicyId;

    @Param
    static byte[] nullifierThreadAssetName;

    @Param
    static byte[] proofVaultScriptHash;

    @Param
    static byte[] rootScriptHash;

    @Param
    static byte[] rootThreadPolicyId;

    @Param
    static byte[] rootThreadAssetName;

    record NullifierState(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger nextSequence,
            BigInteger generation
    ) {
    }

    private NullifierStateValidator() {
    }

    @Entrypoint
    public static boolean validate(
            NullifierState current,
            PlutusData redeemer,
            ScriptContext context
    ) {
        if (!shape(current)) {
            return false;
        }
        Optional<NullifierState> next = continuingState(context);
        if (next.isEmpty() || !shape(next.get())) {
            return false;
        }
        NullifierState output = next.get();
        if (!Builtins.equalsByteString(
                current.chainId(), output.chainId())) {
            return false;
        }
        if (Builtins.constrTag(redeemer) == 0) {
            Optional<ClaimIdentity> claim = claim(redeemer);
            return claim.isPresent()
                    && scriptStateIsSpent(
                    context, proofVaultScriptHash)
                    && Builtins.equalsByteString(
                    claim.get().chainId(), current.chainId())
                    && claim.get().bridgeEpoch().equals(
                    current.bridgeEpoch())
                    && claim.get().sequence().equals(
                    current.nextSequence())
                    && output.bridgeEpoch().equals(
                    current.bridgeEpoch())
                    && output.generation().equals(current.generation())
                    && output.nextSequence().equals(
                    current.nextSequence().add(BigInteger.ONE));
        }
        return Builtins.constrTag(redeemer) == 1
                && rootMigrationMatches(context, output)
                && output.bridgeEpoch().equals(
                current.bridgeEpoch().add(BigInteger.ONE))
                && output.generation().equals(
                current.generation().add(BigInteger.ONE))
                && output.nextSequence().equals(BigInteger.ZERO);
    }

    static boolean shape(NullifierState state) {
        return Builtins.constrTag(state) == 0
                && state.version().equals(BigInteger.ONE)
                && Builtins.lengthOfByteString(state.chainId()) >= 1
                && Builtins.lengthOfByteString(state.chainId()) <= 128
                && state.bridgeEpoch().signum() >= 0
                && state.nextSequence().signum() >= 0
                && state.generation().signum() >= 0;
    }

    private static Optional<NullifierState> continuingState(
            ScriptContext context
    ) {
        var continuing = ContextsLib.getContinuingOutputs(context);
        if (continuing.size() != 1) {
            return Optional.empty();
        }
        TxOut output = continuing.head();
        return ValuesLib.assetOf(
                output.value(),
                nullifierThreadPolicyId,
                nullifierThreadAssetName).equals(BigInteger.ONE)
                ? state(output.datum()) : Optional.empty();
    }

    private static Optional<NullifierState> state(OutputDatum datum) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return stateData(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<NullifierState> stateData(PlutusData value) {
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chain = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger sequence = Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        BigInteger generation = Builtins.unIData(Builtins.headList(f4));
        PlutusData trailing = Builtins.tailList(f4);
        if (Builtins.constrTag(value) != 0
                || !Builtins.nullList(trailing)) {
            return Optional.empty();
        }
        return Optional.of(new NullifierState(
                version, chain, epoch, sequence, generation));
    }

    private static Optional<ClaimIdentity> claim(PlutusData redeemer) {
        PlutusData fields = Builtins.constrFields(redeemer);
        BigInteger proofVersion =
                Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        PlutusData commitment = Builtins.headList(f1);
        PlutusData trailingProofFields = Builtins.tailList(
                Builtins.tailList(
                        Builtins.tailList(
                                Builtins.tailList(
                                        Builtins.tailList(f1)))));
        if (!proofVersion.equals(BigInteger.ONE)
                || !Builtins.nullList(trailingProofFields)
                || Builtins.constrTag(commitment) != 3) {
            return Optional.empty();
        }
        PlutusData claimFields = Builtins.constrFields(commitment);
        BigInteger claimVersion =
                Builtins.unIData(Builtins.headList(claimFields));
        PlutusData c1 = Builtins.tailList(claimFields);
        byte[] chain = Builtins.unBData(Builtins.headList(c1));
        PlutusData c2 = Builtins.tailList(c1);
        BigInteger epoch = Builtins.unIData(Builtins.headList(c2));
        PlutusData c3 = Builtins.tailList(c2);
        BigInteger sequence = Builtins.unIData(Builtins.headList(c3));
        if (!claimVersion.equals(BigInteger.ONE)) {
            return Optional.empty();
        }
        return Optional.of(new ClaimIdentity(chain, epoch, sequence));
    }

    private static boolean scriptStateIsSpent(
            ScriptContext context,
            byte[] scriptHash
    ) {
        boolean found = false;
        for (var input : context.txInfo().inputs()) {
            PlutusData credential =
                    input.resolved().address().credential().toPlutusData();
            PlutusData fields = Builtins.constrFields(credential);
            if (Builtins.constrTag(credential) == 1
                    && Builtins.equalsByteString(
                    Builtins.unBData(Builtins.headList(fields)),
                    scriptHash)) {
                found = true;
                break;
            }
        }
        return found;
    }

    private static boolean rootMigrationMatches(
            ScriptContext context,
            NullifierState next
    ) {
        BigInteger inputCount = BigInteger.ZERO;
        BigInteger outputCount = BigInteger.ZERO;
        Optional<RootIdentity> migrated = Optional.empty();
        for (TxInInfo input : context.txInfo().inputs()) {
            if (atScriptAddress(input.resolved(), rootScriptHash)
                    && ValuesLib.assetOf(
                    input.resolved().value(),
                    rootThreadPolicyId,
                    rootThreadAssetName).equals(BigInteger.ONE)) {
                inputCount = inputCount.add(BigInteger.ONE);
            }
        }
        for (TxOut output : context.txInfo().outputs()) {
            if (atScriptAddress(output, rootScriptHash)
                    && ValuesLib.assetOf(
                    output.value(),
                    rootThreadPolicyId,
                    rootThreadAssetName).equals(BigInteger.ONE)) {
                outputCount = outputCount.add(BigInteger.ONE);
                migrated = rootIdentity(output.datum());
            }
        }
        return inputCount.equals(BigInteger.ONE)
                && outputCount.equals(BigInteger.ONE)
                && migrated.isPresent()
                && Builtins.equalsByteString(
                migrated.get().chainId(), next.chainId())
                && migrated.get().bridgeEpoch().equals(
                next.bridgeEpoch())
                && migrated.get().generation().equals(
                next.generation());
    }

    private static Optional<RootIdentity> rootIdentity(
            OutputDatum datum
    ) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return rootIdentityData(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<RootIdentity> rootIdentityData(
            PlutusData value
    ) {
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(
                Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chain = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        PlutusData f4 = Builtins.tailList(f3);
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData f6 = Builtins.tailList(f5);
        PlutusData f7 = Builtins.tailList(f6);
        BigInteger generation = Builtins.unIData(
                Builtins.headList(f7));
        PlutusData trailing = Builtins.tailList(f7);
        if (Builtins.constrTag(value) != 0
                || !version.equals(BigInteger.ONE)
                || Builtins.lengthOfByteString(chain) < 1
                || Builtins.lengthOfByteString(chain) > 128
                || epoch.signum() < 0
                || generation.signum() < 0
                || !Builtins.nullList(trailing)) {
            return Optional.empty();
        }
        return Optional.of(new RootIdentity(
                chain, epoch, generation));
    }

    private static boolean atScriptAddress(
            TxOut output,
            byte[] scriptHash
    ) {
        PlutusData credential =
                output.address().credential().toPlutusData();
        PlutusData fields = Builtins.constrFields(credential);
        return Builtins.constrTag(credential) == 1
                && Builtins.nullList(Builtins.tailList(fields))
                && Builtins.equalsByteString(
                Builtins.unBData(Builtins.headList(fields)),
                scriptHash);
    }

    record ClaimIdentity(
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger sequence
    ) {
    }

    record RootIdentity(
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger generation
    ) {
    }
}
