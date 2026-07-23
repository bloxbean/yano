package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
                case "role-lifecycle" -> roleLifecycle(config, output);
                case "run", "publish", "republish", "verify", "replay" ->
                        scenario(config, parsed.scenarioRequest(config), output);
                case "load" -> load(config, parsed.loadRequest(config), output, error);
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

    private static int roleLifecycle(DemoConfig config, PrintStream output) {
        if (!config.roleAware()) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        RoleDemoWorkflow.LifecycleResult result;
        try (DemoEnvironment environment = new DemoEnvironment(config)) {
            result = new DemoInitializer(environment).roleLifecycle();
        }
        output.println("PASS command=role-lifecycle actor=" + result.actorId()
                + " revision=" + result.revision()
                + " rotation=verified revocation=verified"
                + " proposalsCreated=" + result.proposalsCreated()
                + " proposalsPending=" + result.proposalsPending()
                + " proposalsCancelled=" + result.proposalsCancelled());
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

    private static int scenario(DemoConfig config,
                                ScenarioRequest request,
                                PrintStream output) {
        ScenarioReport report;
        try (DemoEnvironment environment = new DemoEnvironment(config)) {
            report = new EvidenceScenario(environment,
                    new ReportStore(config.reportDirectory())).run(request);
        }
        output.println("PASS command=" + request.operation().name().toLowerCase()
                + " scenario=" + report.scenarioId());
        return 0;
    }

    private static int load(DemoConfig config,
                            LoadRequest request,
                            PrintStream output,
                            PrintStream error) {
        LoadReport report = new EvidenceLoadRunner(config,
                new ReportStore(config.reportDirectory())).run(request);
        String summary = String.format(Locale.ROOT,
                "command=load load=%s requested=%d succeeded=%d failed=%d "
                        + "mode=%s concurrency=%d maxInFlight=%d durationMs=%d "
                        + "successfulPerSecond=%.3f appMessagesPerSecond=%.3f",
                report.loadId(), report.requested(), report.succeeded(), report.failed(),
                report.mode(), report.concurrency(), report.maxInFlight(),
                report.durationMillis(), report.successfulPerSecond(),
                report.throughput().appMessagesPerSecond());
        if (report.failed() == 0) {
            output.println("PASS " + summary);
            return 0;
        }
        output.println("FAIL " + summary);
        error.println("FAIL code=" + DemoError.LOAD_PARTIAL_FAILURE.name());
        return 2;
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
                             int expectedRecords, String expectedEffectId,
                             String evidenceId, Long businessVersion, Path sampleFile,
                             Integer count, Integer concurrency, String idPrefix,
                             String loadMode, Integer maxInFlight) {
        private static final Set<String> COMMANDS = Set.of(
                "validate-config", "probe", "bootstrap-s3", "audit-kafka", "init-connectors",
                "init", "role-lifecycle", "run", "publish", "republish", "verify", "replay",
                "load", "serve");
        private static final Set<String> SCENARIO_COMMANDS = Set.of(
                "run", "publish", "republish", "verify", "replay");

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
                    return new Arguments(args[0], Path.of(args[2]), expected, args[6],
                            null, null, null, null, null, null, null, null);
                } catch (NumberFormatException failure) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
            }
            if (args == null || args.length < 3 || args[0] == null
                    || !COMMANDS.contains(args[0])) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
            Map<String, String> options = options(args);
            if (!SCENARIO_COMMANDS.contains(args[0]) && !"load".equals(args[0])
                    && !options.keySet().equals(Set.of("--config"))) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
            Path config = path(options.get("--config"));
            String evidenceId = options.get("--evidence-id");
            Long businessVersion = version(options.get("--business-version"));
            Path sampleFile = options.containsKey("--sample-file")
                    ? path(options.get("--sample-file")) : null;
            Integer count = positiveInt(options.get("--count"), LoadRequest.MAX_COUNT);
            Integer concurrency = positiveInt(
                    options.get("--concurrency"), LoadRequest.MAX_CONCURRENCY);
            String idPrefix = options.get("--id-prefix");
            String loadMode = options.get("--load-mode");
            Integer maxInFlight = positiveInt(
                    options.get("--max-in-flight"), LoadRequest.MAX_IN_FLIGHT);
            validateScenarioOptions(args[0], evidenceId, businessVersion, sampleFile,
                    count, concurrency, idPrefix, loadMode, maxInFlight);
            return new Arguments(args[0], config, -1, null,
                    evidenceId, businessVersion, sampleFile, count, concurrency, idPrefix,
                    loadMode, maxInFlight);
        }

        ScenarioRequest scenarioRequest(DemoConfig config) {
            ScenarioRequest.Operation operation = ScenarioRequest.Operation.valueOf(
                    command.toUpperCase());
            if (operation == ScenarioRequest.Operation.RUN) {
                return new ScenarioRequest(operation,
                        evidenceId == null ? config.evidenceId() : evidenceId,
                        0, sampleFile == null ? config.sampleFile() : sampleFile);
            }
            long version = operation == ScenarioRequest.Operation.PUBLISH
                    ? 1 : businessVersion == null ? 0 : businessVersion;
            return new ScenarioRequest(operation, evidenceId,
                    version, sampleFile);
        }

        LoadRequest loadRequest(DemoConfig config) {
            int workers = concurrency == null ? 1 : concurrency;
            LoadRequest.Mode mode = LoadRequest.Mode.parse(loadMode);
            int inFlight = maxInFlight != null ? maxInFlight
                    : mode == LoadRequest.Mode.LIFECYCLE ? workers
                    : Math.min(count, Math.max(config.evidenceCapacityPerBlock(),
                    Math.multiplyExact(workers, 4)));
            return new LoadRequest(count, workers, mode, inFlight, idPrefix, sampleFile);
        }

        private static Map<String, String> options(String[] args) {
            if (args.length % 2 == 0) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
            Map<String, String> values = new HashMap<>();
            Set<String> allowed = Set.of(
                    "--config", "--evidence-id", "--business-version", "--sample-file",
                    "--count", "--concurrency", "--id-prefix", "--load-mode",
                    "--max-in-flight");
            for (int index = 1; index < args.length; index += 2) {
                String option = args[index];
                String value = args[index + 1];
                if (!allowed.contains(option) || value == null || value.isBlank()
                        || values.putIfAbsent(option, value) != null) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
            }
            if (!values.containsKey("--config")) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
            return Map.copyOf(values);
        }

        private static void validateScenarioOptions(
                String command,
                String evidenceId,
                Long version,
                Path sampleFile,
                Integer count,
                Integer concurrency,
                String idPrefix,
                String loadMode,
                Integer maxInFlight
        ) {
            if ("load".equals(command)) {
                if (evidenceId != null || version != null || sampleFile == null
                        || count == null || idPrefix == null
                        || maxInFlight != null
                        && LoadRequest.Mode.parse(loadMode) != LoadRequest.Mode.PIPELINE) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
                int workers = concurrency == null ? 1 : concurrency;
                LoadRequest.Mode mode = LoadRequest.Mode.parse(loadMode);
                int validationInFlight = maxInFlight != null ? maxInFlight : workers;
                new LoadRequest(count, workers, mode, validationInFlight,
                        idPrefix, sampleFile);
                return;
            }
            if (count != null || concurrency != null || idPrefix != null
                    || loadMode != null || maxInFlight != null) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
            if (!SCENARIO_COMMANDS.contains(command)) {
                if (evidenceId != null || version != null || sampleFile != null) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
                return;
            }
            if ("run".equals(command)) {
                if (version != null) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
                return;
            }
            if (evidenceId == null
                    || "verify".equals(command) != (sampleFile == null)
                    || "publish".equals(command) && version != null
                    || Set.of("republish", "replay").contains(command) && version == null) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
        }

        private static Path path(String value) {
            try {
                if (value == null || value.isBlank()) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
                return Path.of(value);
            } catch (DemoException failure) {
                throw failure;
            } catch (RuntimeException invalid) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
        }

        private static Long version(String value) {
            if (value == null) {
                return null;
            }
            if ("latest".equals(value)) {
                return 0L;
            }
            if (!value.matches("[1-9][0-9]{0,18}")) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
            try {
                long parsed = Long.parseLong(value);
                if (parsed == Long.MAX_VALUE) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
        }

        private static Integer positiveInt(String value, int maximum) {
            if (value == null) {
                return null;
            }
            if (!value.matches("[1-9][0-9]{0,9}")) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > maximum) {
                    throw new DemoException(DemoError.INVALID_ARGUMENT);
                }
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new DemoException(DemoError.INVALID_ARGUMENT);
            }
        }
    }
}
