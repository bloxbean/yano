package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** CLI entry point shared by Compose and normal deployments. */
public final class EvidenceDemoMain {
    private EvidenceDemoMain() {
    }

    public static void main(String[] args) {
        int result = run(args, System.out, System.err);
        if (result != 0) {
            System.exit(result);
        }
    }

    static int run(String[] args, PrintStream output, PrintStream error) {
        try {
            Arguments parsed = Arguments.parse(args);
            if ("serve".equals(parsed.command())) {
                return serve(UiConfig.load(parsed.config()), output);
            }
            if ("bootstrap-s3".equals(parsed.command())) {
                return bootstrapS3(S3BootstrapConfig.load(parsed.config()), output);
            }
            if ("audit-kafka".equals(parsed.command())) {
                return auditKafka(DemoConfig.loadKafkaSettings(parsed.config()),
                        parsed.expectedRecords(), parsed.expectedEffectId(), output);
            }
            DemoConfig config = DemoConfig.load(parsed.config());
            return switch (parsed.command()) {
                case "validate-config" -> validateConfig(output);
                case "probe" -> probe(config, output);
                case "init-connectors" -> initializeConnectors(config, output);
                case "init" -> initialize(config, output);
                case "run" -> scenario(config, output);
                case "serve" -> throw new DemoException(DemoError.INTERNAL_ERROR);
                case "audit-kafka" -> throw new DemoException(DemoError.INTERNAL_ERROR);
                default -> throw new DemoException(DemoError.INVALID_ARGUMENT);
            };
        } catch (DemoException failure) {
            error.println("FAIL code=" + failure.error().name());
            return 2;
        } catch (RuntimeException failure) {
            error.println("FAIL code=" + DemoError.INTERNAL_ERROR.name());
            return 2;
        }
    }

    private static int validateConfig(PrintStream output) {
        output.println("PASS command=validate-config");
        return 0;
    }

    private static int probe(DemoConfig config, PrintStream output) {
        try (DemoEnvironment environment = new DemoEnvironment(config)) {
            new DemoInitializer(environment).probe();
        }
        output.println("PASS command=probe");
        return 0;
    }

    private static int initialize(DemoConfig config, PrintStream output) {
        try (DemoEnvironment environment = new DemoEnvironment(config)) {
            new DemoInitializer(environment).initialize();
        }
        output.println("PASS command=init");
        return 0;
    }

    private static int initializeConnectors(DemoConfig config, PrintStream output) {
        DemoInitializer.initializeConnectors(config);
        output.println("PASS command=init-connectors");
        return 0;
    }

    private static int bootstrapS3(S3BootstrapConfig config, PrintStream output) {
        new RustFsAdminBootstrapper(config).bootstrap();
        try (S3BucketBootstrapper bootstrapper = new S3BucketBootstrapper(config)) {
            bootstrapper.bootstrap();
        }
        new S3IamVerifier(config).verify();
        output.println("PASS command=bootstrap-s3");
        return 0;
    }

    private static int auditKafka(DemoConfig.KafkaSettings settings,
                                  int expectedRecords,
                                  String expectedEffectId,
                                  PrintStream output) {
        try (KafkaDemoClient kafka = new KafkaDemoClient(settings)) {
            output.println(kafka.audit(expectedRecords, expectedEffectId).toJson());
        }
        return 0;
    }

    private static int scenario(DemoConfig config, PrintStream output) {
        ScenarioReport report;
        try (DemoEnvironment environment = new DemoEnvironment(config)) {
            report = new EvidenceScenario(environment,
                    new ReportStore(config.reportDirectory())).run();
        }
        output.println("PASS command=run scenario=" + report.scenarioId());
        return 0;
    }

    private static int serve(UiConfig config, PrintStream output) {
        ReportServer server = new ReportServer(config.bindAddress(), config.port(),
                new ReportStore(config.reportDirectory()));
        CountDownLatch closed = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            server.close();
            closed.countDown();
        }, "evidence-demo-ui-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        server.start();
        output.println("PASS command=serve port=" + server.port());
        try {
            closed.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            server.close();
            return 0;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // The JVM is already running shutdown hooks.
            }
        }
        return 0;
    }

    private record Arguments(String command, Path config,
                             int expectedRecords, String expectedEffectId) {
        private static final Set<String> COMMANDS = Set.of(
                "validate-config", "probe", "bootstrap-s3", "audit-kafka", "init-connectors",
                "init", "run", "serve");

        static Arguments parse(String[] args) {
            if (args != null && args.length > 0 && "audit-kafka".equals(args[0])) {
                if (args.length != 7 || !"--config".equals(args[1])
                        || args[2] == null || args[2].isBlank()
                        || !"--expected-records".equals(args[3])
                        || args[4] == null || !args[4].matches("0|[1-9][0-9]*")
                        || !"--expected-effect-id".equals(args[5])
                        || args[6] == null || !args[6].matches("[0-9a-f]{64}")) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
                try {
                    int expected = Integer.parseInt(args[4]);
                    if (expected > KafkaDemoClient.MAX_AUDIT_RECORDS) {
                        throw new DemoException(DemoError.INVALID_ARGUMENT);
                    }
                    return new Arguments(args[0], Path.of(args[2]), expected, args[6]);
                } catch (NumberFormatException failure) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
            }
            if (args == null || args.length != 3 || !"--config".equals(args[1])
                    || args[0] == null || args[0].isBlank()
                    || !COMMANDS.contains(args[0])
                    || args[2] == null || args[2].isBlank()) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
            return new Arguments(args[0], Path.of(args[2]), -1, null);
        }
    }
}
