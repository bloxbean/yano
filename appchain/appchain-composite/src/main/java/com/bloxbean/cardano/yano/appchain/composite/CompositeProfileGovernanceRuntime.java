package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeGovernanceStatusV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileEpochV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map;

/** Deterministic authenticated state protocol for ADR-015 profile epochs. */
final class CompositeProfileGovernanceRuntime {
    private static final int PROPOSAL_CODEC_VERSION = 1;
    private static final int MAX_PROPOSAL_STATE_BYTES = 72 * 1024;
    private static final int DRAIN_CODEC_VERSION = 1;
    private static final int MAX_RETIRED_DRAINS = 256;
    private static final CborStructurePreflight.Limits COMMAND_LIMITS =
            new CborStructurePreflight.Limits(
                    CompositeProfileGovernanceV1.MAX_COMMAND_BYTES, 4, 24, 12,
                    CompositeProfileGovernanceV1.MAX_CHUNK_BYTES);

    private final String chainId;
    private final CompositeGovernanceConfig config;
    private final AppChainMembershipView membership;
    private final CompositeProfileCatalog catalog;
    private final int frameworkMaxEffects;
    private final byte[] initialProfile;
    private volatile Map<String, Object> cachedOperationalStatus = Map.of("mode", "governed");

    CompositeProfileGovernanceRuntime(String chainId,
                                      CompositeGovernanceConfig config,
                                      AppChainMembershipView membership,
                                      CompositeProfileCatalog catalog,
                                      int frameworkMaxEffects,
                                      byte[] initialProfile) {
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.config = Objects.requireNonNull(config, "config");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.frameworkMaxEffects = frameworkMaxEffects;
        this.initialProfile = Objects.requireNonNull(initialProfile, "initialProfile").clone();
    }

    void init(AppStateReader state) {
        Optional<byte[]> marker = state.get(CompositeStateKeys.profileMarkerKey());
        if (marker.isEmpty()) {
            byte[] root = state.stateRoot();
            if (root == null || root.length != 32 || !Arrays.equals(root, new byte[32])) {
                throw new IllegalStateException(
                        "governed composite marker is absent from retained state");
            }
            captureOperationalStatus(state, state.committedHeight());
            return;
        }
        verifyConfig(state);
        long epochNumber = currentEpoch(state);
        CompositeProfileEpochV1 epoch = epoch(state, epochNumber);
        if (!Arrays.equals(marker.get(), epoch.canonicalProfileBytes())) {
            throw new IllegalStateException("active composite marker disagrees with epoch history");
        }
        catalog.require(epoch.profileDigest());
        verifyEpochChain(state, epochNumber, epoch);
        for (long number = 0; number <= epochNumber; number++) {
            catalog.require(epoch(state, number).profileDigest());
        }
        Proposal proposal = readProposal(state).orElse(null);
        if (proposal != null && proposal.status == Status.SCHEDULED) {
            catalog.require(proposal.begin.targetProfileDigest());
        }
        for (Drain drain : readDrains(state)) {
            if (!catalog.componentProducts().containsKey(drain.generation())) {
                throw new IllegalStateException(
                        "retired composite generation is absent from executable catalog: "
                                + drain.generation());
            }
        }
        captureOperationalStatus(state, state.committedHeight());
    }

    byte[] profileForCandidateHeight(long blockHeight, AppStateReader state) {
        Objects.requireNonNull(state, "state");
        Optional<byte[]> marker = state.get(CompositeStateKeys.profileMarkerKey());
        if (marker.isEmpty()) {
            if (blockHeight != 1) {
                throw new IllegalStateException(
                        "governed composite marker is absent after genesis");
            }
            return initialProfile.clone();
        }
        verifyConfig(state);
        Proposal proposal = readProposal(state).orElse(null);
        if (proposal == null || proposal.status != Status.SCHEDULED) {
            return marker.get();
        }
        AppChainMembershipEpoch currentMembership = membership.epochAt(blockHeight);
        AppChainMembershipEpoch activationMembership = membership.epochAt(
                proposal.begin.activationHeight());
        if (!Arrays.equals(currentMembership.digest(), proposal.begin.membershipEpochDigest())
                || !Arrays.equals(activationMembership.digest(),
                proposal.begin.membershipEpochDigest())
                || blockHeight > proposal.begin.expiryHeight()) {
            return marker.get();
        }
        if (blockHeight < proposal.begin.activationHeight()) {
            return marker.get();
        }
        if (blockHeight > proposal.begin.activationHeight()) {
            throw new IllegalStateException("scheduled composite activation height was skipped");
        }
        CompositeProfileCatalog.Entry entry = catalog.require(
                proposal.begin.targetProfileDigest());
        byte[] target = proposal.profileBytes();
        if (!Arrays.equals(entry.profile().canonicalBytes(), target)) {
            throw new IllegalStateException(
                    "catalog target bytes disagree with scheduled proposal");
        }
        return target;
    }

