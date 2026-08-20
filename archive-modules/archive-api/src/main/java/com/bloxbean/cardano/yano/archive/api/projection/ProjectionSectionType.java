package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;

import java.util.Objects;

/**
 * Transport grouping for one logical projection section.
 *
 * <p>A section type is permanently bound to exactly one {@link ArchiveDatasetId} and
 * derives its version from that dataset's shipped {@code projectionVersion} rather
 * than declaring one of its own. ADR-039 §3 requires that section names stay
 * transport groupings and never become permission to repartition tables between
 * datasets or to renumber a projection version; deriving the version here makes the
 * two impossible to drift apart silently.
 */
public enum ProjectionSectionType {
    TRANSACTION(1, "transaction", ArchiveDatasetId.TRANSACTION),
    UTXO_HISTORY(2, "utxo-history", ArchiveDatasetId.UTXO_HISTORY),
    ACCOUNT_EVENT(3, "account-events", ArchiveDatasetId.ACCOUNT_EVENT),
    ADDRESS_TRANSACTION(4, "address-transaction", ArchiveDatasetId.ADDRESS_TRANSACTION);

    private final int code;
    private final String wirePrefix;
    private final ArchiveDatasetId dataset;

    ProjectionSectionType(int code, String wirePrefix, ArchiveDatasetId dataset) {
        this.code = code;
        this.wirePrefix = wirePrefix;
        this.dataset = dataset;
    }

    /**
     * Stable persisted code, deliberately not {@code ordinal()}: outbox keys outlive
     * the process, so reordering this enum must not reinterpret stored records.
     */
    public int code() {
        return code;
    }

    public static ProjectionSectionType fromCode(int code) {
        for (ProjectionSectionType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("unknown projection section code: " + code);
    }

    public ArchiveDatasetId dataset() {
        return dataset;
    }

    /** Shipped projection version of the owning dataset; never independently chosen. */
    public int version() {
        return ArchiveSchemas.schema(dataset).projectionVersion();
    }

    /** Stable wire name, e.g. {@code utxo-history:v5}. */
    public String wireName() {
        return wirePrefix + ":v" + version();
    }

    public static ProjectionSectionType fromWireName(String wireName) {
        String name = Objects.requireNonNull(wireName, "wireName").trim();
        for (ProjectionSectionType type : values()) {
            if (type.wireName().equals(name)) return type;
        }
        throw new IllegalArgumentException("unknown projection section: " + wireName);
    }
}
