package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.zip.ZipFile;

/** Process-level probe for the documented self-contained plugin-directory layout. */
public final class PluginBundleLaunchProbe {
    private static final String EVIDENCE_BUNDLE_ID =
            "com.bloxbean.cardano.yano.appchain.evidence-registry";
    private static final String ORIGINAL_EVIDENCE_CONTRACT_PACKAGE =
            "com.bloxbean.cardano.yano.appchain.examples.evidence.";
    private static final String RELOCATED_EVIDENCE_CONTRACT_PACKAGE =
            "com.bloxbean.cardano.yano.appchain.examples.internal.evidencecontracts.v1.";
    private static final String RELOCATED_CONNECTOR_PACKAGE =
            "com.bloxbean.cardano.yano.appchain.examples.evidence.internal.contracts.v1.";
    private static final String EVIDENCE_GOLDEN_VECTORS =
            "META-INF/yano/contracts/evidence/v1/golden-vectors.properties";
    private static final Set<String> EXPECTED_BUNDLES = Set.of(
            "com.bloxbean.cardano.yano.appchain.kafka",
            "com.bloxbean.cardano.yano.appchain.objectstore.s3",
            "com.bloxbean.cardano.yano.appchain.ipfs",
            EVIDENCE_BUNDLE_ID,
            "com.bloxbean.cardano.yano.appchain.effects.cardano",
            "com.bloxbean.cardano.yano.appchain.zk");