    byte[] ensureProfileForHeight(long blockHeight, AppStateWriter state) {
        Optional<byte[]> marker = state.get(CompositeStateKeys.profileMarkerKey());
        if (marker.isEmpty()) {
            if (blockHeight != 1) {
                throw new IllegalStateException("governed composite marker is absent after genesis");
            }
            state.put(CompositeStateKeys.profileMarkerKey(), initialProfile.clone());
            state.put(CompositeStateKeys.governanceConfigKey(), config.canonicalBytes());
            state.put(CompositeStateKeys.currentProfileEpochKey(), encodeLong(0));
            state.put(CompositeStateKeys.profileEpochKey(0), new CompositeProfileEpochV1(
                    1, 0, 1, new byte[32], initialProfile, new byte[32]).encode());
            return initialProfile.clone();
        }
        verifyConfig(state);
        List<Drain> storedDrains = readDrains(state);
        List<Drain> drains = pruneDrains(storedDrains, blockHeight);
        writeDrainsIfChanged(state, storedDrains, drains);
        Proposal proposal = readProposal(state).orElse(null);
        if (proposal == null) {
            return marker.get();
        }
        AppChainMembershipEpoch currentMembership = membership.epochAt(blockHeight);
        AppChainMembershipEpoch activationMembership = membership.epochAt(
                proposal.begin.activationHeight());
        if (!Arrays.equals(currentMembership.digest(), proposal.begin.membershipEpochDigest())
                || !Arrays.equals(activationMembership.digest(),
                proposal.begin.membershipEpochDigest())
                || blockHeight > proposal.begin.expiryHeight()) {
            state.delete(CompositeStateKeys.activeProposalKey());
            return marker.get();
        }
        if (proposal.status != Status.SCHEDULED) {
            return marker.get();
        }
        long activation = proposal.begin.activationHeight();
        if (blockHeight < activation) {
            return marker.get();
        }
        if (blockHeight > activation) {
            throw new IllegalStateException("scheduled composite activation height was skipped");
        }
        byte[] target = proposal.profileBytes();
        CompositeProfileCatalog.Entry entry = catalog.require(proposal.begin.targetProfileDigest());
        if (!Arrays.equals(entry.profile().canonicalBytes(), target)) {
            throw new IllegalStateException("catalog target bytes disagree with scheduled proposal");
        }
        long currentEpoch = currentEpoch(state);
        if (currentEpoch + 1 >= config.maximumEpochs()) {
            throw new IllegalStateException("composite profile epoch bound is exhausted");
        }
        byte[] previousDigest = CompositeCommitmentV1.profileDigest(marker.get());
        CompositeProfileEpochV1 next = new CompositeProfileEpochV1(
                1, currentEpoch + 1, activation, previousDigest, target, proposal.proposalHash);
        state.put(CompositeStateKeys.profileEpochKey(currentEpoch + 1), next.encode());
        state.put(CompositeStateKeys.currentProfileEpochKey(), encodeLong(currentEpoch + 1));
        state.put(CompositeStateKeys.profileMarkerKey(), target);
        List<Drain> nextDrains = transitionDrains(
                CompositeProfileCodec.decode(marker.get()), entry.profile(), activation, drains);
        validateTransitionQuota(entry.profile(), activation, nextDrains);
        writeDrains(state, nextDrains);
        state.delete(CompositeStateKeys.activeProposalKey());
        return target;
    }

