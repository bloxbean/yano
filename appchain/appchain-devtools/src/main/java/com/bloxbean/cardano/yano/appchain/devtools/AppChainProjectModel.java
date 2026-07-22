package com.bloxbean.cardano.yano.appchain.devtools;

import java.util.List;
import java.util.Map;

/** Versioned, data-only contracts used by the initializer, resolver, and renderer. */
final class AppChainProjectModel {
    static final String API_VERSION = "yano.bloxbean.com/v1alpha1";
    static final String BLUEPRINT_KIND = "AppChainProject";
    static final String LOCK_KIND = "AppChainLock";

    private AppChainProjectModel() {
    }

    record Blueprint(String apiVersion, String kind, Metadata metadata, Spec spec) {
    }

    record Metadata(String name) {
    }

    record Spec(
            String yanoVersion,
            String network,
            RuntimeSelection runtime,
            DeploymentSelection deployment,
            List<ChainIntent> chains,
            List<ComponentCatalogRef> componentCatalogs) {

        Spec(String yanoVersion, String network, RuntimeSelection runtime,
             DeploymentSelection deployment, List<ChainIntent> chains) {
            this(yanoVersion, network, runtime, deployment, chains, List.of());
        }
    }

    record ComponentCatalogRef(
            String path,
            String trustedKeyId,
            String trustedPublicKey) {
    }

    record RuntimeSelection(String type) {
    }

    record DeploymentSelection(String target) {
    }

    record ChainIntent(
            String chainId,
            String recipe,
            List<String> capabilities,
            Map<String, String> answers,
            Topology topology) {
    }

    record Topology(
            int members,
            List<String> memberKeys,
            List<String> nodeHosts,
            String finality,
            String sequencing,
            String membership,
            Integer httpPortBase,
            Integer serverPortBase) {
    }

    record CapabilityCatalog(
            String schemaVersion,
            List<Artifact> artifacts,
            List<Capability> capabilities) {
    }

    record Artifact(
            String id,
            String availability,
            String bundleId,
            String nativePosture,
            List<String> runtimeTypes,
            List<String> deploymentTargets) {
    }

    record Capability(
            String id,
            String name,
            String category,
            String availability,
            String maturity,
            String scope,
            Boolean selectable,
            String trustStatement,
            String description,
            List<String> provides,
            List<String> requires,
            List<String> implies,
            List<String> conflicts,
            List<String> runtimeTypes,
            List<String> deploymentTargets,
            List<String> artifacts,
            String nativePosture,
            List<String> externalPrerequisites,
            List<String> bootstrapRequirements,
            List<String> nonSecretAnswers,
            Map<String, String> secretReferences,
            Map<String, String> properties,
            String documentation,
            String acceptanceScenario) {

        String effectiveScope() {
            return scope == null || scope.isBlank() ? "chain" : scope;
        }

        boolean effectiveSelectable() {
            return selectable == null || selectable;
        }
    }

    record RecipeCatalog(String schemaVersion, List<Recipe> recipes) {
    }

    record Recipe(
            String id,
            String version,
            String name,
            String category,
            String availability,
            String maturity,
            String scope,
            Boolean selectable,
            String trustStatement,
            String description,
            String primaryOutcome,
            String firstCommand,
            String verificationQuery,
            List<String> capabilities,
            Map<String, String> recommended,
            List<String> runtimeTypes,
            List<String> deploymentTargets,
            List<String> artifacts,
            String nativePosture,
            List<String> externalPrerequisites,
            List<String> bootstrapRequirements,
            List<String> nonSecretAnswers,
            String documentation,
            String acceptanceScenario) {

        String effectiveScope() {
            return scope == null || scope.isBlank() ? "chain" : scope;
        }

        boolean effectiveSelectable() {
            return selectable == null || selectable;
        }
    }

    record ReleaseIndex(
            String schemaVersion,
            String schemaStatus,
            String stabilizationDecision,
            String yanoVersion,
            String tooling,
            List<String> runtimeTypes,
            List<String> deploymentTargets,
            List<String> recipes,
            List<String> artifacts,
            List<DistributionFlavor> distributions) {
    }

    record DistributionFlavor(
            String id,
            String runtimeType,
            String archivePattern,
            String tooling,
            List<String> platforms,
            List<String> artifacts) {
    }

    record Resolution(
            Blueprint blueprint,
            Recipe recipe,
            List<String> selectedCapabilities,
            List<String> impliedCapabilities,
            List<String> artifacts,
            Map<String, String> consensusProperties,
            Map<String, String> nodePropertyTemplate,
            int threshold,
            boolean bootstrapRequired,
            String maturity,
            String validationCoverage) {
    }

    record Lock(
            String apiVersion,
            String kind,
            String blueprintSchema,
            String blueprintDigest,
            Map<String, String> catalogDigests,
            String yanoVersion,
            String runtime,
            String deployment,
            String network,
            String recipe,
            List<String> selectedCapabilities,
            List<String> impliedCapabilities,
            List<String> artifacts,
            Map<String, String> consensusValues,
            String resolvedConfigDigest,
            String validationCoverage,
            String maturity,
            List<String> acknowledgements,
            Map<String, String> generatedFiles) {
    }

    record ProjectValidation(
            Lock lock,
            int generatedFileCount,
            int acknowledgementCount) {
    }

    record LockChange(String key, String policy) {
    }

    record LockDiff(String status, List<LockChange> changes, Map<String, Integer> categories) {
    }

    record DoctorCheck(String id, String status, String detail) {
    }

    record DoctorReport(String status, List<DoctorCheck> checks) {
    }

    record RuntimeIdentity(
            String schemaVersion,
            String chainId,
            String consensusProfileDigest,
            String compositeProfileDigest,
            String pluginCatalogFingerprint,
            String resolvedConfigDigest,
            String releaseCatalogDigest,
            String identityCoverage) {
    }

    record DriftCheck(String category, String peer, String status) {
    }

    record DriftReport(String status, int peerCount, List<DriftCheck> checks) {
    }

    record GitOpsLock(
            String apiVersion,
            String kind,
            String target,
            String sourceBlueprintDigest,
            String sourceResolvedConfigDigest,
            String sourceReleaseCatalogDigest,
            Map<String, String> generatedFiles) {
    }

    record GitOpsResult(
            String status,
            String target,
            int generatedFileCount,
            GitOpsLock lock) {
    }

    record MetadataTrustResult(
            String status,
            int schemaVersion,
            String algorithm,
            String keyId,
            String bundleId,
            String descriptorId,
            String descriptorSha256,
            String runtimeManifestSha256,
            String validationCoverage) {
    }
}
