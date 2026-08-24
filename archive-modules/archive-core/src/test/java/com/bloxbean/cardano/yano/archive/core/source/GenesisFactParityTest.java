package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-039 projection had no genesis path at all, which is how a preprod archive came to be
 * missing the whole 30,000,000,000,000,000 lovelace Byron distribution while reporting itself
 * complete. These tests pin the new path against the replay worker's conventions, because the
 * two archives are compared row for row and any divergence is a Phase 7 blocker.
 */
class GenesisFactParityTest {

    private static final String BYRON_ADDR = "FHnt4NL7yPXuYUxBF33VX5dZMBDAab2kvSNLRzCskvuKNCSDknzrQvKeQhGUw5a";
    private static final String ZERO_HASH = "00".repeat(32);

    private static YaciUtxoHistoryDecoder decoder() {
        return new YaciUtxoHistoryDecoder(slot -> 0, slot -> 0);
    }

    @Test
    void theTransactionHashIsTheNormalizersAndMatchesTheReplayWorkerConvention() {
        // The replay worker derives blake2b256(rawAddress). The normalizer derives it over the
        // decoded address bytes. They must be the same bytes, or the two archives disagree on
        // every genesis outpoint.
        var utxo = GenesisUtxos.byron(BYRON_ADDR, BigInteger.valueOf(30_000_000_000_000_000L),
                0, 0, ZERO_HASH);

        var fact = decoder().genesisFact(List.of(utxo));

        assertThat(fact.outputs()).hasSize(1);
        var output = fact.outputs().getFirst();
        assertThat(HexUtil.encodeHexString(output.txHash()))
                .isEqualTo(utxo.txHash())
                .isEqualTo(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(Base58.decode(BYRON_ADDR))));
    }

    @Test
    void genesisOutputsCarryTheReplayWorkersRowSemantics() {
        var utxo = GenesisUtxos.byron(BYRON_ADDR, BigInteger.valueOf(42), 0, 0, ZERO_HASH);

        var output = decoder().genesisFact(List.of(utxo)).outputs().getFirst();

        assertThat(output.outputIndex()).isZero();
        assertThat(output.txIndex()).as("a genesis output belongs to no transaction").isEqualTo(-1);
        assertThat(output.originType()).isEqualTo("genesis_byron");
        assertThat(output.lovelace()).isEqualTo(42);
        assertThat(output.collateralReturn()).isFalse();
    }

    @Test
    void addressDecompositionIsSharedWithOrdinaryOutputs() {
        // Genesis rows must not be a second address derivation: the address key and credentials
        // have to come from the same decode every other output row uses.
        var shelley = GenesisUtxos.shelley("00" + "11".repeat(28) + "22".repeat(28),
                BigInteger.TEN, 1, 0, 0, ZERO_HASH);

        var fact = decoder().genesisFact(List.of(shelley));

        assertThat(fact.newAddresses()).hasSize(1);
        var output = fact.outputs().getFirst();
        assertThat(output.addressKey()).isNotNull().isNotEmpty();
        assertThat(output.paymentCredential()).as("a base address exposes a payment credential").isNotNull();
        assertThat(output.stakeCredential()).as("and a delegation credential").isNotNull();
    }

    @Test
    void byronAndShelleySourcesCoexistInOneFact() {
        var utxos = GenesisUtxos.of(
                Map.of("00" + "11".repeat(28) + "22".repeat(28), BigInteger.ONE),
                Map.of(BYRON_ADDR, BigInteger.TWO),
                1, 0, 0, ZERO_HASH);

        var fact = decoder().genesisFact(utxos);

        assertThat(fact.outputs()).hasSize(2);
        assertThat(fact.outputs()).extracting(o -> o.originType())
                .containsExactlyInAnyOrder("genesis_shelley", "genesis_byron");
        assertThat(fact.outputs()).extracting(o -> o.lovelace())
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void anEmptyDistributionProducesNoRowsRatherThanFailing() {
        // Devnets and direct-start configurations distribute nothing; that is a valid archive.
        var fact = decoder().genesisFact(List.of());

        assertThat(fact.outputs()).isEmpty();
        assertThat(fact.newAddresses()).isEmpty();
    }

    @Test
    void outputsSharingAnOutpointAreSummedNotDuplicated() {
        // AVVM and non-AVVM can name the same address; ByronGenesisData already sums them, and
        // this path must not undo that by emitting two rows for one outpoint.
        var first = GenesisUtxos.byron(BYRON_ADDR, BigInteger.valueOf(10), 0, 0, ZERO_HASH);
        var second = GenesisUtxos.byron(BYRON_ADDR, BigInteger.valueOf(32), 0, 0, ZERO_HASH);

        var fact = decoder().genesisFact(List.of(first, second));

        assertThat(fact.outputs()).hasSize(1);
        assertThat(fact.outputs().getFirst().lovelace()).isEqualTo(42);
    }

    @Test
    void repeatedDerivationIsDeterministic() {
        // The bootstrap may run again after a crash; it must produce byte-identical rows.
        var utxos = GenesisUtxos.of(Map.of("00" + "11".repeat(28) + "22".repeat(28), BigInteger.ONE),
                Map.of(BYRON_ADDR, BigInteger.TWO), 1, 0, 0, ZERO_HASH);

        var a = decoder().genesisFact(utxos);
        var b = decoder().genesisFact(utxos);

        assertThat(a.outputs()).hasSameSizeAs(b.outputs());
        for (int i = 0; i < a.outputs().size(); i++) {
            assertThat(HexUtil.encodeHexString(a.outputs().get(i).txHash()))
                    .isEqualTo(HexUtil.encodeHexString(b.outputs().get(i).txHash()));
            assertThat(a.outputs().get(i).lovelace()).isEqualTo(b.outputs().get(i).lovelace());
        }
        assertThat(GenesisUtxos.digest(utxos)).isEqualTo(GenesisUtxos.digest(utxos));
    }

    @Test
    void aGenesisUtxoAlwaysCarriesItsCoordinate() {
        var utxo = GenesisUtxos.byron(BYRON_ADDR, BigInteger.ONE, 0, 0, ZERO_HASH);

        assertThat(utxo.blockNumber()).isZero();
        assertThat(utxo.slot()).isZero();
        assertThat(utxo.blockHash()).isEqualTo(ZERO_HASH);
        assertThat(utxo).isInstanceOf(GenesisUtxo.class);
    }
}
