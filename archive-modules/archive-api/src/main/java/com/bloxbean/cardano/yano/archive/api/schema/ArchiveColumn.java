package com.bloxbean.cardano.yano.archive.api.schema;

import java.util.Objects;

public record ArchiveColumn(String name, ArchiveValueType type, boolean nullable) {
    public ArchiveColumn {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }
}
