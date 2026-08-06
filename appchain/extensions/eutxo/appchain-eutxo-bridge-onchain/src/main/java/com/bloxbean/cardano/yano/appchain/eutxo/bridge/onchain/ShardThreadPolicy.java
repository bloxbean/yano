package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.types.AssetEntry;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/**
 * ADR-UTXO-009 SP-M6: one-shot policy for the 16 nullifier-shard thread
 * tokens (§7.1). Parameterized by a bootstrap seed UTxO: minting is valid
 * only when that exact UTxO is consumed, so the 16 tokens can only ever be
 * minted once. The mint must be EXACTLY the names {0x00 … 0x0F}, each at
 * quantity +1 — no other names, no burns ever: the threads live forever at
 * the shard validator (its continuing-output rule re-emits the token on
 * every spend).
 *
 * <p>The vault pairs a settlement with a shard spend by THIS policy id
 * ({@code SettlementVaultValidator.shardThreadPolicyId}); the root thread
 * NFT uses a DISTINCT one-shot policy so a root-update spend can never
 * impersonate a shard spend.
 */
@MintingValidator
public class ShardThreadPolicy {

    /** Tx hash (32 bytes) of the bootstrap seed UTxO. */
    @Param
    static byte[] seedTxId;

    /** Output index of the bootstrap seed UTxO. */
    @Param
    static BigInteger seedIndex;

    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        byte[] ownPolicyHash = ContextsLib.ownHash(ctx);
        return consumesSeed(ctx.txInfo().inputs())
                && mintsExactlyShardTokens(ctx.txInfo().mint(), ownPolicyHash);
    }

    static boolean consumesSeed(
            com.bloxbean.cardano.julc.core.types.JulcList<TxInInfo> inputs) {
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

    /**
     * The mint under our policy is exactly {0x00 … 0x0F}, each +1. A ledger
     * value map cannot hold duplicate names, so 16 entries that are each a
     * single byte below 16 at amount +1 ARE the full domain — no ordering or
     * duplicate check needed; any rider name/quantity/burn breaks one of the
     * three conditions.
     */
    static boolean mintsExactlyShardTokens(Value mint, byte[] ownPolicyHash) {
        BigInteger count = BigInteger.ZERO;
        boolean valid = true;
        for (AssetEntry entry : ValuesLib.flattenTyped(mint)) {
            if (Builtins.equalsByteString(entry.policyId(), ownPolicyHash)) {
                boolean shardName =
                        Builtins.lengthOfByteString(entry.tokenName()) == 1
                        && Builtins.indexByteString(entry.tokenName(), 0) < 16
                        && entry.amount().equals(BigInteger.ONE);
                if (shardName) {
                    count = count.add(BigInteger.ONE);
                } else {
                    valid = false;
                }
            }
        }
        return valid && count.equals(BigInteger.valueOf(16));
    }
}
