package com.bloxbean.cardano.yano.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoBech32IdsTest {
    private static final String HASH = "11".repeat(28);
    private static final long MAINNET_MAGIC = 764824073L;

    @Test
    void stakeAddressUsesProtocolMagicForPrefix() {
        assertTrue(CardanoBech32Ids.stakeAddress(0, HASH, MAINNET_MAGIC).startsWith("stake1"));
        assertTrue(CardanoBech32Ids.stakeAddress(0, HASH, 0).startsWith("stake_test1"));
        assertTrue(CardanoBech32Ids.stakeAddress(1, HASH, 0).startsWith("stake_test1"));
    }

    @Test
    void stakeCredentialRoundTripsKeyAndScriptRewardAddresses() {
        for (int type = 0; type <= 1; type++) {
            String address = CardanoBech32Ids.stakeAddress(type, HASH, 0);
            CardanoBech32Ids.StakeCredential parsed = CardanoBech32Ids.stakeCredential(address);

            assertNotNull(parsed);
            assertEquals(address, parsed.stakeAddress());
            assertEquals(type, parsed.credentialType());
            assertEquals(HASH, parsed.credentialHash());
            assertEquals(0, parsed.networkId());
        }

        CardanoBech32Ids.StakeCredential mainnet = CardanoBech32Ids.stakeCredential(
                CardanoBech32Ids.stakeAddress(0, HASH, 764824073));
        assertNotNull(mainnet);
        assertEquals(1, mainnet.networkId());
    }

    @Test
    void poolAndDRepIdsUseCardanoClientLibFormatting() {
        assertTrue(CardanoBech32Ids.poolId(HASH).startsWith("pool1"));
        assertTrue(CardanoBech32Ids.drepId(0, HASH).startsWith("drep1"));
        assertTrue(CardanoBech32Ids.drepId(1, HASH).startsWith("drep"));
        assertTrue(CardanoBech32Ids.drepHex(0, HASH).endsWith(HASH));
    }

    @Test
    void unsupportedOrMalformedInputsReturnNull() {
        assertNull(CardanoBech32Ids.stakeAddress(9, HASH, 0));
        assertNull(CardanoBech32Ids.stakeAddress(0, "not-hex", 0));
        assertNull(CardanoBech32Ids.poolId("not-hex"));
        assertNull(CardanoBech32Ids.drepId(2, HASH));
        assertNull(CardanoBech32Ids.drepId(0, null));
        assertNull(CardanoBech32Ids.stakeCredential(null));
        assertNull(CardanoBech32Ids.stakeCredential("not-an-address"));
        assertNull(CardanoBech32Ids.stakeCredential("60" + "00".repeat(28)));
        assertNull(CardanoBech32Ids.stakeCredential("stake_test1" + "a".repeat(300)));
    }
}
