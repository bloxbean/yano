package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Reads small UTF-8 secrets from regular, non-shared files. */
final class SecretFiles {
    private static final int MAX_SECRET_BYTES = 4_096;

    private SecretFiles() {
    }

    static SecretValue read(Path path) {
        try {
            byte[] bytes = BoundedFiles.read(path, MAX_SECRET_BYTES, true, true);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            String value = stripOneLineEnding(decoded);
            if (value.isEmpty() || value.length() > 1_024
                    || value.chars().anyMatch(character -> character < 0x21 || character == 0x7f)) {
                throw new DemoException(DemoError.INVALID_SECRET_FILE);
            }
            return new SecretValue(value);
        } catch (DemoException failure) {
            throw failure;
        } catch (BoundedFiles.InvalidFileException | NoSuchFileException failure) {
            throw new DemoException(DemoError.INVALID_SECRET_FILE);
        } catch (BoundedFiles.InsecureFileException failure) {
            throw new DemoException(DemoError.INSECURE_SECRET_FILE);
        } catch (CharacterCodingException failure) {
            throw new DemoException(DemoError.INVALID_SECRET_FILE);
        } catch (IOException failure) {
            throw new DemoException(DemoError.SECRET_FILE_UNAVAILABLE);
        }
    }

    private static String stripOneLineEnding(String value) {
        String stripped = value;
        if (stripped.endsWith("\n")) {
            stripped = stripped.substring(0, stripped.length() - 1);
            if (stripped.endsWith("\r")) {
                stripped = stripped.substring(0, stripped.length() - 1);
            }
        }
        return stripped;
    }
}
