package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedFilesTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsAnInitiallyOversizedFileBeforeReadingIt() throws Exception {
        Path input = temporary.resolve("oversized");
        Files.write(input, new byte[65]);

        assertThatThrownBy(() -> BoundedFiles.read(input, 64, true, false))
                .isInstanceOf(BoundedFiles.InvalidFileException.class);
    }

    @Test
    void rejectsAFileThatGrowsAfterInitialValidationAndReadsOnlyMaxPlusOne() throws Exception {
        Path input = temporary.resolve("growing");
        Files.writeString(input, "safe");

        assertThatThrownBy(() -> BoundedFiles.read(input, 8, true, false,
                () -> append(input, "-hostile-growth")))
                .isInstanceOf(BoundedFiles.InvalidFileException.class);
    }

    @Test
    void rejectsAPathReplacedAfterInitialValidationEvenAtTheSameSize() throws Exception {
        Path input = temporary.resolve("replaceable");
        Path replacement = temporary.resolve("replacement");
        Files.writeString(input, "first");
        Files.writeString(replacement, "other");

        assertThatThrownBy(() -> BoundedFiles.read(input, 8, true, false,
                () -> replace(replacement, input)))
                .isInstanceOf(BoundedFiles.InvalidFileException.class);
    }

    @Test
    void strictUtf8ReadRejectsMalformedInput() throws Exception {
        Path input = temporary.resolve("malformed-utf8");
        Files.write(input, new byte[]{(byte) 0xc3, (byte) 0x28});

        assertThatThrownBy(() -> BoundedFiles.readUtf8(input, 64, true, false))
                .isInstanceOf(java.nio.charset.CharacterCodingException.class);
    }

    private static void append(Path path, String content) {
        try {
            Files.writeString(path, content, StandardOpenOption.APPEND);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void replace(Path source, Path destination) {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
