package com.bloxbean.cardano.yano.api.genesis;

import java.math.BigInteger;
import java.util.Objects;

/**
 * One genesis-distributed output, normalised once and consumed by every path that needs it.
 *
 * <p>Genesis funds are not produced by any block, so every consumer has to synthesise the same
 * outputs from the genesis files. Before this record existed there were two independent
 * derivations - the live UTXO store's and the replay-worker archive's - and the ADR-039
 * projection had none at all, which is how a preprod archive came to be missing the entire
 * 30,000,000,000,000,000 lovelace Byron distribution while reporting itself complete.
 *
 * <p>The representation is deliberately the <em>output</em>, not the genesis file entry: the
 * transaction hash convention, the address form and the coordinate are exactly the parts that
 * must not be re-derived independently.
 *
 * @param address     bech32 for Shelley initial funds, the base58 string as-is for Byron
 * @param amount      lovelace
 * @param txHash      hex blake2b-256 over the decoded address bytes
 * @param outputIndex always 0; genesis outputs are one per address
 * @param originType  {@code genesis_shelley} or {@code genesis_byron}
 * @param blockNumber coordinate the outputs are attributed to
 * @param slot        coordinate the outputs are attributed to
 * @param blockHash   hex coordinate the outputs are attributed to
 */
public record GenesisUtxo(String address,
                          BigInteger amount,
                          String txHash,
                          int outputIndex,
                          String originType,
                          long blockNumber,
                          long slot,
                          String blockHash) {

    public static final String ORIGIN_SHELLEY = "genesis_shelley";
    public static final String ORIGIN_BYRON = "genesis_byron";

    public GenesisUtxo {
        address = Objects.requireNonNull(address, "address");
        Objects.requireNonNull(amount, "amount");
        txHash = Objects.requireNonNull(txHash, "txHash");
        originType = Objects.requireNonNull(originType, "originType");
        blockHash = Objects.requireNonNull(blockHash, "blockHash");
        if (outputIndex < 0) throw new IllegalArgumentException("outputIndex must not be negative");
        if (blockNumber < 0 || slot < 0) throw new IllegalArgumentException("invalid genesis coordinate");
        if (!ORIGIN_SHELLEY.equals(originType) && !ORIGIN_BYRON.equals(originType)) {
            throw new IllegalArgumentException("unknown genesis origin type: " + originType);
        }
    }

    public boolean isByron() {
        return ORIGIN_BYRON.equals(originType);
    }
}
