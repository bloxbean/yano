package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateStore;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * In-memory implementation of chain-state capabilities for tests, devnet, and
 * non-persistent runtime profiles.
 */
@Slf4j
public class InMemoryChainState implements ChainState, NonceStateStore,
        ByronEbHeaderStore, OriginRollbackCapable {
    // Use hex string keys instead of byte[] to ensure proper equals/hashCode behavior
    private Map<String, byte[]> blockStore = new ConcurrentHashMap<>();
    private Map<String, byte[]> blockHeaderStore = new ConcurrentHashMap<>();
    private Map<Long, byte[]> blockHashByNumber = new ConcurrentHashMap<>();
    private ConcurrentSkipListMap<Long, Long> blockNumberBySlot = new ConcurrentSkipListMap<>();
    private Map<Long, byte[]> headerHashByNumber = new ConcurrentHashMap<>();
    private ConcurrentSkipListMap<Long, Long> headerNumberBySlot = new ConcurrentSkipListMap<>();
    private ConcurrentSkipListMap<Long, byte[]> headerHashBySlot = new ConcurrentSkipListMap<>();
    private ConcurrentSkipListMap<Long, byte[]> ebbHeaderHashBySlot = new ConcurrentSkipListMap<>();
    private ConcurrentSkipListMap<Long, Long> ebbHeaderNumberBySlot = new ConcurrentSkipListMap<>();

    private static String toHex(byte[] bytes) {
        return HexUtil.encodeHexString(bytes);
    }

    private ChainTip tip;
    private ChainTip headerTip;
    private volatile byte[] epochNonceState;
    private final Map<Integer, byte[]> epochNonces = new ConcurrentHashMap<>();
    private final Map<Integer, NonceStateSnapshot> epochNonceCheckpoints = new ConcurrentHashMap<>();

    @Override
    public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {
        String key = toHex(blockHash);
        blockStore.put(key, block);
        blockHeaderStore.put(key, block);
        blockHashByNumber.put(blockNumber, blockHash);
        blockNumberBySlot.put(slot, blockNumber);
        indexMainHeader(blockHash, blockNumber, slot);
        tip = new ChainTip(slot, blockHash, blockNumber);
    }

    @Override
    public byte[] getBlock(byte[] blockHash) {
        return blockStore.get(toHex(blockHash));
    }

    @Override
    public boolean hasBlock(byte[] blockHash) {
        return blockStore.containsKey(toHex(blockHash));
    }

    @Override
    public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {
        blockHeaderStore.put(toHex(blockHash), blockHeader);
        indexMainHeader(blockHash, blockNumber, slot);
        headerTip = new ChainTip(slot, blockHash, blockNumber);
    }

    public void storeByronEbHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {
        blockHeaderStore.put(toHex(blockHash), blockHeader);
        if (slot != null) {
            ebbHeaderHashBySlot.put(slot, blockHash);
            if (blockNumber != null) {
                ebbHeaderNumberBySlot.put(slot, blockNumber);
            }
        }
        headerTip = new ChainTip(slot, blockHash, blockNumber);
    }

    @Override
    public byte[] getBlockHeader(byte[] blockHash) {
        return blockHeaderStore.get(toHex(blockHash));
    }

    @Override
    public byte[] getBlockByNumber(Long number) {
        byte[] blockHash = blockHashByNumber.get(number);
        if (blockHash != null) {
            return blockStore.get(toHex(blockHash));
        }
        return null;
    }

    @Override
    public void rollbackTo(Long slot) {
        headerHashBySlot.tailMap(slot, false).forEach((headerSlot, blockHash) -> {
            String key = toHex(blockHash);
            blockHeaderStore.remove(key);
            removeHashValue(headerHashByNumber, blockHash);
            headerNumberBySlot.remove(headerSlot);
        });
        headerHashBySlot.tailMap(slot, false).clear();
        headerNumberBySlot.tailMap(slot, false).clear();

        ebbHeaderHashBySlot.tailMap(slot, false).forEach((headerSlot, blockHash) ->
                blockHeaderStore.remove(toHex(blockHash)));
        ebbHeaderHashBySlot.tailMap(slot, false).clear();
        ebbHeaderNumberBySlot.tailMap(slot, false).clear();

        // Get all body block numbers greater than the provided slot
        blockNumberBySlot.tailMap(slot, false).forEach((blockSlot, blockNumber) -> {
            byte[] blockHash = blockHashByNumber.get(blockNumber);
            if (blockHash != null) {
                String key = toHex(blockHash);
                blockStore.remove(key);
                blockHashByNumber.remove(blockNumber);
            }
        });

        // Remove the entries from blockNumberBySlot where slots are greater than the provided slot
        blockNumberBySlot.tailMap(slot, false).clear();

        tip = bodyTipAtOrBefore(slot);
        headerTip = headerTipAtOrBefore(slot);
    }

    private ChainTip bodyTipAtOrBefore(Long slot) {
        var lastEntry = blockNumberBySlot.floorEntry(slot);
        if (lastEntry == null) return null;
        Long tipSlot = lastEntry.getKey();
        Long blockNumber = lastEntry.getValue();
        byte[] blockHash = blockHashByNumber.get(blockNumber);
        return blockHash != null && blockStore.containsKey(toHex(blockHash))
                ? new ChainTip(tipSlot, blockHash, blockNumber)
                : null;
    }

    private ChainTip headerTipAtOrBefore(Long slot) {
        HeaderPoint headerPoint = lastHeaderPointAtOrBefore(slot);
        return headerPoint != null
                ? new ChainTip(headerPoint.slot(), headerPoint.hash(), headerPoint.blockNumber())
                : null;
    }

    public void rollbackToOrigin() {
        blockStore.clear();
        blockHeaderStore.clear();
        blockHashByNumber.clear();
        blockNumberBySlot.clear();
        headerHashByNumber.clear();
        headerNumberBySlot.clear();
        headerHashBySlot.clear();
        ebbHeaderHashBySlot.clear();
        ebbHeaderNumberBySlot.clear();
        tip = null;
        headerTip = null;
        epochNonceState = null;
        epochNonces.clear();
        epochNonceCheckpoints.clear();
    }

    @Override
    public ChainTip getTip() {
        return tip;
    }

    @Override
    public ChainTip getHeaderTip() {
        return headerTip;
    }

    @Override
    public byte[] getBlockHeaderByNumber(Long blockNumber) {
        byte[] blockHash = headerHashByNumber.get(blockNumber);
        if (blockHash != null) {
            return blockHeaderStore.get(toHex(blockHash));
        }
        return null;
    }

    @Override
    public Point findNextBlockHeader(Point currentPoint) {
        // Slot-first: iterate by slot order up to header tip, independent of block number continuity
        if (currentPoint == null) return null;

        long currentSlot = currentPoint.getSlot();
        ChainTip headerTip = getHeaderTip();

        if (headerTip == null || currentSlot >= headerTip.getSlot()) return null;

        HeaderPoint next = nextHeaderPointAfter(currentSlot, currentPoint.getHash());
        return next != null ? new Point(next.slot(), HexUtil.encodeHexString(next.hash())) : null;
    }

    @Override
    public Point findNextBlock(Point currentPoint) {
        // Slot-first: behave like findNextBlockHeader but bounded by body tip
        if (currentPoint == null) return null;

        // If ORIGIN, return the first block we have
        if (currentPoint.getSlot() == 0 && currentPoint.getHash() == null) {
            return getFirstBlock();
        }

        long currentSlot = currentPoint.getSlot();
        ChainTip bodyTip = getTip();
        if (bodyTip == null || currentSlot >= bodyTip.getSlot()) return null;

        Long nextSlot = blockNumberBySlot.higherKey(currentSlot);
        if (nextSlot == null) return null;

        Long blockNumber = blockNumberBySlot.get(nextSlot);
        if (blockNumber == null) return null;

        byte[] blockHash = blockHashByNumber.get(blockNumber);
        if (blockHash == null) return null;

        return new Point(nextSlot, HexUtil.encodeHexString(blockHash));
    }

    /**
     * Find the first (earliest) block in our chain
     */
    public Point getFirstBlock() {
        HeaderPoint first = firstHeaderPoint();
        return first != null ? new Point(first.slot(), HexUtil.encodeHexString(first.hash())) : null;
    }

    @Override
    public List<Point> findBlocksInRange(Point from, Point to) {
        // Slot-first: return points between slots [min(from.slot, to.slot), max] inclusive
        List<Point> out = new ArrayList<>();
        if (from == null || to == null) return out;

        try {
            // Resolve start slot (handle ORIGIN)
            Long startSlot = from.getSlot() == 0 && from.getHash() == null
                    ? firstHeaderSlot()
                    : from.getSlot();
            Long endSlot = to.getSlot();
            if (startSlot == null || endSlot == null) return out;

            long minSlot = Math.min(startSlot, endSlot);
            long maxSlot = Math.max(startSlot, endSlot);

            headerPointsInRange(minSlot, maxSlot).forEach(headerPoint ->
                    out.add(new Point(headerPoint.slot(), HexUtil.encodeHexString(headerPoint.hash()))));
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("Error finding blocks in range: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public boolean hasPoint(Point point) {
        if (point == null) {
            return false;
        }

        // Handle genesis point (Point.ORIGIN with slot=0, hash=null)
        if (point.getSlot() == 0 && point.getHash() == null) {
            // Genesis point is always considered valid if we have any blocks
            return tip != null;
        }

        // Handle normal points with hash
        if (point.getHash() == null) {
            return false;
        }

        try {
            String key = point.getHash();
            return blockStore.containsKey(key) || blockHeaderStore.containsKey(key);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Point findLastPointAfterNBlocks(Point from, long batchSize) {
        if (from == null) {
            return null;
        }

        try {
            long fromSlot = from.getSlot();

            List<HeaderPoint> points = headerPointsInRange(fromSlot, Long.MAX_VALUE).stream()
                    .limit(batchSize)
                    .toList();

            if (points.isEmpty()) {
                return null;
            }

            HeaderPoint lastPoint = points.get(points.size() - 1);
            return new Point(lastPoint.slot(), HexUtil.encodeHexString(lastPoint.hash()));
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error finding last point after N blocks: {}", e.getMessage());
            }
            return null;
        }
    }

    @Override
    public Long getBlockNumberBySlot(Long slot) {
        Long blockNumber = headerNumberBySlot.get(slot);
        return blockNumber != null ? blockNumber : blockNumberBySlot.get(slot);
    }

    @Override
    public Long getSlotByBlockNumber(Long blockNumber) {
        for (Map.Entry<Long, Long> entry : headerNumberBySlot.entrySet()) {
            if (entry.getValue().equals(blockNumber)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void indexMainHeader(byte[] blockHash, Long blockNumber, Long slot) {
        if (blockNumber != null) {
            headerHashByNumber.put(blockNumber, blockHash);
        }
        if (slot != null) {
            headerHashBySlot.put(slot, blockHash);
            if (blockNumber != null) {
                headerNumberBySlot.put(slot, blockNumber);
            }
        }
    }

    private static void removeHashValue(Map<Long, byte[]> map, byte[] hash) {
        if (hash == null) return;
        map.entrySet().removeIf(entry -> java.util.Arrays.equals(entry.getValue(), hash));
    }

    private Long firstHeaderSlot() {
        HeaderPoint first = firstHeaderPoint();
        return first != null ? first.slot() : null;
    }

    private HeaderPoint firstHeaderPoint() {
        HeaderPoint main = firstHeaderPoint(headerHashBySlot, headerNumberBySlot);
        HeaderPoint ebb = firstHeaderPoint(ebbHeaderHashBySlot, ebbHeaderNumberBySlot);
        return earlier(main, ebb);
    }

    private HeaderPoint lastHeaderPointAtOrBefore(long slot) {
        HeaderPoint main = floorHeaderPoint(headerHashBySlot, headerNumberBySlot, slot);
        HeaderPoint ebb = floorHeaderPoint(ebbHeaderHashBySlot, ebbHeaderNumberBySlot, slot);
        return later(main, ebb);
    }

    private HeaderPoint nextHeaderPointAfter(long currentSlot, String currentHash) {
        HeaderPoint main = nextHeaderPoint(headerHashBySlot, headerNumberBySlot, currentSlot, currentHash);
        HeaderPoint ebb = nextHeaderPoint(ebbHeaderHashBySlot, ebbHeaderNumberBySlot, currentSlot, currentHash);
        return earlier(main, ebb);
    }

    private List<HeaderPoint> headerPointsInRange(long minSlot, long maxSlot) {
        List<HeaderPoint> points = new ArrayList<>();
        headerHashBySlot.subMap(minSlot, true, maxSlot, true).forEach((slot, hash) ->
                points.add(new HeaderPoint(slot, headerNumberBySlot.getOrDefault(slot, -1L), hash)));
        ebbHeaderHashBySlot.subMap(minSlot, true, maxSlot, true).forEach((slot, hash) ->
                points.add(new HeaderPoint(slot, ebbHeaderNumberBySlot.getOrDefault(slot, -1L), hash)));
        points.sort((left, right) -> {
            int slotCompare = Long.compare(left.slot(), right.slot());
            if (slotCompare != 0) return slotCompare;
            boolean leftEbb = ebbHeaderHashBySlot.get(left.slot()) != null
                    && java.util.Arrays.equals(ebbHeaderHashBySlot.get(left.slot()), left.hash());
            boolean rightEbb = ebbHeaderHashBySlot.get(right.slot()) != null
                    && java.util.Arrays.equals(ebbHeaderHashBySlot.get(right.slot()), right.hash());
            if (leftEbb != rightEbb) return leftEbb ? -1 : 1;
            return left.blockHashHex().compareTo(right.blockHashHex());
        });
        return points;
    }

    private static HeaderPoint firstHeaderPoint(ConcurrentSkipListMap<Long, byte[]> hashes,
                                                ConcurrentSkipListMap<Long, Long> numbers) {
        var entry = hashes.firstEntry();
        return entry != null ? new HeaderPoint(entry.getKey(), numbers.getOrDefault(entry.getKey(), -1L), entry.getValue()) : null;
    }

    private static HeaderPoint floorHeaderPoint(ConcurrentSkipListMap<Long, byte[]> hashes,
                                                ConcurrentSkipListMap<Long, Long> numbers,
                                                long slot) {
        var entry = hashes.floorEntry(slot);
        return entry != null ? new HeaderPoint(entry.getKey(), numbers.getOrDefault(entry.getKey(), -1L), entry.getValue()) : null;
    }

    private static HeaderPoint nextHeaderPoint(ConcurrentSkipListMap<Long, byte[]> hashes,
                                               ConcurrentSkipListMap<Long, Long> numbers,
                                               long currentSlot,
                                               String currentHash) {
        var entry = hashes.ceilingEntry(currentSlot);
        while (entry != null && entry.getKey() == currentSlot && currentHash != null
                && currentHash.equals(HexUtil.encodeHexString(entry.getValue()))) {
            entry = hashes.higherEntry(currentSlot);
        }
        return entry != null ? new HeaderPoint(entry.getKey(), numbers.getOrDefault(entry.getKey(), -1L), entry.getValue()) : null;
    }

    private static HeaderPoint earlier(HeaderPoint left, HeaderPoint right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.slot() <= right.slot() ? left : right;
    }

    private static HeaderPoint later(HeaderPoint left, HeaderPoint right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.slot() >= right.slot() ? left : right;
    }

    /**
     * Header index entry used to locate nearest rollback points by slot.
     */
    private record HeaderPoint(long slot, long blockNumber, byte[] hash) {
        String blockHashHex() {
            return HexUtil.encodeHexString(hash);
        }
    }

    // --- NonceStateStore implementation ---

    @Override
    public void storeEpochNonceState(byte[] serialized) {
        this.epochNonceState = serialized;
    }

    @Override
    public byte[] getEpochNonceState() {
        return epochNonceState;
    }

    @Override
    public void storeEpochNonce(int epoch, byte[] nonce) {
        if (nonce != null) {
            epochNonces.put(epoch, nonce.clone());
        }
    }

    @Override
    public byte[] getEpochNonce(int epoch) {
        byte[] nonce = epochNonces.get(epoch);
        return nonce != null ? nonce.clone() : null;
    }

    @Override
    public void pruneEpochNoncesAfter(int epoch) {
        epochNonces.keySet().removeIf(storedEpoch -> storedEpoch > epoch);
    }

    @Override
    public void storeEpochNonceCheckpoint(int epoch, NonceStateSnapshot snapshot) {
        if (snapshot != null) {
            epochNonceCheckpoints.put(epoch, snapshot);
        }
    }

    @Override
    public List<NonceStateSnapshot> getEpochNonceCheckpointsAtOrBeforeSlot(long slot) {
        return epochNonceCheckpoints.values().stream()
                .filter(snapshot -> snapshot.slot() <= slot)
                .sorted(java.util.Comparator
                        .comparingLong(NonceStateSnapshot::slot)
                        .thenComparingLong(NonceStateSnapshot::blockNumber)
                        .reversed())
                .toList();
    }

    @Override
    public void pruneEpochNonceCheckpointsAfter(int epoch) {
        epochNonceCheckpoints.keySet().removeIf(storedEpoch -> storedEpoch > epoch);
    }
}
