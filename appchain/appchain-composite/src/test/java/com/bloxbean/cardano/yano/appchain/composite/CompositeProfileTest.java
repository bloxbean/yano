package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeProfileTest {

    @Test
    void canonicalProfileHasFrozenBytesAndDigest() {
        CompositeProfile profile = fixture();

        assertThat(HexFormat.of().formatHex(profile.canonicalBytes())).isEqualTo(
                "000000010000000b65766964656e63652d763100000005312e302e3000000002"
                        + "00000008726567697374727900000005312e302e30000000067261772d7631"
                        + "0000001172656769737472792d73746174652d763100000000000000010000"
                        + "000000000000000000020000001372656769737472"
                        + "792e636f6d6d616e642e76310000001272656769737472792e696d706f7274"
                        + "2e76310000000200000009656e7472792f6765740000000a656e7472792f68"
                        + "656164000000000000000865766964656e636500000005312e312e30000000"
                        + "0c6469726563742d61742d32300000001165766964656e63652d7374617465"
                        + "2d7631000000000000000100000000000000000000"
                        + "00010000001365766964656e63652e636f6d6d616e642e7631000000010000"
                        + "000367657400000003000000010000000772656c6561736500000005312e30"
                        + "2e300000001365766964656e63652e72656c656173652e7631000000000000"
                        + "00140000000000000000000000020000000872656769737472790000000531"
                        + "2e302e3000000000000000010000000865766964656e636500000005312e31"
                        + "2e30000000000000000100000001000000010000000c65766964656e63652f"
                        + "6765740000000865766964656e636500000003676574000000100001000000"
                        + "100000");
        assertThat(HexFormat.of().formatHex(profile.digest())).isEqualTo(
                "0952c78a015c5ad6aa179cc582fb2016c44480f5436e63b68a11ec0d9f2bd5f7");
    }

    @Test
    void canonicalizesSetsAndSortedProfileSectionsButPreservesComponentOrder() {
        CompositeProfile profile = fixture();

        assertThat(profile.components()).extracting(ComponentDescriptor::componentId)
                .containsExactly("registry", "evidence");
        assertThat(profile.components().getFirst().topics())
                .containsExactly("registry.command.v1", "registry.import.v1");
        assertThat(profile.workflows()).extracting(WorkflowDescriptor::workflowId)
                .containsExactly("release");
        assertThat(profile.queryAliases()).extracting(LegacyQueryAlias::aliasPath)
                .containsExactly("evidence/get");
    }

    @Test
    void rejectsOverlappingComponentAndWorkflowRoutes() {
        ComponentDescriptor first = descriptor("first", "shared.v1", 1, 0);
        ComponentDescriptor second = descriptor("second", "shared.v1", 10, 0);

        assertThatThrownBy(() -> CompositeProfile.of("bad", "1", List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlapping component topic ownership");

        WorkflowDescriptor workflow = new WorkflowDescriptor(
                "workflow", "1", "shared.v1", 1, 0, List.of(first.generation()), 0);
        assertThatThrownBy(() -> new CompositeProfile(1, "bad", "1", List.of(first),
                List.of(workflow), List.of(), AggregateQueryLimitsV1.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow topic overlaps component route");
    }

    @Test
    void permitsRouteTransferOnlyAcrossNonOverlappingIntervals() {
        ComponentDescriptor first = descriptor("first", "shared.v1", 1, 10);
        ComponentDescriptor second = descriptor("second", "shared.v1", 10, 0);

        assertThat(CompositeProfile.of("transfer", "1", List.of(first, second)).components())
                .containsExactly(first, second);
    }

    @Test
    void sameIdReplacementRequiresExplicitStateAndResultCompatibility() {
        ComponentDescriptor oldGeneration = new ComponentDescriptor(
                "item", "1", "config-v1", "schema-v1", 1, 10,
                List.of("item.v1"), List.of(), 1);
        ComponentDescriptor incompatible = new ComponentDescriptor(
                "item", "2", "config-v2", "schema-v2", 10, 0,
                List.of("item.v2"), List.of(), 1);

        assertThatThrownBy(() -> CompositeProfile.of(
                "replacement", "1", List.of(oldGeneration, incompatible)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state/result compatibility identity");
    }

    @Test
    void aliasesCannotShadowReservedCompositeDispatchers() {
        ComponentDescriptor component = new ComponentDescriptor(
                "item", "1", "config", "schema-v1", 1, 0,
                List.of("item.v1"), List.of("get"), 0);

        for (String reserved : List.of("composite/aggregate-v1", "components/item/get")) {
            assertThatThrownBy(() -> new CompositeProfile(1, "aliases", "1",
                    List.of(component), List.of(),
                    List.of(new LegacyQueryAlias(reserved, "item", "get")),
                    AggregateQueryLimitsV1.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("composite-owned query route");
        }
    }

    @Test
    void workflowGenerationReplacementMustNotOverlapEvenAcrossDistinctTopics() {
        ComponentDescriptor component = descriptor("component", "component.v1", 1, 0);
        WorkflowDescriptor oldWorkflow = new WorkflowDescriptor(
                "release", "1", "release.v1", 1, 10,
                List.of(component.generation()), 0);
        WorkflowDescriptor newWorkflow = new WorkflowDescriptor(
                "release", "2", "release.v2", 5, 0,
                List.of(component.generation()), 0);

        assertThatThrownBy(() -> new CompositeProfile(1, "bad", "1",
                List.of(component), List.of(oldWorkflow, newWorkflow), List.of(),
                AggregateQueryLimitsV1.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlapping generations for workflow release");
    }

    @Test
    void rejectsQuotaReservationAboveFrameworkCapAtActivationBoundary() {
        ComponentDescriptor first = new ComponentDescriptor(
                "first", "1", "cfg", "state-v1", 1, 10,
                List.of("first.v1"), List.of(), 3);
        ComponentDescriptor second = new ComponentDescriptor(
                "second", "1", "cfg", "state-v1", 10, 20,
                List.of("second.v1"), List.of(), 4);
        CompositeProfile profile = CompositeProfile.of("quota", "1", List.of(first, second));

        assertThatThrownBy(() -> profile.validateEffectBudget(6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quota 7")
                .hasMessageContaining("height 10");
        profile.validateEffectBudget(7);
    }

    @Test
    void committedCanonicalProfileCannotExceedPublicProofClientBound() {
        List<ComponentDescriptor> components = java.util.stream.IntStream.range(0, 64)
                .mapToObj(component -> new ComponentDescriptor(
                        "c" + component, "1", "cfg", "state-v1", 1, 0,
                        java.util.stream.IntStream.range(0, 64)
                                .mapToObj(route -> "topic-" + component + "-" + route
                                        + "-" + "x".repeat(100)).toList(),
                        List.of(), 0))
                .toList();
        CompositeProfile profile = CompositeProfile.of("large", "1", components);

        assertThatThrownBy(profile::canonicalBytes)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 65536");
    }

    private static CompositeProfile fixture() {
        ComponentDescriptor registry = new ComponentDescriptor(
                "registry", "1.0.0", "raw-v1", "registry-state-v1", 1, 0,
                List.of("registry.import.v1", "registry.command.v1"),
                List.of("entry/get", "entry/head"), 0);
        ComponentDescriptor evidence = new ComponentDescriptor(
                "evidence", "1.1.0", "direct-at-20", "evidence-state-v1", 1, 0,
                List.of("evidence.command.v1"), List.of("get"), 3);
        WorkflowDescriptor release = new WorkflowDescriptor(
                "release", "1.0.0", "evidence.release.v1", 20, 0,
                List.of(registry.generation(), evidence.generation()), 1);
        return new CompositeProfile(1, "evidence-v1", "1.0.0",
                List.of(registry, evidence), List.of(release),
                List.of(new LegacyQueryAlias("evidence/get", "evidence", "get")),
                new AggregateQueryLimitsV1(16, 65_536, 1_048_576));
    }

    private static ComponentDescriptor descriptor(
            String id,
            String topic,
            long fromHeight,
            long untilHeight
    ) {
        return new ComponentDescriptor(id, "1", "cfg", "state-v1", fromHeight, untilHeight,
                List.of(topic), List.of(), 0);
    }
}
