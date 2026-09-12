package org.yanoproject.runtime.wallet;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialFilterTest {
    @Test
    void sipHashReferenceVectorsAndReusableState() {
        byte[] seed = new byte[16];
        for (int i = 0; i < seed.length; i++) seed[i] = (byte) i;
        SipHash24 hash = new SipHash24(seed);
        assertThat(hash.hash(new byte[0])).isEqualTo(0x726fdb47dd0e0e31L);
        assertThat(hash.hash(new byte[]{0})).isEqualTo(0x74f839c593dc67fdL);
        assertThat(hash.hash(new byte[]{0, 1})).isEqualTo(0x0d6c8009d9a94f5aL);
        byte[] message = new byte[15];
        for (int i = 0; i < message.length; i++) message[i] = (byte) i;
        assertThat(hash.hash(message)).isEqualTo(0xa129ca6149be45e5L);
        assertThat(hash.hash(new byte[0])).isEqualTo(0x726fdb47dd0e0e31L);
    }

    @Test
    void independentGoldenEncodingFromPublishedSipHashValues() {
        // SipHash reference vectors.h lengths 0, 1, 2, 15 with key 00..0f.
        // With N=4, M=12288, unsigned multiply-high gives sorted values
        // [2577, 21971, 22458, 30943], hence deltas [2577, 19394, 487, 8485].
        // Encode quotient in unary then 13 MSB-first remainder bits; pad with zeroes.
        // This literal was calculated independently, not by CredentialFilter.encode.
        byte[] seed = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f");
        List<byte[]> messages = List.of(new byte[0], new byte[]{0}, new byte[]{0, 1},
                HexFormat.of().parseHex("000102030405060708090a0b0c0d0e"));
        byte[] expected = HexFormat.of().parseHex("01000102030405060708090a0b0c0d0e0f0000000428472f081e7824a0");
        assertThat(CredentialFilter.encode(seed, messages)).isEqualTo(expected);
        for (byte[] message : messages) assertThat(CredentialFilter.matches(expected, List.of(message))).isTrue();
    }

    @Test
    void noFalseNegativesAndIndependentOfOrderOrDuplicates() {
        Random random = new Random(119);
        for (int size : new int[]{0, 1, 40, 80, 1_000}) {
            byte[] seed = new byte[16];
            random.nextBytes(seed);
            List<byte[]> elements = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                byte[] element = new byte[29];
                random.nextBytes(element);
                elements.add(element);
            }
            byte[] encoded = CredentialFilter.encode(seed, elements);
            for (byte[] element : elements) {
                assertThat(CredentialFilter.matches(encoded, List.of(element))).isTrue();
            }
            assertThat(CredentialFilter.matches(encoded, List.of())).isFalse();
            Collections.shuffle(elements, random);
            if (!elements.isEmpty()) elements.add(elements.getFirst().clone());
            assertThat(CredentialFilter.encode(seed, elements)).isEqualTo(encoded);
        }
    }

    @Test
    void boundsFalsePositivesOnDeterministicDisjointQueries() {
        byte[] seed = new byte[16];
        List<byte[]> elements = new ArrayList<>();
        for (int i = 0; i < 80; i++) elements.add(ByteBuffer.allocate(8).putLong(i).array());
        byte[] encoded = CredentialFilter.encode(seed, elements);
        int matches = 0;
        for (long i = 1_000; i < 101_000; i++) {
            if (CredentialFilter.matches(encoded, List.of(ByteBuffer.allocate(8).putLong(i).array()))) matches++;
        }
        assertThat(matches).isBetween(1, 35);
    }

    @Test
    void rejectsTruncationUnsupportedVersionAndTrailingDataEvenAfterMatch() {
        byte[] element = new byte[]{1, 2, 3};
        byte[] encoded = CredentialFilter.encode(new byte[16], List.of(element));
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> CredentialFilter.matches(truncated, List.of(element)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThatThrownBy(() -> CredentialFilter.matches(trailing, List.of(element)))
                .isInstanceOf(IllegalArgumentException.class);
        encoded[0] = 2;
        assertThatThrownBy(() -> CredentialFilter.matches(encoded, List.of(element)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyFilterAndInvalidCounts() {
        byte[] empty = CredentialFilter.encode(new byte[16], List.of());
        assertThat(empty).hasSize(21);
        assertThat(CredentialFilter.matches(empty, List.of(new byte[]{1}))).isFalse();
        ByteBuffer.wrap(empty, 17, 4).putInt(Integer.MAX_VALUE);
        assertThatThrownBy(() -> CredentialFilter.matches(empty, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
