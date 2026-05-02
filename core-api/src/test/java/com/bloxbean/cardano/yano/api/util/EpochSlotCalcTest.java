package com.bloxbean.cardano.yano.api.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link EpochSlotCalc} — ported from yaci-store EpochConfigTest
 * with additional boundary, preview-bug-guard, and custom-devnet vectors.
 */
class EpochSlotCalcTest {

    // --- Network constants ---

    // Mainnet: epochLength=432000, Byron k=2160, byronSlotsPerEpoch=21600, firstNonByronSlot=4492800 (epoch 208)
    private static final EpochSlotCalc MAINNET = new EpochSlotCalc(432000, 21600, 4492800);

    // Preprod: epochLength=432000, Byron k=2160, byronSlotsPerEpoch=21600, firstNonByronSlot=86400 (epoch 4)
    private static final EpochSlotCalc PREPROD = new EpochSlotCalc(432000, 21600, 86400);

    // Preview: epochLength=86400, Byron k=432, byronSlotsPerEpoch=4320, firstNonByronSlot=0 (no Byron)
    private static final EpochSlotCalc PREVIEW = new EpochSlotCalc(86400, 4320, 0);

    // --- Constructor validation ---

    @Test
    void constructor_zeroEpochLength_throws() {
        assertThatThrownBy(() -> new EpochSlotCalc(0, 21600, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shelleyEpochLength must be positive");
    }

    @Test
    void constructor_negativeEpochLength_throws() {
        assertThatThrownBy(() -> new EpochSlotCalc(-1, 21600, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_zeroByronSlots_throws() {
        assertThatThrownBy(() -> new EpochSlotCalc(432000, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byronSlotsPerEpoch must be positive");
    }

    @Test
    void constructor_nonAlignedFirstNonByronSlot_throws() {
        // 4492801 is not divisible by 21600
        assertThatThrownBy(() -> new EpochSlotCalc(432000, 21600, 4492801))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch-aligned");
    }

    @Test
    void constructor_alignedFirstNonByronSlot_ok() {
        assertThatCode(() -> new EpochSlotCalc(432000, 21600, 4492800))
                .doesNotThrowAnyException();
    }

    @Test
    void constructor_zeroFirstNonByronSlot_ok() {
        assertThatCode(() -> new EpochSlotCalc(86400, 4320, 0))
                .doesNotThrowAnyException();
    }

    // --- Mainnet vectors (from yaci-store EpochConfigTest) ---

    @Nested
    class MainnetEpochs {

        @Test
        void firstShelleySlot_isEpoch208() {
            assertThat(MAINNET.slotToEpoch(4492800)).isEqualTo(208);
        }

        @Test
        void firstShelleySlotPlus80_isEpoch208() {
            assertThat(MAINNET.slotToEpoch(4492880)).isEqualTo(208);
        }

        @Test
        void slot86707137_isEpoch398() {
            assertThat(MAINNET.slotToEpoch(86707137)).isEqualTo(398);
        }

        @Test
        void slot66095599_isEpoch350() {
            assertThat(MAINNET.slotToEpoch(66095599)).isEqualTo(350);
        }

        @Test
        void slot24791234_isEpoch254() {
            assertThat(MAINNET.slotToEpoch(24791234)).isEqualTo(254);
        }

        @Test
        void epochSlot_94807439_is26639() {
            assertThat(MAINNET.slotToEpochSlot(94807439)).isEqualTo(26639);
        }

        @Test
        void epochSlot_94348799_is431999() {
            assertThat(MAINNET.slotToEpochSlot(94348799)).isEqualTo(431999);
        }

        @Test
        void epochSlot_94348926_is126() {
            assertThat(MAINNET.slotToEpochSlot(94348926)).isEqualTo(126);
        }

        @Test
        void epochSlotToAbsoluteSlot_484_370607() {
            assertThat(MAINNET.epochSlotToAbsoluteSlot(484, 370607)).isEqualTo(124095407L);
        }

        @Test
        void epochSlotToAbsoluteSlot_437_201404() {
            assertThat(MAINNET.epochSlotToAbsoluteSlot(437, 201404)).isEqualTo(103622204L);
        }

        @Test
        void epochSlotToAbsoluteSlot_208_0() {
            assertThat(MAINNET.epochSlotToAbsoluteSlot(208, 0)).isEqualTo(4492800L);
        }

        @Test
        void epochSlotToAbsoluteSlot_208_431980() {
            assertThat(MAINNET.epochSlotToAbsoluteSlot(208, 431980)).isEqualTo(4924780L);
        }

        @Test
        void firstNonByronEpoch_is208() {
            assertThat(MAINNET.firstNonByronEpoch()).isEqualTo(208);
        }
    }

    // --- Mainnet boundary tests ---

    @Nested
    class MainnetBoundary {

        @Test
        void lastByronSlot_isEpoch207() {
            assertThat(MAINNET.slotToEpoch(4492799)).isEqualTo(207);
        }

        @Test
        void firstShelleySlot_isEpoch208() {
            assertThat(MAINNET.slotToEpoch(4492800)).isEqualTo(208);
        }

        @Test
        void firstShelleySlotPlus1_isEpoch208() {
            assertThat(MAINNET.slotToEpoch(4492801)).isEqualTo(208);
        }

        @Test
        void lastByronSlot_epochSlot_usesByronModulo() {
            // Slot 4492799 is the last Byron slot. byronSlotsPerEpoch=21600
            // Epoch 207 starts at slot 207*21600=4471200
            // epochSlot = 4492799 - 4471200 = 21599
            assertThat(MAINNET.slotToEpochSlot(4492799)).isEqualTo(21599);
        }

        @Test
        void firstShelleySlot_epochSlot_is0() {
            assertThat(MAINNET.slotToEpochSlot(4492800)).isEqualTo(0);
        }

        @Test
        void firstShelleySlotPlus1_epochSlot_is1() {
            assertThat(MAINNET.slotToEpochSlot(4492801)).isEqualTo(1);
        }

        @Test
        void epochToStartSlot_byron207() {
            assertThat(MAINNET.epochToStartSlot(207)).isEqualTo(207L * 21600);
        }

        @Test
        void epochToStartSlot_shelley208() {
            assertThat(MAINNET.epochToStartSlot(208)).isEqualTo(4492800L);
        }

        @Test
        void epochToStartSlot_shelley209() {
            assertThat(MAINNET.epochToStartSlot(209)).isEqualTo(4492800L + 432000);
        }

        @Test
        void epochToStartSlot_byron0() {
            assertThat(MAINNET.epochToStartSlot(0)).isEqualTo(0L);
        }
    }

    // --- Preprod vectors (from yaci-store EpochConfigTest) ---

    @Nested
    class PreprodEpochs {

        @Test
        void firstShelleySlot_isEpoch4() {
            assertThat(PREPROD.slotToEpoch(86400)).isEqualTo(4);
        }

        @Test
        void slot1006073_isEpoch6() {
            assertThat(PREPROD.slotToEpoch(1006073)).isEqualTo(6);
        }

        @Test
        void slot2048749_isEpoch8() {
            assertThat(PREPROD.slotToEpoch(2048749)).isEqualTo(8);
        }

        @Test
        void slot13782223_isEpoch35() {
            assertThat(PREPROD.slotToEpoch(13782223)).isEqualTo(35);
        }

        @Test
        void slot23368840_isEpoch57() {
            assertThat(PREPROD.slotToEpoch(23368840)).isEqualTo(57);
        }

        @Test
        void firstNonByronEpoch_is4() {
            assertThat(PREPROD.firstNonByronEpoch()).isEqualTo(4);
        }
    }

    // --- Preview vectors (from yaci-store EpochConfigTest) ---

    @Nested
    class PreviewEpochs {

        @Test
        void slot0_isEpoch0() {
            assertThat(PREVIEW.slotToEpoch(0)).isEqualTo(0);
        }

        @Test
        void slot172921_isEpoch2() {
            assertThat(PREVIEW.slotToEpoch(172921)).isEqualTo(2);
        }

        @Test
        void slot259215_isEpoch3() {
            assertThat(PREVIEW.slotToEpoch(259215)).isEqualTo(3);
        }

        @Test
        void slot518402_isEpoch6() {
            assertThat(PREVIEW.slotToEpoch(518402)).isEqualTo(6);
        }

        @Test
        void epochSlot_173026_is226() {
            assertThat(PREVIEW.slotToEpochSlot(173026)).isEqualTo(226);
        }

        @Test
        void epochSlot_259215_is15() {
            assertThat(PREVIEW.slotToEpochSlot(259215)).isEqualTo(15);
        }

        @Test
        void epochSlot_604914_is114() {
            assertThat(PREVIEW.slotToEpochSlot(604914)).isEqualTo(114);
        }

        @Test
        void firstNonByronEpoch_is0() {
            assertThat(PREVIEW.firstNonByronEpoch()).isEqualTo(0);
        }
    }

    // --- Preview bug guard: slot 864004 must map to epoch 10, NOT epoch 2 ---

    @Nested
    class PreviewBugGuard {

        @Test
        void slot864004_isEpoch10_notEpoch2() {
            // The original bug: epochLength=432000 would give 864004/432000 = 2
            // Correct with epochLength=86400: 864004/86400 = 10
            assertThat(PREVIEW.slotToEpoch(864004)).isEqualTo(10);
        }

        @Test
        void epochToStartSlot_epoch10() {
            assertThat(PREVIEW.epochToStartSlot(10)).isEqualTo(864000L);
        }
    }

    // --- Custom devnet (no Byron, non-standard epoch length) ---

    @Nested
    class CustomDevnet {

        private static final EpochSlotCalc DEVNET = new EpochSlotCalc(600, 100, 0);

        @Test
        void slot1200_isEpoch2() {
            assertThat(DEVNET.slotToEpoch(1200)).isEqualTo(2);
        }

        @Test
        void slot599_isEpoch0() {
            assertThat(DEVNET.slotToEpoch(599)).isEqualTo(0);
        }

        @Test
        void slot600_isEpoch1() {
            assertThat(DEVNET.slotToEpoch(600)).isEqualTo(1);
        }

        @Test
        void epochSlot_1201_is1() {
            assertThat(DEVNET.slotToEpochSlot(1201)).isEqualTo(1);
        }

        @Test
        void epochToStartSlot_3_is1800() {
            assertThat(DEVNET.epochToStartSlot(3)).isEqualTo(1800L);
        }
    }

    // --- Roundtrip tests ---

    @Nested
    class Roundtrip {

        @Test
        void mainnet_epoch_epochSlot_roundtrip() {
            long slot = 86707137L;
            int epoch = MAINNET.slotToEpoch(slot);
            int epochSlot = MAINNET.slotToEpochSlot(slot);
            long reconstructed = MAINNET.epochSlotToAbsoluteSlot(epoch, epochSlot);
            assertThat(reconstructed).isEqualTo(slot);
        }

        @Test
        void preprod_epoch_epochSlot_roundtrip() {
            long slot = 13782223L;
            int epoch = PREPROD.slotToEpoch(slot);
            int epochSlot = PREPROD.slotToEpochSlot(slot);
            long reconstructed = PREPROD.epochSlotToAbsoluteSlot(epoch, epochSlot);
            assertThat(reconstructed).isEqualTo(slot);
        }

        @Test
        void preview_epoch_epochSlot_roundtrip() {
            long slot = 604914L;
            int epoch = PREVIEW.slotToEpoch(slot);
            int epochSlot = PREVIEW.slotToEpochSlot(slot);
            long reconstructed = PREVIEW.epochSlotToAbsoluteSlot(epoch, epochSlot);
            assertThat(reconstructed).isEqualTo(slot);
        }

        @Test
        void mainnet_byronSlot_roundtrip() {
            long slot = 4000000L; // Byron era
            int epoch = MAINNET.slotToEpoch(slot);
            int epochSlot = MAINNET.slotToEpochSlot(slot);
            long reconstructed = MAINNET.epochSlotToAbsoluteSlot(epoch, epochSlot);
            assertThat(reconstructed).isEqualTo(slot);
        }

        @Test
        void mainnet_epochToStartSlot_then_slotToEpoch() {
            for (int e : new int[]{0, 100, 207, 208, 209, 300, 400}) {
                long startSlot = MAINNET.epochToStartSlot(e);
                assertThat(MAINNET.slotToEpoch(startSlot))
                        .as("epochToStartSlot(%d) then slotToEpoch", e)
                        .isEqualTo(e);
            }
        }

        @Test
        void preview_epochToStartSlot_then_slotToEpoch() {
            for (int e : new int[]{0, 1, 2, 5, 10, 50}) {
                long startSlot = PREVIEW.epochToStartSlot(e);
                assertThat(PREVIEW.slotToEpoch(startSlot))
                        .as("epochToStartSlot(%d) then slotToEpoch", e)
                        .isEqualTo(e);
            }
        }
    }

    // --- Accessor tests ---

    @Test
    void accessors() {
        assertThat(MAINNET.shelleyEpochLength()).isEqualTo(432000);
        assertThat(MAINNET.byronSlotsPerEpoch()).isEqualTo(21600);
        assertThat(MAINNET.firstNonByronSlot()).isEqualTo(4492800);
        assertThat(MAINNET.firstNonByronEpoch()).isEqualTo(208);

        assertThat(PREVIEW.shelleyEpochLength()).isEqualTo(86400);
        assertThat(PREVIEW.byronSlotsPerEpoch()).isEqualTo(4320);
        assertThat(PREVIEW.firstNonByronSlot()).isEqualTo(0);
        assertThat(PREVIEW.firstNonByronEpoch()).isEqualTo(0);
    }
}
