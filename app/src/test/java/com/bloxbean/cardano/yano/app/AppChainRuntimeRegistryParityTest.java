package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.appchain.config.AppChainConfigParser;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainRuntimeRegistryParityTest {
    private static final Pattern QUOTED_SUFFIX = Pattern.compile("\"([a-z0-9.-]+)\"");

    @Test
    void registryCoversFixedAndDynamicMultiChainRuntimeParsing() throws Exception {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        String producer = Files.readString(repository.resolve(
                "app/src/main/java/com/bloxbean/cardano/yano/app/YanoProducer.java"));
        String fixedBlock = between(producer, "String[] suffixes = {", "};");
        Set<String> fixed = quotedValues(fixedBlock);
        assertThat(producer).contains(
                "APP_CHAIN_DYNAMIC_PREFIXES = AppChainConfigParser.dynamicPrefixes()");
        Set<String> dynamic = new TreeSet<>(AppChainConfigParser.dynamicPrefixes());

        AppChainPropertyRegistry registry = AppChainPropertyRegistry.framework();
        Set<String> registryDynamic = registry.dynamicNamespaces().stream()
                .map(namespace -> namespace.prefix())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(registryDynamic).containsAll(dynamic);
        assertThat(registryDynamic).allMatch(
                registered -> dynamic.stream().anyMatch(registered::startsWith));

        Set<String> indexed = registry.definitions().stream()
                .filter(definition -> definition.indexed())
                .map(definition -> definition.suffix())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> runtimeHandled = new TreeSet<>(fixed);
        indexed.stream().filter(suffix -> dynamic.stream().anyMatch(suffix::startsWith))
                .forEach(runtimeHandled::add);
        assertThat(runtimeHandled).isEqualTo(indexed);
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
}
