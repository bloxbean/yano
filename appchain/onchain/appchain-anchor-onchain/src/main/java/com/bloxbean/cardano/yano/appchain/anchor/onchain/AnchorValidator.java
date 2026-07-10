package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxInfo;
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
 * App-chain script anchor — spending validator (ADR app-layer/008.4 §2.2,
 * ABI: core-api/src/main/cddl/appchain/anchor-v1.cddl; datum wire format is
 * Constr(0, [fields]) — the julc record codec and Aiken types agree on this).
 * Default (julc/Java) implementation; interchangeable with the Aiken
 * implementation via the same ABI.
 *
 * <p>Advance rules:
 * <ol>
 *   <li>exactly one continuing output to this script, carrying the
 *       state-thread NFT, with an inline datum</li>
 *   <li>out.height &gt; in.height; chain-id and version unchanged</li>
 *   <li>&ge; in.threshold of in.member-keys signed the tx (compared as
 *       blake2b-224 key hashes against the required signers)</li>
 *   <li>membership may evolve (rule 3 authorizes against the INPUT datum);
 *       1 &le; out.threshold &le; len(out.member-keys)</li>
 *   <li>locked lovelace is preserved or increased</li>
 * </ol>
 *
 * <p>The redeemer is a reserved action discriminant: v1 has a single action
 * (Advance) and does not inspect the value — all safety comes from the datum
 * rules and the signature threshold.
 */
@SpendingValidator
public class AnchorValidator {

    /** Policy id (28 bytes) of the one-shot state-thread NFT. */
    @Param
    static byte[] threadPolicyId;

    record AnchorDatum(BigInteger version,
                       byte[] chainId,
                       BigInteger height,
                       byte[] blockHash,
                       byte[] stateRoot,
                       JulcList<byte[]> memberKeys,
                       BigInteger threshold) {
    }

    @Entrypoint
    public static boolean validate(AnchorDatum datum, BigInteger redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Rule 1: exactly one continuing output carrying the thread NFT,
        // with an inline datum
        JulcList<TxOut> continuing = ContextsLib.getContinuingOutputs(ctx);
        boolean oneContinuing = continuing.size() == 1;
        TxOut next = continuing.head();
        boolean threadContinues = ValuesLib.countTokensWithQty(
                next.value(), threadPolicyId, BigInteger.ONE).equals(BigInteger.ONE);
        // Rules 2 + 4 over the continuing (output) datum — must be inline
        boolean nextStateValid = nextStateValid(next.datum(), datum);

        // Rule 3: threshold of the INPUT datum's members signed this tx
        boolean thresholdSigned = countSigners(txInfo, datum.memberKeys())
                .compareTo(datum.threshold()) >= 0;

        // Rule 5: no draining
        boolean valuePreserved = ValuesLib.lovelaceOf(next.value())
                .compareTo(ownInputLovelace(ctx)) >= 0;

        return oneContinuing && threadContinues
                && nextStateValid && thresholdSigned && valuePreserved;
    }

    /** The continuing datum must be INLINE and satisfy rules 2 + 4. */
    static boolean nextStateValid(OutputDatum outputDatum, AnchorDatum current) {
        if (outputDatum instanceof OutputDatum.OutputDatumInline inlineDatum) {
            return checkNextDatum(inlineDatum.datum(), current);
        }
        return false;
    }

    /** How many of the given member keys (Ed25519, 32B) signed this tx. */
    static BigInteger countSigners(TxInfo txInfo, JulcList<byte[]> memberKeys) {
        BigInteger count = BigInteger.ZERO;
        for (byte[] memberKey : memberKeys) {
            byte[] keyHash = Builtins.blake2b_224(memberKey);
            if (ContextsLib.signedBy(txInfo, keyHash)) {
                count = count.add(BigInteger.ONE);
            }
        }
        return count;
    }

    static BigInteger ownInputLovelace(ScriptContext ctx) {
        Optional<TxInInfo> ownInputOpt = ContextsLib.findOwnInput(ctx);
        TxInInfo ownInput = ownInputOpt.get();
        return ValuesLib.lovelaceOf(ownInput.resolved().value());
    }

    /**
     * Decode the continuing output's inline datum (Constr(0, [version,
     * chain-id, height, block-hash, state-root, member-keys, threshold]))
     * and enforce rules 2 (monotonic, stable identity) and 4 (sane successor
     * membership shape) against the current datum.
     */
    static boolean checkNextDatum(PlutusData data, AnchorDatum current) {
        PlutusData fields = Builtins.sndPair(Builtins.unConstrData(data));
        BigInteger nextVersion = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] nextChainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger nextHeight = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);   // block-hash (no rule on it)
        PlutusData f4 = Builtins.tailList(f3);   // state-root (no rule on it)
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData nextMemberList = Builtins.unListData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger nextThreshold = Builtins.unIData(Builtins.headList(f6));

        boolean monotonic = nextHeight.compareTo(current.height()) > 0;
        boolean sameChain = nextChainId.equals(current.chainId());
        boolean sameVersion = nextVersion.equals(current.version());
        BigInteger nextMemberCount = lengthOf(nextMemberList);
        boolean thresholdSane = nextThreshold.compareTo(BigInteger.ONE) >= 0
                && nextThreshold.compareTo(nextMemberCount) <= 0;

        return monotonic && sameChain && sameVersion && thresholdSane;
    }

    static BigInteger lengthOf(PlutusData list) {
        BigInteger count = BigInteger.ZERO;
        PlutusData rest = list;
        while (!Builtins.nullList(rest)) {
            count = count.add(BigInteger.ONE);
            rest = Builtins.tailList(rest);
        }
        return count;
    }
}
