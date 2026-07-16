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
 *   <li>both datums satisfy the complete v1 base profile: version 1,
 *       1..128 chain-id bytes, non-negative height, and 32-byte block hash
 *       and state root; additionally out.height &gt; in.height and chain-id is
 *       unchanged</li>
 *   <li>&ge; in.threshold of in.member-keys signed the tx (compared as
 *       blake2b-224 key hashes against the required signers)</li>
 *   <li>membership may evolve (rule 3 authorizes against the INPUT datum);
 *       both input and output member profiles contain 1..32 strictly sorted,
 *       unique 32-byte keys and a threshold in range</li>
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

        // The input can originate at bootstrap, rather than from a prior
        // validator-controlled output. Validate the complete v1 base profile
        // before hashing/scanning signers so every field is constrained from
        // the first spend onward. Member profile and signer checks share one
        // traversal to keep 32-of-32 comfortably inside mainnet limits.
        boolean currentShapeValid = datumShapeValid(datum);
        boolean currentBaseProfileValid = baseProfileValid(
                datum.version(), datum.chainId(), datum.height(),
                datum.blockHash(), datum.stateRoot());
        boolean currentValidAndSigned = currentValidAndSigned(
                txInfo, datum.memberKeys(), datum.threshold());
        if (!currentShapeValid || !currentBaseProfileValid
                || !currentValidAndSigned) {
            return false;
        }

        // Rule 1: exactly one continuing output carrying the thread NFT,
        // with an inline datum
        JulcList<TxOut> continuing = ContextsLib.getContinuingOutputs(ctx);
        boolean oneContinuing = continuing.size() == 1;
        TxOut next = continuing.head();
        boolean threadContinues = ValuesLib.countTokensWithQty(
                next.value(), threadPolicyId, BigInteger.ONE).equals(BigInteger.ONE);
        // Rules 2 + 4 over the continuing (output) datum — must be inline.
        // Rule 4 includes the same bounded/canonical member profile as input.
        boolean nextStateValid = nextStateValid(next.datum(), datum);

        // Rule 5: no draining
        boolean valuePreserved = ValuesLib.lovelaceOf(next.value())
                .compareTo(ownInputLovelace(ctx)) >= 0;

        return oneContinuing && threadContinues
                && nextStateValid && valuePreserved;
    }

    /** The continuing datum must be INLINE and satisfy rules 2 + 4. */
    static boolean nextStateValid(OutputDatum outputDatum, AnchorDatum current) {
        if (outputDatum instanceof OutputDatum.OutputDatumInline inlineDatum) {
            return checkNextDatum(inlineDatum.datum(), current);
        }
        return false;
    }

    /** Validate input profile and count its signers in one bounded traversal. */
    static boolean currentValidAndSigned(TxInfo txInfo, JulcList<byte[]> memberKeys,
                                         BigInteger threshold) {
        if (memberKeys.size() < 1 || memberKeys.size() > 32) {
            return false;
        }
        BigInteger count = BigInteger.ZERO;
        BigInteger signerCount = BigInteger.ZERO;
        byte[] previous = Builtins.emptyByteString();
        boolean valid = true;
        for (byte[] memberKey : memberKeys) {
            if (Builtins.lengthOfByteString(memberKey) != 32
                    || (count.compareTo(BigInteger.ZERO) > 0
                    && !Builtins.lessThanByteString(previous, memberKey))) {
                valid = false;
                break;
            }
            previous = memberKey;
            count = count.add(BigInteger.ONE);
            byte[] keyHash = Builtins.blake2b_224(memberKey);
            if (ContextsLib.signedBy(txInfo, keyHash)) {
                signerCount = signerCount.add(BigInteger.ONE);
            }
        }
        return valid && count.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(count) <= 0
                && signerCount.compareTo(threshold) >= 0;
    }

    /**
     * Complete scalar/byte-string profile shared by current and successor.
     * Plutus exposes chain-id only as bytes, so canonical UTF-8 and NUL
     * rejection remain the responsibility of the off-chain AnchorDatumV1
     * codec; this script enforces the consensus-visible byte bound.
     */
    static boolean baseProfileValid(BigInteger version, byte[] chainId,
                                    BigInteger height, byte[] blockHash,
                                    byte[] stateRoot) {
        long chainIdLength = Builtins.lengthOfByteString(chainId);
        return version.equals(BigInteger.ONE)
                && chainIdLength >= 1 && chainIdLength <= 128
                && height.compareTo(BigInteger.ZERO) >= 0
                && Builtins.lengthOfByteString(blockHash) == 32
                && Builtins.lengthOfByteString(stateRoot) == 32;
    }

    /** Require the v1 datum's exact Constr(0, seven fields) wire shape. */
    static boolean datumShapeValid(AnchorDatum datum) {
        long alternative = Builtins.constrTag(datum);
        PlutusData pair = Builtins.unConstrData(datum);
        PlutusData fields = Builtins.sndPair(pair);
        PlutusData f1 = Builtins.tailList(fields);
        PlutusData f2 = Builtins.tailList(f1);
        PlutusData f3 = Builtins.tailList(f2);
        PlutusData f4 = Builtins.tailList(f3);
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData f6 = Builtins.tailList(f5);
        PlutusData trailingFields = Builtins.tailList(f6);
        return alternative == 0
                && Builtins.nullList(trailingFields);
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
        PlutusData pair = Builtins.unConstrData(data);
        long alternative = Builtins.constrTag(data);
        PlutusData fields = Builtins.sndPair(pair);
        BigInteger nextVersion = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] nextChainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger nextHeight = Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] nextBlockHash = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] nextStateRoot = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData nextMemberList = Builtins.unListData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger nextThreshold = Builtins.unIData(Builtins.headList(f6));
        PlutusData trailingFields = Builtins.tailList(f6);

        boolean exactShape = alternative == 0
                && Builtins.nullList(trailingFields);
        boolean baseProfileValid = baseProfileValid(nextVersion, nextChainId,
                nextHeight, nextBlockHash, nextStateRoot);
        boolean monotonic = nextHeight.compareTo(current.height()) > 0;
        boolean sameChain = nextChainId.equals(current.chainId());
        boolean sameVersion = nextVersion.equals(current.version());
        boolean memberProfileValid = memberProfileValid(nextMemberList, nextThreshold);

        return exactShape && baseProfileValid && monotonic && sameChain && sameVersion
                && memberProfileValid;
    }

    /** Validate the manually decoded successor datum member profile. */
    static boolean memberProfileValid(PlutusData list, BigInteger threshold) {
        BigInteger count = BigInteger.ZERO;
        byte[] previous = Builtins.emptyByteString();
        PlutusData rest = list;
        boolean valid = true;
        while (!Builtins.nullList(rest)) {
            byte[] memberKey = Builtins.unBData(Builtins.headList(rest));
            if (count.compareTo(BigInteger.valueOf(32)) >= 0
                    || Builtins.lengthOfByteString(memberKey) != 32
                    || (count.compareTo(BigInteger.ZERO) > 0
                    && !Builtins.lessThanByteString(previous, memberKey))) {
                valid = false;
                break;
            }
            previous = memberKey;
            count = count.add(BigInteger.ONE);
            rest = Builtins.tailList(rest);
        }
        return valid && count.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(count) <= 0;
    }
}
