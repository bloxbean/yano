package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;

/** Verified demo workspace and its public/secret boundary. */
public final class EutxoDemoWorkspace {
    static final String MARKER = "YANO_EUTXO_DEMO_WORKSPACE_V1\n";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path root;
    private final EutxoDemoManifest manifest;

    private EutxoDemoWorkspace(Path root, EutxoDemoManifest manifest) {
        this.root = root;
        this.manifest = manifest;
    }

    public static EutxoDemoWorkspace create(
            EutxoDemoOptions options,
            EutxoDemoScenarioProvider provider) throws IOException {
        Path root = safeRoot(options.workspace());
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root)) {
                throw new IllegalArgumentException("demo workspace cannot be a symbolic link");
            }
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalStateException(
                            "demo workspace must not already contain files");
                }
            }
        } else {
            Files.createDirectories(root);
        }
        Files.writeString(root.resolve(".yano-eutxo-demo"), MARKER,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        for (String directory : List.of(
                "project", "secrets/members", "secrets/cardano", "secrets/l2",
                "runtime/journal", "runtime/validity", "runtime/locks",
                "artifacts/l1", "artifacts/l2", "artifacts/proofs",
                "artifacts/reports")) {
            Path created = root.resolve(directory);
            if (directory.startsWith("secrets/")) {
                EutxoDemoIdentityService.ownerDirectory(created);
            } else {
                Files.createDirectories(created);
            }
        }
        List<String> memberKeys = new EutxoDemoIdentityService()
                .generateMembers(root.resolve("secrets/members"), options.members());
        EutxoDemoIdentityService identities = new EutxoDemoIdentityService();
        EutxoDemoIdentityService.WalletIdentity aliceWallet =
                identities.generateWallet(root.resolve("secrets/cardano/ledger.seed"));
        EutxoDemoIdentityService.WalletIdentity bobWallet =
                identities.generateWallet(root.resolve("secrets/cardano/recipient.seed"));
        EutxoDemoIdentityService.WalletIdentity bobPayoutWallet =
                identities.generateWallet(root.resolve("secrets/cardano/payout.seed"));
        EutxoDemoIdentityService.WalletIdentity operatorWallet =
                identities.generateWallet(root.resolve("secrets/cardano/operator.seed"));
        Map<String, String> secretReferences = new LinkedHashMap<>();
        for (int index = 0; index < options.members(); index++) {
            secretReferences.put("member" + index,
                    "secrets/members/node" + index + ".env");
        }
        secretReferences.put("ledgerWallet", "secrets/cardano/ledger.seed");
        secretReferences.put("recipientWallet", "secrets/cardano/recipient.seed");
        secretReferences.put("aliceWallet", "secrets/cardano/ledger.seed");
        secretReferences.put("bobWallet", "secrets/cardano/recipient.seed");
        secretReferences.put("bobPayoutWallet", "secrets/cardano/payout.seed");
        secretReferences.put("operatorWallet", "secrets/cardano/operator.seed");
        Map<String, String> publicIdentities = new LinkedHashMap<>();
        publicIdentities.put("trustBoundary", provider.trustBoundary());
        publicIdentities.put("ledgerAddress", aliceWallet.address());
        publicIdentities.put("ledgerPublicKey", aliceWallet.publicKey());
        publicIdentities.put("recipientAddress", bobWallet.address());
        publicIdentities.put("recipientPublicKey", bobWallet.publicKey());
        publicIdentities.put("aliceAddress", aliceWallet.address());
        publicIdentities.put("alicePublicKey", aliceWallet.publicKey());
        publicIdentities.put("bobAddress", bobWallet.address());
        publicIdentities.put("bobPublicKey", bobWallet.publicKey());
        publicIdentities.put("bobPayoutAddress", bobPayoutWallet.address());
        publicIdentities.put("bobPayoutPublicKey", bobPayoutWallet.publicKey());
        ScriptPubkey vaultScript;
        String vaultScriptHash;
        try {
            vaultScript = ScriptPubkey.create(
                    VerificationKey.create(HexFormat.of().parseHex(
                            operatorWallet.publicKey())));
            vaultScriptHash = vaultScript.getPolicyId();
        } catch (Exception failure) {
            throw new IOException("cannot create disposable demo vault identity", failure);
        }
        publicIdentities.put("operatorAddress", operatorWallet.address());
        publicIdentities.put("operatorPublicKey", operatorWallet.publicKey());
        publicIdentities.put("payoutAddress", bobPayoutWallet.address());
        publicIdentities.put("vaultAddress", AddressProvider.getEntAddress(
                vaultScript, Networks.testnet()).toBech32());
        publicIdentities.put("vaultScriptHash", vaultScriptHash);
        String implementationVersion = EutxoDemoWorkspace.class.getPackage()
                .getImplementationVersion();
        EutxoDemoManifest manifest = new EutxoDemoManifest(
                1,
                implementationVersion == null ? "development" : implementationVersion,
                provider.id(),
                provider.version(),
                provider.getClass().getName(),
                provider.maturity(),
                "devnet",
                options.name(),
                options.chainId(),
                options.members(),
                options.httpPortBase(),
                options.serverPortBase(),
                memberKeys,
                publicIdentities,
                secretReferences,
                Instant.now().toString());
        writeManifest(root, manifest);
        Files.writeString(root.resolve(".gitignore"),
                "secrets/\nruntime/\nproject/data/\nproject/logs/\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new EutxoDemoWorkspace(root, manifest);
    }

    public static EutxoDemoWorkspace open(Path requested) throws IOException {
        Path root = safeRoot(requested);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("demo workspace does not exist");
        }
        Path marker = root.resolve(".yano-eutxo-demo");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !MARKER.equals(Files.readString(marker))) {
            throw new IllegalArgumentException(
                    "directory is not a verified Yano EUTxO demo workspace");
        }
        EutxoDemoManifest manifest =
                YAML.readValue(root.resolve("demo.yaml").toFile(), EutxoDemoManifest.class);
        if (manifest.schemaVersion() != 1 || !"devnet".equals(manifest.network())) {
            throw new IllegalStateException("unsupported EUTxO demo manifest");
        }
        return new EutxoDemoWorkspace(root, manifest);
    }

    public Path root() {
        return root;
    }

    public Path project() {
        return root.resolve("project");
    }

    public EutxoDemoManifest manifest() {
        return manifest;
    }

    public EutxoDemoJournal journal() {
        return new EutxoDemoJournal(root);
    }

    public byte[] readSecretSeed(String referenceName) throws IOException {
        String relative = manifest.secretReferences().get(referenceName);
        if (relative == null || !relative.matches("secrets/[a-z0-9/.-]+")) {
            throw new IllegalArgumentException("unknown demo secret reference");
        }
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root.resolve("secrets"))
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new IllegalStateException("demo secret reference is unsafe");
        }
        String value = Files.readString(path).trim();
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("demo secret seed is malformed");
        }
        return HexFormat.of().parseHex(value);
    }

    public void reset() throws IOException {
        rejectBroadDeletionTarget(root);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void writeManifest(Path root, EutxoDemoManifest manifest)
            throws IOException {
        Path target = root.resolve("demo.yaml");
        Path temporary = root.resolve("demo.yaml.tmp");
        YAML.writeValue(temporary.toFile(), manifest);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target);
        }
    }

    private static Path safeRoot(Path requested) {
        if (requested == null) {
            throw new IllegalArgumentException("demo workspace is required");
        }
        Path root = requested.toAbsolutePath().normalize();
        if (root.getParent() == null) {
            throw new IllegalArgumentException("demo workspace is too broad");
        }
        return root;
    }

    private static void rejectBroadDeletionTarget(Path root) {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path current = Path.of("").toAbsolutePath().normalize();
        String configured = System.getenv("YANO_HOME");
        Path yanoHome = configured == null || configured.isBlank()
                ? null : Path.of(configured).toAbsolutePath().normalize();
        if (root.equals(home) || root.equals(current)
                || (yanoHome != null && root.equals(yanoHome))
                || root.getParent() == null) {
            throw new IllegalArgumentException("refusing to reset a broad directory");
        }
    }
}
