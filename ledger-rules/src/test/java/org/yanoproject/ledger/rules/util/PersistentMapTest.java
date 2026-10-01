package org.yanoproject.ledger.rules.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentMapTest {

    /** A key whose hash is chosen by the test, to force collisions and deep branches. */
    private record Key(int id, int hash) {
        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.id == id;
        }
    }

    @Test
    void randomOperationsMatchAHashMapAndOldVersionsStayValid() {
        Random random = new Random(42);
        PersistentMap<Key, Integer> map = PersistentMap.empty();
        Map<Key, Integer> expected = new HashMap<>();
        List<PersistentMap<Key, Integer>> versions = new ArrayList<>();
        List<Map<Key, Integer>> expectedVersions = new ArrayList<>();
        for (int step = 0; step < 20_000; step++) {
            int id = random.nextInt(3_000);
            // Few distinct hashes: many full collisions, and hashes that share long prefixes.
            Key key = new Key(id, random.nextBoolean() ? id % 97 : id * 0x9E3779B1);
            if (random.nextInt(3) == 0) {
                map = map.minus(key);
                expected.remove(key);
            } else {
                int value = random.nextInt();
                map = map.plus(key, value);
                expected.put(key, value);
            }
            if (step % 2_000 == 0) {
                versions.add(map);
                expectedVersions.add(new HashMap<>(expected));
            }
        }
        assertEqual(map, expected);
        for (int i = 0; i < versions.size(); i++) {
            assertEqual(versions.get(i), expectedVersions.get(i));
        }
    }

    @Test
    void removingEverythingYieldsTheEmptyMap() {
        PersistentMap<String, String> map = PersistentMap.empty();
        for (int i = 0; i < 1_000; i++) {
            map = map.plus("k" + i, "v" + i);
        }
        for (int i = 0; i < 1_000; i++) {
            map = map.minus("k" + i);
        }
        assertThat(map.isEmpty()).isTrue();
        assertThat(map).isSameAs(PersistentMap.empty());
    }

    @Test
    void unchangedOperationsReturnTheSameInstance() {
        PersistentMap<String, String> map = PersistentMap.<String, String>empty().plus("a", "1");
        String value = map.get("a");
        assertThat(map.plus("a", value)).isSameAs(map);
        assertThat(map.minus("b")).isSameAs(map);
    }

    private static void assertEqual(PersistentMap<Key, Integer> map, Map<Key, Integer> expected) {
        assertThat(map.size()).isEqualTo(expected.size());
        Map<Key, Integer> iterated = new HashMap<>();
        map.forEach(iterated::put);
        assertThat(iterated).isEqualTo(expected);
        for (Map.Entry<Key, Integer> e : expected.entrySet()) {
            assertThat(map.get(e.getKey())).isEqualTo(e.getValue());
        }
        assertThat(map.keys()).hasSize(expected.size());
    }
}