    void processCommands(AppBlock block, AppStateWriter state) {
        Proposal proposal = readProposal(state).orElse(null);
        AppChainMembershipEpoch epoch = membership.epochAt(block.height());
        if (proposal != null && (!Arrays.equals(
                proposal.begin.membershipEpochDigest(), epoch.digest())
                || !Arrays.equals(proposal.begin.membershipEpochDigest(),
                membership.epochAt(proposal.begin.activationHeight()).digest()))) {
            state.delete(CompositeStateKeys.activeProposalKey());
            proposal = null;
        }
        for (AppMessage message : block.messages()) {
            if (!CompositeProfileGovernanceV1.TOPIC.equals(message.getTopic())) {
                continue;
            }
            CompositeProfileGovernanceV1.Command command = decode(message.getBody());
            if (command == null) {
                continue;
            }
            String sender = sender(message);
            proposal = applyCommand(block.height(), state, epoch, proposal, sender, command);
        }
        if (proposal == null) {
            state.delete(CompositeStateKeys.activeProposalKey());
        } else {
            state.put(CompositeStateKeys.activeProposalKey(), proposal.encode());
        }
    }

    boolean permitsLocalSubmission(byte[] body) {
        CompositeProfileGovernanceV1.Command command = decode(body);
        if (command == null) return false;
        if (command instanceof CompositeProfileGovernanceV1.Begin begin) {
            return catalog.find(begin.targetProfileDigest()).isPresent();
        }
        if (command instanceof CompositeProfileGovernanceV1.Ready ready) {
            return catalog.find(ready.targetProfileDigest()).isPresent();
        }
        return true;
    }

    byte[] queryEpoch(byte[] params, AppStateReader state) {
        long number;
        if (params == null || params.length == 0) {
            number = currentEpoch(state);
        } else if (params.length == Long.BYTES) {
            number = ByteBuffer.wrap(params).getLong();
            if (number < 0 || number > currentEpoch(state)) {
                throw new com.bloxbean.cardano.yano.api.appchain.AppQueryException(
                        com.bloxbean.cardano.yano.api.appchain.AppQueryException.Code.INVALID_REQUEST,
                        "profile epoch is outside retained history");
            }
        } else {
            throw new com.bloxbean.cardano.yano.api.appchain.AppQueryException(
                    com.bloxbean.cardano.yano.api.appchain.AppQueryException.Code.INVALID_REQUEST,
                    "profile epoch query expects zero or eight bytes");
        }
        return epoch(state, number).encode();
    }

    byte[] queryStatus(AppStateReader state) {
        long number = currentEpoch(state);
        CompositeProfileEpochV1 active = epoch(state, number);
        Proposal staged = readProposal(state).orElse(null);
        CompositeGovernanceStatusV1.Proposal proposal = staged == null ? null
                : new CompositeGovernanceStatusV1.Proposal(
                staged.status.ordinal(), staged.begin.proposalId(),
                staged.proposalHash != null ? staged.proposalHash : new byte[0],
                staged.begin.targetProfileDigest(), staged.begin.membershipEpochDigest(),
                staged.begin.activationHeight(), staged.begin.expiryHeight(),
                staged.approvals.stream().sorted().toList(),
                staged.readiness.stream().sorted().toList(),
                staged.cancellations.stream().sorted().toList());
        List<CompositeGovernanceStatusV1.Drain> drains = readDrains(state).stream()
                .map(drain -> new CompositeGovernanceStatusV1.Drain(
                        drain.generation().componentId(), drain.generation().semanticVersion(),
                        drain.generation().fromHeight(), drain.quota(), drain.throughHeight()))
                .toList();
        return new CompositeGovernanceStatusV1(CompositeGovernanceStatusV1.VERSION,
                number, active.fromHeight(), active.profileDigest(), proposal, drains).encode();
    }

