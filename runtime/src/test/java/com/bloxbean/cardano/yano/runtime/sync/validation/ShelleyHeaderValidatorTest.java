package com.bloxbean.cardano.yano.runtime.sync.validation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockSigner;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilder;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        DevnetBlockBuilder.BlockBuildResult block = builder.buildBlock(1, 10, null, List.of());
        Array headerArray = headerArrayFromWrapped(block.wrappedHeaderCbor());
        return new HeaderFixture(headerArray, wrap(headerArray));
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

    private record HeaderFixture(Array headerArray, byte[] wrappedHeaderCbor) {
        BlockHeader blockHeader() {
            return BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);
        }

        HeaderFixture withKesSignatureByteFlipped() {
            Array copy = headerArrayFromWrapped(wrappedHeaderCbor);
            byte[] signature = ((ByteString) copy.getDataItems().get(1)).getBytes().clone();
            signature[0] ^= 0x01;
            copy.getDataItems().set(1, new ByteString(signature));
            return new HeaderFixture(copy, wrap(copy));
        }

        HeaderFixture withBadOpCertSignatureAndFreshKesSignature() {
            Array copy = headerArrayFromWrapped(wrappedHeaderCbor);
            Array body = (Array) copy.getDataItems().get(0);
            Array opcert = (Array) body.getDataItems().get(8);

            byte[] coldSignature = ((ByteString) opcert.getDataItems().get(3)).getBytes().clone();
            coldSignature[0] ^= 0x01;
            opcert.getDataItems().set(3, new ByteString(coldSignature));

            byte[] bodyCbor = CborSerializationUtil.serialize(body);
            byte[] kesSignature = new BlockSigner().signHeaderBody(
                    keys.getKesSkey(),
                    bodyCbor,
                    0,
                    (int) keys.getOpCert().getKesPeriod());
            copy.getDataItems().set(1, new ByteString(kesSignature));
            return new HeaderFixture(copy, wrap(copy));
        }
    }
}
