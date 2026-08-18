package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.dataset.*;
import com.bloxbean.cardano.yano.archive.core.source.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Streams ephemeral epoch facts to immutable restartable source files. */
final class EpochArchiveStagingService implements EpochArchiveStagingSink {
    private final ChainQuery chain;
    private final LedgerQuery ledger;
    private final ArchiveNetworkIdentity network;
    private final Path root;
    private final Set<Dataset> enabled;
    private final Map<String, SourceBinding<?>> sources = new ConcurrentHashMap<>();
    private volatile Boundary boundary;
    private volatile String error;

    EpochArchiveStagingService(ChainQuery chain, LedgerQuery ledger, ArchiveNetworkIdentity network,
                               Path root, Set<Dataset> enabled) {
        this.chain = chain; this.ledger = ledger; this.network = network;
        this.root = root.toAbsolutePath().normalize(); this.enabled = Set.copyOf(enabled);
        try {
            Files.createDirectories(completedDirectory());
            try (var files = Files.list(completedDirectory())) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    if (path.getFileName().toString().endsWith(".tmp")) Files.deleteIfExists(path);
                }
            }
            if (Files.exists(failureMarker())) error = Files.readString(failureMarker(), StandardCharsets.UTF_8);
        }
        catch (Exception e) { throw new ArchiveStoreException("cannot create epoch completion directory", e); }
        registerKnownSources();
    }

    public boolean enabled(Dataset dataset) { return enabled.contains(dataset) && error == null; }
    public void beginBoundary(Boundary value) { boundary = value; }
    public void completeBoundary(Boundary value) {
        if (!Objects.equals(boundary, value)) return;
        try {
            if (enabled.isEmpty()) return;
            if (error == null) publishCompleted(value);
            else discardBoundary(value);
        } catch (Exception e) { fail(e); }
        finally { boundary = null; }
    }
    public void abortBoundary(Boundary value) {
        if (!Objects.equals(boundary, value)) return;
        // Core epoch phases commit independently. Preserve source parts from
        // already-committed phases so startup recovery can add the remaining
        // parts and publish one whole-boundary completion marker.
        try { Files.deleteIfExists(completion(value.blockNumber())); }
        catch (Exception e) { fail(e); }
        finally { boundary = null; }
    }
    Optional<String> error() { return Optional.ofNullable(error); }
    Collection<SourceBinding<?>> sources() { return List.copyOf(sources.values()); }

    List<EpochArchiveJob> pending(SourceBinding<?> binding, int limit) {
        return binding.source().pending(Integer.MAX_VALUE).stream().filter(this::isCompleted).limit(limit).toList();
    }

    void acknowledge(SourceBinding<?> binding, EpochArchiveJob job) {
        binding.source().acknowledge(job);
        boolean remaining = sources().stream().flatMap(source -> source.source().pending(Integer.MAX_VALUE).stream())
                .anyMatch(candidate -> candidate.boundaryBlockNumber() == job.boundaryBlockNumber());
        if (!remaining) try { Files.deleteIfExists(completion(job.boundaryBlockNumber())); }
        catch (Exception e) { throw new ArchiveStoreException("cannot clean epoch completion marker", e); }
    }

    int discardAfterEpoch(long epoch) {
        int discarded = 0;
        for (SourceBinding<?> binding : sources()) discarded += binding.source().discardAfterEpoch(epoch);
        try (var markers = Files.list(completedDirectory())) {
            for (Path marker : markers.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".properties")).toList()) {
                Properties values = readProperties(marker);
                if (Long.parseLong(values.getProperty("newEpoch")) > epoch) Files.deleteIfExists(marker);
            }
        } catch (Exception e) { throw new ArchiveStoreException("cannot discard epoch completion markers", e); }
        return discarded;
    }

    int discardAfterBlock(long block) {
        int discarded = 0;
        for (SourceBinding<?> binding : sources()) discarded += binding.source().discardAfterBlock(block);
        try (var markers = Files.list(completedDirectory())) {
            for (Path marker : markers.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".properties")).toList()) {
                Properties values = readProperties(marker);
                if (Long.parseLong(values.getProperty("block")) > block) Files.deleteIfExists(marker);
            }
        } catch (Exception e) { throw new ArchiveStoreException("cannot discard epoch completion markers", e); }
        return discarded;
    }

    public FactWriter<StakeFact> openStake(int epoch) {
        return writer(Dataset.EPOCH_STAKE, ArchiveDatasetId.EPOCH_STAKE, epoch, "snapshot",
                StandardEpochFactCodecs.EPOCH_STAKE, StandardEpochDatasets.epochStake(), fact ->
                        new EpochStakeFact(credentialType(fact.credentialType()), hex(fact.credentialHash()),
                                hex(fact.poolHash()), exact(fact.amount())));
    }
    public FactWriter<DrepFact> openDrep(int epoch) {
        return writer(Dataset.DREP_DISTRIBUTION, ArchiveDatasetId.DREP_DISTRIBUTION, epoch, "distribution",
                StandardEpochFactCodecs.DREP, StandardEpochDatasets.drepDistribution(), fact ->
                        new DrepDistributionFact(drepType(fact.drepType()),
                                fact.drepType() <= 1 ? nullableHex(fact.credentialHash()) : null,
                                exact(fact.amount()), fact.storedExpiry() == null ? null : fact.storedExpiry().longValue(),
                                fact.dormantEpochs(), fact.effectiveExpiry() == null ? null : fact.effectiveExpiry().longValue(),
                                fact.active()));
    }
    public FactWriter<AdaPotFact> openAdaPot(int epoch) {
        return writer(Dataset.ADA_POT, ArchiveDatasetId.ADA_POT, epoch, "final",
                StandardEpochFactCodecs.ADA_POT, StandardEpochDatasets.adaPot(), fact ->
                        new com.bloxbean.cardano.yano.archive.core.dataset.AdaPotFact(exact(fact.treasury()),
                                exact(fact.reserves()), exact(fact.deposits()), exact(fact.fees()),
                                exact(fact.distributed()), exact(fact.undistributed()), exact(fact.rewardsPot()),
                                exact(fact.poolRewardsPot())));
    }
    public FactWriter<GovernanceFact> openGovernance(int epoch, String part) {
        return writer(Dataset.GOVERNANCE_PROPOSAL_STATUS, ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                epoch, part, StandardEpochFactCodecs.GOVERNANCE, StandardEpochDatasets.governanceProposalStatus(),
                fact -> new GovernanceProposalStatusFact(hex(fact.txHash()), fact.governanceActionIndex(),
                        fact.actionType().toLowerCase(Locale.ROOT), fact.observationPhase().toLowerCase(Locale.ROOT),
                        fact.statusCode().toLowerCase(Locale.ROOT), fact.decisionReason(), exact(fact.deposit()),
                        hex(fact.returnAddress()), fact.submittedEpoch(), fact.expiresAfterEpoch()));
    }
    public FactWriter<RewardFact> openRewards(int epoch, String part) {
        return writer(Dataset.REWARD, ArchiveDatasetId.REWARD, epoch, part,
                StandardEpochFactCodecs.REWARD, StandardEpochDatasets.rewards(), fact ->
                        new com.bloxbean.cardano.yano.archive.core.dataset.RewardFact(hex(fact.credentialHash()),
                                credentialType(fact.credentialType()), nullableHex(fact.poolHash()),
                                fact.rewardType().toLowerCase(Locale.ROOT), fact.earnedEpoch(), fact.spendableEpoch(),
                                exact(fact.amount()), fact.sourceId()));
    }

    private <I, O> FactWriter<I> writer(Dataset selected, ArchiveDatasetId dataset, long epoch, String part,
                                         EpochFactCodec<O> codec, EpochArchiveDataset<O> projection,
                                         Function<I, O> converter) {
        if (!enabled(selected)) return noop();
        try {
            Boundary current = Objects.requireNonNull(boundary, "epoch boundary was not prepared");
            var canonical = chain.getCanonicalBlockReference(current.blockNumber()).orElseThrow(() ->
                    new ArchiveStoreException("canonical epoch boundary is unavailable at block " + current.blockNumber()));
            if (canonical.slot() != current.slot()) throw new ArchiveStoreException("epoch boundary slot mismatch");
            String key = dataset.name() + "/" + safePart(part);
            @SuppressWarnings("unchecked")
            SourceBinding<O> binding = (SourceBinding<O>) sources.computeIfAbsent(key,
                    ignored -> binding(key, dataset, codec, projection));
            String state = "ledger-boundary-v1/" + safePart(part);
            UUID id = UUID.nameUUIDFromBytes((network.canonicalForm() + '|' + dataset + '|' + epoch + '|'
                    + current.blockNumber() + '|' + HexUtil.encodeHexString(canonical.blockHash()) + '|' + state)
                    .getBytes(StandardCharsets.UTF_8));
            var job = new EpochArchiveJob(id, network, dataset, projection.projectionVersion(), epoch,
                    current.blockNumber(), current.slot(), ledger.slotToUnixTime(current.slot()),
                    canonical.blockHash(), state, key + "/" + epoch, Instant.now());
            var output = binding.source().open(job);
            return new FactWriter<>() {
                private boolean done;
                private boolean failed;
                public void append(I fact) {
                    if (done || failed) return;
                    try {
                        output.append(converter.apply(fact));
                    } catch (Exception e) {
                        failed = true;
                        output.close();
                        fail(e);
                    }
                }
                public void commit() {
                    if (done) return;
                    done = true;
                    if (failed) { output.close(); return; }
                    try { output.commit(); }
                    catch (Exception e) { output.close(); fail(e); }
                }
                public void abort() { if (!done) { done = true; output.close(); } }
            };
        } catch (Exception e) {
            fail(e); return noop();
        }
    }

    private void fail(Exception e) {
        error = e.getMessage() != null ? e.getMessage() : e.toString();
        Boundary current = boundary;
        if (current != null) try { discardBoundary(current); } catch (Exception ignored) { }
        try {
            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, "epoch-source-failure-", ".tmp");
            try {
                Files.writeString(temporary, error, StandardCharsets.UTF_8);
                move(temporary, failureMarker());
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception ignored) {
            // The in-memory error still keeps capture disabled for this run.
        }
    }
    private static <T> FactWriter<T> noop() { return new FactWriter<>() { public void append(T ignored) { } public void commit() { } }; }
    private static String safePart(String value) { if (value == null || !value.matches("[a-z0-9-]+")) throw new IllegalArgumentException("invalid epoch source part"); return value; }
    private static byte[] hex(String value) { return HexUtil.decodeHexString(Objects.requireNonNull(value)); }
    private static byte[] nullableHex(String value) { return value == null || value.isBlank() ? null : hex(value); }
    private static long exact(java.math.BigInteger value) { return Objects.requireNonNull(value).longValueExact(); }
    private static String credentialType(int type) { return type == 0 ? "key" : type == 1 ? "script" : "type_" + type; }
    private static String drepType(int type) { return switch (type) { case 0 -> "key"; case 1 -> "script"; case 2 -> "always_abstain"; case 3 -> "always_no_confidence"; default -> "type_" + type; }; }

    private void registerKnownSources() {
        if (enabled.contains(Dataset.EPOCH_STAKE)) register("EPOCH_STAKE/snapshot", ArchiveDatasetId.EPOCH_STAKE,
                StandardEpochFactCodecs.EPOCH_STAKE, StandardEpochDatasets.epochStake());
        if (enabled.contains(Dataset.DREP_DISTRIBUTION)) register("DREP_DISTRIBUTION/distribution",
                ArchiveDatasetId.DREP_DISTRIBUTION, StandardEpochFactCodecs.DREP,
                StandardEpochDatasets.drepDistribution());
        if (enabled.contains(Dataset.ADA_POT)) register("ADA_POT/final", ArchiveDatasetId.ADA_POT,
                StandardEpochFactCodecs.ADA_POT, StandardEpochDatasets.adaPot());
        if (enabled.contains(Dataset.GOVERNANCE_PROPOSAL_STATUS)) {
            register("GOVERNANCE_PROPOSAL_STATUS/lifecycle", ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                    StandardEpochFactCodecs.GOVERNANCE, StandardEpochDatasets.governanceProposalStatus());
            register("GOVERNANCE_PROPOSAL_STATUS/ratification", ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                    StandardEpochFactCodecs.GOVERNANCE, StandardEpochDatasets.governanceProposalStatus());
        }
        if (enabled.contains(Dataset.REWARD)) for (String part : List.of("rewards", "pool-reap", "governance", "mir")) {
            register("REWARD/" + part, ArchiveDatasetId.REWARD, StandardEpochFactCodecs.REWARD,
                    StandardEpochDatasets.rewards());
        }
    }

    private <T> void register(String key, ArchiveDatasetId dataset, EpochFactCodec<T> codec,
                              EpochArchiveDataset<T> projection) {
        sources.putIfAbsent(key, binding(key, dataset, codec, projection));
    }

    private <T> SourceBinding<T> binding(String key, ArchiveDatasetId dataset, EpochFactCodec<T> codec,
                                         EpochArchiveDataset<T> projection) {
        return new SourceBinding<>(new DurableEpochFileSource<>(dataset,
                root.resolve(key.toLowerCase(Locale.ROOT)), codec), projection);
    }

    private void publishCompleted(Boundary value) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("previousEpoch", Integer.toString(value.previousEpoch()));
        properties.setProperty("newEpoch", Integer.toString(value.newEpoch()));
        properties.setProperty("slot", Long.toString(value.slot()));
        properties.setProperty("block", Long.toString(value.blockNumber()));
        Path temporary = Files.createTempFile(completedDirectory(), Long.toString(value.blockNumber()), ".tmp");
        try {
            try (var out = Files.newOutputStream(temporary)) { properties.store(out, "completed epoch boundary"); }
            try { Files.move(temporary, completion(value.blockNumber()), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, completion(value.blockNumber()), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private void discardBoundary(Boundary value) {
        for (SourceBinding<?> binding : sources()) {
            for (EpochArchiveJob job : binding.source().pending(Integer.MAX_VALUE)) {
                if (job.boundaryBlockNumber() == value.blockNumber()) binding.source().acknowledge(job);
            }
        }
        try { Files.deleteIfExists(completion(value.blockNumber())); }
        catch (Exception e) { throw new ArchiveStoreException("cannot discard epoch boundary", e); }
    }

    private boolean isCompleted(EpochArchiveJob job) {
        Path marker = completion(job.boundaryBlockNumber());
        if (!Files.exists(marker)) return false;
        Properties values = readProperties(marker);
        return Long.parseLong(values.getProperty("block")) == job.boundaryBlockNumber()
                && Long.parseLong(values.getProperty("slot")) == job.boundarySlot();
    }

    private Properties readProperties(Path path) {
        try (var in = Files.newInputStream(path)) { Properties values = new Properties(); values.load(in); return values; }
        catch (Exception e) { throw new ArchiveStoreException("invalid epoch completion marker " + path, e); }
    }

    private Path completedDirectory() { return root.resolve("completed"); }
    private Path completion(long block) { return completedDirectory().resolve(block + ".properties"); }
    private Path failureMarker() { return root.resolve("FAILED"); }

    private static void move(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record SourceBinding<T>(DurableEpochFileSource<T> source, EpochArchiveDataset<T> projection) {
        ArchiveDatasetId dataset() {
            return projection.dataset();
        }
    }
}
