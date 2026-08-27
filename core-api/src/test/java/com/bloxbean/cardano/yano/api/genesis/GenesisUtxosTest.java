package com.bloxbean.cardano.yano.api.genesis;

import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Genesis funds are not produced by any block, so every consumer synthesises them. These tests
 * pin the conventions mainnet already depends on, because the live UTXO store and the ADR-039
 * projection now share this one derivation - a change here silently changes both.
 */
class GenesisUtxosTest {

    // A real preprod Byron bootstrap address, and a Shelley-shaped hex address.
    private static final String BYRON_ADDR = "FHnt4NL7yPXuYUxBF33VX5dZMBDAab2kvSNLRzCskvuKNCSDknzrQvKeQhGUw5a";
    private static final String SHELLEY_HEX = "00" + "11".repeat(28) + "22".repeat(28);
    private static final String ZERO_HASH = "00".repeat(32);

    @Test
    void shelleyFundsUseBlake2bOverTheDecodedHexAddress() {
        var utxo = GenesisUtxos.shelley(SHELLEY_HEX, BigInteger.valueOf(1_000_000), 1, 0, 0, ZERO_HASH);

        String expected = HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash256(HexUtil.decodeHexString(SHELLEY_HEX)));
        assertThat(utxo.txHash()).isEqualTo(expected);
        assertThat(utxo.outputIndex()).isZero();
        assertThat(utxo.originType()).isEqualTo("genesis_shelley");
        assertThat(utxo.address()).startsWith("addr_test");
        assertThat(utxo.amount()).isEqualTo(BigInteger.valueOf(1_000_000));
    }

    @Test
    void mainnetMagicSelectsTheMainnetBech32Prefix() {
        // The prefix is the only network-dependent part of the derivation.
        var mainnet = GenesisUtxos.shelley(SHELLEY_HEX, BigInteger.ONE, 764824073, 0, 0, ZERO_HASH);
        var testnet = GenesisUtxos.shelley(SHELLEY_HEX, BigInteger.ONE, 1, 0, 0, ZERO_HASH);

        assertThat(mainnet.address()).startsWith("addr1");
        assertThat(testnet.address()).startsWith("addr_test1");
        assertThat(mainnet.txHash())
                .as("the hash is over the address bytes, not its rendering")
                .isEqualTo(testnet.txHash());
    }

    @Test
    void byronBalancesUseBlake2bOverTheBase58DecodedAddressAndKeepItAsIs() {
        var utxo = GenesisUtxos.byron(BYRON_ADDR, BigInteger.valueOf(30_000_000_000_000_000L), 0, 0, ZERO_HASH);

        assertThat(utxo.txHash()).isEqualTo(
                HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(Base58.decode(BYRON_ADDR))));
        assertThat(utxo.address())
                .as("Byron addresses are not bech32 and must not be converted")
                .isEqualTo(BYRON_ADDR);
        assertThat(utxo.originType()).isEqualTo("genesis_byron");
        assertThat(utxo.isByron()).isTrue();
    }

    @Test
    void anUnconvertibleShelleyAddressFallsBackToHexRatherThanBeingDropped() {
        // A genesis entry that cannot be rendered as bech32 is still a real output; the live
        // store has always kept it, and losing it here would lose lovelace from the archive.
        var utxo = GenesisUtxos.shelley("ff", BigInteger.TEN, 1, 0, 0, ZERO_HASH);

        assertThat(utxo.address()).isEqualTo("ff");
        assertThat(utxo.amount()).isEqualTo(BigInteger.TEN);
    }

    // ------------------------------------------------------------- network shapes

    @Test
    void aByronStartNetworkCarriesBothSources() {
        var shelley = Map.of(SHELLEY_HEX, BigInteger.valueOf(5));
        var byron = Map.of(BYRON_ADDR, BigInteger.valueOf(7));

        var utxos = GenesisUtxos.of(shelley, byron, 1, 0, 0, ZERO_HASH);

        assertThat(utxos).hasSize(2);
        assertThat(utxos.stream().filter(u -> !u.isByron()).count()).isEqualTo(1);
        assertThat(utxos.stream().filter(GenesisUtxo::isByron).count()).isEqualTo(1);
    }

    @Test
    void aShelleyStartTestNetworkHasNoByronBalances() {
        var utxos = GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.ONE), Map.of(), 1, 0, 0, ZERO_HASH);

        assertThat(utxos).hasSize(1);
        assertThat(utxos.getFirst().originType()).isEqualTo("genesis_shelley");
    }

    @Test
    void aDirectStartConfigurationMayDistributeNothing() {
        // Devnets and direct-start configurations legitimately have no funds at all. An empty
        // distribution must be a valid, digestible state - not an error and not a skipped step.
        assertThat(GenesisUtxos.of(Map.of(), Map.of(), 1, 0, 0, ZERO_HASH)).isEmpty();
        assertThat(GenesisUtxos.of(null, null, 1, 0, 0, ZERO_HASH)).isEmpty();
        assertThat(GenesisUtxos.digest(List.of())).isNotBlank();
    }

    @Test
    void theCoordinateIsCarriedThroughUnchanged() {
        var utxos = GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.ONE), Map.of(BYRON_ADDR, BigInteger.ONE),
                1, 12, 34, "ab".repeat(32));

        assertThat(utxos).allSatisfy(u -> {
            assertThat(u.blockNumber()).isEqualTo(12);
            assertThat(u.slot()).isEqualTo(34);
            assertThat(u.blockHash()).isEqualTo("ab".repeat(32));
        });
    }

    // ------------------------------------------------------------- digest binding

    @Test
    void theDigestIsStableAcrossIterationOrder() {
        Map<String, BigInteger> a = new LinkedHashMap<>();
        a.put(SHELLEY_HEX, BigInteger.ONE);
        a.put("00" + "33".repeat(28) + "44".repeat(28), BigInteger.TWO);
        Map<String, BigInteger> b = new LinkedHashMap<>();
        b.put("00" + "33".repeat(28) + "44".repeat(28), BigInteger.TWO);
        b.put(SHELLEY_HEX, BigInteger.ONE);

        assertThat(GenesisUtxos.digest(GenesisUtxos.of(a, Map.of(), 1, 0, 0, ZERO_HASH)))
                .isEqualTo(GenesisUtxos.digest(GenesisUtxos.of(b, Map.of(), 1, 0, 0, ZERO_HASH)));
    }

    @Test
    void theDigestChangesWithNetworkAmountAndDistribution() {
        var base = GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.ONE), Map.of(), 1, 0, 0, ZERO_HASH);
        String baseline = GenesisUtxos.digest(base);

        // A different network renders a different address, so the archive must not be reusable.
        assertThat(GenesisUtxos.digest(
                GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.ONE), Map.of(), 764824073, 0, 0, ZERO_HASH)))
                .isNotEqualTo(baseline);
        // An edited amount must not pass as the same distribution.
        assertThat(GenesisUtxos.digest(
                GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.TWO), Map.of(), 1, 0, 0, ZERO_HASH)))
                .isNotEqualTo(baseline);
        // An added entry must not pass either.
        assertThat(GenesisUtxos.digest(
                GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.ONE), Map.of(BYRON_ADDR, BigInteger.ONE),
                        1, 0, 0, ZERO_HASH)))
                .isNotEqualTo(baseline);
    }

    @Test
    void aDuplicatedEntryCannotCancelOutOfTheDigest() {
        // XOR alone would let an even number of identical rows vanish; the count and total that
        // travel with it are what stop that.
        var one = GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.ONE), Map.of(), 1, 0, 0, ZERO_HASH);
        var duplicated = List.of(one.getFirst(), one.getFirst());

        assertThat(GenesisUtxos.digest(duplicated)).isNotEqualTo(GenesisUtxos.digest(one));
    }

    @Test
    void anUnknownOriginTypeIsRejected() {
        assertThatThrownBy(() -> new GenesisUtxo("addr", BigInteger.ONE, "aa", 0, "genesis_other",
                0, 0, ZERO_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown genesis origin type");
    }
}
