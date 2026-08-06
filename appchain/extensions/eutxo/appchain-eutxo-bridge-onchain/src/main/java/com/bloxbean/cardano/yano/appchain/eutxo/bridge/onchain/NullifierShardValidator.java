package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf.MerklePatriciaForestry;
import com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf.ProofStep;

import java.math.BigInteger;

/**
 * ADR-UTXO-009 nullifier shard thread (§7.1): one of k=16 UTxOs holding an
 * MPF root over settled claim ids. A spend must pair with a settlement vault
 * spend and prove, for every inserted claim id, NON-membership under the
 * running root and then advance to the computed post-insert root — chained
 * in order — with the continuing datum carrying the final root. Double
 * settlement is impossible by UTxO exclusivity plus the exclusion check.
 *
 * <p>Datum: Constr0[version=1, chainId, bridgeEpoch, shardIndex,
 * nullifierRoot]. Redeemer: JulcList of Insert{claimId(32),
 * JulcList&lt;ProofStep&gt;}. The MPF value for a claim id is the claim id
 * itself; a claim id's shard is its last nibble; the shard thread token name
 * is the single shard-index byte.
 */
@SpendingValidator
public final class NullifierShardValidator {
    @Param
    static byte[] shardThreadPolicyId;

    @Param
    static byte[] vaultScriptHash;

    record ShardDatum(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger shardIndex,
            byte[] nullifierRoot
    ) {
    }

    record Insert(byte[] claimId, JulcList<ProofStep> proof) {
    }

    record InsertBatch(JulcList<Insert> inserts) {
    }

    private NullifierShardValidator() {
    }

    @Entrypoint
    public static boolean validate(
            ShardDatum current,
            InsertBatch redeemer,
            ScriptContext context
    ) {
        JulcList<Insert> inserts = redeemer.inserts();
        if (!datumShapeValid(current)
                || inserts.isEmpty()
                || !vaultIsSpent(context)) {
            return false;
        }
        byte[] finalRoot = foldInserts(
                inserts, current.nullifierRoot(),
                current.shardIndex().longValue());
        if (ByteStringLib.length(finalRoot) != 32) {
            return false;
        }
        JulcList<TxOut> continuing =
                ContextsLib.getContinuingOutputs(context);
        return continuing.size() == 1
                && hasThread(continuing.head(), current.shardIndex().longValue())
                && nextDatumValid(
                continuing.head().datum(), current, finalRoot);
    }

    /**
     * Chain the inserts: each claim id must be ABSENT under the running root
     * (exclusion proof) and the running root advances to its post-insert
     * value; a wrong-shard id or a failed exclusion collapses to an
     * all-zero sentinel that can never equal a real 32-byte root.
     */
    private static byte[] foldInserts(
            JulcList<Insert> inserts, byte[] runningRoot, long shardIndex) {
        if (inserts.isEmpty()) {
            return runningRoot;
        }
        Insert insert = inserts.head();
        byte[] claimId = insert.claimId();
        if (ByteStringLib.length(claimId) != 32
                || ByteStringLib.at(claimId, 31) % 16 != shardIndex
                || !MerklePatriciaForestry.miss(
                runningRoot, claimId, insert.proof())) {
            return ByteStringLib.zeros(1);
        }
        byte[] nextRoot = MerklePatriciaForestry.including(
                claimId, claimId, insert.proof());
        return foldInserts(inserts.tail(), nextRoot, shardIndex);
    }

    static boolean datumShapeValid(ShardDatum datum) {
        long chainLength = ByteStringLib.length(datum.chainId());
        return datum.version().equals(BigInteger.ONE)
                && chainLength >= 1 && chainLength <= 128
                && datum.bridgeEpoch().signum() >= 0
                && datum.shardIndex().signum() >= 0
                && datum.shardIndex().compareTo(BigInteger.valueOf(16)) < 0
                && ByteStringLib.length(datum.nullifierRoot()) == 32;
    }

    private static boolean vaultIsSpent(ScriptContext context) {
        boolean found = false;
        for (var input : context.txInfo().inputs()) {
            PlutusData credential =
                    input.resolved().address().credential().toPlutusData();
            if (Builtins.constrTag(credential) == 1
                    && Builtins.equalsByteString(
                    Builtins.unBData(Builtins.headList(
                            Builtins.constrFields(credential))),
                    vaultScriptHash)) {
                found = true;
                break;
            }
        }
        return found;
    }

    private static boolean nextDatumValid(
            OutputDatum outputDatum,
            ShardDatum current,
            byte[] finalRoot
    ) {
        if (outputDatum instanceof OutputDatum.OutputDatumInline inline) {
            return nextDataValid(inline.datum(), current, finalRoot);
        }
        return false;
    }

    private static boolean nextDataValid(
            PlutusData data, ShardDatum current, byte[] finalRoot) {
        PlutusData fields = Builtins.sndPair(Builtins.unConstrData(data));
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger shardIndex = Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] root = Builtins.unBData(Builtins.headList(f4));
        return Builtins.constrTag(data) == 0
                && Builtins.nullList(Builtins.tailList(f4))
                && version.equals(current.version())
                && Builtins.equalsByteString(chainId, current.chainId())
                && epoch.equals(current.bridgeEpoch())
                && shardIndex.equals(current.shardIndex())
                && Builtins.equalsByteString(root, finalRoot);
    }

    private static boolean hasThread(TxOut output, long shardIndex) {
        return ValuesLib.assetOf(
                output.value(),
                shardThreadPolicyId,
                Builtins.consByteString(
                        shardIndex, Builtins.emptyByteString()))
                .equals(BigInteger.ONE);
    }
}
