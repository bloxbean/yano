package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.TemplateContract;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainRepositoryParityTest {
    private static final Pattern QUOTED_SUFFIX = Pattern.compile("\"([a-z0-9.-]+)\"");
    private static final Pattern LAUNCHER_INJECTED = Pattern.compile(
            "yano\\.app-chain\\.chains\\[(?:%s|\\$idx)]\\.([a-z0-9.-]+)");

    @Test
    void registryCoversFixedAndDynamicMultiChainRuntimeParsing() throws Exception {
        String producer = source("app/src/main/java/com/bloxbean/cardano/yano/app/YanoProducer.java");
        String fixedBlock = between(producer, "String[] suffixes = {", "};");
        String dynamicBlock = between(producer,
                "APP_CHAIN_DYNAMIC_PREFIXES = java.util.List.of(", ");");
        Set<String> fixed = quotedValues(fixedBlock);
        Set<String> dynamic = quotedValues(dynamicBlock);

        AppChainPropertyRegistry registry = AppChainPropertyRegistry.framework();
        Set<String> registryDynamic = registry.dynamicNamespaces().stream()
                .map(namespace -> namespace.prefix()).collect(Collectors.toCollection(TreeSet::new));
        assertThat(dynamic).isEqualTo(registryDynamic);

        Set<String> indexed = registry.definitions().stream()
                .filter(definition -> definition.indexed())
                .map(definition -> definition.suffix())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> runtimeHandled = new TreeSet<>(fixed);
        indexed.stream().filter(suffix -> dynamic.stream().anyMatch(suffix::startsWith))
                .forEach(runtimeHandled::add);
        assertThat(runtimeHandled).isEqualTo(indexed);
    }

    @Test
    void builtInContractMatchesEveryMaintainedLauncherInjectedProperty() throws Exception {
        String launcher = source("app/appchain-cluster/cluster.sh");
        Set<String> injected = new TreeSet<>();
        Matcher matcher = LAUNCHER_INJECTED.matcher(launcher);
        while (matcher.find()) {
            injected.add(matcher.group(1));
        }
        TemplateContract contract = new AppChainDescriptorLoader()
                .loadContract("builtin:cluster");
        Set<String> declared = contract.suppliedProperties().stream()
                .map(requirement -> requirement.propertyPattern()
                        .substring("yano.app-chain.chains[*].".length()))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(declared).isEqualTo(injected);
    }

    @Test
    void maintainedTemplateDeclaresThePackagedSchema() throws Exception {
        String firstLine = Files.readAllLines(repository()
                .resolve("app/config/application-appchain.yml")).getFirst();
        assertThat(firstLine).isEqualTo(
                "# yaml-language-server: $schema=./schema/appchain-runtime.schema.json");
    }

    private static Set<String> quotedValues(String source) {
        Set<String> values = new TreeSet<>();
        Matcher matcher = QUOTED_SUFFIX.matcher(source);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertThat(from).as(start).isNotNegative();
        int to = source.indexOf(end, from + start.length());
        assertThat(to).as(end).isGreaterThan(from);
        return source.substring(from + start.length(), to);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(repository().resolve(relative));
    }

    private static Path repository() {
        return Path.of(System.getProperty("yano.test.repo-root"));
    }
}
