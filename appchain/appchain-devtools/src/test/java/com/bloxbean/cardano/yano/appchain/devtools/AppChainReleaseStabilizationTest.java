package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainReleaseStabilizationTest {
    @Test
    void acceptanceIndexCoversTheCatalogAndEveryEvidenceReferenceResolves()
            throws Exception {
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(
                AppChainPropertyRegistry.framework());
        AppChainProjectModel.ReleaseAcceptanceIndex index =
                catalog.releaseAcceptanceIndex();

        assertThat(index.schemaVersion()).isEqualTo("v1alpha1");
        assertThat(index.schemaStatus()).isEqualTo("alpha");
        assertThat(index.stabilizationDecision()).isEqualTo("RETAIN_V1ALPHA1");
        assertThat(index.externalThirdPartyUsage()).isFalse();
        assertThat(index.cleanRoomExercises()).isNotEmpty();
        assertThat(index.knownLimitations()).isNotEmpty();
        assertThat(index.capabilityEvidence().keySet())
                .containsExactlyInAnyOrderElementsOf(catalog.capabilities().stream()
                        .map(AppChainProjectModel.Capability::id)
                        .toList());
        assertThat(index.recipes()).extracting(AppChainProjectModel.RecipeAcceptance::id)
                .containsExactlyInAnyOrderElementsOf(catalog.recipes().stream()
                        .map(AppChainProjectModel.Recipe::id)
                        .toList());

        assertThat(index.recipes().stream()
                .filter(recipe -> "stable".equals(recipe.maturity())))
                .allSatisfy(recipe -> assertThat(recipe.outcomeLevel())
                        .isEqualTo("PACKAGED_RUNTIME"));
        assertThat(index.recipes().stream()
                .filter(recipe -> "preview".equals(recipe.maturity())))
                .allSatisfy(recipe -> assertThat(recipe.outcomeLevel())
                        .isEqualTo("MODULE_OUTCOME_AND_PACKAGED_PROVIDER"));
        assertThat(index.recipes().stream()
                .filter(recipe -> "experimental".equals(recipe.maturity())))
                .allSatisfy(recipe -> assertThat(recipe.outcomeLevel())
                        .isEqualTo("OPERATOR_OWNED_REFERENCE"));

        Set<String> references = new LinkedHashSet<>();
        index.capabilityEvidence().values().forEach(references::addAll);
        index.recipes().stream().map(AppChainProjectModel.RecipeAcceptance::evidence)
                .forEach(references::addAll);
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        for (String reference : references) {
            List<String> parts = List.of(reference.split("#", -1));
            assertThat(parts).as("evidence syntax: %s", reference).hasSize(2);
            Path evidenceFile = repository.resolve(parts.get(0)).normalize();
            assertThat(evidenceFile).as("evidence file: %s", reference)
                    .startsWith(repository.normalize())
                    .isRegularFile();
            assertThat(Files.readString(evidenceFile))
                    .as("evidence fragment: %s", reference)
                    .contains(parts.get(1));
        }
    }
}
