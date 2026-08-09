package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochStakeContractTest {

    @Test
    void roundTripsMaximumChunkWithoutRelaxingOtherContractLimits() {
        List<EpochStakeContract.Entry> entries = entries(EpochStakeContract.MAX_CHUNK_ENTRIES);
        byte[] root = EpochStakeContract.snapshotRoot(List.of(EpochStakeContract.chunkHash(entries)));
        EpochStakeContract.Chunk chunk = new EpochStakeContract.Chunk(500, root, 0, entries);

        byte[] encoded = EpochStakeContract.encodeChunk(chunk);

        assertThat(encoded.length).isBetween(1_048_577, 3 * 1_048_576);
        assertThat(EpochStakeContract.decodeChunk(encoded)).isEqualTo(chunk);
    }

    @Test
    void rejectsDuplicateAndReorderedCredentials() {
        EpochStakeContract.Entry first = entry(1);
        EpochStakeContract.Entry second = entry(2);

        assertThatThrownBy(() -> new EpochStakeContract.Chunk(
                500, new byte[32], 0, List.of(second, first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical order");
        assertThatThrownBy(() -> new EpochStakeContract.Chunk(
                500, new byte[32], 0, List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical order");
    }

    @Test
    void manifestPinsEndOfEpochSemanticsAndChunkCount() {
        byte[] root = EpochStakeContract.snapshotRoot(List.of(new byte[32], new byte[32]));
        EpochStakeContract.Manifest manifest = new EpochStakeContract.Manifest(
                499, 25_001, 25_000, 2, root);

        assertThat(EpochStakeContract.decodeManifest(
                EpochStakeContract.encodeManifest(manifest))).isEqualTo(manifest);
        assertThatThrownBy(() -> new EpochStakeContract.Manifest(
                499, 25_001, 25_000, 1, root))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneLeafCommitsCoinAndPoolTogether() {
        EpochStakeContract.Entry entry = entry(42);

        assertThat(EpochStakeContract.encodeValue(entry)).isNotEmpty();
        assertThat(EpochStakeContract.entryKey(499, entry.credType(), entry.credHash()))
                .asString()
                .startsWith("stake/499/00");
    }

    @Test
    void completenessIsBoundToTheManifestAndOnlyTrueAfterEveryChunk() {
        EpochStakeContract.Manifest manifest = new EpochStakeContract.Manifest(
                499, 25_001, 25_000, 2, new byte[32]);
        EpochStakeContract.Meta complete = new EpochStakeContract.Meta(manifest, 2, true);

        assertThat(EpochStakeContract.decodeMeta(
                EpochStakeContract.encodeMeta(complete))).isEqualTo(complete);
        assertThatThrownBy(() -> new EpochStakeContract.Meta(manifest, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<EpochStakeContract.Entry> entries(int size) {
        List<EpochStakeContract.Entry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) entries.add(entry(index));
        return entries;
    }

    private static EpochStakeContract.Entry entry(long index) {
        return new EpochStakeContract.Entry(0, bytes(index),
                BigInteger.valueOf(1_000_000L + index), bytes(index % 1_000));
    }

    private static byte[] bytes(long value) {
        byte[] result = new byte[28];
        ByteBuffer.wrap(result, 20, 8).putLong(value);
        return result;
    }
}
