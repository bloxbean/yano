package com.bloxbean.cardano.yano.runtime.sync.validation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockSigner;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilder;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ShelleyHeaderValidatorTest {
    private static final long EPOCH_LENGTH = 600;
    private static final long SECURITY_PARAM = 100;
    private static final double ACTIVE_SLOTS_COEFF = 1.0;
    private static final long SLOTS_PER_KES_PERIOD = 129600;
    private static final long MAX_KES_EVOLUTIONS = 60;

    private static BlockProducerKeys keys;
    private static byte[] genesisBytes;

    @BeforeAll
    static void setUp() throws Exception {
        Path base = Path.of("src/test/resources/devnet");
        keys = BlockProducerKeys.load(
                base.resolve("vrf.skey"),
                base.resolve("kes.skey"),
                base.resolve("opcert.cert"));
        genesisBytes = Files.readAllBytes(base.resolve("shelley-genesis.json"));
    }

    @Test
    void structuralValidationAcceptsValidShelleyHeader() {
        HeaderFixture fixture = validHeader();
        ShelleyHeaderValidator validator = new ShelleyHeaderValidator(
                "structural", SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS);

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages()).containsExactly("structural");
        assertThat(validator.snapshot().acceptedHeaders()).isEqualTo(1);
        assertThat(validator.snapshot().rejectedHeaders()).isZero();
    }

    @Test
    void headerSignatureValidationAcceptsValidKesAndOpCertSignatures() {
        HeaderFixture fixture = validHeader();
        ShelleyHeaderValidator validator = new ShelleyHeaderValidator(
                "header-signature", SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS);

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages()).containsExactly("structural", "kes-signature", "opcert-signature");
        assertThat(validator.snapshot().acceptedHeaders()).isEqualTo(1);
    }

    @Test
    void praosLiteValidationAcceptsValidVrfProof() {
        HeaderFixture fixture = validHeader();
        ShelleyHeaderValidator validator = new ShelleyHeaderValidator(
                "praos-lite", SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS, slot -> fixture.epochNonce());

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages())
                .containsExactly("structural", "kes-signature", "opcert-signature", "vrf-proof");
        assertThat(validator.snapshot().acceptedHeaders()).isEqualTo(1);
    }

    @Test
    void praosLiteValidationRejectsWhenEpochNonceUnavailable() {
        HeaderFixture fixture = validHeader();
        ShelleyHeaderValidator validator = new ShelleyHeaderValidator(
                "praos-lite", SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS, slot -> null);

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("vrf-proof");
        assertThat(result.reason()).contains("epoch nonce unavailable");
        assertThat(result.acceptedStages()).containsExactly("structural", "kes-signature", "opcert-signature");
    }

    @Test
    void praosLiteValidationRejectsBadVrfProof() {
        HeaderFixture fixture = validHeader().withBadVrfProofAndFreshKesSignature();
        ShelleyHeaderValidator validator = new ShelleyHeaderValidator(
                "praos-lite", SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS, slot -> fixture.epochNonce());

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("vrf-proof");
        assertThat(result.reason()).contains("VRF proof");
        assertThat(result.acceptedStages()).containsExactly("structural", "kes-signature", "opcert-signature");
    }

    @Test
    void praosLedgerValidationAcceptsLeaderThresholdAndProtocolWhenOpCertStateIsDisabled() {
        HeaderFixture fixture = validHeader();
        HeaderValidationPipeline pipeline = HeaderValidationPipeline.builder()
                .slotsPerKESPeriod(SLOTS_PER_KES_PERIOD)
                .maxKESEvolutions(MAX_KES_EVOLUTIONS)
                .nonceProvider(slot -> fixture.epochNonce())
                .ledgerViewProvider(ledgerViewProvider(BigInteger.ONE, BigInteger.ONE, true))
                .useProfile("praos-ledger")
                .disableValidator("opcert-state")
                .build();

        HeaderValidationResult result = pipeline.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages()).containsExactly(
                "structural",
                "kes-signature",
                "opcert-signature",
                "vrf-proof",
                "leader-threshold",
                "protocol-view");
    }

    @Test
    void factoryDisablesOpCertCounterStageWhenModeIsNone() {
        HeaderFixture fixture = validHeader();
        HeaderValidator validator = HeaderValidatorFactory.from(
                UpstreamValidationConfig.builder()
                        .level("praos-ledger")
                        .opCertCounterMode("none")
                        .build(),
                null,
                slot -> fixture.epochNonce(),
                ledgerViewProvider(BigInteger.ONE, BigInteger.ONE, true));

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages()).containsExactly(
                "structural",
                "kes-signature",
                "opcert-signature",
                "vrf-proof",
                "leader-threshold",
                "protocol-view");
    }

    @Test
    void praosLedgerValidationRejectsLeaderValueAboveThreshold() {
        HeaderFixture fixture = validHeader();
        HeaderValidationPipeline pipeline = HeaderValidationPipeline.builder()
                .slotsPerKESPeriod(SLOTS_PER_KES_PERIOD)
                .maxKESEvolutions(MAX_KES_EVOLUTIONS)
                .nonceProvider(slot -> fixture.epochNonce())
                .ledgerViewProvider(ledgerViewProvider(BigInteger.ZERO, BigInteger.ONE, true))
                .useProfile("praos-ledger")
                .disableValidator("opcert-state")
                .build();

        HeaderValidationResult result = pipeline.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("leader-threshold");
        assertThat(result.reason()).contains("above threshold");
    }

    @Test
    void praosLedgerValidationRejectsProtocolMismatch() {
        HeaderFixture fixture = validHeader();
        HeaderValidationPipeline pipeline = HeaderValidationPipeline.builder()
                .slotsPerKESPeriod(SLOTS_PER_KES_PERIOD)
                .maxKESEvolutions(MAX_KES_EVOLUTIONS)
                .nonceProvider(slot -> fixture.epochNonce())
                .ledgerViewProvider(ledgerViewProvider(BigInteger.ONE, BigInteger.ONE, false))
                .useProfile("praos-ledger")
                .disableValidator("opcert-state")
                .build();

        HeaderValidationResult result = pipeline.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("protocol-view");
        assertThat(result.reason()).contains("protocol version");
    }

    @Test
    void praosLedgerValidationSkipsOpCertCounterWhenStateIsUnavailable() {
        HeaderFixture fixture = validHeader();
        HeaderValidationPipeline pipeline = HeaderValidationPipeline.builder()
                .slotsPerKESPeriod(SLOTS_PER_KES_PERIOD)
                .maxKESEvolutions(MAX_KES_EVOLUTIONS)
                .nonceProvider(slot -> fixture.epochNonce())
                .ledgerViewProvider(ledgerViewProvider(BigInteger.ONE, BigInteger.ONE, true))
                .useProfile("praos-ledger")
                .build();

        HeaderValidationResult result = pipeline.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptedStages()).containsExactly(
                "structural",
                "kes-signature",
                "opcert-signature",
                "vrf-proof",
                "leader-threshold",
                "opcert-state",
                "protocol-view");
    }

    @Test
    void praosLedgerValidationRejectsWhenOpCertCounterIsBelowAvailableState() {
        HeaderFixture fixture = validHeader();
        long currentCounter = fixture.opcertCounter();
        HeaderValidationPipeline pipeline = HeaderValidationPipeline.builder()
                .slotsPerKESPeriod(SLOTS_PER_KES_PERIOD)
                .maxKESEvolutions(MAX_KES_EVOLUTIONS)
                .nonceProvider(slot -> fixture.epochNonce())
                .ledgerViewProvider(ledgerViewProvider(BigInteger.ONE, BigInteger.ONE, true, currentCounter + 1))
                .useProfile("praos-ledger")
                .build();

        HeaderValidationResult result = pipeline.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("opcert-state");
        assertThat(result.reason()).contains("below ledger state");
    }

    @Test
    void opCertCounterStageRejectsWhenHeaderCounterOverIncrementsAvailableState() {
        HeaderFixture fixture = validHeader().withOpCertCounter(2);
        HeaderValidationPipeline pipeline = HeaderValidationPipeline.builder()
                .ledgerViewProvider(ledgerViewProvider(BigInteger.ONE, BigInteger.ONE, true, 0L))
                .useDefault("opcert-state")
                .build();

        HeaderValidationResult result = pipeline.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("opcert-state");
        assertThat(result.reason()).contains("over-incremented");
    }

    @Test
    void structuralValidationRejectsHeaderHashMismatch() {
        HeaderFixture fixture = validHeader();
        HeaderFixture tampered = fixture.withKesSignatureByteFlipped();
        ShelleyHeaderValidator validator = new ShelleyHeaderValidator(
                "structural", SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS);

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), tampered.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("header-hash");
        assertThat(validator.snapshot().rejectedHeaders()).isEqualTo(1);
    }

    @Test
    void headerSignatureValidationRejectsBadKesSignature() {
        HeaderFixture fixture = validHeader().withKesSignatureByteFlipped();
        ShelleyHeaderValidator validator = new ShelleyHeaderValidator(
                "header-signature", SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS);

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("kes-signature");
        assertThat(result.acceptedStages()).containsExactly("structural");
    }

    @Test
    void headerSignatureValidationRejectsBadOperationalCertificateSignature() {
        HeaderFixture fixture = validHeader().withBadOpCertSignatureAndFreshKesSignature();
        ShelleyHeaderValidator validator = new ShelleyHeaderValidator(
                "header-signature", SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS);

        HeaderValidationResult result = validator.validateShelley(fixture.blockHeader(), fixture.wrappedHeaderCbor());

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("opcert-signature");
        assertThat(result.acceptedStages()).containsExactly("structural", "kes-signature");
    }

    private static HeaderFixture validHeader() {
        EpochNonceState nonceState = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        nonceState.initFromGenesis(genesisBytes);
        SignedBlockBuilder builder = new SignedBlockBuilder(
                keys, SLOTS_PER_KES_PERIOD, MAX_KES_EVOLUTIONS, nonceState, null);
        byte[] epochNonce = nonceState.previewEpochNonceForSlot(10);
        DevnetBlockBuilder.BlockBuildResult block = builder.buildBlock(1, 10, null, List.of());
        Array headerArray = headerArrayFromWrapped(block.wrappedHeaderCbor());
        return new HeaderFixture(headerArray, wrap(headerArray), epochNonce);
    }

    private static HeaderValidationLedgerViewProvider ledgerViewProvider(BigInteger poolStake,
                                                                         BigInteger totalStake,
                                                                         boolean protocolMatches) {
        return ledgerViewProvider(poolStake, totalStake, protocolMatches, null);
    }

    private static HeaderValidationLedgerViewProvider ledgerViewProvider(BigInteger poolStake,
                                                                         BigInteger totalStake,
                                                                         boolean protocolMatches,
                                                                         Long opCertCounter) {
        return new HeaderValidationLedgerViewProvider() {
            @Override
            public Optional<LeaderStakeView> leaderStakeFor(ShelleyHeaderView header) {
                return Optional.of(new LeaderStakeView(
                        "pool",
                        LedgerStateHeaderValidationLedgerViewProvider.vrfKeyHash(header),
                        poolStake,
                        totalStake,
                        BigDecimal.ONE));
            }

            @Override
            public Optional<ProtocolView> protocolViewFor(ShelleyHeaderView header) {
                int major = protocolMatches
                        ? Math.toIntExact(header.protocolMajor())
                        : Math.toIntExact(header.protocolMajor() + 1);
                return Optional.of(new ProtocolView(
                        0,
                        major,
                        Math.toIntExact(header.protocolMinor()),
                        1100));
            }

            @Override
            public Optional<OpCertStateView> opCertStateFor(ShelleyHeaderView header) {
                return opCertCounter != null
                        ? Optional.of(new OpCertStateView("pool", opCertCounter))
                        : Optional.empty();
            }
        };
    }

    private static Array headerArrayFromWrapped(byte[] wrappedHeaderCbor) {
        Array wrapped = (Array) CborSerializationUtil.deserializeOne(wrappedHeaderCbor);
        ByteString inner = (ByteString) wrapped.getDataItems().get(1);
        return (Array) CborSerializationUtil.deserializeOne(inner.getBytes());
    }

    private static byte[] wrap(Array headerArray) {
        byte[] headerArrayBytes = CborSerializationUtil.serialize(headerArray);
        Array wrapped = new Array();
        wrapped.add(new UnsignedInteger(6));
        ByteString inner = new ByteString(headerArrayBytes);
        inner.setTag(24);
        wrapped.add(inner);
        return CborSerializationUtil.serialize(wrapped);
    }

    private record HeaderFixture(Array headerArray, byte[] wrappedHeaderCbor, byte[] epochNonce) {
        private HeaderFixture {
            epochNonce = epochNonce.clone();
        }

        public byte[] epochNonce() {
            return epochNonce.clone();
        }

        BlockHeader blockHeader() {
            return BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);
        }

        long opcertCounter() {
            Array body = (Array) headerArray.getDataItems().get(0);
            Array opcert = (Array) body.getDataItems().get(8);
            return ((UnsignedInteger) opcert.getDataItems().get(1)).getValue().longValueExact();
        }

        HeaderFixture withOpCertCounter(long counter) {
            Array copy = headerArrayFromWrapped(wrappedHeaderCbor);
            Array body = (Array) copy.getDataItems().get(0);
            Array opcert = (Array) body.getDataItems().get(8);
            opcert.getDataItems().set(1, new UnsignedInteger(counter));
            return new HeaderFixture(copy, wrap(copy), epochNonce);
        }

        HeaderFixture withKesSignatureByteFlipped() {
            Array copy = headerArrayFromWrapped(wrappedHeaderCbor);
            byte[] signature = ((ByteString) copy.getDataItems().get(1)).getBytes().clone();
            signature[0] ^= 0x01;
            copy.getDataItems().set(1, new ByteString(signature));
            return new HeaderFixture(copy, wrap(copy), epochNonce);
        }

        HeaderFixture withBadOpCertSignatureAndFreshKesSignature() {
            Array copy = headerArrayFromWrapped(wrappedHeaderCbor);
            Array body = (Array) copy.getDataItems().get(0);
            Array opcert = (Array) body.getDataItems().get(8);

            byte[] coldSignature = ((ByteString) opcert.getDataItems().get(3)).getBytes().clone();
            coldSignature[0] ^= 0x01;
            opcert.getDataItems().set(3, new ByteString(coldSignature));

            signHeaderBody(copy, body);
            return new HeaderFixture(copy, wrap(copy), epochNonce);
        }

        HeaderFixture withBadVrfProofAndFreshKesSignature() {
            Array copy = headerArrayFromWrapped(wrappedHeaderCbor);
            Array body = (Array) copy.getDataItems().get(0);
            Array vrfResult = (Array) body.getDataItems().get(5);

            byte[] proof = ((ByteString) vrfResult.getDataItems().get(1)).getBytes().clone();
            proof[0] ^= 0x01;
            vrfResult.getDataItems().set(1, new ByteString(proof));

            signHeaderBody(copy, body);
            return new HeaderFixture(copy, wrap(copy), epochNonce);
        }

        private static void signHeaderBody(Array headerArray, Array body) {
            byte[] bodyCbor = CborSerializationUtil.serialize(body);
            byte[] kesSignature = new BlockSigner().signHeaderBody(
                    keys.getKesSkey(),
                    bodyCbor,
                    0,
                    (int) keys.getOpCert().getKesPeriod());
            headerArray.getDataItems().set(1, new ByteString(kesSignature));
        }
    }
}
