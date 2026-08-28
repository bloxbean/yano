package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Objects;

/**
 * What an archive promises about one epoch artifact.
 *
 * <p>Recorded per artifact and compared at startup, because the block-section fingerprint cannot
 * carry this. Sections are part of the identity string; artifacts are referenced from an
 * envelope's artifact list, so without a separate contract a node capturing epoch stake and a
 * node capturing nothing would produce archives with the <em>same</em> fingerprint - and the
 * second would report itself complete for artifacts it never captured and, for rewards and
 * governance status, could never reconstruct.
 *
 * <p>It records capture semantics, not just names. {@code epoch-stake:v1} is not enough: a staged
 * file and an immutable generation reference have different recovery classes, so changing between
 * them changes what the archive can promise after a crash.
 *
 * @param dataset            which epoch dataset
 * @param schemaVersion      the dataset's shipped schema version, as for block sections
 * @param codecVersion       the encoding used for the captured artifact
 * @param representation     how the durable source is materialised
 * @param reconstructibility whether a later archive could produce this without having captured it
 */
public record ProjectionArtifactContract(ArchiveDatasetId dataset,
                                         int schemaVersion,
                                         int codecVersion,
                                         ProjectionArtifactRepresentation representation,
                                         ProjectionArtifactReconstructibility reconstructibility) {

    public ProjectionArtifactContract {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(reconstructibility, "reconstructibility");
        if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH) {
            throw new IllegalArgumentException("artifact contracts describe epoch datasets; "
                    + dataset + " is a block dataset and belongs in the section set");
        }
        if (schemaVersion < 1 || codecVersion < 1) {
            throw new IllegalArgumentException("schema and codec versions must be positive");
        }
    }

    /**
     * The class the whole dataset is treated as, which for a mixed dataset is its strictest part.
     *
     * <p>DREP distribution is the case that forced this to be explicit. Its {@code amount} is a
     * distribution over the same stake inputs epoch stake uses, so that column is reconstructible;
     * its {@code storedExpiry}, {@code dormantEpochs}, {@code effectiveExpiry} and {@code active}
     * columns are boundary state that later state overwrites, so those are not. One contract per
     * dataset cannot describe both.
     *
     * <p>The alternative - composite components with independently versioned representations -
     * was rejected as the default. It doubles the identity surface, makes partial capture
     * expressible (an archive holding the amounts but not the state columns, which is a row that
     * cannot be emitted), and buys nothing unless capturing the reconstructible half separately
     * turns out to be materially cheaper. The whole dataset is therefore captured together at the
     * strictest class of any column it contains, and the cost is measured rather than assumed.
     * If measurement shows it unacceptable, splitting is a deliberate later change.
     */
    public ProjectionArtifactReconstructibility effectiveClass() {
        return reconstructibility;
    }

    /** Stable comparable form, e.g. {@code epoch-stake:s1:c1:IMMUTABLE_GENERATION:RECONSTRUCTIBLE}. */
    public String wireForm() {
        return wireName() + ":s" + schemaVersion + ":c" + codecVersion
                + ':' + representation.name() + ':' + reconstructibility.name();
    }

    /** Rebuild a contract from {@link #wireForm()}. */
    public static ProjectionArtifactContract parse(String wire) {
        String[] parts = Objects.requireNonNull(wire, "wire").split(":");
        if (parts.length != 5) {
            throw new IllegalArgumentException("malformed artifact contract: " + wire);
        }
        var dataset = ArchiveDatasetId.valueOf(parts[0].toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        return new ProjectionArtifactContract(dataset,
                Integer.parseInt(parts[1].substring(1)), Integer.parseInt(parts[2].substring(1)),
                ProjectionArtifactRepresentation.valueOf(parts[3]),
                ProjectionArtifactReconstructibility.valueOf(parts[4]));
    }

    /** Lower-kebab dataset name, matching the section vocabulary. */
    public String wireName() {
        return dataset.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** Operator-facing versioned selector, e.g. {@code reward:v1}. */
    public String selector() {
        return wireName() + ":v" + schemaVersion;
    }

    /**
     * Whether an archive can backfill this artifact retroactively without being rebuilt.
     *
     * <p>This is not ADR-044 prospective enrollment: every class may join at its next eligible
     * boundary with the earlier prefix marked {@code NOT_PROJECTED}. Only
     * {@link ProjectionArtifactReconstructibility#RECONSTRUCTIBLE} can potentially fill already
     * passed epochs, and even then only when retained-source coverage is proven.
     */
    public boolean addableToAnExistingArchive() {
        return reconstructibility == ProjectionArtifactReconstructibility.RECONSTRUCTIBLE;
    }
}
