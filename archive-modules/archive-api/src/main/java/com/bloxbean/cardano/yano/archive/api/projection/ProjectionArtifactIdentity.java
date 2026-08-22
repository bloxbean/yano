package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The set of epoch artifacts an archive holds, and on what terms.
 *
 * <p>Deliberately separate from {@link ProjectionIdentity} rather than folded into its
 * fingerprint, for two reasons that pull in opposite directions:
 *
 * <ul>
 *   <li>a <strong>section</strong> set can never grow - a section added later would be missing
 *       from every earlier block - so its fingerprint is compared for exact equality;</li>
 *   <li>an <strong>artifact</strong> set legitimately can grow, but only for artifacts that are
 *       reconstructible from retained data. Folding both into one string would forbid the safe
 *       case along with the unsafe one.</li>
 * </ul>
 *
 * <p>The failure modes differ too, which is why this must fail closed rather than warn. A missing
 * section is discovered the first time a query returns nothing. A missing irreproducible artifact
 * is discovered when someone asks for a particular epoch's rewards years later - by which time the
 * inputs are long gone and the only remedy is a genesis rebuild.
 */
public record ProjectionArtifactIdentity(Map<ArchiveDatasetId, ProjectionArtifactContract> contracts) {

    public ProjectionArtifactIdentity {
        Objects.requireNonNull(contracts, "contracts");
        // A key that disagrees with its contract would make lookups and the fingerprint describe
        // different things, and the mismatch would only surface as a confusing refusal later.
        contracts.forEach((dataset, contract) -> {
            if (contract.dataset() != dataset) {
                throw new IllegalArgumentException("artifact contract keyed as " + dataset
                        + " but describes " + contract.dataset());
            }
        });
        contracts = Map.copyOf(contracts);
    }

    /** An archive holding no epoch artifacts - complete for blocks, incomplete for every epoch. */
    public static final ProjectionArtifactIdentity NONE = new ProjectionArtifactIdentity(Map.of());

    public static ProjectionArtifactIdentity of(Collection<ProjectionArtifactContract> contracts) {
        return new ProjectionArtifactIdentity(contracts.stream()
                .collect(Collectors.toMap(ProjectionArtifactContract::dataset, c -> c)));
    }

    public boolean isEmpty() {
        return contracts.isEmpty();
    }

    public Optional<ProjectionArtifactContract> contractFor(ArchiveDatasetId dataset) {
        return Optional.ofNullable(contracts.get(dataset));
    }

    /**
     * Stable comparable form, sorted by <strong>wire name</strong>.
     *
     * <p>Not by enum order: {@code ArchiveDatasetId}'s declaration order is an implementation
     * detail that a future reordering would silently change, and this string is compared against
     * one persisted in an archive. A fingerprint that shifts because someone moved an enum
     * constant would refuse a perfectly good archive - or, worse, a reordering that happened to
     * produce the old string would accept an incompatible one.
     */
    public String fingerprint() {
        if (contracts.isEmpty()) return "artifacts:none";
        return "artifacts:" + contracts.values().stream()
                .sorted(java.util.Comparator.comparing(ProjectionArtifactContract::wireName))
                .map(ProjectionArtifactContract::wireForm)
                .collect(Collectors.joining(","));
    }

    /**
     * Whether a node configured for {@code this} may open an archive that recorded {@code stored},
     * and why not when it may not.
     *
     * @param coverage    whether this node still retains the sources to backfill a missing
     *                    artifact across {@code fromEpoch..throughEpoch}. Reconstructibility alone
     *                    is not enough: epoch stake is reconstructible <em>in kind</em>, but its
     *                    delegation generations are pruned unless a lease protected them, so a
     *                    normal node cannot actually rebuild them
     * @param fromEpoch   first epoch the archive covers
     * @param throughEpoch last epoch the archive covers
     *
     * <p>Four rules, each of which exists because the alternative is silent:
     *
     * <ol>
     *   <li>identical contracts open, obviously;</li>
     *   <li>a node asking for <strong>more</strong> may proceed only where every extra artifact is
     *       reconstructible - it can backfill those, but not conjure an irreproducible one;</li>
     *   <li>a node asking for <strong>less</strong> is refused: it would silently stop maintaining
     *       artifacts the archive claims to hold, and the archive would keep reporting them;</li>
     *   <li>a <strong>changed</strong> representation or codec for the same artifact is refused -
     *       that is a rebuild, exactly as a section version bump is, because the recovery class
     *       and the on-disk encoding both change.</li>
     * </ol>
     *
     * @return empty when the archive may be opened, or the reason it may not
     */
    /** Stable serialised form: contracts sorted by wire name, joined by {@code |}. */
    public String wireForm() {
        return contracts.values().stream()
                .map(ProjectionArtifactContract::wireForm)
                .sorted()
                .collect(java.util.stream.Collectors.joining("|"));
    }

    /** Inverse of {@link #wireForm()}; an empty string means an archive holding no artifacts. */
    public static ProjectionArtifactIdentity parse(String wire) {
        if (wire == null || wire.isBlank()) return NONE;
        return of(java.util.Arrays.stream(wire.split("\\|"))
                .map(ProjectionArtifactContract::parse)
                .toList());
    }

    public Optional<String> refuseToOpen(ProjectionArtifactIdentity stored,
                                         ProjectionArtifactCoverage coverage,
                                         int fromEpoch, int throughEpoch) {
        List<String> problems = new java.util.ArrayList<>();

        for (var entry : stored.contracts().entrySet()) {
            var mine = contracts.get(entry.getKey());
            if (mine == null) {
                problems.add("the archive holds " + entry.getValue().wireName()
                        + " but this node is not configured to maintain it; it would stop being"
                        + " updated while the archive still reports it");
            } else if (!mine.equals(entry.getValue())) {
                problems.add(entry.getValue().wireName() + " changed from "
                        + entry.getValue().wireForm() + " to " + mine.wireForm()
                        + "; representation and codec changes require a rebuild");
            }
        }

        for (var entry : contracts.entrySet()) {
            if (stored.contracts().containsKey(entry.getKey())) continue;
            var contract = entry.getValue();
            if (!contract.addableToAnExistingArchive()) {
                problems.add(contract.wireName() + " is " + contract.reconstructibility()
                        + " and the archive does not hold it; it cannot be produced for epochs that"
                        + " have already passed, so this archive can never be complete for it");
            } else if (throughEpoch >= fromEpoch
                    && !coverage.covers(contract.dataset(), fromEpoch, throughEpoch)) {
                // Reconstructible in kind, but not from what this node still has.
                problems.add(contract.wireName() + " is reconstructible, but the sources for epochs "
                        + fromEpoch + ".." + throughEpoch + " are no longer retained on this node -"
                        + " generations are pruned unless a lease protected them since genesis."
                        + " Enabling it on this archive requires a rebuild.");
            }
        }

        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
    }

    /**
     * Artifacts this node would have to backfill to satisfy its configuration - always
     * reconstructible ones with retained coverage, because {@code refuseToOpen} rejects the rest.
     */
    public List<ProjectionArtifactContract> backfillRequired(ProjectionArtifactIdentity stored) {
        return contracts.values().stream()
                .filter(c -> !stored.contracts().containsKey(c.dataset()))
                .sorted(java.util.Comparator.comparing(ProjectionArtifactContract::wireName))
                .toList();
    }
}
