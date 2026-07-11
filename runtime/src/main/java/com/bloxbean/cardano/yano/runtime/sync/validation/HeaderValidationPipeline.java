package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.crypto.config.CryptoExtConfiguration;
import com.bloxbean.cardano.client.crypto.kes.KesVerifier;
import com.bloxbean.cardano.client.crypto.vrf.VrfResult;
import com.bloxbean.cardano.client.crypto.vrf.VrfVerifier;
import com.bloxbean.cardano.client.crypto.vrf.cardano.CardanoLeaderCheck;
import com.bloxbean.cardano.client.crypto.vrf.cardano.CardanoVrfInput;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ordered fail-closed Shelley+ header-validation pipeline.
 */
public final class HeaderValidationPipeline implements HeaderValidator {
    private static final Logger log = LoggerFactory.getLogger(HeaderValidationPipeline.class);

    public static final String PROFILE_NONE = "none";
    public static final String PROFILE_STRUCTURAL = "structural";
    public static final String PROFILE_HEADER_SIGNATURE = "header-signature";
    public static final String PROFILE_PRAOS_LITE = "praos-lite";
    public static final String PROFILE_PRAOS_LEDGER = "praos-ledger";
    public static final String STAGE_STRUCTURAL = "structural";
    public static final String STAGE_KES_SIGNATURE = "kes-signature";
    public static final String STAGE_OPCERT_SIGNATURE = "opcert-signature";
    public static final String STAGE_VRF_PROOF = "vrf-proof";
    public static final String STAGE_LEADER_THRESHOLD = "leader-threshold";
    public static final String STAGE_OPCERT_STATE = "opcert-state";
    public static final String STAGE_PROTOCOL_VIEW = "protocol-view";

    private static final int VKEY_SIZE = 32;
    private static final int EPOCH_NONCE_SIZE = 32;
    private static final MathContext MC = new MathContext(40);

    private final String profile;
    private final long slotsPerKESPeriod;
    private final long maxKESEvolutions;
    private final HeaderValidationNonceProvider nonceProvider;
    private final HeaderValidationLedgerViewProvider ledgerViewProvider;
    private final List<Stage> stages;
    private final AtomicLong acceptedHeaders = new AtomicLong();
    private final AtomicLong rejectedHeaders = new AtomicLong();
    private volatile String lastRejectedStage;
    private volatile String lastRejectedReason;

