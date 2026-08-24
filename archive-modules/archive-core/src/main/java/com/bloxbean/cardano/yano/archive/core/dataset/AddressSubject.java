package com.bloxbean.cardano.yano.archive.core.dataset;

public record AddressSubject(String subjectType, byte[] subjectKey) {
    public AddressSubject {
        java.util.Objects.requireNonNull(subjectType, "subjectType");
        if (subjectKey == null || subjectKey.length == 0) throw new IllegalArgumentException("subjectKey is required");
        subjectKey = subjectKey.clone();
    }
    @Override public byte[] subjectKey() { return subjectKey.clone(); }
}
