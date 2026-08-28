package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContract;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.stream.Collectors;

/** Strict ADR-044 parser for {@code yano.history.projection.epoch-artifacts}. */
final class EpochArtifactSelectionParser {
    private EpochArtifactSelectionParser() { }

    static ProjectionArtifactIdentity parse(String configured, ProjectionArtifactIdentity shipped) {
        Objects.requireNonNull(shipped, "shipped");
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("epoch-artifacts was set but blank; use 'none'"
                    + " for no epoch artifacts");
        }
        var tokens = Arrays.stream(value.split(",", -1)).map(String::trim).toList();
        if (tokens.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("blank epoch-artifact selector in '" + configured + "'");
        }
        boolean all = tokens.contains("all");
        boolean none = tokens.contains("none");
        if ((all || none) && tokens.size() != 1) {
            throw new IllegalArgumentException("'all' and 'none' cannot be combined with"
                    + " epoch-artifact selectors");
        }
        if (all) return shipped;
        if (none) return ProjectionArtifactIdentity.NONE;

        var bySelector = shipped.contracts().values().stream().collect(
                Collectors.toMap(ProjectionArtifactContract::selector, contract -> contract));
        var selected = new LinkedHashSet<ProjectionArtifactContract>();
        for (String token : tokens) {
            var contract = bySelector.get(token);
            if (contract == null) {
                throw new IllegalArgumentException("unknown epoch-artifact selector '" + token
                        + "'; allowed values are all, none, "
                        + bySelector.keySet().stream().sorted().toList());
            }
            selected.add(contract);
        }
        return ProjectionArtifactIdentity.of(selected);
    }
}
