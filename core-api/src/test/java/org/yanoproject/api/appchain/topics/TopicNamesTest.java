package org.yanoproject.api.appchain.topics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR app-layer/039 D3 name rules. */
class TopicNamesTest {

    @Test
    void namespacesAndExactEntries() {
        assertThat(TopicNames.isNamespace("acme/")).isTrue();
        assertThat(TopicNames.isNamespace("acme/orders/")).isTrue();
        assertThat(TopicNames.isNamespace("a.b-c_d/")).isTrue();
        for (String notNamespace : new String[] {"Acme/", "/", "a b/", "é/", "./", "../", "a/../", "a//", "acme"}) {
            assertThat(TopicNames.isNamespace(notNamespace)).as(notNamespace).isFalse();
        }
        assertThat(TopicNames.isValidEntryName("orders.v1")).isTrue();
        assertThat(TopicNames.isValidEntryName("acme/orders/new")).isTrue();
        assertThat(TopicNames.isValidEntryName("Acme/")).as("not a namespace, ends in /").isFalse();
        assertThat(TopicNames.isValidEntryName("~topics/registry")).isFalse();
        assertThat(TopicNames.isValidEntryName("a".repeat(257))).isFalse();
        assertThat(TopicNames.isValidEntryName("a\0b")).isFalse();
    }

    @Test
    void topLevelNamesAreSingleSegment() {
        assertThat(TopicNames.isTopLevel("acme/")).isTrue();
        assertThat(TopicNames.isTopLevel("orders.v1")).isTrue();
        assertThat(TopicNames.isTopLevel("acme/orders/")).isFalse();
        assertThat(TopicNames.isTopLevel("acme/orders")).isFalse();
        assertThat(TopicNames.isTopLevel("Acme/x")).isFalse();
    }

    @Test
    void applicableNamesCoverEveryNamespacePrefix() {
        assertThat(TopicNames.applicableNames("a/b/c")).containsExactly("a/", "a/b/", "a/b/c");
        assertThat(TopicNames.applicableNames("a//b")).containsExactly("a/", "a//b");
        assertThat(TopicNames.applicableNames("Acme/x")).containsExactly("Acme/x");
        assertThat(TopicNames.applicableNames("orders.v1")).containsExactly("orders.v1");
        assertThat(TopicNames.topLevel("a/b/c")).isEqualTo("a/");
        assertThat(TopicNames.covers("a/", "a/b/c")).isTrue();
        assertThat(TopicNames.covers("a/b", "a/b/c")).as("an exact topic covers only itself").isFalse();
    }
}