    private PluginBundleLaunchProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != EXPECTED_BUNDLES.size()) {
            throw new IllegalArgumentException("Unexpected first-party plugin bundle count");
        }
        Path directory = Files.createTempDirectory("yano-plugin-bundle-probe-");
        ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
        ClassLoader callerTccl = new ClassLoader(originalTccl) { };
        Thread.currentThread().setContextClassLoader(callerTccl);
        try {
            for (String argument : args) {
                Path source = Path.of(argument);
                Path copy = directory.resolve(source.getFileName());
                Files.copy(source, copy,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
            try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), PluginLoaderHandle.directory(
                            directory, PluginBundleLaunchProbe.class.getClassLoader()))) {
                requireTccl(callerTccl, "catalog construction");
                if (!environment.selectedBundleIds().containsAll(EXPECTED_BUNDLES)) {
                    throw new IllegalStateException("Plugin directory catalog misses first-party bundles: "
                            + environment.selectedBundleIds());
                }
                require(environment, FinalizedStreamSinkFactory.class, "kafka");
                AppEffectExecutorFactory kafkaEffects = require(
                        environment, AppEffectExecutorFactory.class, "kafka");
                if (!kafkaEffects.create("probe", Map.of()).isEmpty()) {
                    throw new IllegalStateException(
                            "Kafka effect contribution activated without executor config");
                }
                // Exercise the relocated command-fingerprint path without
                // opening a producer. This proves the bundle-private contract,
                // CBOR, and BLAKE2b classes link and execute together.
                var kafkaExecutors = kafkaEffects.create("probe", Map.of(
                        "enabled", "true",
                        "targets.probe.target-id", "probe-v1",
                        "targets.probe.bootstrap-servers", "localhost:9092",
                        "targets.probe.security-profile", "local-demo",
                        "topics.events.target", "probe",
                        "topics.events.name", "probe.events.v1"));
                if (kafkaExecutors.size() != 1) {
                    throw new IllegalStateException(
                            "Kafka effect contribution did not create one configured executor");
                }
                kafkaExecutors.getFirst().close();
                AppEffectExecutorFactory objectStoreEffects = require(
                        environment, AppEffectExecutorFactory.class, "objectstore-s3");
                if (!objectStoreEffects.create("probe", Map.of()).isEmpty()) {
                    throw new IllegalStateException(
                            "Object-store contribution activated without executor config");
                }
                // Construct a configured executor without performing network
                // I/O. This exercises the relocated contract/fingerprint and
                // AWS client-factory linkage through the directory bundle.
                var objectStoreExecutors = objectStoreEffects.create("probe", Map.ofEntries(
                        Map.entry("enabled", "true"),
                        Map.entry("targets.probe.target-id", "probe-v1"),
                        Map.entry("targets.probe.endpoint", "http://127.0.0.1:9000"),
                        Map.entry("targets.probe.region", "us-east-1"),
                        Map.entry("targets.probe.security-profile", "local-demo"),
                        Map.entry("targets.probe.path-style", "true"),
                        Map.entry("targets.probe.credentials-provider", "static"),
                        Map.entry("targets.probe.credentials.access-key-id", "probe-access"),
                        Map.entry("targets.probe.credentials.secret-access-key", "probe-secret"),
                        Map.entry("targets.probe.source-bucket", "probe-staging"),
                        Map.entry("targets.probe.source-prefix", "incoming"),
                        Map.entry("targets.probe.destination-bucket", "probe-archive"),
                        Map.entry("targets.probe.destination-prefix", "verified"),
                        Map.entry("targets.probe.encryption-policy-id", "local-none-v1"),
                        Map.entry("targets.probe.encryption-mode", "none"),
                        Map.entry("targets.probe.retention-policy-id", "none-v1"),
                        Map.entry("targets.probe.require-versioning", "true")));
                if (objectStoreExecutors.size() != 1) {
                    throw new IllegalStateException(
                            "Object-store contribution did not create one configured executor");
                }
                objectStoreExecutors.getFirst().close();
                AppEffectExecutorFactory ipfsEffects = require(
                        environment, AppEffectExecutorFactory.class, "ipfs");
                if (!ipfsEffects.create("probe", Map.of()).isEmpty()) {
                    throw new IllegalStateException(
                            "IPFS contribution activated without executor config");
                }
                // Construct and close a configured executor without performing
                // network I/O. This exercises the directory-loaded factory,
                // relocated CID contract, and strict Kubo client linkage.
                var ipfsExecutors = ipfsEffects.create("probe", Map.ofEntries(
                        Map.entry("enabled", "true"),
                        Map.entry("targets.probe.target-id", "probe-v1"),
                        Map.entry("targets.probe.api-url", "http://127.0.0.1:5001"),
                        Map.entry("targets.probe.security-profile", "local-demo"),
                        Map.entry("targets.probe.allowed-codecs", "raw,dag-pb"),
                        Map.entry("targets.probe.recursive", "true"),
                        Map.entry("targets.probe.replication-policy", "demo-single"),
                        Map.entry("targets.probe.connect-timeout-ms", "1000"),
                        Map.entry("targets.probe.request-timeout-ms", "1000"),
                        Map.entry("targets.probe.close-timeout-ms", "1000")));
                if (ipfsExecutors.size() != 1) {
                    throw new IllegalStateException(
                            "IPFS contribution did not create one configured executor");
                }
                ipfsExecutors.getFirst().close();
                require(environment, AppEffectExecutorFactory.class, "cardano");
                for (String machine : List.of("credential-registry", "zk-gate", "zk-membership")) {
                    require(environment, AppStateMachineProvider.class, machine);
                }
                AppStateMachineProvider evidenceProvider = require(
                        environment, AppStateMachineProvider.class, "evidence-registry");
                AppStateMachine evidenceMachine = evidenceProvider.create(
                        new AppStateMachineContext() {
                            @Override public String chainId() { return "probe"; }
                            @Override public Map<String, String> settings() {
                                return Map.of(
                                        "effects.enabled", "true",
                                        "effects.max-per-block", "8",
                                        "effects.max-payload-bytes", "4096");
                            }
                            @Override
                            public java.util.Optional<com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile>
                            consensusProfile() {
                                return java.util.Optional.of(
                                        AppTestConsensusProfiles.enabledEffects(8, 4096));
                            }
                        });
                if (!"evidence-registry".equals(evidenceMachine.id())) {
                    throw new IllegalStateException(
                            "Evidence registry provider returned the wrong state machine");
                }
                DomainApiProvider evidenceDomainProvider = require(
                        environment, DomainApiProvider.class,
                        "com.bloxbean.cardano.yano.appchain.evidence-registry");
                try (DomainApi evidenceApi = evidenceDomainProvider.create(
                        new DomainApiContext(Map.of(), new DomainQueryService() {
                            @Override public List<String> chainIds() { return List.of("probe"); }
                            @Override public AppQueryResult query(
                                    String chainId, String path, byte[] params) {
                                return new AppQueryResult(chainId, "evidence-registry",
                                        0, new byte[32], new byte[0]);
                            }
                        }))) {
                    if (evidenceApi.routes().size() != 1) {
                        throw new IllegalStateException(
                                "Evidence registry domain API did not publish one route");
                    }
                }
                exerciseEvidenceBundleContracts(environment, evidenceMachine);
                assertParentEvidenceContractCannotShadow(environment);
                requireTccl(callerTccl, "provider construction and metadata");
                // Force representative third-party linkage and prove it came
                // from each bundle, not a coincidental host dependency.
                requireOwnedClass(environment,
                        "org.apache.kafka.clients.producer.KafkaProducer",
                        "com.bloxbean.cardano.yano.appchain.kafka");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.kafka.internal.contracts.v1."
                                + "kafka.KafkaPublishCommandV1",
                        "com.bloxbean.cardano.yano.appchain.kafka");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.kafka.internal.contracts."
                                + "v1deps.cbor.CborDecoder",
                        "com.bloxbean.cardano.yano.appchain.kafka");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.kafka.internal.contracts."
                                + "v1deps.bouncycastle.crypto.digests.Blake2bDigest",
                        "com.bloxbean.cardano.yano.appchain.kafka");
                requireAbsentClass(environment,
                        "com.bloxbean.cardano.yano.appchain.integration.kafka."
                                + "KafkaPublishCommandV1");
                requireOwnedClass(environment,
                        "software.amazon.awssdk.services.s3.S3Client",
                        "com.bloxbean.cardano.yano.appchain.objectstore.s3");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.contracts."
                                + "v1.objectstore.ObjectPutCommandV1",
                        "com.bloxbean.cardano.yano.appchain.objectstore.s3");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.contracts."
                                + "v1deps.cbor.CborDecoder",
                        "com.bloxbean.cardano.yano.appchain.objectstore.s3");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.contracts."
                                + "v1deps.bouncycastle.crypto.digests.Blake2bDigest",
                        "com.bloxbean.cardano.yano.appchain.objectstore.s3");
                requireAbsentClass(environment,
                        "com.bloxbean.cardano.yano.appchain.integration.objectstore."
                                + "ObjectPutCommandV1");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.ipfs.internal.contracts."
                                + "v1.ipfs.CanonicalCid",
                        "com.bloxbean.cardano.yano.appchain.ipfs");
                requireInitializedOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo."
                                + "KuboIpfsPinClient",
                        "com.bloxbean.cardano.yano.appchain.ipfs");
                requireAbsentClass(environment,
                        "com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid");
                requireOwnedClass(environment,
                        RELOCATED_EVIDENCE_CONTRACT_PACKAGE + "EvidenceContract",
                        EVIDENCE_BUNDLE_ID);
                requireOwnedClass(environment,
                        RELOCATED_EVIDENCE_CONTRACT_PACKAGE
                                + "internal.EvidenceValidation",
                        EVIDENCE_BUNDLE_ID);
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.examples.evidence.internal.contracts."
                                + "v1.ConnectorTypes",
                        "com.bloxbean.cardano.yano.appchain.evidence-registry");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.examples.evidence.internal.contracts."
                                + "v1deps.cbor.CborDecoder",
                        "com.bloxbean.cardano.yano.appchain.evidence-registry");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.yano.appchain.examples.evidence.internal.contracts."
                                + "v1deps.bouncycastle.crypto.digests.Blake2bDigest",
                        "com.bloxbean.cardano.yano.appchain.evidence-registry");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService",
                        "com.bloxbean.cardano.yano.appchain.effects.cardano");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.zeroj.verifier.core.VerifierOrchestrator",
                        "com.bloxbean.cardano.yano.appchain.zk");

                // Exercise the real ZeroJ default-ServiceLoader path through a
                // plugin factory callback. The context assertion proves the
                // facade installed the directory loader while ZeroJ discovers
                // its verifier backends; the postcondition proves restoration.
                Path vk = directory.resolve("probe.vk");
                byte[] vkBytes = "yano-zk-backend-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(vk, vkBytes);
                Map<String, String> settings = Map.of(
                        "zk.circuits[0].id", "probe",
                        "zk.circuits[0].vk-file", vk.toString(),
                        "zk.circuits[0].vk-hash",
                        HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(vkBytes)),
                        "zk.circuits[0].proof-system", "groth16",
                        "zk.circuits[0].curve", "bls12381");
                ClassLoader pluginLoader = environment.classLoader();
                AppStateMachineProvider zkProvider = require(
                        environment, AppStateMachineProvider.class, "zk-gate");
                AppStateMachine zkMachine = zkProvider.create(new AppStateMachineContext() {
                    @Override
                    public String chainId() {
                        requireTccl(pluginLoader, "ZK provider chain context");
                        return "probe";
                    }

                    @Override
                    public Map<String, String> settings() {
                        requireTccl(pluginLoader, "ZK provider settings context");
                        return settings;
                    }
                });
                if (!"zk-gate".equals(zkMachine.id())) {
                    throw new IllegalStateException("ZK provider returned the wrong state machine");
                }
                requireTccl(callerTccl, "ZK provider and state-machine callbacks");
                instantiateZkVerifierBackends(environment,
                        "com.bloxbean.cardano.yano.appchain.zk");
                requireTccl(callerTccl, "ZK backend probe");
            }
            requireTccl(callerTccl, "plugin environment close");
        } finally {
            Thread.currentThread().setContextClassLoader(originalTccl);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static <P> P require(PluginRuntimeEnvironment environment,
                                 Class<P> type,
                                 String name) {
        return environment.providers().find(type, name).orElseThrow(() ->
                new IllegalStateException("Missing provider " + type.getName() + "/" + name));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void instantiateZkVerifierBackends(
            PluginRuntimeEnvironment environment,
            String expectedBundleId) throws Exception {
        ClassLoader loader = environment.classLoader();
        Class serviceType = Class.forName(
                "com.bloxbean.cardano.zeroj.backend.spi.ZkVerifier", true, loader);
        List<ServiceLoader.Provider<?>> providers = (List) ServiceLoader
                .load(serviceType, loader).stream().toList();
        if (providers.isEmpty()) {
            throw new IllegalStateException("ZK bundle exposes no verifier backend services");
        }
        for (ServiceLoader.Provider<?> provider : providers) {
            Object backend = provider.get();
            requireOwnedClass(environment, backend.getClass().getName(), expectedBundleId);
            Object descriptor = backend.getClass().getMethod("descriptor").invoke(backend);
            if (descriptor == null) {
                throw new IllegalStateException("ZK verifier backend returned no descriptor");
            }
        }
    }

    private static void requireTccl(ClassLoader expected, String operation) {
        if (Thread.currentThread().getContextClassLoader() != expected) {
            throw new IllegalStateException(operation + " did not restore the calling TCCL");
        }
    }

    /**
     * Executes the bundle-private evidence wire/state contracts without
     * linking the probe itself to evidence types. This catches relocation
     * failures that a class-presence check cannot detect.
     */
    private static void exerciseEvidenceBundleContracts(
            PluginRuntimeEnvironment environment,
            AppStateMachine evidenceMachine) throws Exception {
        Class<?> commandCodec = requireOwnedClass(environment,
                RELOCATED_EVIDENCE_CONTRACT_PACKAGE
                        + "command.EvidenceCommandCodec",
                EVIDENCE_BUNDLE_ID);
        Properties vectors = loadGoldenVectors(commandCodec);

        byte[] goldenCommand = goldenVector(vectors, "command.submit");
        Object decodedCommand = commandCodec.getMethod("decode", byte[].class)
                .invoke(null, (Object) goldenCommand);
        Class<?> submitCommand = requireOwnedClass(environment,
                RELOCATED_EVIDENCE_CONTRACT_PACKAGE
                        + "command.SubmitEvidenceCommandV1",
                EVIDENCE_BUNDLE_ID);
        if (decodedCommand.getClass() != submitCommand) {
            throw new IllegalStateException(
                    "Evidence command golden vector decoded to an unexpected type");
        }
        byte[] reencodedCommand = (byte[]) submitCommand.getMethod("encode")
                .invoke(decodedCommand);
        requireExactVector("command.submit", goldenCommand, reencodedCommand);

        AppMessage message = AppMessage.builder()
                .messageId(new byte[32])
                .chainId("probe")
                .topic("evidence.command.v1")
                .sender(new byte[32])
                .senderSeq(1)
                .expiresAt(4_000_000_000L)
                .body(goldenCommand)
                .authScheme(0)
                .authProof(new byte[64])
                .build();
        if (!evidenceMachine.validate(message).isAccepted()) {
            throw new IllegalStateException(
                    "Evidence bundle state machine rejected its canonical command");
        }

        Class<?> evidenceKeys = requireOwnedClass(environment,
                RELOCATED_EVIDENCE_CONTRACT_PACKAGE + "state.EvidenceKeys",
                EVIDENCE_BUNDLE_ID);
        byte[] expectedRecordKey = goldenVector(vectors, "key.record");
        byte[] firstRecordKey = (byte[]) evidenceKeys
                .getMethod("recordKey", String.class, long.class)
                .invoke(null, "batch-001", 1L);
        byte[] secondRecordKey = (byte[]) evidenceKeys
                .getMethod("recordKey", String.class, long.class)
                .invoke(null, "batch-001", 1L);
        requireExactVector("key.record", expectedRecordKey, firstRecordKey);
        requireExactVector("key.record repeat", firstRecordKey, secondRecordKey);

        Class<?> evidenceRecord = requireOwnedClass(environment,
                RELOCATED_EVIDENCE_CONTRACT_PACKAGE + "state.EvidenceRecordV1",
                EVIDENCE_BUNDLE_ID);
        byte[] goldenRecord = goldenVector(vectors, "state.record");
        Object decodedRecord = evidenceRecord.getMethod("decode", byte[].class)
                .invoke(null, (Object) goldenRecord);
        if (decodedRecord.getClass() != evidenceRecord) {
            throw new IllegalStateException(
                    "Evidence state golden vector decoded to an unexpected type");
        }
        byte[] reencodedRecord = (byte[]) evidenceRecord.getMethod("encode")
                .invoke(decodedRecord);
        requireExactVector("state.record", goldenRecord, reencodedRecord);
        Object decodedAgain = evidenceRecord.getMethod("decode", byte[].class)
                .invoke(null, (Object) reencodedRecord);
        if (!decodedRecord.equals(decodedAgain)) {
            throw new IllegalStateException("Evidence record round trip changed its value");
        }

        assertRelocatedConnectorValue(environment, decodedRecord, evidenceRecord,
                "objectPut", RELOCATED_CONNECTOR_PACKAGE
                        + "objectstore.ObjectPutCommandV1");
        assertRelocatedConnectorValue(environment, decodedRecord, evidenceRecord,
                "ipfsPin", RELOCATED_CONNECTOR_PACKAGE + "ipfs.IpfsPinCommandV1");
    }

    /**
     * Proves the parent supplies the public SDK class while the bundle
     * continues to execute its privately relocated pinned copy.
     */
    private static void assertParentEvidenceContractCannotShadow(
            PluginRuntimeEnvironment environment) throws Exception {
        String publicName = ORIGINAL_EVIDENCE_CONTRACT_PACKAGE
                + "command.EvidenceCommandCodec";
        Class<?> publicCodec = Class.forName(
                publicName, true, environment.classLoader());
        Path publicSource = codeSource(publicCodec);
        if (publicCodec.getClassLoader() != PluginBundleLaunchProbe.class.getClassLoader()
                || !publicSource.getFileName().toString()
                .contains("appchain-evidence-contracts")) {
            throw new IllegalStateException(
                    "Evidence shadowing probe did not resolve the public parent contract JAR");
        }

        Class<?> relocated = requireOwnedClass(environment,
                RELOCATED_EVIDENCE_CONTRACT_PACKAGE + "command.EvidenceCommandCodec",
                EVIDENCE_BUNDLE_ID);
        if (Files.isSameFile(publicSource, codeSource(relocated))) {
            throw new IllegalStateException(
                    "Evidence bundle contract was resolved from the public parent SDK");
        }
    }

    private static void assertRelocatedConnectorValue(
            PluginRuntimeEnvironment environment,
            Object record,
            Class<?> recordType,
            String accessor,
            String expectedClassName) throws Exception {
        Object value = recordType.getMethod(accessor).invoke(record);
        Class<?> expectedType = requireOwnedClass(
                environment, expectedClassName, EVIDENCE_BUNDLE_ID);
        if (value.getClass() != expectedType) {
            throw new IllegalStateException("Evidence " + accessor
                    + " did not return its bundle-private relocated contract type");
        }
    }

    private static Properties loadGoldenVectors(Class<?> ownedContract) throws Exception {
        Path bundle = codeSource(ownedContract);
        try (ZipFile archive = new ZipFile(bundle.toFile())) {
            var entry = archive.getEntry(EVIDENCE_GOLDEN_VECTORS);
            if (entry == null || entry.isDirectory()) {
                throw new IllegalStateException(
                        "Evidence bundle is missing canonical golden vectors");
            }
            Properties vectors = new Properties();
            try (var input = archive.getInputStream(entry)) {
                vectors.load(input);
            }
            return vectors;
        }
    }

    private static byte[] goldenVector(Properties vectors, String name) {
        String encoded = vectors.getProperty(name);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Missing evidence golden vector " + name);
        }
        try {
            return HexFormat.of().parseHex(encoded);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Malformed evidence golden vector " + name,
                    malformed);
        }
    }

    private static void requireExactVector(String name, byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Evidence canonical vector mismatch for " + name);
        }
    }

    private static void requireAbsentClass(PluginRuntimeEnvironment environment,
                                           String className) throws Exception {
        try {
            Class.forName(className, false, environment.classLoader());
        } catch (ClassNotFoundException expected) {
            return;
        }
        throw new IllegalStateException(
                "Self-contained plugin directory exposes forbidden host contract " + className);
    }

    private static Class<?> requireOwnedClass(PluginRuntimeEnvironment environment,
                                              String className,
                                              String expectedBundleId) throws Exception {
        return requireOwnedClass(environment, className, expectedBundleId, false);
    }

    private static void requireInitializedOwnedClass(PluginRuntimeEnvironment environment,
                                                     String className,
                                                     String expectedBundleId) throws Exception {
        requireOwnedClass(environment, className, expectedBundleId, true);
    }

    private static Class<?> requireOwnedClass(PluginRuntimeEnvironment environment,
                                              String className,
                                              String expectedBundleId,
                                              boolean initialize) throws Exception {
        ClassLoader loader = environment.classLoader();
        Class<?> dependency = Class.forName(className, initialize, loader);
        Path actual = codeSource(dependency);
        List<Path> providerSources = environment.catalog().bundles().stream()
                .filter(bundle -> bundle.selected() && bundle.id().equals(expectedBundleId))
                .flatMap(bundle -> bundle.contributions().stream())
                .map(contribution -> contribution.providerClass())
                .map(providerClass -> {
                    try {
                        return codeSource(Class.forName(providerClass, false, loader));
                    } catch (Exception failure) {
                        throw new IllegalStateException(
                                "Cannot resolve provider source for " + providerClass, failure);
                    }
                })
                .distinct()
                .toList();
        if (providerSources.isEmpty()) {
            throw new IllegalStateException("Missing selected bundle mapping for "
                    + expectedBundleId);
        }
        boolean owned = false;
        for (Path providerSource : providerSources) {
            if (Files.isSameFile(actual, providerSource)) {
                owned = true;
                break;
            }
        }
        if (!owned) {
            throw new IllegalStateException("Dependency " + className
                    + " resolved outside its bundle: " + actual.getFileName());
        }
        return dependency;
    }

    private static Path codeSource(Class<?> type) throws Exception {
        var codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalStateException("Class has no code source: " + type.getName());
        }
        return Path.of(codeSource.getLocation().toURI());
    }
}
