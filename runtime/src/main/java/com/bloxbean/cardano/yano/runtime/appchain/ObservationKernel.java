package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.AppObservationEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ExactValueQuorumPolicy;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificateVerifier;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationEvidenceVerifier;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationIntent;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationKeys;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReconciliationPolicy;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscription;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscriptionId;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscriptionStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTopics;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTick;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic ADR-037 observation scheduler and result interpreter.
 *
 * <p>The kernel reads only committed consensus-tier records and stages pure
 * writes. It never observes the node-local acquisition/report journal. One
 * {@link BlockSession} owns the observation emission ordinal across effect
 * callbacks, observation callbacks, and application execution.</p>
 */
final class ObservationKernel {

    interface Reader {
        Optional<ObservationSubscription> subscription(byte[] subscriptionId);

        Optional<ObservationRound> round(byte[] subscriptionId, long roundNumber);

        List<DueEntry> dueAtOrBefore(ObservationAnchorType anchorType, long anchor, int limit);

        List<ObservationRound> openRounds(int limit);

        long activeCount();

        long openRoundCount();

        long activeCount(byte[] definitionDigest);

        long activeCount(String applicationId);

        long highWaterSlot();
    }

    interface MembershipView {
        AppChainMembershipEpoch epochAt(long height);
    }

    interface VerifierRegistry {
        ObservationEvidenceVerifier evidenceVerifier(ObservationDefinition definition);

        ObservationReconciliationPolicy policy(ObservationDefinition definition);
    }

    record DueEntry(ObservationAnchorType anchorType, long dueAnchor, byte[] subscriptionId) {
        DueEntry {
            anchorType = Objects.requireNonNull(anchorType, "anchorType");
            if (dueAnchor < 1) {
                throw new IllegalArgumentException("due anchor must be positive");
            }
            subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId").clone();
            if (subscriptionId.length != 32) {
                throw new IllegalArgumentException("subscription id must be 32 bytes");
            }
        }

        @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
    }

    record Result(
            List<ObservationSubscription> subscriptions,
            List<ObservationRound> rounds,
            List<DueEntry> dueAdds,
            List<DueEntry> dueDeletes,
            List<ObservationResult> results,
            long activeCount,
            long openRoundCount,
            long highWaterSlot
    ) {
        static final Result NONE = new Result(List.of(), List.of(), List.of(), List.of(),
                List.of(), 0, 0, -1);

        boolean isEmpty() {
            return subscriptions.isEmpty() && rounds.isEmpty() && dueAdds.isEmpty()
                    && dueDeletes.isEmpty() && results.isEmpty() && highWaterSlot < 0;
        }
    }

    private static final Comparator<ObservationCertificate> CERTIFICATE_ORDER = (left, right) -> {
        int comparison = Arrays.compareUnsigned(left.subscriptionId(), right.subscriptionId());
        if (comparison != 0) return comparison;
        comparison = Long.compare(left.roundNumber(), right.roundNumber());
        if (comparison != 0) return comparison;
        comparison = Arrays.compareUnsigned(left.resultId(), right.resultId());
        return comparison != 0 ? comparison
                : Arrays.compareUnsigned(left.digest(), right.digest());
    };

    private final ObservationProfileV1 profile;
    private final byte[] chainGenesisId;
    private final String chainId;
    private final byte[] consensusProfileDigest;
    private final int maxByzantineMembers;
    private final MembershipView memberships;
    private final VerifierRegistry verifiers;
    private final Map<String, ObservationDefinition> definitionsById;