    private HeaderValidationPipeline(String profile,
                                     long slotsPerKESPeriod,
                                     long maxKESEvolutions,
                                     HeaderValidationNonceProvider nonceProvider,
                                     HeaderValidationLedgerViewProvider ledgerViewProvider,
                                     List<Stage> stages) {
        this.profile = normalize(profile, PROFILE_NONE);
        this.slotsPerKESPeriod = slotsPerKESPeriod > 0 ? slotsPerKESPeriod : 129600;
        this.maxKESEvolutions = maxKESEvolutions > 0 ? maxKESEvolutions : 62;
        this.nonceProvider = nonceProvider != null ? nonceProvider : HeaderValidationNonceProvider.none();
        this.ledgerViewProvider = ledgerViewProvider != null
                ? ledgerViewProvider : HeaderValidationLedgerViewProvider.none();
        this.stages = List.copyOf(stages);
        if (!this.stages.isEmpty()) {
            log.info("Shelley+ header validation enabled: profile={}, stages={}, slotsPerKESPeriod={}, maxKESEvolutions={}",
                    this.profile,
                    this.stages.stream().map(Stage::id).toList(),
                    this.slotsPerKESPeriod,
                    this.maxKESEvolutions);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static HeaderValidationPipeline forProfile(String profile,
                                                      long slotsPerKESPeriod,
                                                      long maxKESEvolutions) {
        return forProfile(profile, slotsPerKESPeriod, maxKESEvolutions, HeaderValidationNonceProvider.none());
    }

    public static HeaderValidationPipeline forProfile(String profile,
                                                      long slotsPerKESPeriod,
                                                      long maxKESEvolutions,
                                                      HeaderValidationNonceProvider nonceProvider) {
        return forProfile(profile, slotsPerKESPeriod, maxKESEvolutions,
                nonceProvider, HeaderValidationLedgerViewProvider.none());
    }

    public static HeaderValidationPipeline forProfile(String profile,
                                                      long slotsPerKESPeriod,
                                                      long maxKESEvolutions,
                                                      HeaderValidationNonceProvider nonceProvider,
                                                      HeaderValidationLedgerViewProvider ledgerViewProvider) {
        return builder()
                .slotsPerKESPeriod(slotsPerKESPeriod)
                .maxKESEvolutions(maxKESEvolutions)
                .nonceProvider(nonceProvider)
                .ledgerViewProvider(ledgerViewProvider)
                .useProfile(profile)
                .build();
    }

    @Override
    public HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes) {
        HeaderValidationResult result = validate(new HeaderValidationContext(
                blockHeader,
                originalHeaderBytes,
                slotsPerKESPeriod,
                maxKESEvolutions,
                nonceProvider,
                ledgerViewProvider));
        if (result.accepted()) {
            acceptedHeaders.incrementAndGet();
            if (!stages.isEmpty() && log.isDebugEnabled()) {
                var body = blockHeader != null ? blockHeader.getHeaderBody() : null;
                log.debug("Accepted Shelley+ header validation: profile={}, stages={}, slot={}, blockNumber={}, hash={}",
                        result.level(),
                        result.acceptedStages(),
                        body != null ? body.getSlot() : null,
                        body != null ? body.getBlockNumber() : null,
                        body != null ? body.getBlockHash() : null);
            }
        } else {
            rejectedHeaders.incrementAndGet();
            lastRejectedStage = result.stage();
            lastRejectedReason = result.reason();
        }
        return result;
    }

    @Override
    public HeaderValidationSnapshot snapshot() {
        return new HeaderValidationSnapshot(
                profile,
                acceptedHeaders.get(),
                rejectedHeaders.get(),
                lastRejectedStage,
                lastRejectedReason);
    }

    private HeaderValidationResult validate(HeaderValidationContext context) {
        if (stages.isEmpty()) {
            return HeaderValidationResult.accepted(profile, List.of());
        }

        List<String> acceptedStages = new ArrayList<>();
        for (Stage stage : stages) {
            HeaderValidationResult stageResult;
            try {
                stageResult = stage.validator().validate(context);
            } catch (HeaderValidationFailure e) {
                return HeaderValidationResult.rejected(profile, e.stage(), e.getMessage(), acceptedStages);
            } catch (RuntimeException e) {
                return HeaderValidationResult.rejected(
                        profile,
                        stage.id(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                        acceptedStages);
            }
            if (stageResult == null) {
                return HeaderValidationResult.rejected(
                        profile,
                        stage.id(),
                        "validator returned null",
                        acceptedStages);
            }
            if (!stageResult.accepted()) {
                return HeaderValidationResult.rejected(
                        profile,
                        stageResult.stage() != null ? stageResult.stage() : stage.id(),
                        stageResult.reason(),
                        acceptedStages);
            }
            acceptedStages.add(stage.id());
        }
        return HeaderValidationResult.accepted(profile, acceptedStages);
    }

    private static HeaderStageValidator defaultStage(String id) {
        return switch (id) {
            case STAGE_STRUCTURAL -> context -> {
                context.shelleyHeader();
                return HeaderValidationResult.accepted(STAGE_STRUCTURAL);
            };
            case STAGE_KES_SIGNATURE -> new KesSignatureStage();
            case STAGE_OPCERT_SIGNATURE -> new OpCertSignatureStage();
            case STAGE_VRF_PROOF -> new VrfProofStage();
            case STAGE_LEADER_THRESHOLD -> new LeaderThresholdStage();
            case STAGE_OPCERT_STATE -> new OpCertStateStage();
            case STAGE_PROTOCOL_VIEW -> new ProtocolViewStage();
            default -> throw new IllegalArgumentException("Unknown default header validator: " + id);
        };
    }

    private static String normalize(String value, String defaultValue) {
        return value == null || value.isBlank()
                ? defaultValue
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] opcertSignedData(byte[] kesVkey, long counter, long kesPeriod) {
        byte[] data = new byte[VKEY_SIZE + Long.BYTES + Long.BYTES];
        System.arraycopy(kesVkey, 0, data, 0, VKEY_SIZE);
        writeLong(data, VKEY_SIZE, counter);
        writeLong(data, VKEY_SIZE + Long.BYTES, kesPeriod);
        return data;
    }

    private static void writeLong(byte[] target, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            target[offset + (7 - i)] = (byte) (value >>> (i * 8));
        }
    }

    private static String vrfKeyHash(ShelleyHeaderView header) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(header.vrfVkey()));
    }

    private static String poolHash(ShelleyHeaderView header) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(header.issuerVkey()));
    }

    private record Stage(String id, HeaderStageValidator validator) {
        private Stage {
            id = normalize(id, "");
            if (id.isBlank()) {
                throw new IllegalArgumentException("stage id must not be blank");
            }
            Objects.requireNonNull(validator, "validator");
        }
    }

    private static final class KesSignatureStage implements HeaderStageValidator {
        private final KesVerifier kesVerifier = CryptoExtConfiguration.INSTANCE.getKesVerifier();

        @Override
        public HeaderValidationResult validate(HeaderValidationContext context) {
            ShelleyHeaderView header = context.shelleyHeader();
            int currentKesPeriod = Math.toIntExact(header.slot() / context.slotsPerKESPeriod());
            int opcertKesPeriod = Math.toIntExact(header.opcertKesPeriod());
            int relativePeriod = currentKesPeriod - opcertKesPeriod;
            if (relativePeriod < 0 || relativePeriod >= context.maxKESEvolutions()) {
                return HeaderValidationResult.rejected(
                        PROFILE_HEADER_SIGNATURE,
                        "kes-period",
                        "KES relative period is outside op-cert evolution window");
            }

            boolean kesValid = kesVerifier.verify(
                    header.signature(),
                    header.headerBodyBytes(),
                    header.opcertKesVkey(),
                    relativePeriod);
            if (!kesValid) {
                return HeaderValidationResult.rejected(
                        PROFILE_HEADER_SIGNATURE,
                        STAGE_KES_SIGNATURE,
                        "KES signature does not verify over header body");
            }
            return HeaderValidationResult.accepted(STAGE_KES_SIGNATURE);
        }
    }

    private static final class OpCertSignatureStage implements HeaderStageValidator {
        private final SigningProvider signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();

        @Override
        public HeaderValidationResult validate(HeaderValidationContext context) {
            ShelleyHeaderView header = context.shelleyHeader();
            byte[] signedOpCert = opcertSignedData(
                    header.opcertKesVkey(),
                    header.opcertCounter(),
                    header.opcertKesPeriod());
            boolean opcertValid = signingProvider.verify(
                    header.opcertColdSignature(),
                    signedOpCert,
                    header.issuerVkey());
            if (!opcertValid) {
                return HeaderValidationResult.rejected(
                        PROFILE_HEADER_SIGNATURE,
                        STAGE_OPCERT_SIGNATURE,
                        "operational certificate cold signature does not verify");
            }
            return HeaderValidationResult.accepted(STAGE_OPCERT_SIGNATURE);
        }
    }

    private static final class VrfProofStage implements HeaderStageValidator {
        private final VrfVerifier vrfVerifier = CryptoExtConfiguration.INSTANCE.getVrfVerifier();

        @Override
        public HeaderValidationResult validate(HeaderValidationContext context) {
            ShelleyHeaderView header = context.shelleyHeader();
            byte[] epochNonce = context.epochNonceForHeader();
            if (epochNonce == null || epochNonce.length != EPOCH_NONCE_SIZE) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LITE,
                        STAGE_VRF_PROOF,
                        "epoch nonce unavailable for slot " + header.slot());
            }

            if (header.hasSeparateNonceVrf()) {
                HeaderValidationResult leader = verifyVrfCert(
                        header.vrfVkey(),
                        header.leaderVrfProof(),
                        CardanoVrfInput.mkSeedLeader(header.slot(), epochNonce),
                        header.leaderVrfOutput(),
                        "TPraos leader VRF proof");
                if (!leader.accepted()) {
                    return leader;
                }
                return verifyVrfCert(
                        header.vrfVkey(),
                        header.nonceVrfProof(),
                        CardanoVrfInput.mkSeedNonce(header.slot(), epochNonce),
                        header.nonceVrfOutput(),
                        "TPraos nonce VRF proof");
            }

            return verifyVrfCert(
                    header.vrfVkey(),
                    header.leaderVrfProof(),
                    CardanoVrfInput.mkInputVrf(header.slot(), epochNonce),
                    header.leaderVrfOutput(),
                    "Praos VRF proof");
        }

        private HeaderValidationResult verifyVrfCert(byte[] vrfVkey,
                                                     byte[] proof,
                                                     byte[] alpha,
                                                     byte[] expectedOutput,
                                                     String label) {
            VrfResult result = vrfVerifier.verify(vrfVkey, proof, alpha);
            if (result == null || !result.isValid()) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LITE,
                        STAGE_VRF_PROOF,
                        label + " does not verify");
            }
            if (!Arrays.equals(expectedOutput, result.getOutput())) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LITE,
                        STAGE_VRF_PROOF,
                        label + " output does not match header");
            }
            return HeaderValidationResult.accepted(STAGE_VRF_PROOF);
        }
    }

    private static final class LeaderThresholdStage implements HeaderStageValidator {
        @Override
        public HeaderValidationResult validate(HeaderValidationContext context) {
            ShelleyHeaderView header = context.shelleyHeader();
            var view = context.ledgerViewProvider().leaderStakeFor(header).orElse(null);
            if (view == null) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_LEADER_THRESHOLD,
                        "leader stake view unavailable for pool " + poolHash(header));
            }
            if (view.registeredVrfKeyHash() == null || view.registeredVrfKeyHash().isBlank()) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_LEADER_THRESHOLD,
                        "registered pool VRF key hash unavailable for pool " + view.poolHash());
            }
            String headerVrfKeyHash = vrfKeyHash(header);
            if (!view.registeredVrfKeyHash().equalsIgnoreCase(headerVrfKeyHash)) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_LEADER_THRESHOLD,
                        "header VRF key is not registered for pool " + view.poolHash());
            }
            if (view.poolStake() == null || view.poolStake().signum() < 0) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_LEADER_THRESHOLD,
                        "pool active stake unavailable for pool " + view.poolHash());
            }
            if (view.totalStake() == null || view.totalStake().signum() <= 0) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_LEADER_THRESHOLD,
                        "total active stake unavailable");
            }
            if (view.activeSlotCoeff() == null || view.activeSlotCoeff().signum() <= 0) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_LEADER_THRESHOLD,
                        "active slot coefficient unavailable");
            }
            BigDecimal sigma = new BigDecimal(view.poolStake()).divide(new BigDecimal(view.totalStake()), MC);
            byte[] leaderValue = CardanoLeaderCheck.vrfLeaderValue(header.leaderVrfOutput());
            if (!CardanoLeaderCheck.checkLeaderValue(leaderValue, sigma, view.activeSlotCoeff())) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_LEADER_THRESHOLD,
                        "VRF leader value is above threshold for pool " + view.poolHash());
            }
            return HeaderValidationResult.accepted(STAGE_LEADER_THRESHOLD);
        }
    }

    private static final class ProtocolViewStage implements HeaderStageValidator {
        @Override
        public HeaderValidationResult validate(HeaderValidationContext context) {
            ShelleyHeaderView header = context.shelleyHeader();
            var view = context.ledgerViewProvider().protocolViewFor(header).orElse(null);
            if (view == null) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_PROTOCOL_VIEW,
                        "protocol view unavailable for slot " + header.slot());
            }
            if (header.protocolMajor() != view.protocolMajor()
                    || header.protocolMinor() != view.protocolMinor()) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_PROTOCOL_VIEW,
                        "header protocol version does not match epoch " + view.epoch());
            }
            if (view.maxBlockHeaderSize() != null
                    && header.headerArrayBytes().length > view.maxBlockHeaderSize()) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_PROTOCOL_VIEW,
                        "header size exceeds maxBlockHeaderSize for epoch " + view.epoch());
            }
            return HeaderValidationResult.accepted(STAGE_PROTOCOL_VIEW);
        }
    }

    private static final class OpCertStateStage implements HeaderStageValidator {
        @Override
        public HeaderValidationResult validate(HeaderValidationContext context) {
            ShelleyHeaderView header = context.shelleyHeader();
            var view = context.ledgerViewProvider().opCertStateFor(header).orElse(null);
            if (view == null || view.expectedCounter() == null) {
                // Compatibility mode accepts missing counter evidence for old/checkpointed databases.
                return HeaderValidationResult.accepted(STAGE_OPCERT_STATE);
            }
            long expectedCounter = view.expectedCounter();
            long headerCounter = header.opcertCounter();
            if (headerCounter < expectedCounter) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_OPCERT_STATE,
                        "operational certificate counter is below ledger state");
            }
            // Modern Haskell Praos accepts only the stored counter or the next issue number:
            // storedCounter <= headerCounter <= storedCounter + 1.
            // This rejects skipped rotations such as 5 -> 7 until a block with counter 6 is accepted.
            // Guard the +1 comparison so a corrupt Long.MAX_VALUE state cannot overflow.
            if (expectedCounter < Long.MAX_VALUE && headerCounter > expectedCounter + 1) {
                return HeaderValidationResult.rejected(
                        PROFILE_PRAOS_LEDGER,
                        STAGE_OPCERT_STATE,
                        "operational certificate counter is over-incremented");
            }
            return HeaderValidationResult.accepted(STAGE_OPCERT_STATE);
        }
    }

    public static final class Builder {
        private String profile = PROFILE_NONE;
        private long slotsPerKESPeriod = 129600;
        private long maxKESEvolutions = 62;
        private HeaderValidationNonceProvider nonceProvider = HeaderValidationNonceProvider.none();
        private HeaderValidationLedgerViewProvider ledgerViewProvider = HeaderValidationLedgerViewProvider.none();
        private final LinkedHashMap<String, HeaderStageValidator> validators = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder slotsPerKESPeriod(long slotsPerKESPeriod) {
            this.slotsPerKESPeriod = slotsPerKESPeriod;
            return this;
        }

        public Builder maxKESEvolutions(long maxKESEvolutions) {
            this.maxKESEvolutions = maxKESEvolutions;
            return this;
        }

        public Builder nonceProvider(HeaderValidationNonceProvider nonceProvider) {
            this.nonceProvider = nonceProvider != null ? nonceProvider : HeaderValidationNonceProvider.none();
            return this;
        }

        public Builder ledgerViewProvider(HeaderValidationLedgerViewProvider ledgerViewProvider) {
            this.ledgerViewProvider = ledgerViewProvider != null
                    ? ledgerViewProvider : HeaderValidationLedgerViewProvider.none();
            return this;
        }

        public Builder useProfile(String profile) {
            this.profile = normalize(profile, PROFILE_NONE);
            validators.clear();
            switch (this.profile) {
                case PROFILE_NONE -> {
                }
                case PROFILE_STRUCTURAL -> useDefault(STAGE_STRUCTURAL);
                case PROFILE_HEADER_SIGNATURE -> {
                    useDefault(STAGE_STRUCTURAL);
                    useDefault(STAGE_KES_SIGNATURE);
                    useDefault(STAGE_OPCERT_SIGNATURE);
                }
                case PROFILE_PRAOS_LITE -> {
                    useDefault(STAGE_STRUCTURAL);
                    useDefault(STAGE_KES_SIGNATURE);
                    useDefault(STAGE_OPCERT_SIGNATURE);
                    useDefault(STAGE_VRF_PROOF);
                }
                case PROFILE_PRAOS_LEDGER -> {
                    useDefault(STAGE_STRUCTURAL);
                    useDefault(STAGE_KES_SIGNATURE);
                    useDefault(STAGE_OPCERT_SIGNATURE);
                    useDefault(STAGE_VRF_PROOF);
                    useDefault(STAGE_LEADER_THRESHOLD);
                    useDefault(STAGE_OPCERT_STATE);
                    useDefault(STAGE_PROTOCOL_VIEW);
                }
                default -> throw new IllegalArgumentException("Unsupported header validation profile: " + profile);
            }
            return this;
        }

        public Builder useDefault(String id) {
            String normalized = normalize(id, "");
            validators.put(normalized, defaultStage(normalized));
            return this;
        }

        public Builder addValidator(String id, HeaderStageValidator validator) {
            String normalized = normalize(id, "");
            if (validators.containsKey(normalized)) {
                throw new IllegalArgumentException("Header validator already exists: " + normalized);
            }
            validators.put(normalized, Objects.requireNonNull(validator, "validator"));
            return this;
        }

        public Builder disableValidator(String id) {
            validators.remove(normalize(id, ""));
            return this;
        }

        public Builder overrideValidator(String id, HeaderStageValidator validator) {
            String normalized = normalize(id, "");
            if (!validators.containsKey(normalized)) {
                throw new IllegalArgumentException("Cannot override missing header validator: " + normalized);
            }
            validators.put(normalized, Objects.requireNonNull(validator, "validator"));
            return this;
        }

        public HeaderValidationPipeline build() {
            List<Stage> stages = validators.entrySet().stream()
                    .map(entry -> new Stage(entry.getKey(), entry.getValue()))
                    .toList();
            return new HeaderValidationPipeline(profile, slotsPerKESPeriod, maxKESEvolutions,
                    nonceProvider, ledgerViewProvider, stages);
        }
    }
}
