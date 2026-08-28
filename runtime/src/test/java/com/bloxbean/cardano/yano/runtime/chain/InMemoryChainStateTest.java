package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryChainStateTest {

    @Test
    void rollbackPreservesBodyTipWhenHeaderTipWasAhead() {
        InMemoryChainState chainState = new InMemoryChainState();
        byte[] bodyHash = hash(1);
        byte[] headerHash = hash(2);

        chainState.storeBlock(bodyHash, 1L, 10L, new byte[]{1});
        chainState.storeBlockHeader(headerHash, 2L, 20L, new byte[]{2});

        chainState.rollbackTo(15L);

        assertNotNull(chainState.getTip());
        assertEquals(10L, chainState.getTip().getSlot());
        assertEquals(1L, chainState.getTip().getBlockNumber());
        assertNotNull(chainState.getHeaderTip());
        assertEquals(10L, chainState.getHeaderTip().getSlot());
        assertNull(chainState.getBlockHeader(headerHash));
    }

    @Test
    void byronEbHeaderDoesNotOverwriteMainHeaderNumberIndex() {
        InMemoryChainState chainState = new InMemoryChainState();
        byte[] mainHash = hash(1);
        byte[] ebbHash = hash(2);

        chainState.storeBlockHeader(mainHash, 5L, 50L, new byte[]{1});
        chainState.storeByronEbHeader(ebbHash, 5L, 40L, new byte[]{2});

        assertArrayEquals(new byte[]{1}, chainState.getBlockHeaderByNumber(5L));
        assertArrayEquals(new byte[]{2}, chainState.getBlockHeader(ebbHash));
    }

    @Test
    void pointRollbackDistinguishesEbbAndSameSlotMain() {
        InMemoryChainState chainState = new InMemoryChainState();
        byte[] main1 = hash(10);
        byte[] ebb = hash(11);
        byte[] main2 = hash(12);
        storeMain(chainState, main1, 1L, 30L);
        storeEbb(chainState, ebb, 1L, 40L);
        storeMain(chainState, main2, 2L, 40L);

        chainState.rollbackTo(new Point(40L, HexUtil.encodeHexString(ebb)));

        assertArrayEquals(ebb, chainState.getTip().getBlockHash());
        assertArrayEquals(ebb, chainState.getHeaderTip().getBlockHash());
        assertNull(chainState.getBlock(main2));
        assertNotNull(chainState.getBlock(ebb));
    }

    @Test
    void pointOriginAcceptsYaciOriginAndLegacyCompensationShape() {
        InMemoryChainState chainState = new InMemoryChainState();
        storeMain(chainState, hash(20), 1L, 30L);
        chainState.rollbackTo(Point.ORIGIN);
        assertNull(chainState.getTip());

        storeMain(chainState, hash(21), 1L, 30L);
        chainState.rollbackTo(new Point(-1L, null));
        assertNull(chainState.getTip());
    }

    @Test
    void slotZeroWithHashIsARealPointNotOrigin() {
        InMemoryChainState chainState = new InMemoryChainState();
        byte[] genesisEbb = hash(22);
        storeEbb(chainState, genesisEbb, 0L, 0L);

        chainState.rollbackTo(new Point(0L, HexUtil.encodeHexString(genesisEbb)));

        assertNotNull(chainState.getTip());
        assertArrayEquals(genesisEbb, chainState.getTip().getBlockHash());
    }

    private static void storeMain(InMemoryChainState state, byte[] hash, long number, long slot) {
        state.storeBlockHeader(hash, number, slot, new byte[]{hash[31]});
        state.storeBlock(hash, number, slot, new byte[]{hash[31]});
    }

    private static void storeEbb(InMemoryChainState state, byte[] hash, long number, long slot) {
        state.storeByronEbHeader(hash, number, slot, new byte[]{hash[31]});
        state.storeBlock(hash, number, slot, new byte[]{hash[31]});
    }

    private static byte[] hash(int value) {
        return HexUtil.decodeHexString(String.format("%064x", value));
    }
}