    ObservationKernel(ObservationProfileV1 profile, byte[] chainGenesisId, String chainId,
                      byte[] consensusProfileDigest, int maxByzantineMembers,
                      MembershipView memberships, VerifierRegistry verifiers) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.chainGenesisId = Objects.requireNonNull(chainGenesisId, "chainGenesisId").clone();
        if (this.chainGenesisId.length != 32) {
            throw new IllegalArgumentException("chain genesis id must be 32 bytes");
        }
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.consensusProfileDigest = Objects.requireNonNull(
                consensusProfileDigest, "consensusProfileDigest").clone();
        if (this.consensusProfileDigest.length != 32 || maxByzantineMembers < 0) {
            throw new IllegalArgumentException("invalid observation kernel consensus identity");
        }
        this.maxByzantineMembers = maxByzantineMembers;
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.verifiers = Objects.requireNonNull(verifiers, "verifiers");
        Map<String, ObservationDefinition> indexed = new LinkedHashMap<>();
        profile.definitions().forEach(definition -> indexed.put(definition.id(), definition));
        this.definitionsById = Map.copyOf(indexed);
    }

    static VerifierRegistry exactValueRegistry(ObservationEvidenceVerifier evidenceVerifier) {
        ObservationEvidenceVerifier evidence = Objects.requireNonNull(
                evidenceVerifier, "evidenceVerifier");
        return new VerifierRegistry() {
            @Override
            public ObservationEvidenceVerifier evidenceVerifier(ObservationDefinition definition) {
                return evidence;
            }

            @Override
            public ObservationReconciliationPolicy policy(ObservationDefinition definition) {
                return new ExactValueQuorumPolicy();
            }
        };
    }

    BlockSession begin(AppStateMachine machine, AppBlockExecutionContext context,
                       AppStateWriter state, Reader reader) {
        return new BlockSession(machine.id(), context, state, reader);
    }

    final class BlockSession implements AppObservationEmitter {
        private final AppBlockExecutionContext context;
        private final AppBlock block;
        private final AppStateWriter state;
        private final Reader reader;
        private final String applicationId;
        private final List<ObservationCertificate> certificates;
        private final Map<IdKey, ObservationSubscription> subscriptionPuts = new LinkedHashMap<>();
        private final Map<RoundKey, ObservationRound> roundPuts = new LinkedHashMap<>();
        private final Map<DueKey, DueEntry> dueAdds = new LinkedHashMap<>();
        private final Map<DueKey, DueEntry> dueDeletes = new LinkedHashMap<>();
        private final Map<IdKey, ObservationResult> resultPuts = new LinkedHashMap<>();
        private final Map<IdKey, Long> definitionActive = new LinkedHashMap<>();
        private final Map<String, Long> applicationActive = new LinkedHashMap<>();
        private long activeCount;
        private long openRoundCount;
        private final long highWaterSlot;
        private final boolean highWaterChanged;
        private int emissionOrdinal;

        private BlockSession(String applicationId, AppBlockExecutionContext context,
                             AppStateWriter state, Reader reader) {
            this.applicationId = Objects.requireNonNull(applicationId, "applicationId");
            this.context = Objects.requireNonNull(context, "context");
            this.block = context.block();
            this.state = Objects.requireNonNull(state, "state");
            this.reader = Objects.requireNonNull(reader, "reader");
            this.activeCount = reader.activeCount();
            this.openRoundCount = reader.openRoundCount();
            this.highWaterSlot = profile.logicalTimeVersion() == 2
                    ? Math.max(reader.highWaterSlot(), block.l1Slot()) : 0;
            this.highWaterChanged = highWaterSlot > reader.highWaterSlot();
            if (highWaterChanged) {
                state.put(ObservationKeys.highWaterSlot(),
                        ByteBuffer.allocate(Long.BYTES).putLong(highWaterSlot).array());
            }
            this.certificates = preverifyLayout(block);
        }

        void incorporateResults(AppStateMachine machine, AppStateWriter machineWriter,
                                AppEffectEmitter effects) {
            Map<RoundKey, byte[]> accepted = new LinkedHashMap<>();
            for (ObservationCertificate certificate : certificates) {
                RoundKey key = new RoundKey(certificate.subscriptionId(), certificate.roundNumber());
                // Classification is against the committed state at block
                // start. A first certificate may stage a terminal record, but
                // that must not turn a conflicting second certificate in the
                // same block into a stale no-op.
                ObservationRound round = reader.round(
                        certificate.subscriptionId(), certificate.roundNumber()).orElse(null);
                if (round == null) {
                    continue;
                }
                ObservationSubscription subscription = reader.subscription(
                        certificate.subscriptionId()).orElseThrow(() -> new IllegalStateException(
                        "Observation index inconsistent: round references a missing subscription"));
                if (subscription.status() != ObservationSubscriptionStatus.ACTIVE
                        || round.roundNumber() != subscription.nextRoundNumber()) {
                    continue;
                }
                long lastInclusionHeight = round.resultExpiryHeight() > 0
                        ? round.resultExpiryHeight() : round.absoluteMaxRoundHeight();
                if (block.height() > lastInclusionHeight) {
                    continue;
                }
                ObservationDefinition definition = definition(round.definitionDigest());
                if (!verifyCertificate(definition, round, certificate)) {
                    throw new IllegalArgumentException(
                            "invalid active-round observation certificate");
                }
                byte[] previous = accepted.putIfAbsent(key, certificate.resultId());
                if (previous != null) {
                    if (!Arrays.equals(previous, certificate.resultId())) {
                        throw new IllegalArgumentException(
                                "conflicting valid results for one active observation round");
                    }
                    continue;
                }

                byte[] valueDigest = ObservationHashes.digest(certificate.output());
                ObservationResult result = new ObservationResult(1, certificate.resultId(),
                        certificate.subscriptionId(), certificate.roundNumber(),
                        certificate.definitionDigest(), ObservationResultStatus.VALUE,
                        certificate.output(), valueDigest, certificate.digest(),
                        distinctSourceCount(certificate.reports()), certificate.reports().size(),
                        freshnessSummary(certificate.reports()), block.height());
                resultPuts.put(new IdKey(result.resultId()), result);
                state.put(ObservationKeys.result(result.resultId()), result.encode());

                completeRound(subscription, round, result);
                machine.onObservationResult(context, result, machineWriter, effects, this);
            }
        }

        void advance(AppStateMachine machine, AppStateWriter machineWriter,
                     AppEffectEmitter effects) {
            expireAndClose(machine, machineWriter, effects);
            openDueRounds();
        }

        Result finish() {
            if (profile.stateCodecVersion() == 2) {
                state.put(ObservationKeys.schedulerCounts(), ByteBuffer.allocate(16)
                        .putLong(activeCount).putLong(openRoundCount).array());
            }
            return new Result(List.copyOf(subscriptionPuts.values()),
                    List.copyOf(roundPuts.values()), List.copyOf(dueAdds.values()),
                    List.copyOf(dueDeletes.values()), List.copyOf(resultPuts.values()),
                    activeCount, openRoundCount, highWaterChanged ? highWaterSlot : -1);
        }

        @Override
        public ObservationSubscriptionId watch(ObservationIntent intent) {
            Objects.requireNonNull(intent, "intent");
            if (!profile.enabled()) {
                throw new IllegalStateException("Generic observations are disabled for this chain");
            }
            ObservationDefinition definition = definitionsById.get(intent.definitionId());
            if (definition == null) {
                throw new IllegalArgumentException(
                        "unknown observation definition: " + intent.definitionId());
            }
            if ((intent.anchorType() != ObservationAnchorType.APP_HEIGHT
                    && profile.logicalTimeVersion() != 2)
                    || (profile.roundRulesVersion() == 1 && intent.cadence() != 0)) {
                throw new IllegalArgumentException(
                        "Phase 1 supports one-shot APP_HEIGHT observations only");
            }
            if (intent.firstDueAnchor() <= anchor(intent.anchorType())) {
                throw new IllegalArgumentException(
                        "observation first due height must be later than the current block");
            }
            if (profile.roundRulesVersion() == 1
                    && intent.reportDeadlineAnchor() != intent.subscriptionExpiryAnchor()) {
                throw new IllegalArgumentException(
                        "Phase 1 requires report deadline and subscription expiry to match");
            }
            if (profile.roundRulesVersion() == 1
                    && intent.subscriptionExpiryAnchor() - block.height() > profile.maxRoundHeights()) {
                throw new IllegalArgumentException("observation exceeds the maximum round lifetime");
            }
            if (intent.parameters().length > definition.maxParameterBytes()) {
                throw new IllegalArgumentException("observation parameters exceed definition bound");
            }
            if (profile.roundRulesVersion() == 2 && intent.completionPolicy() > 1) {
                throw new IllegalArgumentException("unknown observation completion policy");
            }
            if (profile.roundRulesVersion() == 2
                    && emissionOrdinal >= ObservationProfileV1.MAX_SUBSCRIPTIONS_CREATED_PER_BLOCK_V2) {
                throw new IllegalStateException("observation per-block creation bound exceeded");
            }
            if (countApplicationActive() >= profile.maxSubscriptionsPerApplication()
                    || activeCount >= profile.maxActiveSubscriptions()) {
                throw new IllegalStateException("observation subscription quota exceeded");
            }
            long definitionActive = countDefinitionActive(definition.digest());
            if (definitionActive >= profile.maxSubscriptionsPerDefinition()) {
                throw new IllegalStateException("observation definition subscription quota exceeded");
            }

            int ordinal = emissionOrdinal++;
            byte[] id = ObservationHashes.subscriptionId(chainGenesisId, block.height(), ordinal,
                    definition.digest(), intent.parameters());
            ObservationSubscription subscription = new ObservationSubscription(
                    profile.stateCodecVersion(), id,
                    machineApplicationId(), intent.route(), definition.digest(), intent.parameters(),
                    block.height(), intent.anchorType(), intent.firstDueAnchor(), intent.cadence(),
                    intent.subscriptionExpiryAnchor(), intent.completionPolicy(),
                    ObservationSubscriptionStatus.ACTIVE, intent.firstDueAnchor(), 0, null,
                    intent.reportDeadlineAnchor() - intent.firstDueAnchor());
            putSubscription(subscription);
            DueEntry due = new DueEntry(intent.anchorType(), intent.firstDueAnchor(), id);
            dueAdds.put(new DueKey(due), due);
            activeCount++;
            return new ObservationSubscriptionId(id);
        }

        @Override
        public void cancel(ObservationSubscriptionId subscriptionId) {
            Objects.requireNonNull(subscriptionId, "subscriptionId");
            ObservationSubscription subscription = currentSubscription(subscriptionId.bytes())
                    .orElse(null);
            if (subscription == null || subscription.status() != ObservationSubscriptionStatus.ACTIVE) {
                return;
            }
            if (!subscription.applicationId().equals(machineApplicationId())) {
                throw new IllegalArgumentException(
                        "application cannot cancel another application's observation");
            }
            if (subscription.nextDueAnchor() > 0) {
                DueEntry due = new DueEntry(subscription.anchorType(),
                        subscription.nextDueAnchor(), subscription.subscriptionId());
                dueDeletes.put(new DueKey(due), due);
                dueAdds.remove(new DueKey(due));
            } else if (currentRound(new RoundKey(subscription.subscriptionId(),
                    subscription.nextRoundNumber())).isPresent()) {
                openRoundCount--;
            }
            byte[] emptyDigest = ObservationHashes.digest(new byte[0]);
            byte[] resultId = ObservationHashes.resultId(subscription.subscriptionId(),
                    subscription.nextRoundNumber(), subscription.definitionDigest(),
                    ObservationResultStatus.CANCELLED, emptyDigest);
            ObservationResult cancelled = new ObservationResult(1, resultId,
                    subscription.subscriptionId(), subscription.nextRoundNumber(),
                    subscription.definitionDigest(), ObservationResultStatus.CANCELLED,
                    new byte[0], emptyDigest, null, 0, 0, new byte[0], block.height());
            resultPuts.put(new IdKey(resultId), cancelled);
            state.put(ObservationKeys.result(resultId), cancelled.encode());
            putSubscription(copySubscription(subscription, ObservationSubscriptionStatus.CANCELLED,
                    0, subscription.nextRoundNumber(), resultId));
            activeCount--;
        }

        @Override
        public long activeCount() {
            return activeCount;
        }

        private String machineApplicationId() {
            // AppStateMachine is one application per app chain in v1. The ID is
            // recovered from the current subscription where required; new
            // subscriptions use a stable host route label.
            return applicationId;
        }

        private void expireAndClose(AppStateMachine machine, AppStateWriter machineWriter,
                                    AppEffectEmitter effects) {
            List<ObservationRound> open = reader.openRounds(profile.maxOpenRounds() + 1);
            if (open.size() > profile.maxOpenRounds()) {
                throw new IllegalStateException("observation open-round index exceeds profile bound");
            }
            for (ObservationRound committed : open) {
                RoundKey key = new RoundKey(committed.subscriptionId(), committed.roundNumber());
                ObservationRound round = roundPuts.getOrDefault(key, committed);
                ObservationSubscription subscription = currentSubscription(round.subscriptionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Observation index inconsistent: open round has no subscription"));
                if (subscription.status() != ObservationSubscriptionStatus.ACTIVE
                        || subscription.nextRoundNumber() != round.roundNumber()
                        || subscription.nextDueAnchor() != 0) {
                    continue;
                }
                if (round.resultExpiryHeight() == 0
                        && anchor(round.anchorType()) > round.reportDeadlineAnchor()) {
                    long expiry = Math.min(round.absoluteMaxRoundHeight(),
                            Math.addExact(block.height(), round.resultInclusionGraceHeights()));
                    round = copyRound(round, expiry);
                    putRound(round);
                }
                long expiry = round.resultExpiryHeight() > 0
                        ? round.resultExpiryHeight() : round.absoluteMaxRoundHeight();
                if (block.height() <= expiry) {
                    continue;
                }
                byte[] emptyDigest = ObservationHashes.digest(new byte[0]);
                byte[] resultId = ObservationHashes.resultId(round.subscriptionId(),
                        round.roundNumber(), round.definitionDigest(),
                        ObservationResultStatus.EXPIRED, emptyDigest);
                ObservationResult result = new ObservationResult(1, resultId,
                        round.subscriptionId(), round.roundNumber(), round.definitionDigest(),
                        ObservationResultStatus.EXPIRED, new byte[0], emptyDigest, null,
                        0, 0, new byte[0], block.height());
                resultPuts.put(new IdKey(resultId), result);
                state.put(ObservationKeys.result(resultId), result.encode());
                completeRound(subscription, round, result);
                machine.onObservationResult(context, result, machineWriter, effects, this);
            }
        }

        private void completeRound(ObservationSubscription subscription, ObservationRound round,
                                   ObservationResult result) {
            boolean value = result.status() == ObservationResultStatus.VALUE;
            boolean recur = subscription.version() == 2 && subscription.cadence() > 0
                    && !(value && subscription.completionPolicy() == 1)
                    && subscription.cadence() <= subscription.expiryAnchor() - round.dueAnchor();
            if (recur) {
                long nextDue = Math.addExact(round.dueAnchor(), subscription.cadence());
                putSubscription(copySubscription(subscription, ObservationSubscriptionStatus.ACTIVE,
                        nextDue, Math.incrementExact(round.roundNumber()), result.resultId()));
                DueEntry due = new DueEntry(subscription.anchorType(), nextDue,
                        subscription.subscriptionId());
                dueAdds.put(new DueKey(due), due);
            } else {
                putSubscription(copySubscription(subscription, value
                                ? ObservationSubscriptionStatus.COMPLETED
                                : ObservationSubscriptionStatus.EXPIRED,
                        0, round.roundNumber(), result.resultId()));
                activeCount--;
            }
            openRoundCount--;
        }

        private void openDueRounds() {
            int scanLimit = Math.min(profile.maxActiveSubscriptions(),
                    Math.max(profile.maxRoundsOpenedPerBlock(), 1));
            List<DueEntry> due = new ArrayList<>(reader.dueAtOrBefore(
                    ObservationAnchorType.APP_HEIGHT, block.height(), scanLimit));
            if (profile.logicalTimeVersion() == 2 && due.size() < scanLimit) {
                due.addAll(reader.dueAtOrBefore(ObservationAnchorType.VERIFIED_L1_SLOT,
                        highWaterSlot, scanLimit - due.size()));
            }
            int opened = 0;
            for (DueEntry entry : due) {
                if (opened >= profile.maxRoundsOpenedPerBlock()) {
                    break;
                }
                if (dueDeletes.containsKey(new DueKey(entry))) {
                    continue; // A preceding deterministic callback cancelled this due item.
                }
                ObservationSubscription subscription = currentSubscription(entry.subscriptionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Observation due index references a missing subscription"));
                if (subscription.status() != ObservationSubscriptionStatus.ACTIVE
                        || subscription.nextDueAnchor() != entry.dueAnchor()
                        || subscription.anchorType() != entry.anchorType()) {
                    throw new IllegalStateException(
                            "Observation due index does not match its subscription");
                }
                if (openRoundCount >= profile.maxOpenRounds()) {
                    break;
                }
                AppChainMembershipEpoch epoch = memberships.epochAt(block.height());
                List<byte[]> reporters = reporterKeys(epoch);
                ObservationDefinition definition = definition(subscription.definitionDigest());
                if (definition.reporterMode() != ObservationReporterMode.ACTIVE_MEMBERS
                        || definition.reporterFaultBound() != maxByzantineMembers
                        || (profile.roundRulesVersion() == 1
                            && definition.reportThreshold() != epoch.threshold())
                        || !Arrays.equals(definition.reporterSetDigest(),
                            profile.roundRulesVersion() == 2 ? ObservationHashes.activeMemberRuleDigest()
                                    : ObservationHashes.reporterSetDigest(reporters))) {
                    throw new IllegalStateException(
                            "active-member observation definition differs from opening membership");
                }
                int reportThreshold = profile.roundRulesVersion() == 2
                        ? Math.max(definition.reportThreshold(), epoch.threshold())
                        : definition.reportThreshold();
                if (reportThreshold > definition.maxReports()
                        || reportThreshold > profile.maxReportsPerRound()) {
                    throw new IllegalStateException("Opening membership exceeds observation report bounds");
                }
                long absoluteMax = profile.roundRulesVersion() == 2
                        ? Math.addExact(block.height(), profile.maxRoundHeights())
                        : Math.max(block.height(), Math.min(
                        Math.addExact(block.height(), profile.maxRoundHeights()),
                        Math.addExact(subscription.expiryAnchor(),
                                profile.resultInclusionGraceHeights())));
                long reportDeadline = subscription.version() == 2
                        ? entry.dueAnchor() + Math.min(subscription.reportWindow(),
                                subscription.expiryAnchor() - entry.dueAnchor())
                        : subscription.expiryAnchor();
                ObservationRound round = new ObservationRound(1,
                        subscription.subscriptionId(), subscription.nextRoundNumber(),
                        subscription.anchorType(), entry.dueAnchor(), block.height(),
                        reportDeadline, profile.resultInclusionGraceHeights(),
                        absoluteMax, 0, subscription.definitionDigest(),
                        ObservationHashes.digest(subscription.parameters()), epoch.fromHeight(),
                        epoch.digest(), epoch.members().size(), epoch.threshold(),
                        maxByzantineMembers, definition.reporterMode(),
                        ObservationHashes.reporterSetDigest(reporters), epoch.members().size(),
                        definition.reporterFaultBound(), reportThreshold,
                        definition.sourceConfigurationDigest(), definition.policyParametersDigest());
                putRound(round);
                dueDeletes.put(new DueKey(entry), entry);
                dueAdds.remove(new DueKey(entry));
                putSubscription(copySubscription(subscription,
                        ObservationSubscriptionStatus.ACTIVE, 0,
                        subscription.nextRoundNumber(), subscription.lastResultId()));
                openRoundCount++;
                opened++;
            }
        }

        private long anchor(ObservationAnchorType type) {
            return type == ObservationAnchorType.APP_HEIGHT ? block.height() : highWaterSlot;
        }

        private boolean verifyCertificate(ObservationDefinition definition,
                                          ObservationRound round,
                                          ObservationCertificate certificate) {
            AppChainMembershipEpoch epoch = memberships.epochAt(round.openingHeight());
            if (epoch.fromHeight() != round.membershipEpoch()
                    || !Arrays.equals(epoch.digest(), round.membershipDigest())) {
                throw new IllegalStateException(
                        "retained observation round membership snapshot is unavailable");
            }
            return ObservationCertificateVerifier.verify(definition, round, certificate, profile,
                    chainGenesisId, chainId, consensusProfileDigest, reporterKeys(epoch),
                    (publicKey, digest, signature) ->
                            AppMessageSigner.verify(signature, digest, publicKey),
                    verifiers.evidenceVerifier(definition),
                    verifiers.policy(definition));
        }

        private Optional<ObservationSubscription> currentSubscription(byte[] id) {
            ObservationSubscription staged = subscriptionPuts.get(new IdKey(id));
            return staged != null ? Optional.of(staged) : reader.subscription(id);
        }

        private Optional<ObservationRound> currentRound(RoundKey key) {
            ObservationRound staged = roundPuts.get(key);
            return staged != null ? Optional.of(staged)
                    : reader.round(key.subscriptionId(), key.roundNumber());
        }

        private void putSubscription(ObservationSubscription subscription) {
            ObservationSubscription previous = currentSubscription(subscription.subscriptionId()).orElse(null);
            int delta = (subscription.status() == ObservationSubscriptionStatus.ACTIVE ? 1 : 0)
                    - (previous != null && previous.status() == ObservationSubscriptionStatus.ACTIVE ? 1 : 0);
            if (delta != 0) {
                IdKey definition = new IdKey(subscription.definitionDigest());
                long count = definitionActive.computeIfAbsent(definition,
                        ignored -> reader.activeCount(subscription.definitionDigest()));
                definitionActive.put(definition, Math.addExact(count, delta));
                count = applicationActive.computeIfAbsent(subscription.applicationId(), reader::activeCount);
                applicationActive.put(subscription.applicationId(), Math.addExact(count, delta));
            }
            subscriptionPuts.put(new IdKey(subscription.subscriptionId()), subscription);
            state.put(ObservationKeys.subscription(subscription.subscriptionId()),
                    subscription.encode());
        }

        private void putRound(ObservationRound round) {
            roundPuts.put(new RoundKey(round.subscriptionId(), round.roundNumber()), round);
            state.put(ObservationKeys.round(round.subscriptionId(), round.roundNumber()),
                    round.encode());
        }

        private long countDefinitionActive(byte[] definitionDigest) {
            return definitionActive.computeIfAbsent(new IdKey(definitionDigest),
                    ignored -> reader.activeCount(definitionDigest));
        }

        private long countApplicationActive() {
            return applicationActive.computeIfAbsent(applicationId, reader::activeCount);
        }
    }

    private List<ObservationCertificate> preverifyLayout(AppBlock block) {
        if (!profile.enabled()) {
            for (AppMessage message : block.messages()) {
                if (ObservationTopics.isReserved(message.getTopic())) {
                    throw new IllegalArgumentException(
                            "observation system input is disabled for this chain");
                }
            }
            return List.of();
        }
        List<ObservationCertificate> certificates = new ArrayList<>();
        boolean resultRegionClosed = false;
        boolean l1Prefix = true;
        ObservationCertificate previous = null;
        int resultBytes = 0;
        int ticks = 0;
        for (AppMessage message : block.messages()) {
            String topic = message.getTopic();
            if (topic != null && topic.startsWith("~l1/") && l1Prefix) {
                continue;
            }
            l1Prefix = false;
            if (ObservationTopics.isDiffusionOnly(topic)) {
                throw new IllegalArgumentException("observation diffusion input cannot be sequenced");
            }
            if (ObservationTopics.TICK.equals(topic)) {
                if (profile.logicalTimeVersion() != 2) {
                    throw new IllegalArgumentException("observation ticks are unavailable in Phase 1");
                }
                ObservationTick.decode(message.getBody());
                if (++ticks > profile.maxTicksPerBlock()) {
                    throw new IllegalArgumentException("observation tick bound exceeded");
                }
            }
            if (!ObservationTopics.RESULT.equals(topic)) {
                resultRegionClosed = true;
                continue;
            }
            if (resultRegionClosed) {
                throw new IllegalArgumentException(
                        "observation results must immediately follow the canonical L1 prefix");
            }
            byte[] body = message.getBody();
            if (body == null || body.length > profile.maxCertificateBytes()) {
                throw new IllegalArgumentException("oversized ~obs/result/v1 input");
            }
            resultBytes = Math.addExact(resultBytes, body.length);
            if (certificates.size() >= profile.maxResultsPerBlock()
                    || resultBytes > profile.maxResultBytesPerBlock()) {
                throw new IllegalArgumentException("observation result block bound exceeded");
            }
            final ObservationCertificate certificate;
            try {
                certificate = ObservationCertificate.decode(body);
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException("malformed ~obs/result/v1 input", malformed);
            }
            if (previous != null && CERTIFICATE_ORDER.compare(previous, certificate) > 0) {
                throw new IllegalArgumentException("observation results are not canonically ordered");
            }
            certificates.add(certificate);
            previous = certificate;
        }
        return List.copyOf(certificates);
    }

    private ObservationDefinition definition(byte[] digest) {
        return profile.definitions().stream()
                .filter(candidate -> Arrays.equals(candidate.digest(), digest))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "retained observation record references an unknown definition"));
    }

    private static List<byte[]> reporterKeys(AppChainMembershipEpoch epoch) {
        return epoch.members().stream().map(HexUtil::decodeHexString).toList();
    }

    private static ObservationSubscription copySubscription(
            ObservationSubscription source, ObservationSubscriptionStatus status,
            long nextDueAnchor, long nextRoundNumber, byte[] lastResultId) {
        return new ObservationSubscription(source.version(), source.subscriptionId(),
                source.applicationId(), source.route(), source.definitionDigest(),
                source.parameters(), source.creationHeight(), source.anchorType(),
                source.firstDueAnchor(), source.cadence(), source.expiryAnchor(),
                source.completionPolicy(), status, nextDueAnchor, nextRoundNumber, lastResultId,
                source.reportWindow());
    }

    private static ObservationRound copyRound(ObservationRound source, long resultExpiryHeight) {
        return new ObservationRound(source.version(), source.subscriptionId(),
                source.roundNumber(), source.anchorType(), source.dueAnchor(),
                source.openingHeight(), source.reportDeadlineAnchor(),
                source.resultInclusionGraceHeights(), source.absoluteMaxRoundHeight(),
                resultExpiryHeight, source.definitionDigest(), source.parametersDigest(),
                source.membershipEpoch(), source.membershipDigest(), source.memberCount(),
                source.finalityQuorum(), source.maxByzantineMembers(), source.reporterMode(),
                source.reporterSetDigest(), source.reporterCount(), source.reporterFaultBound(),
                source.reportThreshold(), source.sourceSetDigest(), source.policyDigest());
    }

    private static int distinctSourceCount(List<ObservationReport> reports) {
        return (int) reports.stream().map(report -> new IdKey(report.sourceId())).distinct().count();
    }

    private static byte[] freshnessSummary(List<ObservationReport> reports) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                for (ObservationReport report : reports) {
                    out.writeInt(report.sourceVersion().length);
                    out.write(report.sourceVersion());
                    out.writeInt(report.freshnessAnchorType());
                    out.writeLong(report.freshnessAnchor());
                }
            }
            return ObservationHashes.digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private record IdKey(byte[] value) {
        private IdKey {
            value = Objects.requireNonNull(value, "value").clone();
        }

        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof IdKey key && Arrays.equals(value, key.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    private record RoundKey(byte[] subscriptionId, long roundNumber) {
        private RoundKey {
            subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId").clone();
        }

        @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof RoundKey key && roundNumber == key.roundNumber
                    && Arrays.equals(subscriptionId, key.subscriptionId);
        }
        @Override public int hashCode() {
            return 31 * Arrays.hashCode(subscriptionId) + Long.hashCode(roundNumber);
        }
    }

    private record DueKey(int anchorCode, long dueAnchor, IdKey subscriptionId) {
        private DueKey(DueEntry entry) {
            this(entry.anchorType().code(), entry.dueAnchor(), new IdKey(entry.subscriptionId()));
        }
    }
}
