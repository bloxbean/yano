package org.yanoproject.api.appchain.topics;

import org.yanoproject.api.appchain.AppChainConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Topic and registry-entry names (ADR app-layer/039 D3). A namespace is one or more segments
 * {@code [a-z0-9._-]{1,64}} that are not all dots, each followed by {@code /}; an exact-topic entry
 * is any valid topic that does not end in {@code /}. The policy of a topic comes from its exact entry
 * and from every namespace that is a prefix of it.
 */
public final class TopicNames {
    private static final Pattern NAMESPACE = Pattern.compile("((?!\\.+/)[a-z0-9._-]{1,64}/)+");

    private TopicNames() {
    }

    /** Today's topic rule: 1..256 UTF-8 bytes, no NUL, not reserved ({@code ~}). */
    public static boolean isValidTopic(String topic) {
        if (topic == null || topic.isEmpty() || topic.startsWith("~") || topic.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(topic)) {
            return false; // a lone surrogate has no UTF-8 encoding: it would hash as '?' on the proof paths
        }
        return topic.getBytes(StandardCharsets.UTF_8).length <= AppChainConfig.MAX_TOPIC_BYTES;
    }

    public static boolean isNamespace(String name) {
        return isValidTopic(name) && NAMESPACE.matcher(name).matches();
    }

    /** A registrable entry name: a namespace, or an exact topic that does not end in {@code /}. */
    public static boolean isValidEntryName(String name) {
        return isNamespace(name) || isValidTopic(name) && !name.endsWith("/");
    }

    /**
     * Top-level entries (created by claims, registrars or governance) are single-segment (I6): a namespace
     * with one segment, or an exact topic without {@code /}.
     */
    public static boolean isTopLevel(String name) {
        int slash = name.indexOf('/');
        return isNamespace(name) ? slash == name.length() - 1 : slash < 0;
    }

    /** Every namespace that is a proper prefix of {@code name}, shortest first. */
    public static List<String> ancestors(String name) {
        List<String> prefixes = new ArrayList<>();
        for (int slash = name.indexOf('/'); slash >= 0 && slash < name.length() - 1;
             slash = name.indexOf('/', slash + 1)) {
            String prefix = name.substring(0, slash + 1);
            if (NAMESPACE.matcher(prefix).matches()) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    /**
     * The entries that can govern {@code topic}: every ancestor namespace, then the topic itself,
     * shortest first. A topic's effective-policy proof covers exactly these names (D15).
     */
    public static List<String> applicableNames(String topic) {
        List<String> names = ancestors(topic);
        names.add(topic);
        return names;
    }

    /** The top-level ancestor of {@code name}, or {@code name} itself when it is top-level. */
    public static String topLevel(String name) {
        List<String> ancestors = ancestors(name);
        return ancestors.isEmpty() ? name : ancestors.get(0);
    }

    /** True if {@code ancestor} is {@code name} or one of its ancestor namespaces. */
    public static boolean covers(String ancestor, String name) {
        return ancestor.equals(name) || isNamespace(ancestor) && name.startsWith(ancestor);
    }
}
