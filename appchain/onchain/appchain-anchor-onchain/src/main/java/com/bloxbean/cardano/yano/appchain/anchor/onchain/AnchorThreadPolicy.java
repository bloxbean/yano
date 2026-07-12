package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;

import java.math.BigInteger;

/**
 * One-shot state-thread NFT policy for the app-chain script anchor
 * (ADR app-layer/008.4 §2.3). Parameterized by the bootstrap UTxO outref:
 * minting is valid only when that exact UTxO is consumed, so at most one
 * token can ever exist (a distinct seed yields a distinct policy id).
 * Exactly one token (a single name of the bootstrapper's choosing — the node
 * uses the chain-id so explorers show a readable label — quantity 1) is
 * minted; burning is never permitted — the thread must live forever at the
 * anchor validator. Identity/uniqueness comes from the policy id, never the
 * token name.
 */
@MintingValidator
public class AnchorThreadPolicy {

    /** Tx hash (32 bytes) of the bootstrap seed UTxO. */
    @Param
    static byte[] seedTxId;

    /** Output index of the bootstrap seed UTxO. */
    @Param
    static BigInteger seedIndex;

    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        byte[] ownPolicyHash = ContextsLib.ownHash(ctx);

        // The parameterized seed UTxO must be consumed in this tx (one-shot)
        boolean seedConsumed = consumesSeed(ctx.txInfo().inputs());

        // The mint under our policy must be EXACTLY one name at +1 — no
        // burn, no extra names at ANY quantity. The name itself is free
        // (a display label); the policy id is the identity.
        boolean mintedExactlyOne = mintsExactlyThreadToken(
                ctx.txInfo().mint(), ownPolicyHash);

        return seedConsumed && mintedExactlyOne;
    }

    /** The inner mint map for our policy is exactly [(any-name, 1)]. */
    static boolean mintsExactlyThreadToken(Value mint, byte[] ownPolicyHash) {
        PlutusData policyData = Builtins.bData(ownPolicyHash);
        boolean found = false;
        PlutusData current = Builtins.unMapData(mint);
        while (!Builtins.nullList(current)) {
            PlutusData outerPair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(outerPair), policyData)) {
                PlutusData inner = Builtins.unMapData(
                        (PlutusData.MapData) Builtins.sndPair(outerPair));
                if (!Builtins.nullList(inner) && Builtins.nullList(Builtins.tailList(inner))) {
                    PlutusData entry = Builtins.headList(inner);
                    BigInteger qty = Builtins.unIData(Builtins.sndPair(entry));
                    if (qty.equals(BigInteger.ONE)) {
                        found = true;
                    }
                }
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return found;
    }

    static boolean consumesSeed(JulcList<TxInInfo> inputs) {
        BigInteger matches = BigInteger.ZERO;
        for (TxInInfo input : inputs) {
            // TxId decodes to raw bytes at UPLC level — unwrap via cast
            // (the stdlib idiom; TxId.hash() would double-unwrap)
            byte[] txIdBytes = (byte[]) (Object) input.outRef().txId();
            boolean sameTx = txIdBytes.equals(seedTxId);
            boolean sameIndex = input.outRef().index().equals(seedIndex);
            if (sameTx && sameIndex) {
                matches = matches.add(BigInteger.ONE);
            }
        }
        return matches.compareTo(BigInteger.ZERO) > 0;
    }
}
