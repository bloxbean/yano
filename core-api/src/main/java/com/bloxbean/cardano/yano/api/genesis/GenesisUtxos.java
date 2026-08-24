package com.bloxbean.cardano.yano.api.genesis;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single normalisation of genesis funds into outputs.
 *
 * <p>Pure by design: no store, no database, no configuration lookup. It exists so the live UTXO
 * store and the ADR-039 projection cannot drift apart — they consume the same records rather than
 * each deriving the transaction hash, address form and coordinate for themselves.
 *
 * <p>The conventions here are <strong>not</strong> new. They are lifted verbatim from
 * {@code DefaultUtxoStore.storeGenesisUtxos} and {@code storeByronGenesisUtxos}, which mainnet
 * already depends on:
 *
 * <ul>
 *   <li>Shelley initial funds are keyed by <em>hex</em> address. The transaction hash is
 *       blake2b-256 over those decoded bytes, and the stored address is bech32 with the
 *       network-appropriate prefix, falling back to the hex form if conversion fails.</li>
 *   <li>Byron balances are keyed by <em>base58</em> address. The transaction hash is blake2b-256
 *       over {@code Base58.decode(address)}, and the address is stored as-is — Byron addresses
 *       are not bech32.</li>
 *   <li>Both use output index 0: genesis distributes one output per address.</li>
 * </ul>
 *
 * <p>Byron AVVM and non-AVVM balances arrive already merged by
 * {@code ByronGenesisData.getAllByronBalances()}, which sums a collision. That merge stays where
 * it is; this class must not second-guess it.
 */
public final class GenesisUtxos {

    private GenesisUtxos() {}

    /**
     * Normalise both genesis sources into outputs, Shelley first then Byron.
     *
     * <p>Either map may be empty — a Shelley-start test network has no Byron balances, and a
     * network may distribute nothing at all. Order is stable so a digest over the result is
     * reproducible.
     */
    public static List<GenesisUtxo> of(Map<String, BigInteger> shelleyInitialFunds,
                                       Map<String, BigInteger> byronBalances,
                                       long networkMagic, long blockNumber, long slot,
                                       String blockHash) {
        List<GenesisUtxo> utxos = new ArrayList<>(
                (shelleyInitialFunds == null ? 0 : shelleyInitialFunds.size())
                        + (byronBalances == null ? 0 : byronBalances.size()));
        if (shelleyInitialFunds != null) {
            shelleyInitialFunds.forEach((hexAddress, lovelace) ->
                    utxos.add(shelley(hexAddress, lovelace, networkMagic, blockNumber, slot, blockHash)));
        }
        if (byronBalances != null) {
            byronBalances.forEach((base58Address, lovelace) ->
                    utxos.add(byron(base58Address, lovelace, blockNumber, slot, blockHash)));
        }
        return List.copyOf(utxos);
    }

    /** One Shelley initial-fund output. {@code hexAddress} is the genesis file's key. */
    public static GenesisUtxo shelley(String hexAddress, BigInteger lovelace, long networkMagic,
                                      long blockNumber, long slot, String blockHash) {
        byte[] addressBytes = HexUtil.decodeHexString(hexAddress);
        String txHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(addressBytes));
        String prefix = networkMagic == Constants.MAINNET_PROTOCOL_MAGIC ? "addr" : "addr_test";
        String address;
        try {
            address = new Address(prefix, addressBytes).toBech32();
        } catch (Exception e) {
            // Same fallback the live store uses: a genesis entry that cannot be rendered as
            // bech32 is still a real output and must not be dropped.
            address = hexAddress;
        }
        return new GenesisUtxo(address, lovelace, txHash, 0, GenesisUtxo.ORIGIN_SHELLEY,
                blockNumber, slot, blockHash);
    }

    /** One Byron output, AVVM or non-AVVM — they are indistinguishable once merged. */
    public static GenesisUtxo byron(String base58Address, BigInteger lovelace,
                                    long blockNumber, long slot, String blockHash) {
        byte[] addressBytes = Base58.decode(base58Address);
        String txHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(addressBytes));
        return new GenesisUtxo(base58Address, lovelace, txHash, 0, GenesisUtxo.ORIGIN_BYRON,
                blockNumber, slot, blockHash);
    }

    /**
     * Digest binding a projection to the exact distribution it captured.
     *
     * <p>Order-independent, so it does not depend on map iteration order, and it covers every
     * field that defines an output. A different network, or the same network with edited genesis
     * files, produces a different digest — which is what lets a reopened archive fail closed
     * instead of silently appending to someone else's genesis.
     */
    public static String digest(List<GenesisUtxo> utxos) {
        long count = 0;
        BigInteger total = BigInteger.ZERO;
        byte[] accumulator = new byte[32];
        for (GenesisUtxo utxo : utxos) {
            count++;
            total = total.add(utxo.amount());
            byte[] row = sha256((utxo.txHash() + '|' + utxo.outputIndex() + '|' + utxo.address()
                    + '|' + utxo.amount() + '|' + utxo.originType() + '|' + utxo.blockNumber()
                    + '|' + utxo.slot() + '|' + utxo.blockHash()).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < accumulator.length; i++) accumulator[i] ^= row[i];
        }
        // Count and total travel with the XOR so a duplicated pair cannot cancel out.
        return HexUtil.encodeHexString(sha256((count + "|" + total + "|"
                + HexUtil.encodeHexString(accumulator)).getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
