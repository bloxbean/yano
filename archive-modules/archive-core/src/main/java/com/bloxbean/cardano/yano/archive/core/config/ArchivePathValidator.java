package com.bloxbean.cardano.yano.archive.core.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Rejects equal or nested mutable database/temp paths before any driver opens. */
public final class ArchivePathValidator {
    private ArchivePathValidator() {
    }

    public static Map<String, Path> requireDisjoint(Map<String, Path> paths) {
        LinkedHashMap<String, Path> normalized = new LinkedHashMap<>();
        paths.forEach((name, path) -> normalized.put(
                Objects.requireNonNull(name, "path name"),
                Objects.requireNonNull(path, name).toAbsolutePath().normalize()));
        var entries = normalized.entrySet().toArray(Map.Entry[]::new);
        for (int i = 0; i < entries.length; i++) {
            for (int j = i + 1; j < entries.length; j++) {
                Path left = (Path) entries[i].getValue();
                Path right = (Path) entries[j].getValue();
                if (left.startsWith(right) || right.startsWith(left)) {
                    throw new IllegalArgumentException(entries[i].getKey() + " and "
                            + entries[j].getKey() + " paths overlap");
                }
            }
        }
        return Map.copyOf(normalized);
    }
}
