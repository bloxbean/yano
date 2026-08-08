package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfile;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.appchain.config.AppChainConfigParser;
import com.bloxbean.cardano.yano.appchain.config.AppChainConfigSemantics;
import com.bloxbean.cardano.yano.appchain.config.AppChainApprovalsConfig;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyDefinition;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.PropertyScope;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GenesisActorV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.stdlib.AuthenticatedMapGenesisFactory;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Deterministically expands one blueprint through recipe and capability descriptors. */
final class AppChainProjectResolver {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern MEMBER_KEY = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern LOWER_HEX_56 = Pattern.compile("[0-9a-f]{56}");
    private static final Pattern LOWER_HEX_64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TESTNET_ADDRESS =
            Pattern.compile("addr_test1[a-z0-9]{5,120}");
    private static final Pattern MAINNET_ADDRESS =
            Pattern.compile("addr1[a-z0-9]{5,120}");
    private static final Pattern LABELED_VALUE =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*", Pattern.DOTALL);
    private static final Set<String> NETWORKS =
            Set.copyOf(AppChainProjectModel.DEFAULT_SUPPORTED_NETWORKS);
    private static final Set<String> RUNTIMES = Set.of("jvm", "native");
    private static final Set<String> DEPLOYMENTS = Set.of("host", "docker-compose");
    static final String EUTXO_UNSAFE_TESTNET_ACKNOWLEDGEMENT =
            "EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET";

    private final AppChainPropertyRegistry properties;
    private final AppChainProjectCatalog catalog;

    AppChainProjectResolver(
            AppChainPropertyRegistry properties,
            AppChainProjectCatalog catalog) {
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
    }

