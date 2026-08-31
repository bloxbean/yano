package com.bloxbean.cardano.yano.archive.ducklake;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbNativeSidecarTest {

    @Test
    void generatedSidecarExactlyMatchesDuckDbLoadersPlatformNameAndChecksum() throws Exception {
        Path sidecarDirectory = Path.of(
                System.getProperty("yano.duckdb.native-sidecar-dir"));
        String expectedLibraryName = duckDbNativeLibraryName();
        String expectedExtensionPlatform =
                System.getProperty("yano.duckdb.extension-platform");
        Path library = sidecarDirectory.resolve(expectedLibraryName);
        Path checksum = sidecarDirectory.resolve(expectedLibraryName + ".sha256");

        try (var files = Files.list(sidecarDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted().toList())
                    .containsExactly(expectedLibraryName, expectedLibraryName + ".sha256");
        }
        assertThat(Files.size(library)).isPositive();
        assertThat(Files.readString(checksum).trim()).isEqualTo(sha256(library));
        assertThat(expectedExtensionPlatform).isEqualTo(PackagedDuckDbExtensionLoader.platform());
        if (expectedLibraryName.endsWith("_osx_universal")) {
            assertThat(expectedExtensionPlatform)
                    .startsWith("osx_")
                    .doesNotContain("universal");
        }
    }

    private static String duckDbNativeLibraryName() throws Exception {
        Class<?> nativeClass = Class.forName(
                "org.duckdb.DuckDBNative", false,
                DuckDbNativeSidecarTest.class.getClassLoader());
        Method nativeLibName = nativeClass.getDeclaredMethod("nativeLibName");
        nativeLibName.setAccessible(true);
        return (String) nativeLibName.invoke(null);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