    void captureOperationalStatus(AppStateReader state, long committedHeight) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("mode", "governed");
        result.put("catalogDigests", catalog.entries().stream()
                .map(entry -> HexFormat.of().formatHex(entry.digest())).sorted().toList());
        AppChainMembershipEpoch currentMembership = membership.epochAt(
                Math.max(0, committedHeight));
        result.put("currentMembershipFromHeight", currentMembership.fromHeight());
        result.put("currentMembershipDigest",
                HexFormat.of().formatHex(currentMembership.digest()));
        result.put("currentMembershipThreshold", currentMembership.threshold());
        result.put("currentMembershipMembers", currentMembership.members());
        if (state != null && state.get(CompositeStateKeys.profileMarkerKey()).isPresent()) {
            long number = currentEpoch(state);
            CompositeProfileEpochV1 active = epoch(state, number);
            result.put("currentEpoch", number);
            result.put("activeFromHeight", active.fromHeight());
            result.put("activeProfileDigest", HexFormat.of().formatHex(active.profileDigest()));
            result.put("catalogReady", catalog.find(active.profileDigest()).isPresent());
            readProposal(state).ifPresent(proposal -> {
                result.put("proposalStatus", proposal.status.name());
                result.put("targetProfileDigest",
                        HexFormat.of().formatHex(proposal.begin.targetProfileDigest()));
                result.put("activationHeight", proposal.begin.activationHeight());
                result.put("expiryHeight", proposal.begin.expiryHeight());
                result.put("activationMembershipDigest", HexFormat.of().formatHex(
                        proposal.begin.membershipEpochDigest()));
                result.put("approvals", proposal.approvals.size());
                result.put("readiness", proposal.readiness.size());
                result.put("locallyReady", catalog.find(
                        proposal.begin.targetProfileDigest()).isPresent());
            });
            result.put("retiredDrains", readDrains(state).size());
        } else {
            byte[] initialDigest = CompositeCommitmentV1.profileDigest(initialProfile);
            result.put("currentEpoch", 0L);
            result.put("activeFromHeight", 1L);
            result.put("activeProfileDigest", HexFormat.of().formatHex(initialDigest));
            result.put("catalogReady", catalog.find(initialDigest).isPresent());
            result.put("retiredDrains", 0);
        }
        cachedOperationalStatus = Map.copyOf(result);
    }

    Map<String, Object> operationalStatus() {
        return cachedOperationalStatus;
    }

    private Proposal applyCommand(long height,
                                  AppStateReader state,
                                  AppChainMembershipEpoch epoch,
                                  Proposal proposal,
                                  String sender,
                                  CompositeProfileGovernanceV1.Command command) {
        if (!epoch.members().contains(sender)) {
            return proposal;
        }
        if (command instanceof CompositeProfileGovernanceV1.Begin begin) {
            if (proposal != null || !Arrays.equals(begin.membershipEpochDigest(), epoch.digest())
                    || !Arrays.equals(begin.membershipEpochDigest(),
                    membership.epochAt(begin.activationHeight()).digest())
                    || !Arrays.equals(begin.baseProfileDigest(), activeDigest(state))
                    || begin.activationHeight() < safeAdd(height, config.minimumActivationLag())
                    || begin.expiryHeight() > safeAdd(height, config.proposalTtlBlocks())) {
                return proposal;
            }
            return Proposal.begin(begin, sender);
        }
        if (proposal == null || height > proposal.begin.expiryHeight()) {
            return null;
        }
        if (command instanceof CompositeProfileGovernanceV1.Chunk chunk) {
            if (proposal.status != Status.STAGING || !proposal.author.equals(sender)
                    || !Arrays.equals(chunk.proposalId(), proposal.begin.proposalId())
                    || chunk.index() >= proposal.begin.chunkCount()) {
                return proposal;
            }
            byte[] existing = proposal.chunks.get(chunk.index());
            if (existing != null && !Arrays.equals(existing, chunk.bytes())) {
                return null;
            }
            proposal.chunks.set(chunk.index(), chunk.bytes());
            if (proposal.chunkBytes() > proposal.begin.totalBytes()) return null;
            return proposal;
        }
        if (command instanceof CompositeProfileGovernanceV1.Seal seal) {
            if (proposal.status != Status.STAGING || !proposal.author.equals(sender)
                    || !Arrays.equals(seal.proposalId(), proposal.begin.proposalId())
                    || !proposal.complete()) {
                return proposal;
            }
            byte[] profileBytes = proposal.profileBytes();
            if (profileBytes.length != proposal.begin.totalBytes()
                    || !Arrays.equals(CompositeCommitmentV1.profileDigest(profileBytes),
                    proposal.begin.targetProfileDigest())) {
                return null;
            }
            CompositeProfile base;
            CompositeProfile target;
            try {
                base = CompositeProfileCodec.decode(activeProfile(state));
                target = CompositeProfileCodec.decode(profileBytes);
                validateTransition(state, base, target, proposal.begin.activationHeight());
                if (proposal.begin.activationHeight()
                        < safeAdd(height, config.minimumActivationLag())) {
                    return null;
                }
            } catch (IllegalArgumentException invalid) {
                return null;
            }
            proposal.proposalHash = CompositeProfileGovernanceV1.proposalHash(chainId, proposal.begin);
            proposal.status = Status.SEALED;
            return proposal;
        }
        if (proposal.status == Status.STAGING
                || proposal.proposalHash == null) {
            return proposal;
        }
        if (command instanceof CompositeProfileGovernanceV1.Approve approve) {
            if (Arrays.equals(approve.proposalHash(), proposal.proposalHash)) {
                proposal.approvals.add(sender);
            }
        } else if (command instanceof CompositeProfileGovernanceV1.Ready ready) {
            if (Arrays.equals(ready.proposalHash(), proposal.proposalHash)
                    && Arrays.equals(ready.targetProfileDigest(),
                    proposal.begin.targetProfileDigest())) {
                proposal.readiness.add(sender);
            }
        } else if (command instanceof CompositeProfileGovernanceV1.Cancel cancel
                && Arrays.equals(cancel.proposalHash(), proposal.proposalHash)) {
            proposal.cancellations.add(sender);
        }
        if (proposal.cancellations.size() >= epoch.threshold()) {
            return null;
        }
        if (proposal.approvals.size() >= epoch.threshold()
                && proposal.readiness.containsAll(epoch.members())) {
            if (height >= proposal.begin.activationHeight()) {
                return null;
            }
            proposal.status = Status.SCHEDULED;
        }
        return proposal;
    }

    private void validateTransition(AppStateReader state,
                                    CompositeProfile base,
                                    CompositeProfile target,
                                    long activationHeight) {
        if (Arrays.equals(base.digest(), target.digest())) {
            throw new IllegalArgumentException("target profile is unchanged");
        }
        for (ComponentDescriptor oldDescriptor : base.components()) {
            target.components().stream()
                    .filter(candidate -> candidate.componentId().equals(oldDescriptor.componentId()))
                    .forEach(candidate -> {
                        if (!candidate.stateAndResultCompatibilityId().equals(
                                oldDescriptor.stateAndResultCompatibilityId())) {
                            throw new IllegalArgumentException(
                                    "component state/result compatibility changed");
                        }
                        if (candidate.generation().equals(oldDescriptor.generation())
                                && !candidate.equals(oldDescriptor)) {
                            throw new IllegalArgumentException(
                                    "existing component generation descriptor changed");
                        }
                    });
        }
        target.validateGovernedEffectBudget(frameworkMaxEffects, config.resultDrainBlocks());
        List<Drain> drains = transitionDrains(base, target, activationHeight,
                pruneDrains(readDrains(state), activationHeight));
        validateTransitionQuota(target, activationHeight, drains);
    }

    private List<Drain> transitionDrains(CompositeProfile base,
                                         CompositeProfile target,
                                         long activationHeight,
                                         List<Drain> existing) {
        java.util.LinkedHashMap<ComponentGeneration, Drain> drains = new java.util.LinkedHashMap<>();
        existing.forEach(drain -> drains.put(drain.generation(), drain));
        Set<ComponentGeneration> targetGenerations = target.components().stream()
                .map(ComponentDescriptor::generation).collect(java.util.stream.Collectors.toSet());
        long through = safeAdd(activationHeight, config.resultDrainBlocks());
        base.components().stream()
                .filter(component -> component.fromHeight() <= activationHeight)
                .filter(component -> component.untilHeight() == 0
                        || activationHeight <= safeAdd(
                        component.untilHeight(), config.resultDrainBlocks()))
                .filter(component -> !targetGenerations.contains(component.generation()))
                .forEach(component -> drains.merge(component.generation(),
                        new Drain(component.generation(), component.maxEffectsPerBlock(), through),
                        (left, right) -> new Drain(left.generation(),
                                Math.max(left.quota(), right.quota()),
                                Math.max(left.throughHeight(), right.throughHeight()))));
        targetGenerations.forEach(drains::remove);
        if (drains.size() > MAX_RETIRED_DRAINS) {
            throw new IllegalArgumentException("retired composite generation drain bound exceeded");
        }
        return drains.values().stream()
                .sorted(java.util.Comparator.<Drain, String>comparing(
                                drain -> drain.generation().componentId())
                        .thenComparing(drain -> drain.generation().semanticVersion())
                        .thenComparingLong(drain -> drain.generation().fromHeight()))
                .toList();
    }

    private void validateTransitionQuota(CompositeProfile target,
                                         long activationHeight,
                                         List<Drain> drains) {
        Set<Long> boundaries = new java.util.TreeSet<>();
        boundaries.add(activationHeight);
        long maximumDrain = activationHeight;
        for (Drain drain : drains) {
            maximumDrain = Math.max(maximumDrain, drain.throughHeight());
            boundaries.add(safeAdd(drain.throughHeight(), 1));
        }
        for (ComponentDescriptor component : target.components()) {
            addBoundary(boundaries, component.fromHeight(), activationHeight, maximumDrain);
            if (component.untilHeight() != 0) {
                addBoundary(boundaries, component.untilHeight(), activationHeight, maximumDrain);
                addBoundary(boundaries,
                        safeAdd(component.untilHeight(), config.resultDrainBlocks() + 1),
                        activationHeight, maximumDrain);
            }
        }
        for (WorkflowDescriptor workflow : target.workflows()) {
            addBoundary(boundaries, workflow.fromHeight(), activationHeight, maximumDrain);
            if (workflow.untilHeight() != 0) {
                addBoundary(boundaries, workflow.untilHeight(), activationHeight, maximumDrain);
            }
        }
        for (long height : boundaries) {
            long retained = drains.stream()
                    .filter(drain -> height <= drain.throughHeight())
                    .mapToLong(Drain::quota).sum();
            long required = retained + target.requiredGovernedEffectQuotaAt(
                    height, config.resultDrainBlocks());
            if (required > frameworkMaxEffects) {
                throw new IllegalArgumentException(
                        "transition effect quota exceeds framework cap at height " + height);
            }
        }
    }

    private static void addBoundary(Set<Long> boundaries,
                                    long value,
                                    long minimum,
                                    long maximum) {
        if (value >= minimum && value <= maximum) boundaries.add(value);
    }

    private static List<Drain> pruneDrains(List<Drain> drains, long height) {
        return drains.stream().filter(drain -> height <= drain.throughHeight()).toList();
    }

    private static List<Drain> readDrains(AppStateReader state) {
        return state.get(CompositeStateKeys.retiredGenerationDrainsKey())
                .map(CompositeProfileGovernanceRuntime::decodeDrains).orElse(List.of());
    }

    private static void writeDrainsIfChanged(AppStateWriter state,
                                             List<Drain> before,
                                             List<Drain> after) {
        if (!before.equals(after)) writeDrains(state, after);
    }

    private static void writeDrains(AppStateWriter state, List<Drain> drains) {
        if (drains.isEmpty()) {
            state.delete(CompositeStateKeys.retiredGenerationDrainsKey());
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(DRAIN_CODEC_VERSION);
            out.writeInt(drains.size());
            for (Drain drain : drains) {
                writeBytes(out, CompositeStateKeys.encodeGeneration(drain.generation()));
                out.writeInt(drain.quota());
                out.writeLong(drain.throughHeight());
            }
            out.flush();
            state.put(CompositeStateKeys.retiredGenerationDrainsKey(), bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory drain encoding failed", impossible);
        }
    }

    private static List<Drain> decodeDrains(byte[] encoded) {
        if (encoded == null || encoded.length < 8 || encoded.length > 256 * 1024) {
            throw new IllegalStateException("malformed retired composite drains");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != DRAIN_CODEC_VERSION) throw new IOException("version");
            int count = in.readInt();
            if (count < 1 || count > MAX_RETIRED_DRAINS) throw new IOException("count");
            List<Drain> drains = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                ComponentGeneration generation = CompositeStateKeys.decodeGeneration(
                        readBoundedBytes(in, 512));
                int quota = in.readInt();
                long through = in.readLong();
                if (quota < 0 || through < 1) throw new IOException("drain");
                drains.add(new Drain(generation, quota, through));
            }
            List<Drain> canonical = drains.stream()
                    .sorted(java.util.Comparator.<Drain, String>comparing(
                                    drain -> drain.generation().componentId())
                            .thenComparing(drain -> drain.generation().semanticVersion())
                            .thenComparingLong(drain -> drain.generation().fromHeight()))
                    .toList();
            if (in.available() != 0 || !canonical.equals(drains)
                    || drains.stream().map(Drain::generation).distinct().count() != drains.size()) {
                throw new IOException("canonical");
            }
            return canonical;
        } catch (IOException | RuntimeException malformed) {
            throw new IllegalStateException("malformed retired composite drains", malformed);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBoundedBytes(DataInputStream in, int maximum) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > maximum || size > in.available()) {
            throw new IOException("invalid bounded bytes");
        }
        return in.readNBytes(size);
    }

    private record Drain(ComponentGeneration generation, int quota, long throughHeight) {
    }

    private CompositeProfileGovernanceV1.Command decode(byte[] body) {
        if (!CborStructurePreflight.accepts(body, COMMAND_LIMITS)) return null;
        try {
            return CompositeProfileGovernanceV1.decode(body);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private void verifyConfig(AppStateReader state) {
        byte[] retained = state.get(CompositeStateKeys.governanceConfigKey()).orElseThrow(() ->
                new IllegalStateException("governed composite config marker is absent"));
        if (!Arrays.equals(retained, config.canonicalBytes())) {
            throw new IllegalStateException("governed composite config marker mismatch");
        }
    }

    private void verifyEpochChain(AppStateReader state,
                                  long epochNumber,
                                  CompositeProfileEpochV1 tail) {
        CompositeProfileEpochV1 previous = null;
        for (long number = 0; number <= epochNumber; number++) {
            CompositeProfileEpochV1 current = number == epochNumber ? tail : epoch(state, number);
            if (current.epochNumber() != number) {
                throw new IllegalStateException("composite epoch number mismatch");
            }
            if (number == 0) {
                if (current.fromHeight() != 1
                        || !Arrays.equals(current.canonicalProfileBytes(), initialProfile)) {
                    throw new IllegalStateException("governed composite genesis profile mismatch");
                }
            } else if (previous == null
                    || previous.fromHeight() >= current.fromHeight()
                    || !Arrays.equals(previous.profileDigest(), current.previousProfileDigest())
                    || allZero(current.proposalHash())) {
                throw new IllegalStateException(
                        "composite profile epoch chain is not contiguous");
            }
            previous = current;
        }
    }

    private static boolean allZero(byte[] value) {
        int aggregate = 0;
        for (byte item : value) aggregate |= item;
        return aggregate == 0;
    }

    private static CompositeProfileEpochV1 epoch(AppStateReader state, long number) {
        byte[] encoded = state.get(CompositeStateKeys.profileEpochKey(number)).orElseThrow(() ->
                new IllegalStateException("composite profile epoch is absent: " + number));
        return CompositeProfileEpochV1.decode(encoded);
    }

    private static long currentEpoch(AppStateReader state) {
        byte[] encoded = state.get(CompositeStateKeys.currentProfileEpochKey()).orElseThrow(() ->
                new IllegalStateException("current composite epoch pointer is absent"));
        if (encoded.length != Long.BYTES || ByteBuffer.wrap(encoded).getLong() < 0) {
            throw new IllegalStateException("current composite epoch pointer is malformed");
        }
        return ByteBuffer.wrap(encoded).getLong();
    }

    private static byte[] activeProfile(AppStateReader state) {
        return state.get(CompositeStateKeys.profileMarkerKey()).orElseThrow(() ->
                new IllegalStateException("active composite marker is absent"));
    }

    private static byte[] activeDigest(AppStateReader state) {
        return CompositeCommitmentV1.profileDigest(activeProfile(state));
    }

    private static Optional<Proposal> readProposal(AppStateReader state) {
        return state.get(CompositeStateKeys.activeProposalKey()).map(Proposal::decode);
    }

    private static String sender(AppMessage message) {
        byte[] sender = message.getSender();
        return sender != null && sender.length == 32
                ? HexFormat.of().formatHex(sender) : "";
    }

    private static long safeAdd(long left, int right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static byte[] encodeLong(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private enum Status { STAGING, SEALED, SCHEDULED }

    private static final class Proposal {
        private final CompositeProfileGovernanceV1.Begin begin;
        private final String author;
        private final List<byte[]> chunks;
        private final Set<String> approvals;
        private final Set<String> readiness;
        private final Set<String> cancellations;
        private Status status;
        private byte[] proposalHash;

        private Proposal(CompositeProfileGovernanceV1.Begin begin,
                         String author,
                         List<byte[]> chunks,
                         Status status,
                         byte[] proposalHash,
                         Set<String> approvals,
                         Set<String> readiness,
                         Set<String> cancellations) {
            this.begin = begin;
            this.author = author;
            this.chunks = chunks;
            this.status = status;
            this.proposalHash = proposalHash;
            this.approvals = approvals;
            this.readiness = readiness;
            this.cancellations = cancellations;
        }

        static Proposal begin(CompositeProfileGovernanceV1.Begin begin, String author) {
            return new Proposal(begin, author,
                    new ArrayList<>(java.util.Collections.nCopies(begin.chunkCount(), null)),
                    Status.STAGING, null, new LinkedHashSet<>(),
                    new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        boolean complete() { return chunks.stream().allMatch(Objects::nonNull); }

        int chunkBytes() {
            return chunks.stream().filter(Objects::nonNull).mapToInt(value -> value.length).sum();
        }

        byte[] profileBytes() {
            if (!complete()) throw new IllegalStateException("profile chunks are incomplete");
            ByteArrayOutputStream out = new ByteArrayOutputStream(begin.totalBytes());
            chunks.forEach(out::writeBytes);
            return out.toByteArray();
        }

        byte[] encode() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(PROPOSAL_CODEC_VERSION);
                writeBytes(out, begin.encode());
                writeBytes(out, HexFormat.of().parseHex(author));
                out.writeInt(status.ordinal());
                writeBytes(out, proposalHash != null ? proposalHash : new byte[0]);
                out.writeInt(chunks.size());
                for (byte[] chunk : chunks) writeBytes(out, chunk != null ? chunk : new byte[0]);
                writeSet(out, approvals);
                writeSet(out, readiness);
                writeSet(out, cancellations);
                out.flush();
                byte[] encoded = bytes.toByteArray();
                if (encoded.length > MAX_PROPOSAL_STATE_BYTES) throw malformed();
                return encoded;
            } catch (IOException impossible) {
                throw new IllegalStateException("in-memory proposal encoding failed", impossible);
            }
        }

        static Proposal decode(byte[] encoded) {
            if (encoded == null || encoded.length == 0 || encoded.length > MAX_PROPOSAL_STATE_BYTES) {
                throw malformed();
            }
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
                if (in.readInt() != PROPOSAL_CODEC_VERSION) throw malformed();
                CompositeProfileGovernanceV1.Command decoded =
                        CompositeProfileGovernanceV1.decode(readBytes(in, 512));
                if (!(decoded instanceof CompositeProfileGovernanceV1.Begin begin)) throw malformed();
                String author = HexFormat.of().formatHex(readExact(in, 32));
                int statusValue = in.readInt();
                if (statusValue < 0 || statusValue >= Status.values().length) throw malformed();
                byte[] hash = readBytes(in, 32);
                if (hash.length != 0 && hash.length != 32) throw malformed();
                int chunkCount = in.readInt();
                if (chunkCount != begin.chunkCount()) throw malformed();
                List<byte[]> chunks = new ArrayList<>(chunkCount);
                for (int index = 0; index < chunkCount; index++) {
                    byte[] chunk = readBytes(in, CompositeProfileGovernanceV1.MAX_CHUNK_BYTES);
                    chunks.add(chunk.length == 0 ? null : chunk);
                }
                Proposal proposal = new Proposal(begin, author, chunks,
                        Status.values()[statusValue], hash.length == 0 ? null : hash,
                        readSet(in), readSet(in), readSet(in));
                if (in.available() != 0 || !Arrays.equals(encoded, proposal.encode())) throw malformed();
                if (proposal.status != Status.STAGING && proposal.proposalHash == null) throw malformed();
                return proposal;
            } catch (IOException | RuntimeException malformed) {
                throw malformed();
            }
        }

        private static void writeSet(DataOutputStream out, Set<String> values) throws IOException {
            List<String> sorted = values.stream().sorted().toList();
            out.writeInt(sorted.size());
            for (String value : sorted) writeBytes(out, HexFormat.of().parseHex(value));
        }

        private static Set<String> readSet(DataInputStream in) throws IOException {
            int count = in.readInt();
            if (count < 0 || count > 32) throw malformed();
            Set<String> values = new LinkedHashSet<>();
            for (int index = 0; index < count; index++) {
                values.add(HexFormat.of().formatHex(readExact(in, 32)));
            }
            if (values.size() != count) throw malformed();
            return values;
        }

        private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
            out.writeInt(value.length);
            out.write(value);
        }

        private static byte[] readExact(DataInputStream in, int size) throws IOException {
            byte[] value = readBytes(in, size);
            if (value.length != size) throw malformed();
            return value;
        }

        private static byte[] readBytes(DataInputStream in, int maximum) throws IOException {
            int size = in.readInt();
            if (size < 0 || size > maximum || size > in.available()) throw malformed();
            return in.readNBytes(size);
        }

        private static IllegalArgumentException malformed() {
            return new IllegalArgumentException("malformed composite governance proposal state");
        }
    }
}
