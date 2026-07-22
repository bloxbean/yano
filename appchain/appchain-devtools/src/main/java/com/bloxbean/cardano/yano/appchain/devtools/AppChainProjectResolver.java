package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainConfigParser;
import com.bloxbean.cardano.yano.appchain.config.AppChainConfigSemantics;
import com.bloxbean.cardano.yano.appchain.config.AppChainApprovalsConfig;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyDefinition;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.PropertyScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private static final Set<String> NETWORKS = Set.of("devnet", "preview", "preprod", "mainnet");
    private static final Set<String> RUNTIMES = Set.of("jvm", "native");
    private static final Set<String> DEPLOYMENTS = Set.of("host", "docker-compose");

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
        requireSupported(recipe.runtimeTypes(), spec.runtime().type(), "runtime", recipe.id());
        requireSupported(recipe.deploymentTargets(), spec.deployment().target(),
                "deployment target", recipe.id());

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
            for (String dependency : concat(capability.requires(), capability.implies())) {
                if (!requested.contains(dependency)) implied.add(dependency);
                queue.addLast(dependency);
            }
        }
        validateConflicts(selected);
        validateProvides(selected);

        int threshold = threshold(chain.topology().finality(), chain.topology().members());
        List<String> memberKeys = normalizedMemberKeys(chain.topology());
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
        Map<String, String> answers = validatedAnswers(chain.answers());
        validateAnswers(selected, recipe, answers);
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

    private static List<String> safeList(List<String> values) {
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
