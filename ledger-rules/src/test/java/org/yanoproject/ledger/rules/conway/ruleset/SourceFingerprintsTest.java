package org.yanoproject.ledger.rules.conway.ruleset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The source spans {@link SourceFingerprints} hashes. */
class SourceFingerprintsTest {

    private static final String SOURCE = """
            package p;

            /** Checks. */
            public final class Checks {

                private static final int LIMIT = 3; // a helper

                private Checks() {
                }

                /** One. */
                public static final class One extends Base {
                    @Override
                    String detail(String s) {
                        return s.equals("}") ? "{" : null; /* braces in literals */
                    }

                    record Inner(int x) {
                    }
                }

                public static final class Two {
                    char c = '}';
                }

                static String helper() {
                    return String.class.getName();
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void aNestedDeclarationIsItsKeywordToItsClosingBrace() throws IOException {
        Path file = write(SOURCE);
        List<String> tokens = SourceFingerprints.tokens(file);
        int[] one = SourceFingerprints.declaration(tokens, List.of("Checks", "One"), file);
        assertThat(String.join(" ", tokens.subList(one[0], one[1] + 1))).startsWith("class One extends Base {")
                .contains("\"}\"", "record Inner").endsWith("} }").doesNotContain("Two");
        int[] two = SourceFingerprints.declaration(tokens, List.of("Checks", "Two"), file);
        assertThat(String.join(" ", tokens.subList(two[0], two[1] + 1))).isEqualTo("class Two { char c = '}' ; }");
        assertThat(String.join(" ", SourceFingerprints.helpers(tokens, file)))
                .isEqualTo("private static final int LIMIT = 3 ; private Checks ( ) { } static String helper ( ) "
                        + "{ return String . class . getName ( ) ; }");
    }

    @Test
    void commentsAndWhitespaceDoNotCountCodeDoes() throws IOException {
        Path file = write(SOURCE);
        List<String> base = SourceFingerprints.tokens(file);
        assertThat(SourceFingerprints.tokens(write(SOURCE.replace("/** One. */", "/** The first. */")
                .replace("        return s", "        return   s")))).isEqualTo(base);
        assertThat(SourceFingerprints.tokens(write(SOURCE.replace("\"{\"", "\"[\"")))).isNotEqualTo(base);
    }

    private Path write(String source) throws IOException {
        Path file = dir.resolve("Checks.java");
        Files.writeString(file, source);
        return file;
    }
}
