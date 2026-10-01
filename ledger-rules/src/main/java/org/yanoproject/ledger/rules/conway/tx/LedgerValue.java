package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Haskell's {@code MaryValue}: lovelace plus a multi-asset map, with the arithmetic the UTXO rule needs.
 *
 * <p>Immutable. Quantities are arbitrary-precision so a sum never overflows; like Haskell's canonical
 * {@code MultiAsset} maps, entries whose quantity is zero are dropped, so {@link #equals(Object)} is
 * Haskell's {@code Eq MaryValue}. Policy ids and asset names are lowercase hex, which sorts like their
 * bytes.</p>
 *
 * @param coin   lovelace (may be negative for intermediate results)
 * @param assets policy id → asset name → quantity, without zero quantities or empty policies
 */
public record LedgerValue(BigInteger coin, Map<String, Map<String, BigInteger>> assets) {

    public static final LedgerValue ZERO = new LedgerValue(BigInteger.ZERO, Map.of());

    public LedgerValue {
        Objects.requireNonNull(coin, "coin");
        TreeMap<String, Map<String, BigInteger>> copy = new TreeMap<>();
        for (Map.Entry<String, Map<String, BigInteger>> policy : Objects.requireNonNull(assets, "assets").entrySet()) {
            TreeMap<String, BigInteger> names = new TreeMap<>();
            policy.getValue().forEach((name, quantity) -> {
                if (quantity.signum() != 0) {
                    names.put(name.toLowerCase(Locale.ROOT), quantity);
                }
            });
            if (!names.isEmpty()) {
                copy.put(policy.getKey().toLowerCase(Locale.ROOT), Collections.unmodifiableMap(names));
            }
        }
        assets = Collections.unmodifiableMap(copy);
    }

    public static LedgerValue ofCoin(BigInteger coin) {
        return new LedgerValue(coin, Map.of());
    }

    /** Converts a CCL value (a resolved UTxO's output). */
    public static LedgerValue of(Value value) {
        if (value == null) {
            return ZERO;
        }
        BigInteger coin = value.getCoin() != null ? value.getCoin() : BigInteger.ZERO;
        TreeMap<String, Map<String, BigInteger>> assets = new TreeMap<>();
        if (value.getMultiAssets() != null) {
            for (MultiAsset multiAsset : value.getMultiAssets()) {
                Map<String, BigInteger> names = assets.computeIfAbsent(multiAsset.getPolicyId().toLowerCase(Locale.ROOT),
                        k -> new TreeMap<>());
                if (multiAsset.getAssets() != null) {
                    for (Asset asset : multiAsset.getAssets()) {
                        names.merge(HexUtil.encodeHexString(asset.getNameAsBytes()), asset.getValue(), BigInteger::add);
                    }
                }
            }
        }
        return new LedgerValue(coin, assets);
    }

    public LedgerValue add(LedgerValue other) {
        return combine(other, false);
    }

    public LedgerValue subtract(LedgerValue other) {
        return combine(other, true);
    }

    private LedgerValue combine(LedgerValue other, boolean negate) {
        TreeMap<String, Map<String, BigInteger>> result = new TreeMap<>();
        assets.forEach((policy, names) -> result.put(policy, new TreeMap<>(names)));
        other.assets.forEach((policy, names) -> {
            Map<String, BigInteger> target = result.computeIfAbsent(policy, k -> new TreeMap<>());
            names.forEach((name, quantity) -> target.merge(name, negate ? quantity.negate() : quantity, BigInteger::add));
        });
        return new LedgerValue(negate ? coin.subtract(other.coin) : coin.add(other.coin), result);
    }

    /** @return true when there is no non-zero asset (Haskell {@code isAdaOnly}) */
    public boolean isAdaOnly() {
        return assets.isEmpty();
    }

    /** @return true when every asset quantity is non-negative */
    public boolean assetsNonNegative() {
        return assets.values().stream().flatMap(m -> m.values().stream()).allMatch(q -> q.signum() >= 0);
    }

    /**
     * The length of Haskell's serialisation of this value ({@code serialize pv v}, used by
     * {@code validateOutputTooBigUTxO}, Alonzo/Rules/Utxo.hs:412-428): the coin alone when there are no
     * assets, otherwise {@code [coin, {policy => {name => quantity}}]} ({@code EncCBOR MaryValue},
     * Mary/Value.hs:342-349), with the shortest heads. It is independent of how the value was encoded in the
     * transaction. Both maps are written by {@code encodeMap} (cardano-ledger-binary Encoder.hs:397-408), which
     * is definite-length up to 23 entries and indefinite-length above ({@link #mapHeadSize(int)}).
     */
    public int serializedSize() {
        int size = integerSize(coin);
        if (assets.isEmpty()) {
            return size;
        }
        size += 1 + mapHeadSize(assets.size());
        for (Map.Entry<String, Map<String, BigInteger>> policy : assets.entrySet()) {
            int policyBytes = policy.getKey().length() / 2;
            size += headSize(policyBytes) + policyBytes + mapHeadSize(policy.getValue().size());
            for (Map.Entry<String, BigInteger> asset : policy.getValue().entrySet()) {
                int nameBytes = asset.getKey().length() / 2;
                size += headSize(nameBytes) + nameBytes + integerSize(asset.getValue());
            }
        }
        return size;
    }

    /**
     * The framing bytes of a map as {@code encodeMap} writes it from encoding version 2
     * ({@code variableMapLenEncoding}, cardano-ledger-binary Encoder.hs:432-443): a one-byte definite head for at
     * most {@code lengthThreshold = 23} entries, otherwise the indefinite-length head and the break byte. A
     * definite head would take 3 bytes from 256 entries.
     *
     * <p>Note for readers: this is deliberately <em>not</em> canonical (RFC 8949 deterministic) CBOR, and not the
     * size of the value's bytes in the transaction. The ledger re-encodes the value with its own encoder, which
     * switches to indefinite-length maps above 23 entries ("will result in less bytes on the wire",
     * Encoder.hs:440-441), so a policy with 256 or more assets measures one byte less than a definite-length
     * encoding. Preprod tx
     * {@code 96ae78f724a27b0d76c3d6a861857af3a644de971fe0c7fcbefe4e45811e5687} (a 324-asset policy) has a value
     * of exactly {@code maxValSize} = 5000 bytes this way and 5001 with definite lengths; the chain accepted it.
     * Amaru counts the same way ({@code inherent_value.rs},
     * {@code large_maps_with_indefinite_length_headers_are_valid_with_the_cardano_node_encoding}).</p>
     */
    private static int mapHeadSize(int entries) {
        return entries <= 23 ? 1 : 2;
    }

    /** @return the encoded size of a CBOR integer (major type 0 or 1) */
    static int integerSize(BigInteger value) {
        BigInteger magnitude = value.signum() >= 0 ? value : value.negate().subtract(BigInteger.ONE);
        if (magnitude.bitLength() > 64) {
            return 1 + 1 + (magnitude.bitLength() + 7) / 8; // a bignum tag; never valid in a value
        }
        return headSize(magnitude);
    }

    /** @return the size of a CBOR head carrying {@code argument} */
    static int headSize(long argument) {
        return headSize(BigInteger.valueOf(argument));
    }

    private static int headSize(BigInteger argument) {
        if (argument.compareTo(BigInteger.valueOf(24)) < 0) {
            return 1;
        }
        int bits = argument.bitLength();
        if (bits <= 8) {
            return 2;
        }
        if (bits <= 16) {
            return 3;
        }
        if (bits <= 32) {
            return 5;
        }
        return 9;
    }

    @Override
    public String toString() {
        return assets.isEmpty() ? "Coin " + coin : "MaryValue (Coin " + coin + ") " + assets;
    }
}
