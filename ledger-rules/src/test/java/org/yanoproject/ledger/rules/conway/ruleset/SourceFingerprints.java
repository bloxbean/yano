package org.yanoproject.ledger.rules.conway.ruleset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content fingerprints of rule-set implementations for the frozen manifests (ADR-056 Phase 5c): a digest of the
 * implementation's <em>source</em>, so that an edit to a unit a released protocol version uses shows in the manifest
 * of every version that contains it (toolchain-independent, unlike a bytecode digest).
 *
 * <p>What is hashed, as a normalized token stream (comments and whitespace do not count; everything else, string
 * literals and modifiers included, does):</p>
 * <ul>
 *   <li>the implementation class's declaration, from its {@code class}/{@code record} keyword to its closing brace
 *       (a nested class is found by its path of enclosing type names, with brace matching over the tokens);</li>
 *   <li>the same for each superclass in {@code org.yanoproject} ({@code PredicateCheck}, {@code StateStep}, a family's
 *       abstract base such as {@code UtxoChecks.CollateralCheck});</li>
 *   <li>the members of each of those classes' source file that are not type declarations — the file's shared helpers
 *       (e.g. {@code UtxowChecks.set}).</li>
 * </ul>
 *
 * <p>Not hashed: the subjects, the {@code tx} decoders, and helpers in other files (e.g. {@code MinFee}); changes there
 * are pinned only by the per-protocol-version gates, where a mutant or scenario exercises them.</p>
 */
final class SourceFingerprints {

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");

    private final Path sourceRoot;
    private final Map<Class<?>, String> cache = new ConcurrentHashMap<>();

    SourceFingerprints(Path sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    /** @return the first 16 hex digits of the sha256 of the class's normalized source (see the class comment) */
    String of(Class<?> type) {
        return cache.computeIfAbsent(type, this::compute);
    }

    private String compute(Class<?> type) {
        List<String> parts = new ArrayList<>();
        Set<Path> files = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c.getName().startsWith("org.yanoproject."); c = c.getSuperclass()) {
            Path file = sourceFile(c);
            List<String> tokens = tokens(file);
            List<String> path = Arrays.asList(c.getName().substring(c.getPackageName().length() + 1).split("\\$"));
            int[] span = declaration(tokens, path, file);
            parts.add(c.getName() + ": " + String.join(" ", tokens.subList(span[0], span[1] + 1)));
            files.add(file);
        }
        for (Path file : files) {
            parts.add(sourceRoot.relativize(file) + " helpers: " + String.join(" ", helpers(tokens(file), file)));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path sourceFile(Class<?> type) {
        Class<?> top = type;
        while (top.getEnclosingClass() != null) {
            top = top.getEnclosingClass();
        }
        Path file = sourceRoot.resolve(top.getName().replace('.', '/') + ".java");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no source for " + type.getName() + " at " + file);
        }
        return file;
    }

    /** @return {start, end}: the token indexes of the declaration's keyword and of its closing brace */
    static int[] declaration(List<String> tokens, List<String> path, Path file) {
        int from = 0;
        int to = tokens.size() - 1;
        int[] span = null;
        for (String name : path) {
            span = find(tokens, from, to, name);
            if (span == null) {
                throw new IllegalStateException("no declaration of " + name + " (" + path + ") in " + file);
            }
            from = bodyStart(tokens, span[0]) + 1;
            to = span[1] - 1;
        }
        return span;
    }

    /** Finds {@code <keyword> name} at brace depth 0 of {@code tokens[from..to]}. */
    private static int[] find(List<String> tokens, int from, int to, String name) {
        int depth = 0;
        for (int i = from; i <= to; i++) {
            String t = tokens.get(i);
            if (t.equals("{")) {
                depth++;
            } else if (t.equals("}")) {
                depth--;
            } else if (depth == 0 && isDeclaration(tokens, i) && i + 1 <= to && tokens.get(i + 1).equals(name)) {
                return new int[]{i, closing(tokens, bodyStart(tokens, i))};
            }
        }
        return null;
    }

    private static boolean isDeclaration(List<String> tokens, int i) {
        return TYPE_KEYWORDS.contains(tokens.get(i)) && (i == 0 || !tokens.get(i - 1).equals("."))
                && i + 1 < tokens.size() && Character.isJavaIdentifierStart(tokens.get(i + 1).charAt(0));
    }

    private static int bodyStart(List<String> tokens, int keyword) {
        for (int i = keyword; i < tokens.size(); i++) {
            if (tokens.get(i).equals("{")) {
                return i;
            }
        }
        throw new IllegalStateException("no body after token " + keyword);
    }

    private static int closing(List<String> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).equals("{")) {
                depth++;
            } else if (tokens.get(i).equals("}") && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("unbalanced braces from token " + open);
    }

    /** @return the top-level type's body without its nested type declarations (each with its modifiers) */
    static List<String> helpers(List<String> tokens, Path file) {
        String top = file.getFileName().toString().replace(".java", "");
        int[] span = declaration(tokens, List.of(top), file);
        int from = bodyStart(tokens, span[0]) + 1;
        List<String> out = new ArrayList<>();
        int memberStart = out.size();
        int depth = 0;
        for (int i = from; i < span[1]; i++) {
            String t = tokens.get(i);
            if (depth == 0 && isDeclaration(tokens, i)) {
                // drop the nested declaration and the modifiers/annotations before it in this member
                while (out.size() > memberStart) {
                    out.removeLast();
                }
                i = closing(tokens, bodyStart(tokens, i));
                memberStart = out.size();
                continue;
            }
            out.add(t);
            if (t.equals("{")) {
                depth++;
            } else if (t.equals("}")) {
                depth--;
                if (depth == 0) {
                    memberStart = out.size();
                }
            } else if (t.equals(";") && depth == 0) {
                memberStart = out.size();
            }
        }
        return out;
    }

    /** Java tokens without comments and whitespace: literals whole, identifiers whole, every other char alone. */
    static List<String> tokens(Path file) {
        String s;
        try {
            s = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("//", i)) {
                int end = s.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                tokens.add(s.substring(i, stop));
                i = stop;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                tokens.add(s.substring(i, Math.min(j + 1, n)));
                i = j + 1;
            } else if (Character.isJavaIdentifierPart(c)) {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) {
                    j++;
                }
                tokens.add(s.substring(i, j));
                i = j;
            } else {
                tokens.add(String.valueOf(c));
                i++;
            }
        }
        return tokens;
    }
}
