package org.yanoproject.ledger.conformance.diversity.turbo;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.ProtocolFeatureRegistry;
import org.julclang.vm.java.JavaVmProvider;
import org.julclang.vm.java.PreparedProgram;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;
import org.yanoproject.ledger.conformance.diversity.uplc.UplcEvaluation;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 §5: the {@code uplc-turbo} report, julc over the scenario's mainnet scripts with the default cost models of
 * each script's protocol version (julc's built-in defaults: no cost model is configured).
 */
class TurboDiversityReportTest {

    static final String SCENARIO = "uplc-turbo";

    @Test
    void writesTheReport() {
        List<TurboSample> samples = TurboSample.list(NodeDiversity.requireCorpus("turbo"));
        JavaVmProvider provider = new JavaVmProvider();
        List<PreparedProgram> programs = samples.stream()
                .map(sample -> provider.prepare(decode(sample), target(sample))).toList();
        programs.forEach(TurboMeasurement::evaluate);

        ObjectNode report = NodeDiversity.report(SCENARIO);
        ((ObjectNode) report.get("implementation")).put("language", "Java");
        report.set("machine", machine());
        ArrayNode benchmarks = report.putArray("benchmarks");
        int failed = 0;
        for (int i = 0; i < samples.size(); i++) {
            TurboSample sample = samples.get(i);
            TurboMeasurement m = TurboMeasurement.measure(programs.get(i));
            if (!m.succeeded()) {
                // Left out of the report: the scenario counts a missing benchmark as failed.
                failed++;
                System.out.println("uplc-turbo: " + sample.bench() + " did not evaluate successfully; left out");
                continue;
            }
            benchmarks.addObject()
                    .put("bench", sample.bench())
                    .put("plutus_version", sample.plutusVersion())
                    .put("protocol_version", sample.protocolVersion())
                    .put("budget_cpu", m.budget().cpuSteps())
                    .put("budget_mem", m.budget().memoryUnits())
                    .put("cpu_us", m.cpuUs())
                    .put("mem_bytes", m.memBytes());
        }
        NodeDiversity.write(SCENARIO, report);
        System.out.printf("uplc-turbo: %d scripts measured, %d did not evaluate successfully%n", samples.size(),
                failed);
        assertThat(benchmarks).hasSize(samples.size() - failed).isNotEmpty();
    }

    /** Decodes the Flat program as phase 2 decodes a script, outside the measurement. */
    private static Program decode(TurboSample sample) {
        return UplcFlatDecoder.decodeProgram(sample.flat(),
                ProtocolFeatureRegistry.resolve(target(sample)).decodeLimits().toFlatDecodeLimits());
    }

    private static LedgerEvaluationTarget target(TurboSample sample) {
        return UplcEvaluation.target(sample.language(), sample.protocolMajor());
    }

    private static ObjectNode machine() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        ObjectNode machine = NodeDiversity.JSON.createObjectNode();
        machine.put("os", System.getProperty("os.name"));
        machine.put("os_version", System.getProperty("os.version"));
        machine.put("architecture", System.getProperty("os.arch"));
        machine.put("cpu", cpu().orElse(null));
        machine.put("logical_cores", Runtime.getRuntime().availableProcessors());
        machine.put("memory_bytes", os.getTotalMemorySize());
        return machine;
    }

    /** @return the CPU model: {@code /proc/cpuinfo} on Linux, {@code sysctl} on macOS */
    private static Optional<String> cpu() {
        try {
            Path cpuinfo = Path.of("/proc/cpuinfo");
            if (Files.isReadable(cpuinfo)) {
                return Files.readAllLines(cpuinfo).stream().filter(l -> l.startsWith("model name"))
                        .map(l -> l.substring(l.indexOf(':') + 1).trim()).findFirst();
            }
            Process process = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
            String brand = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(5, TimeUnit.SECONDS) && !brand.isEmpty() ? Optional.of(brand) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
