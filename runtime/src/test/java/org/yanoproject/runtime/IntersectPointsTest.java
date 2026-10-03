package org.yanoproject.runtime;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.runtime.chain.InMemoryChainState;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class IntersectPointsTest {

    @Test
    void fibonacciOffsetsBackFromTheTipThenTheBlockKBack() {
        InMemoryChainState chain = chainOf(30);

        assertThat(IntersectPoints.olderThan(chain, chain.getTip(), 10))
                .containsExactlyElementsOf(points(29, 28, 27, 25, 22, 20));
        // ouroboros-consensus: for k = 2160, [0,1,2,3,5,8,13,21,34,55,89,144,233,377,610,987,1597,2160]
        InMemoryChainState mainnetDeep = chainOf(3000);
        assertThat(IntersectPoints.olderThan(mainnetDeep, mainnetDeep.getTip(), 2160))
                .extracting(point -> 3000 - point.getSlot() / 10)
                .containsExactly(1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L, 55L, 89L, 144L, 233L, 377L, 610L, 987L, 1597L,
                        2160L);
    }

    @Test
    void aChainShorterThanKEndsAtItsFirstBlock() {
        InMemoryChainState chain = chainOf(5);

        assertThat(IntersectPoints.olderThan(chain, chain.getTip(), 2160))
                .containsExactlyElementsOf(points(4, 3, 2, 0));
        assertThat(IntersectPoints.olderThan(chainOf(0), chainOf(0).getTip(), 2160)).isEmpty();
        assertThat(IntersectPoints.olderThan(chain, null, 2160)).isEmpty();
    }

    /** Blocks 0..tip, block n at slot 10n. */
    private static InMemoryChainState chainOf(long tip) {
        InMemoryChainState chain = new InMemoryChainState();
        for (long n = 0; n <= tip; n++) {
            chain.storeBlockHeader(hash(n), n, n * 10, new byte[] {1});
            chain.storeBlock(hash(n), n, n * 10, new byte[] {1});
        }
        return chain;
    }

    private static List<Point> points(long... numbers) {
        return LongStream.of(numbers).mapToObj(n -> new Point(n * 10, HexUtil.encodeHexString(hash(n)))).toList();
    }

    private static byte[] hash(long n) {
        byte[] hash = new byte[32];
        hash[0] = 1;
        hash[24] = (byte) (n >>> 8);
        hash[31] = (byte) n;
        return hash;
    }
}