    AppChainProjectModel.Resolution resolve(AppChainProjectModel.Blueprint blueprint) {
        AppChainProjectModel.ChainIntent chain = validateBlueprint(blueprint);
        AppChainProjectModel.Spec spec = blueprint.spec();
        AppChainProjectModel.Recipe recipe = catalog.recipe(chain.recipe());
        if (!recipe.effectiveSelectable()) {
            throw new IllegalArgumentException("Recipe " + recipe.id()
                    + " is not selectable for app-chain initialization");
        }
        requireSupported(recipe.effectiveSupportedNetworks(), spec.network(),
                "network", recipe.id());
        requireSupported(recipe.runtimeTypes(), spec.runtime().type(), "runtime", recipe.id());
        requireSupported(recipe.deploymentTargets(), spec.deployment().target(),
                "deployment target", recipe.id());
        validateAcknowledgements(spec, recipe);

        LinkedHashSet<String> requested = new LinkedHashSet<>(safeList(recipe.capabilities()));
        requested.removeIf(id -> id.startsWith("sequencer:"));
        requested.removeIf(id -> id.startsWith("membership:"));
        requested.add("sequencer:" + chain.topology().sequencing());
        requested.add("membership:" + chain.topology().membership());
        for (String capabilityId : safeList(chain.capabilities())) {
            AppChainProjectModel.Capability explicit = catalog.capability(capabilityId);
            if (!explicit.effectiveSelectable()) {
                throw new IllegalArgumentException("Capability " + capabilityId
                        + " is not selectable in an app-chain blueprint");
            }
            if (catalog.isExternalCapability(capabilityId)
                    && safeList(explicit.provides()).contains("state-machine")) {
                requested.remove("state:custom-plugin");
            }
            requested.add(capabilityId);
        }

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        LinkedHashSet<String> implied = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(requested);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!selected.add(id)) continue;
            AppChainProjectModel.Capability capability = catalog.capability(id);
            if ("distribution".equals(capability.effectiveScope())) {
                throw new IllegalArgumentException("Distribution capability " + id
                        + " is derived from the selected release and cannot be chain-selected");
            }
            requireSupported(capability.runtimeTypes(), spec.runtime().type(),
                    "runtime", capability.id());
            requireSupported(capability.deploymentTargets(), spec.deployment().target(),
                    "deployment target", capability.id());
            requireSupported(capability.effectiveSupportedNetworks(), spec.network(),
                    "network", capability.id());
            for (String dependency : concat(capability.requires(), capability.implies())) {
                if (!requested.contains(dependency)) implied.add(dependency);
                queue.addLast(dependency);
            }
        }
        validateConflicts(selected);
        validateProvides(selected);
        validateAuthenticatedMapSelection(chain, selected);

        int threshold = threshold(chain.topology().finality(), chain.topology().members());
        List<String> memberKeys = normalizedMemberKeys(chain.topology());
        if (chain.authenticatedMap() != null && memberKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "authenticated-map genesis requires every topology.memberKeys value");
        }
        boolean bootstrapRequired = memberKeys.isEmpty();
        String members = bootstrapRequired
                ? "${YANO_APPCHAIN_MEMBER_KEYS}" : String.join(",", memberKeys);
        String proposer = bootstrapRequired
                ? "${YANO_APPCHAIN_PROPOSER_KEY}" : memberKeys.getFirst();

        Map<String, String> consensus = new TreeMap<>();
        String prefix = "yano.app-chain.chains[0].";
        consensus.put("yano.app-chain.enabled", "true");
        consensus.put(prefix + "chain-id", chain.chainId());
        consensus.put(prefix + "members", members);
        consensus.put(prefix + "threshold", Integer.toString(threshold));

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("proposer", proposer);
        variables.put("chainId", chain.chainId());
        variables.put("network", spec.network());
        Map<String, String> answers = validatedAnswers(chain.answers());
        validateAnswers(selected, recipe, answers);
        validateMaintainedAnswerSemantics(answers, spec.network());
        variables.putAll(answers);
        TreeSet<String> sortedCapabilities = new TreeSet<>(selected);
        TreeSet<String> artifacts = new TreeSet<>();
        String maturity = recipe.maturity();
        Map<String, String> nodeTemplate = new TreeMap<>();
        nodeTemplate.put(prefix + "signing-key", "${YANO_APPCHAIN_SIGNING_KEY}");
        nodeTemplate.put(prefix + "peers", "${YANO_APPCHAIN_PEERS}");
        for (String id : sortedCapabilities) {
            AppChainProjectModel.Capability capability = catalog.capability(id);
            artifacts.addAll(safeList(capability.artifacts()));
            maturity = leastMature(maturity, capability.maturity());
            for (Map.Entry<String, String> assignment : safeMap(capability.properties()).entrySet()) {
                String key = prefix + assignment.getKey();
                String value = expand(assignment.getValue(), variables);
                Map<String, String> target = "node".equals(capability.effectiveScope())
                        ? nodeTemplate : consensus;
                String previous = target.putIfAbsent(key, value);
                if (previous != null && !previous.equals(value)) {
                    throw new IllegalArgumentException("Capabilities assign conflicting values to "
                            + assignment.getKey());
                }
            }
            for (Map.Entry<String, String> reference
                    : safeMap(capability.secretReferences()).entrySet()) {
                String key = prefix + reference.getKey();
                String value = "${" + reference.getValue() + "}";
                String previous = nodeTemplate.putIfAbsent(key, value);
                if (previous != null && !previous.equals(value)) {
                    throw new IllegalArgumentException("Capabilities assign conflicting secret "
                            + "references to " + reference.getKey());
                }
            }
        }
        for (String artifactId : artifacts) {
            AppChainProjectModel.Artifact artifact = catalog.artifact(artifactId);
            requireSupported(artifact.runtimeTypes(), spec.runtime().type(),
                    "runtime", artifact.id());
            requireSupported(artifact.deploymentTargets(), spec.deployment().target(),
                    "deployment target", artifact.id());
        }
        String blockInterval = safeMap(recipe.recommended()).get("blockIntervalMs");
        if (blockInterval != null) {
            consensus.putIfAbsent(prefix + "block.interval-ms", blockInterval);
        }
        materializeConsensusDefaults(consensus, prefix);
        if (chain.authenticatedMap() != null) {
            materializeAuthenticatedMap(
                    chain, consensus, nodeTemplate, prefix);
        }
        materializeStateCommitmentIdentity(
                blueprint.metadata().name(), chain, sortedCapabilities, consensus, prefix);

        validateWithRuntimeParser(consensus, nodeTemplate, chain.topology().members());

        return new AppChainProjectModel.Resolution(
                blueprint,
                recipe,
                List.copyOf(sortedCapabilities),
                implied.stream().sorted().toList(),
                List.copyOf(artifacts),
                Map.copyOf(consensus),
                Map.copyOf(nodeTemplate),
                threshold,
                bootstrapRequired,
                maturity,
                "PARTIAL");
    }

    private AppChainProjectModel.ChainIntent validateBlueprint(
            AppChainProjectModel.Blueprint blueprint) {
        if (blueprint == null
                || !AppChainProjectModel.API_VERSION.equals(blueprint.apiVersion())
                || !AppChainProjectModel.BLUEPRINT_KIND.equals(blueprint.kind())) {
            throw new IllegalArgumentException("Blueprint must use AppChainProject v1alpha1");
        }
        if (blueprint.metadata() == null || !safeName(blueprint.metadata().name())) {
            throw new IllegalArgumentException("Blueprint metadata.name is invalid");
        }
        AppChainProjectModel.Spec spec = blueprint.spec();
        if (spec == null || blank(spec.yanoVersion()) || spec.runtime() == null
                || spec.deployment() == null || spec.chains() == null
                || spec.chains().size() != 1) {
            throw new IllegalArgumentException(
                    "v1alpha1 requires yanoVersion, runtime, deployment, and exactly one chain");
        }
        if (!NETWORKS.contains(spec.network())) {
            throw new IllegalArgumentException("Unsupported network: " + safe(spec.network()));
        }
        if (!RUNTIMES.contains(spec.runtime().type())) {
            throw new IllegalArgumentException("Unsupported runtime: " + safe(spec.runtime().type()));
        }
        if (!DEPLOYMENTS.contains(spec.deployment().target())) {
            throw new IllegalArgumentException(
                    "Unsupported deployment target: " + safe(spec.deployment().target()));
        }
        AppChainProjectModel.ChainIntent chain = spec.chains().getFirst();
        if (chain == null || blank(chain.chainId()) || chain.chainId().length() > 128
                || chain.chainId().chars().anyMatch(Character::isISOControl)
                || blank(chain.recipe()) || chain.topology() == null) {
            throw new IllegalArgumentException("Chain id, recipe, and topology are required");
        }
        AppChainProjectModel.Topology topology = chain.topology();
        if (topology.members() < 1 || topology.members() > 32) {
            throw new IllegalArgumentException("Topology members must be in [1, 32]");
        }
        if (!Set.of("majority", "two-thirds", "all").contains(topology.finality())) {
            throw new IllegalArgumentException("Unsupported finality policy");
        }
        if (!Set.of("fixed", "rotating").contains(topology.sequencing())) {
            throw new IllegalArgumentException("Unsupported sequencing policy");
        }
        if (!Set.of("static", "governed").contains(topology.membership())) {
            throw new IllegalArgumentException("Unsupported membership policy");
        }
        validatePortRange(topology.httpPortBase(), topology.members(), "HTTP");
        validatePortRange(topology.serverPortBase(), topology.members(), "server");
        List<String> hosts = safeList(topology.nodeHosts());
        if (!hosts.isEmpty()) {
            if (hosts.size() != topology.members()
                    || hosts.stream().anyMatch(host -> host == null
                    || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}"))
                    || new LinkedHashSet<>(hosts).size() != hosts.size()) {
                throw new IllegalArgumentException(
                        "nodeHosts must contain one unique safe hostname per member");
            }
            if (!"host".equals(spec.deployment().target())) {
                throw new IllegalArgumentException(
                        "nodeHosts are accepted only for host deployment");
            }
        }
        return chain;
    }

    private static void validateAcknowledgements(
            AppChainProjectModel.Spec spec,
            AppChainProjectModel.Recipe recipe
    ) {
        List<String> acknowledgements = safeList(spec.acknowledgements());
        if (acknowledgements.size() > 32
                || new LinkedHashSet<>(acknowledgements).size()
                != acknowledgements.size()
                || acknowledgements.stream().anyMatch(value ->
                value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}"))) {
            throw new IllegalArgumentException(
                    "Blueprint acknowledgements are invalid");
        }
        if ("eutxo-zeroj-preview".equals(recipe.id())
                && Set.of("preview", "preprod").contains(spec.network())
                && !acknowledgements.contains(
                EUTXO_UNSAFE_TESTNET_ACKNOWLEDGEMENT)) {
            throw new IllegalArgumentException(
                    recipe.id() + " on " + spec.network()
                            + " requires --acknowledge "
                            + EUTXO_UNSAFE_TESTNET_ACKNOWLEDGEMENT);
        }
    }

    private static void validatePortRange(Integer base, int members, String label) {
        if (base != null && (base < 1024 || base + members - 1 > 65535)) {
            throw new IllegalArgumentException(label + " port range is outside [1024, 65535]");
        }
    }

    private static Map<String, String> validatedAnswers(Map<String, String> answers) {
        if (answers == null || answers.isEmpty()) return Map.of();
        Map<String, String> validated = new TreeMap<>();
        for (Map.Entry<String, String> answer : answers.entrySet()) {
            String key = answer.getKey();
            String value = answer.getValue();
            String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9]{0,63}")
                    || normalized.contains("secret") || normalized.contains("password")
                    || normalized.contains("token") || normalized.contains("private")
                    || normalized.contains("mnemonic")) {
                throw new IllegalArgumentException("Recipe answer name is invalid or secret-like");
            }
            if (value == null || value.isBlank() || value.length() > 1024
                    || value.contains("${") || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Recipe answer value must be safe non-secret text");
            }
            validated.put(key, value);
        }
        return Map.copyOf(validated);
    }

    private void validateAnswers(
            Set<String> selected,
            AppChainProjectModel.Recipe recipe,
            Map<String, String> answers) {
        Set<String> required = new TreeSet<>(safeList(recipe.nonSecretAnswers()));
        for (String id : selected) {
            required.addAll(safeList(catalog.capability(id).nonSecretAnswers()));
        }
        Set<String> unexpected = new TreeSet<>(answers.keySet());
        unexpected.removeAll(required);
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("Blueprint declares answers that are not owned by "
                    + "the selected recipe/capabilities: " + unexpected);
        }
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(answers.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Selected capabilities require non-secret answers: "
                    + missing);
        }
    }

    private static void validateMaintainedAnswerSemantics(
            Map<String, String> answers,
            String network) {
        for (Map.Entry<String, String> answer : answers.entrySet()) {
            String name = answer.getKey();
            String value = answer.getValue();
            if (LABELED_VALUE.matcher(value).matches()) {
                throw invalidAnswer(name, "must contain only the value, not NAME=value");
            }
            switch (name) {
                case "eutxoGenesisAddress", "eutxoL2Address",
                        "bridgeVaultAddress", "bridgeWithdrawalAddress" ->
                        validateCardanoAddress(name, value, network);
                case "eutxoL2PublicKey" ->
                        requireMatch(name, value, LOWER_HEX_64,
                                "must be 64 lowercase hexadecimal characters");
                case "bridgeVaultScriptHash" ->
                        requireMatch(name, value, LOWER_HEX_56,
                                "must be 56 lowercase hexadecimal characters");
                case "eutxoGenesisLovelace", "bridgeMaxDepositLovelace",
                        "bridgeMaxWithdrawalLovelace", "bridgeMaxPendingWithdrawals" ->
                        requireLong(name, value, 1);
                case "bridgeEpoch" -> requireLong(name, value, 0);
                default -> {
                    // External catalogs retain their own schema and runtime validation.
                }
            }
        }
    }

    private static void validateCardanoAddress(String name, String value, String network) {
        Pattern expected = "mainnet".equals(network) ? MAINNET_ADDRESS : TESTNET_ADDRESS;
        if (!expected.matcher(value).matches()) {
            String prefix = "mainnet".equals(network) ? "addr1" : "addr_test1";
            throw invalidAnswer(name,
                    "must be a lowercase Cardano address for " + network
                            + " beginning with " + prefix);
        }
    }

    private static void requireMatch(
            String name,
            String value,
            Pattern pattern,
            String requirement) {
        if (!pattern.matcher(value).matches()) {
            throw invalidAnswer(name, requirement);
        }
    }

    private static void requireLong(String name, String value, long minimum) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum) {
                throw invalidAnswer(name, "must be an integer of at least " + minimum);
            }
        } catch (NumberFormatException failure) {
            throw invalidAnswer(name, "must be a bounded decimal integer");
        }
    }

    private static IllegalArgumentException invalidAnswer(String name, String requirement) {
        return new IllegalArgumentException("Recipe answer " + name + " " + requirement);
    }

    private void validateConflicts(Set<String> selected) {
        for (String id : selected) {
            for (String conflict : safeList(catalog.capability(id).conflicts())) {
                if (selected.contains(conflict)) {
                    throw new IllegalArgumentException(
                            "Conflicting capabilities selected: " + id + " and " + conflict);
                }
            }
        }
    }

    private void validateProvides(Set<String> selected) {
        Map<String, String> providers = new LinkedHashMap<>();
        for (String id : selected) {
            for (String provided : safeList(catalog.capability(id).provides())) {
                String prior = providers.putIfAbsent(provided, id);
                if (prior != null && !prior.equals(id)) {
                    throw new IllegalArgumentException("Capabilities " + prior + " and " + id
                            + " both provide exclusive contract " + provided);
                }
            }
        }
    }

    private static void validateAuthenticatedMapSelection(
            AppChainProjectModel.ChainIntent chain,
            Set<String> selected
    ) {
        boolean selectedMap = selected.contains("state:authenticated-map");
        if (selectedMap && chain.authenticatedMap() == null) {
            throw new IllegalArgumentException(
                    "state:authenticated-map requires an authenticatedMap blueprint section");
        }
        if (!selectedMap && chain.authenticatedMap() != null) {
            throw new IllegalArgumentException(
                    "authenticatedMap is valid only with state:authenticated-map");
        }
    }

    private static void materializeAuthenticatedMap(
            AppChainProjectModel.ChainIntent chain,
            Map<String, String> consensus,
            Map<String, String> nodeTemplate,
            String prefix
    ) {
        AppChainProjectModel.AuthenticatedMapIntent intent = chain.authenticatedMap();
        List<AppChainProjectModel.AuthenticatedMapSchemaIntent> schemaIntents =
                safeList(intent.schemas());
        List<AuthenticatedMapContract.ValidatorDescriptor> validators = new ArrayList<>();
        for (AppChainProjectModel.AuthenticatedMapSchemaIntent schema : schemaIntents) {
            if (schema == null || blank(schema.id()) || blank(schema.source())) {
                throw new IllegalArgumentException(
                        "authenticatedMap schemas require id and inline CDDL source");
            }
            String root = blank(schema.root()) ? "root" : schema.root();
            byte[] definition = AuthenticatedMapCddlCompiler
                    .compile(schema.source(), root).definition();
            validators.add(AuthenticatedMapContract.ValidatorDescriptor.schema(
                    schema.id(), definition));
        }

        List<AppChainProjectModel.AuthenticatedMapCollectionIntent> collectionIntents =
                safeList(intent.collections());
        if (collectionIntents.isEmpty()) {
            throw new IllegalArgumentException(
                    "authenticatedMap requires at least one collection");
        }
        List<AuthenticatedMapContract.CollectionDescriptor> collections = new ArrayList<>();
        for (AppChainProjectModel.AuthenticatedMapCollectionIntent collection
                : collectionIntents) {
            if (collection == null) {
                throw new IllegalArgumentException(
                        "authenticatedMap collection must not be null");
            }
            collections.add(new AuthenticatedMapContract.CollectionDescriptor(
                    collection.id(),
                    authorization(collection.authorization()),
                    blank(collection.authorizationPolicy())
                            ? "" : collection.authorizationPolicy(),
                    Boolean.TRUE.equals(collection.restoreAllowed()),
                    valueOr(collection.maxKeyBytes(),
                            AuthenticatedMapContract.MAX_APPLICATION_KEY_BYTES),
                    valueOr(collection.maxValueBytes(), 65_536),
                    valueEncoding(collection.valueEncoding()),
                    blank(collection.validator()) ? "" : collection.validator()));
        }

        byte[] anchorPolicy = canonicalHex32(
                intent.anchorPolicyCommitment(), "authenticatedMap.anchorPolicyCommitment");
        int maxBatchItems = valueOr(intent.maxBatchItems(),
                AuthenticatedMapContract.MAX_BATCH_ITEMS);
        int maxBatchBytes = valueOr(intent.maxBatchBytes(), 65_536);
        AppChainConfig config = runtimeConfig(consensus, nodeTemplate, prefix);
        GovernedGenesisV1 governedGenesis = governedGenesis(
                config.chainId(), intent, collections);
        AuthenticatedMapContract.Genesis genesis = switch (intent.profile()) {
            case AuthenticatedMapContract.PROFILE_MPF_BLAKE2B256_V1 ->
                    AuthenticatedMapGenesisFactory.mpf(
                            config, anchorPolicy, maxBatchItems, maxBatchBytes,
                            collections, validators, List.of(), governedGenesis);
            case AuthenticatedMapContract.PROFILE_JMT_BLAKE2B256_V1 ->
                    AuthenticatedMapGenesisFactory.classicJmt(
                            config, anchorPolicy, maxBatchItems, maxBatchBytes,
                            collections, validators, List.of(), governedGenesis);
            case AuthenticatedMapContract.PROFILE_JMT_POSEIDON_BLS12381_V1 ->
                    throw new IllegalArgumentException(
                            "Poseidon authenticated-map genesis remains deferred by ADR-025 Phase 4");
            case null, default -> throw new IllegalArgumentException(
                    "authenticatedMap.profile must be mpf-blake2b256-v1 or jmt-blake2b256-v1");
        };
        for (Map.Entry<String, String> setting
                : AuthenticatedMapGenesisFactory.settings(genesis).entrySet()) {
            String prior = consensus.putIfAbsent(prefix + setting.getKey(), setting.getValue());
            if (prior != null && !prior.equals(setting.getValue())) {
                throw new IllegalArgumentException(
                        "authenticated-map genesis conflicts with " + setting.getKey());
            }
        }
    }

    private static void materializeStateCommitmentIdentity(
            String projectName,
            AppChainProjectModel.ChainIntent chain,
            Set<String> capabilities,
            Map<String, String> consensus,
            String prefix
    ) {
        String profileKey = prefix + StateCommitmentIdentity.PROFILE_SETTING;
        String genesisKey = prefix + StateCommitmentIdentity.GENESIS_ID_SETTING;
        StateCommitmentProfile profile = StateCommitmentProfiles.require(
                consensus.getOrDefault(profileKey, StateCommitmentProfiles.MPF.id()));
        String genesisHex = consensus.get(genesisKey);
        byte[] genesisId = genesisHex == null
                ? HexFormat.of().parseHex(AppChainProjectCatalog.sha256(
                "yano-appchain-project-genesis-v1\0"
                        + projectName + "\0" + chain.chainId() + "\0"
                        + String.join(",", capabilities)))
                : canonicalHex32(genesisHex, StateCommitmentIdentity.GENESIS_ID_SETTING);
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(profile, genesisId);
        for (Map.Entry<String, String> setting : identity.settings().entrySet()) {
            String key = prefix + setting.getKey();
            String prior = consensus.putIfAbsent(key, setting.getValue());
            if (prior != null && !prior.equals(setting.getValue())) {
                throw new IllegalArgumentException(
                        "state commitment identity conflicts with " + setting.getKey());
            }
        }
    }

    private static AppChainConfig runtimeConfig(
            Map<String, String> consensus,
            Map<String, String> nodeTemplate,
            String prefix
    ) {
        Map<String, String> suffix = new LinkedHashMap<>();
        consensus.forEach((key, value) -> {
            if (key.startsWith(prefix)) suffix.put(key.substring(prefix.length()), value);
        });
        nodeTemplate.forEach((key, value) -> {
            if (key.startsWith(prefix)) suffix.put(key.substring(prefix.length()), value);
        });
        suffix.put("signing-key", "b".repeat(64));
        suffix.put("peers", "");
        AppChainConfig config = AppChainConfigParser.parse(suffix);
        AppChainConfigSemantics.validate(config);
        AppChainEffectsConfig.from(config).consensusProfile(config);
        return config;
    }

    private static int authorization(String value) {
        return switch (value == null ? "open" : value) {
            case "open" -> AuthenticatedMapContract.AUTH_OPEN;
            case "owner" -> AuthenticatedMapContract.AUTH_OWNER;
            case "member" -> AuthenticatedMapContract.AUTH_MEMBER;
            case "governed-role" -> AuthenticatedMapContract.AUTH_GOVERNED_ROLE;
            case "approval" -> AuthenticatedMapContract.AUTH_APPROVAL;
            default -> throw new IllegalArgumentException(
                    "authenticatedMap collection authorization must be open, owner, member, "
                            + "governed-role, or approval");
        };
    }

    private static GovernedGenesisV1 governedGenesis(
            String chainId,
            AppChainProjectModel.AuthenticatedMapIntent intent,
            List<AuthenticatedMapContract.CollectionDescriptor> collections
    ) {
        boolean governed = collections.stream().anyMatch(collection ->
                collection.authorization() == AuthenticatedMapContract.AUTH_GOVERNED_ROLE
                        || collection.authorization() == AuthenticatedMapContract.AUTH_APPROVAL);
        if (!governed) {
            if (intent.authorizationGovernance() != null
                    || intent.genesisRecords() != null
                    || intent.authorizationLimits() != null
                    || !safeList(intent.onboarding()).isEmpty()) {
                throw new IllegalArgumentException(
                        "authenticatedMap governance is valid only for governed collections");
            }
            return null;
        }
        AppChainProjectModel.AuthenticatedMapGovernanceIntent governance =
                intent.authorizationGovernance();
        AppChainProjectModel.AuthenticatedMapGenesisRecordsIntent records =
                intent.genesisRecords();
        if (governance == null || records == null) {
            throw new IllegalArgumentException(
                    "governed authenticatedMap requires authorizationGovernance and genesisRecords");
        }
        AdministratorAuthorityV1 authority = new AdministratorAuthorityV1(
                governance.authorityId(), exactOne(governance.initialRevision(),
                "authorizationGovernance.initialRevision"),
                safeList(governance.administratorActors()),
                requiredPositive(governance.threshold(),
                        "authorizationGovernance.threshold"),
                requiredPositive(governance.maximumMutationLifetimeBlocks(),
                        "authorizationGovernance.maximumMutationLifetimeBlocks"));
        List<OrganizationRecordV1> organizations = safeList(records.organizations()).stream()
                .map(record -> new OrganizationRecordV1(
                        record.id(), exactOne(record.revision(), "organization.revision"),
                        recordStatus(record.status()), optionalHex32(
                        record.metadataCommitment(), "organization.metadataCommitment")))
                .toList();
        List<GenesisActorV1> actors = safeList(records.actors()).stream()
                .map(record -> genesisActor(chainId, record)).toList();
        List<DirectRolePolicyV1> directPolicies = safeList(records.directPolicies()).stream()
                .map(policy -> new DirectRolePolicyV1(
                        policy.id(), exactOne(policy.revision(), "directPolicy.revision"),
                        recordStatus(policy.status()), policy.requiredRole(),
                        requiredPositive(policy.maximumAuthorizationLifetimeBlocks(),
                                "directPolicy.maximumAuthorizationLifetimeBlocks")))
                .toList();
        List<ApprovalPolicyV1> approvalPolicies = safeList(records.approvalPolicies()).stream()
                .map(AppChainProjectResolver::approvalPolicy).toList();
        return new GovernedGenesisV1(chainId, authority, organizations, actors,
                directPolicies, approvalPolicies, limits(intent.authorizationLimits()));
    }

    private static GenesisActorV1 genesisActor(
            String chainId,
            AppChainProjectModel.AuthenticatedMapActorIntent actor
    ) {
        long revision = exactOne(actor.revision(), "actor.revision");
        List<ActorKeyEpochV1> keys = safeList(actor.keys()).stream().map(key -> {
            if (!"ed25519".equals(key.algorithm())) {
                throw new IllegalArgumentException("actor key algorithm must be ed25519");
            }
            return new ActorKeyEpochV1(key.id(), canonicalHex32(
                    key.publicKey(), "actor.key.publicKey"),
                    valueOr(key.validFromHeight(), 1L),
                    valueOr(key.validUntilHeight(), 0L), recordStatus(key.status()));
        }).toList();
        ActorRecordV1 record = new ActorRecordV1(actor.id(), actor.organization(), revision,
                recordStatus(actor.status()), safeList(actor.roles()), keys,
                optionalHex32(actor.metadataCommitment(), "actor.metadataCommitment"));
        Map<String, AppChainProjectModel.AuthenticatedMapActorKeyIntent> keyIntents =
                new LinkedHashMap<>();
        safeList(actor.keys()).forEach(key -> {
            if (key != null && keyIntents.putIfAbsent(key.id(), key) != null) {
                throw new IllegalArgumentException("actor key ids must be unique");
            }
        });
        List<ActorKeyProofV1> proofs = new ArrayList<>();
        for (ActorKeyEpochV1 key : record.keys()) {
            AppChainProjectModel.AuthenticatedMapActorKeyIntent keyIntent =
                    keyIntents.get(key.keyId());
            if (keyIntent == null) {
                throw new IllegalArgumentException("actor key proof is absent");
            }
            proofs.add(new ActorKeyProofV1(chainId, record.actorId(), revision, key,
                    canonicalHex64(keyIntent.proofOfPossession(),
                            "actor.key.proofOfPossession")));
        }
        return new GenesisActorV1(record, proofs);
    }

    private static ApprovalPolicyV1 approvalPolicy(
            AppChainProjectModel.AuthenticatedMapApprovalPolicyIntent policy
    ) {
        List<ApprovalPolicyV1.RequiredClause> clauses = safeList(policy.clauses()).stream()
                .map(clause -> new ApprovalPolicyV1.RequiredClause(
                        clause.id(), clause.role(), requiredPositive(clause.minimumCount(),
                        "approvalPolicy.clause.minimumCount"),
                        switch (clause.distinctBy()) {
                            case "actor" -> ApprovalPolicyV1.DistinctBy.ACTOR;
                            case "organization" -> ApprovalPolicyV1.DistinctBy.ORGANIZATION;
                            case null, default -> throw new IllegalArgumentException(
                                    "approvalPolicy clause distinctBy must be actor or organization");
                        })).toList();
        ApprovalPolicyV1.RejectionMode rejection = switch (policy.rejectionMode()) {
            case "disabled" -> ApprovalPolicyV1.RejectionMode.DISABLED;
            case "any-eligible" -> ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE;
            case null, default -> throw new IllegalArgumentException(
                    "approvalPolicy rejectionMode must be disabled or any-eligible");
        };
        return new ApprovalPolicyV1(policy.id(),
                exactOne(policy.revision(), "approvalPolicy.revision"),
                recordStatus(policy.status()), safeList(policy.proposerRoles()), clauses,
                rejection, requiredPositive(policy.maximumLifetimeBlocks(),
                "approvalPolicy.maximumLifetimeBlocks"));
    }

    private static GovernedAuthorizationLimitsV1 limits(
            AppChainProjectModel.GovernedAuthorizationLimitsIntent intent
    ) {
        GovernedAuthorizationLimitsV1 defaults = GovernedAuthorizationLimitsV1.defaults();
        if (intent == null) return defaults;
        return new GovernedAuthorizationLimitsV1(
                valueOr(intent.maximumEvidenceItemsPerCommand(),
                        defaults.maximumEvidenceItemsPerCommand()),
                valueOr(intent.maximumCoveredIndexesPerEvidence(),
                        defaults.maximumCoveredIndexesPerEvidence()),
                valueOr(intent.maximumGenesisOrganizations(),
                        defaults.maximumGenesisOrganizations()),
                valueOr(intent.maximumGenesisActors(), defaults.maximumGenesisActors()),
                valueOr(intent.maximumGenesisKeys(), defaults.maximumGenesisKeys()),
                valueOr(intent.maximumGenesisPolicies(), defaults.maximumGenesisPolicies()),
                valueOr(intent.maximumGenesisRecordBytes(),
                        defaults.maximumGenesisRecordBytes()),
                valueOr(intent.maximumPendingGovernance(), defaults.maximumPendingGovernance()),
                valueOr(intent.maximumPendingApprovals(), defaults.maximumPendingApprovals()),
                valueOr(intent.maximumPendingPerActor(), defaults.maximumPendingPerActor()),
                valueOr(intent.maximumPendingPerPolicy(), defaults.maximumPendingPerPolicy()),
                valueOr(intent.maximumPendingPerAuthority(), defaults.maximumPendingPerAuthority()),
                valueOr(intent.maximumPendingPerDeadline(), defaults.maximumPendingPerDeadline()),
                valueOr(intent.maximumExpiryWorkPerBlock(), defaults.maximumExpiryWorkPerBlock()),
                valueOr(intent.maximumAuthoritySupersessionWork(),
                        defaults.maximumAuthoritySupersessionWork()),
                valueOr(intent.maximumQueryPageSize(), defaults.maximumQueryPageSize()),
                valueOr(intent.maximumCryptoWorkUnitsPerBlock(),
                        defaults.maximumCryptoWorkUnitsPerBlock()));
    }

    private static RecordStatus recordStatus(String status) {
        return switch (status == null ? "active" : status) {
            case "active" -> RecordStatus.ACTIVE;
            case "suspended" -> RecordStatus.SUSPENDED;
            case "revoked" -> RecordStatus.REVOKED;
            default -> throw new IllegalArgumentException(
                    "governed record status must be active, suspended, or revoked");
        };
    }

    private static long exactOne(Long value, String name) {
        long result = valueOr(value, 1L);
        if (result != 1) throw new IllegalArgumentException(name + " must be 1 at genesis");
        return result;
    }

    private static int requiredPositive(Integer value, String name) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long requiredPositive(Long value, String name) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static byte[] optionalHex32(String value, String name) {
        return blank(value) ? new byte[0] : canonicalHex32(value, name);
    }

    private static byte[] canonicalHex64(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{128}")) {
            throw new IllegalArgumentException(name
                    + " must contain 64 bytes of canonical lowercase hex");
        }
        return HexFormat.of().parseHex(value);
    }

    private static int valueEncoding(String value) {
        return switch (value == null ? "opaque" : value) {
            case "opaque" -> AuthenticatedMapContract.VALUE_ENCODING_OPAQUE;
            case "canonical-cbor" ->
                    AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR;
            default -> throw new IllegalArgumentException(
                    "authenticatedMap valueEncoding must be opaque or canonical-cbor");
        };
    }

    private static byte[] canonicalHex32(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name
                    + " must contain 32 bytes of canonical lowercase hex");
        }
        return HexFormat.of().parseHex(value);
    }

    private static int valueOr(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static long valueOr(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    private void materializeConsensusDefaults(Map<String, String> values, String prefix) {
        for (AppChainPropertyDefinition definition : properties.definitions()) {
            if (!AppChainPropertyRegistry.OWNER_CORE.equals(definition.owner())
                    || definition.scope() != PropertyScope.CONSENSUS_SHARED
                    || definition.defaultValue() == null) {
                continue;
            }
            String key = definition.indexed() ? prefix + definition.suffix() : definition.key();
            values.putIfAbsent(key, definition.defaultValue());
        }
    }

    private static void validateWithRuntimeParser(
            Map<String, String> consensus,
            Map<String, String> nodeTemplate,
            int memberCount) {
        String prefix = "yano.app-chain.chains[0].";
        Map<String, String> suffix = new LinkedHashMap<>();
        consensus.forEach((key, value) -> {
            if (key.startsWith(prefix)) suffix.put(key.substring(prefix.length()), value);
        });
        nodeTemplate.forEach((key, value) -> {
            if (key.startsWith(prefix)) suffix.put(key.substring(prefix.length()), value);
        });
        List<String> syntheticMembers = new ArrayList<>();
        for (int index = 0; index < memberCount; index++) {
            syntheticMembers.add(AppChainProjectCatalog.sha256("member-" + index));
        }
        suffix.put("members", String.join(",", syntheticMembers));
        suffix.put("signing-key", "b".repeat(64));
        if ("true".equals(suffix.get("anchor.enabled"))) {
            suffix.put("anchor.signing-key", "c".repeat(64));
        }
        suffix.put("peers", "");
        if ("fixed".equals(suffix.get("sequencer.mode"))) {
            suffix.put("sequencer.proposer", syntheticMembers.getFirst());
        }
        var config = AppChainConfigParser.parse(suffix);
        AppChainConfigSemantics.validate(config);
        AppChainEffectsConfig.fromSettings(suffix);
        AppChainApprovalsConfig.fromSettings(suffix);
        StateCommitmentIdentity.fromSettings(config.pluginSettings());
    }

    private static List<String> normalizedMemberKeys(AppChainProjectModel.Topology topology) {
        List<String> declared = safeList(topology.memberKeys());
        if (declared.isEmpty()) return List.of();
        if (declared.size() != topology.members()) {
            throw new IllegalArgumentException("memberKeys count must equal topology.members");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String key : declared) {
            if (key == null || !MEMBER_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("memberKeys must be 32-byte hexadecimal keys");
            }
            normalized.add(key.toLowerCase(Locale.ROOT));
        }
        if (normalized.size() != declared.size()) {
            throw new IllegalArgumentException("memberKeys must be unique");
        }
        return List.copyOf(normalized);
    }

    private static int threshold(String finality, int members) {
        return switch (finality) {
            case "majority" -> members / 2 + 1;
            case "two-thirds" -> (2 * members + 2) / 3;
            case "all" -> members;
            default -> throw new IllegalArgumentException("Unsupported finality policy");
        };
    }

    private static String expand(String value, Map<String, String> variables) {
        java.util.regex.Matcher references = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        while (references.find()) {
            if (!variables.containsKey(references.group(1))) {
                throw new IllegalStateException(
                        "Capability assignment contains an unknown variable");
            }
        }
        String expanded = value;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            expanded = expanded.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return expanded;
    }

    private static void requireSupported(
            List<String> supported,
            String selected,
            String dimension,
            String owner) {
        if (supported == null || !supported.contains(selected)) {
            throw new IllegalArgumentException(owner + " does not support "
                    + dimension + " " + safe(selected));
        }
    }

    private static String leastMature(String left, String right) {
        List<String> order = List.of("stable", "preview", "experimental");
        int leftIndex = Math.max(0, order.indexOf(left));
        int rightIndex = Math.max(0, order.indexOf(right));
        return order.get(Math.max(leftIndex, rightIndex));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(safeList(first));
        result.addAll(safeList(second));
        return result;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Map<String, String> safeMap(Map<String, String> values) {
        return values == null ? Map.of() : values;
    }

    private static boolean safeName(String value) {
        return value != null && NAME.matcher(value).matches();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        if (value == null) return "<missing>";
        return value.codePoints().limit(128)
                .collect(StringBuilder::new,
                        (builder, codePoint) -> builder.appendCodePoint(
                                Character.isISOControl(codePoint) ? '?' : codePoint),
                        StringBuilder::append)
                .toString();
    }
}
