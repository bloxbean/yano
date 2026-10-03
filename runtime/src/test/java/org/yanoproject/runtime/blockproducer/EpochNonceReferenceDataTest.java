package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Epoch nonces checked against the published values of the networks in
 * {@code app/epoch-nonce-data/epoch_param_nonce_<network>.json} ({@code epoch_param.nonce}, as Koios serves it).
 */
class EpochNonceReferenceDataTest {

    private static final Path REFERENCE_DIR = Path.of("../app/epoch-nonce-data");
    private static final Path GENESIS_DIR = Path.of("../app/config/network");

    private static Map<Integer, String> published(String network) throws IOException {
        JsonNode rows = new ObjectMapper().readTree(
                REFERENCE_DIR.resolve("epoch_param_nonce_" + network + ".json").toFile());
        Map<Integer, String> nonces = new HashMap<>();
        rows.forEach(row -> nonces.put(row.get("epoch_no").asInt(), row.get("nonce").asText()));
        return nonces;
    }

    /** The first Shelley epoch's nonce is the hash of the Shelley genesis file. */
    @Test
    void theFirstShelleyEpochNonceIsTheShelleyGenesisHash() throws Exception {
        assertFirstShelleyEpochNonce("mainnet", 208);
        assertFirstShelleyEpochNonce("preprod", 4);
        assertFirstShelleyEpochNonce("preview", 0);
    }

    /**
     * The nonce state a synced node held late in an epoch (past the stability window, so the candidate nonce is
     * frozen) gives the next epoch's published nonce: through the leader-check preview and through the epoch
     * transition of block application. The states were read from fully synced chainstates (the persisted latest
     * nonce snapshot, {@code EpochNonceState#serialize()}); the next epoch had not started when they were taken.
     */
    @Test
    void aSyncedNonceStateGivesTheNextEpochsPublishedNonce() throws Exception {
        assertNextEpochNonce("preview", 86_400, 432, 4_320, 0, 124_238_075L,
                "010000059d0113485fc12dcd9813723ed489a352201f5e7c85f9bfd207a30e8891a694260aa2015ca9b1146c50e42ea4"
                        + "9e1bb268ee99bd7d13355c1a011f9cf97da56697546f7401f60c8a77be4dec6f90d579a141defc3b8e2bc5e593ed"
                        + "10059d270a02ee3b746f010c700fa3c7c7f097132a75d258acaa766506794de9d2b216636a295fd8137eae01b563"
                        + "7ee4627ee793b3837f1a6c3397e2ef98aabbccc73afabcc58e95386c958a");
        assertNextEpochNonce("preprod", 432_000, 2_160, 21_600, 86_400, 135_210_843L,
                "010000013c017fbcb75baab7fd95f16631736b83e9315b26619a6f2287f0955d574b087f95330195b2f0c2cd67cbd012"
                        + "f60838a77c343139f72ef67b7c29f368a0b3f6c3bfdcaf01a52345dde70430e159733cc49850739b21ccb1943326"
                        + "5df9b4ea85aab4f0e3fe01750906ab5f53f1bc5c266d7be31cd2c561d073bdc97160c2c312fdebc116880a01a3bf"
                        + "23dc1f682fa937aee074a8fadd671e1a48992bba8e9990f1c863fac1a6d8");
    }

    private static void assertFirstShelleyEpochNonce(String network, int firstShelleyEpoch) throws IOException {
        EpochNonceState state = new EpochNonceState(432_000, 2160, 0.05, 21_600);
        state.initFromGenesis(Files.readAllBytes(GENESIS_DIR.resolve(network).resolve("shelley-genesis.json")));

        assertThat(HexUtil.encodeHexString(state.getEpochNonce())).as(network)
                .isEqualTo(published(network).get(firstShelleyEpoch));
    }

    private static void assertNextEpochNonce(String network, long epochLength, long k, long byronSlotsPerEpoch,
                                             long firstShelleySlot, long snapshotSlot, String serialized)
            throws IOException {
        EpochNonceState state = new EpochNonceState(epochLength, k, 0.05, byronSlotsPerEpoch);
        state.setShelleyStartSlot(firstShelleySlot);
        state.restore(HexUtil.decodeHexString(serialized));
        int epoch = state.getCurrentEpoch();
        long stabilityWindowEnd = state.firstSlotOfEpoch(epoch) + (long) Math.ceil(4.0 * k / 0.05);
        Map<Integer, String> published = published(network);

        assertThat(state.epochForSlot(snapshotSlot)).as(network).isEqualTo(epoch);
        assertThat(snapshotSlot).as(network + ": candidate nonce frozen").isGreaterThanOrEqualTo(stabilityWindowEnd);
        assertThat(HexUtil.encodeHexString(state.getEpochNonce())).as(network).isEqualTo(published.get(epoch));

        long nextEpochStart = state.firstSlotOfEpoch(epoch + 1);
        assertThat(HexUtil.encodeHexString(state.previewEpochNonceForSlot(nextEpochStart))).as(network)
                .isEqualTo(published.get(epoch + 1));
        state.advanceEpochIfNeeded(nextEpochStart);
        assertThat(state.getCurrentEpoch()).isEqualTo(epoch + 1);
        assertThat(HexUtil.encodeHexString(state.getEpochNonce())).as(network).isEqualTo(published.get(epoch + 1));
    }
}
