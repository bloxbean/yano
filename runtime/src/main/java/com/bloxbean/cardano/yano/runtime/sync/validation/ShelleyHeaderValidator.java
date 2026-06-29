package com.bloxbean.cardano.yano.runtime.sync.validation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.crypto.config.CryptoExtConfiguration;
import com.bloxbean.cardano.client.crypto.kes.KesVerifier;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shelley and later header validator.
 *
 * <p>`structural` validates the header CBOR shape and declared hash.
 * `header-signature` additionally validates the KES body signature and
 * operational-certificate cold signature.</p>
 */
public final class ShelleyHeaderValidator implements HeaderValidator {
    private static final int HASH_SIZE = 32;
    private static final int VKEY_SIZE = 32;
    private static final int VRF_OUTPUT_SIZE = 64;
    private static final int VRF_PROOF_SIZE = 80;
    private static final int KES_SIGNATURE_SIZE = 448;
    private static final int COLD_SIGNATURE_SIZE = 64;

    private final String level;
    private final boolean verifyHeaderSignature;
    private final long slotsPerKESPeriod;
    private final long maxKESEvolutions;
    private final KesVerifier kesVerifier;
    private final SigningProvider signingProvider;
    private final AtomicLong acceptedHeaders = new AtomicLong();
    private final AtomicLong rejectedHeaders = new AtomicLong();
    private volatile String lastRejectedStage;
    private volatile String lastRejectedReason;

    public ShelleyHeaderValidator(String level, long slotsPerKESPeriod, long maxKESEvolutions) {
        this.level = normalize(level);
        this.verifyHeaderSignature = "header-signature".equals(this.level);
        this.slotsPerKESPeriod = slotsPerKESPeriod > 0 ? slotsPerKESPeriod : 129600;
        this.maxKESEvolutions = maxKESEvolutions > 0 ? maxKESEvolutions : 62;
        this.kesVerifier = CryptoExtConfiguration.INSTANCE.getKesVerifier();
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
    }

    @Override
    public HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes) {
        HeaderValidationResult result;
        try {
            result = validate(blockHeader, originalHeaderBytes);
        } catch (Exception e) {
            result = reject("exception", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        if (result.accepted()) {
            acceptedHeaders.incrementAndGet();
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
                level,
                acceptedHeaders.get(),
                rejectedHeaders.get(),
                lastRejectedStage,
                lastRejectedReason);
    }

    private HeaderValidationResult validate(BlockHeader blockHeader, byte[] originalHeaderBytes) {
        if (blockHeader == null || blockHeader.getHeaderBody() == null) {
            return reject("shape", "missing decoded Shelley header body");
        }
        if (originalHeaderBytes == null || originalHeaderBytes.length == 0) {
            return reject("shape", "missing original header bytes");
        }

        HeaderView header = extractHeader(originalHeaderBytes);
        BodyView body = BodyView.from(header.body());

        var decoded = blockHeader.getHeaderBody();
        requireEquals("block-number", body.blockNumber(), decoded.getBlockNumber());
        requireEquals("slot", body.slot(), decoded.getSlot());
        requireHexEquals("previous-hash", body.previousHash(), decoded.getPrevHash());
        requireHexEquals("issuer-vkey", body.issuerVkey(), decoded.getIssuerVkey());
        requireHexEquals("vrf-vkey", body.vrfVkey(), decoded.getVrfVkey());
        requireHexEquals("body-hash", body.blockBodyHash(), decoded.getBlockBodyHash());
        requireEquals("body-size", body.blockBodySize(), decoded.getBlockBodySize());

        byte[] computedHash = Blake2bUtil.blake2bHash256(header.headerArrayBytes());
        String computedHashHex = HexUtil.encodeHexString(computedHash);
        if (!computedHashHex.equalsIgnoreCase(decoded.getBlockHash())) {
            return reject("header-hash", "computed block hash does not match decoded header hash");
        }

        if (blockHeader.getBodySignature() != null) {
            requireHexEquals("body-signature", header.signature(), blockHeader.getBodySignature());
        }

        if (verifyHeaderSignature) {
            HeaderValidationResult signatureResult = validateSignatures(header, body);
            if (!signatureResult.accepted()) {
                return signatureResult;
            }
        }

        return HeaderValidationResult.accepted(level);
    }

    private HeaderValidationResult validateSignatures(HeaderView header, BodyView body) {
        int currentKesPeriod = Math.toIntExact(body.slot() / slotsPerKESPeriod);
        int opcertKesPeriod = Math.toIntExact(body.opcertKesPeriod());
        int relativePeriod = currentKesPeriod - opcertKesPeriod;
        if (relativePeriod < 0 || relativePeriod >= maxKESEvolutions) {
            return reject("kes-period", "KES relative period is outside op-cert evolution window");
        }

        boolean kesValid = kesVerifier.verify(
                header.signature(),
                header.headerBodyBytes(),
                body.opcertKesVkey(),
                relativePeriod);
        if (!kesValid) {
            return reject("kes-signature", "KES signature does not verify over header body");
        }

        byte[] signedOpCert = opcertSignedData(body.opcertKesVkey(), body.opcertCounter(), body.opcertKesPeriod());
        boolean opcertValid = signingProvider.verify(body.opcertColdSignature(), signedOpCert, body.issuerVkey());
        if (!opcertValid) {
            return reject("opcert-signature", "operational certificate cold signature does not verify");
        }

        return HeaderValidationResult.accepted(level);
    }

    private static HeaderView extractHeader(byte[] originalHeaderBytes) {
        DataItem root = CborSerializationUtil.deserializeOne(originalHeaderBytes);
        if (root instanceof ByteString byteString) {
            byte[] inner = byteString.getBytes();
            return fromHeaderArrayBytes(inner);
        }
        if (root instanceof Array rootArray) {
            List<DataItem> items = rootArray.getDataItems();
            if (items.size() == 2 && items.get(1) instanceof ByteString wrappedHeader
                    && !(items.get(0) instanceof Array)) {
                return fromHeaderArrayBytes(wrappedHeader.getBytes());
            }
            return fromHeaderArray(rootArray, CborSerializationUtil.serialize(rootArray));
        }
        throw new IllegalArgumentException("unsupported Shelley header CBOR root: " + root.getClass().getSimpleName());
    }

    private static HeaderView fromHeaderArrayBytes(byte[] headerArrayBytes) {
        DataItem inner = CborSerializationUtil.deserializeOne(headerArrayBytes);
        if (!(inner instanceof Array headerArray)) {
            throw new IllegalArgumentException("Shelley header payload is not a CBOR array");
        }
        return fromHeaderArray(headerArray, headerArrayBytes);
    }

    private static HeaderView fromHeaderArray(Array headerArray, byte[] headerArrayBytes) {
        List<DataItem> items = headerArray.getDataItems();
        if (items.size() != 2) {
            throw new IllegalArgumentException("Shelley header array must have 2 items");
        }
        if (!(items.get(0) instanceof Array body)) {
            throw new IllegalArgumentException("Shelley header body is not a CBOR array");
        }
        byte[] signature = bytes(items.get(1), "KES signature");
        requireLength("KES signature", signature, KES_SIGNATURE_SIZE);
        return new HeaderView(headerArrayBytes, body, CborSerializationUtil.serialize(body), signature);
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

    private HeaderValidationResult reject(String stage, String reason) {
        return HeaderValidationResult.rejected(level, stage, reason);
    }

    private static void requireEquals(String field, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalArgumentException(field + " mismatch");
        }
    }

    private static void requireHexEquals(String field, byte[] actual, String expectedHex) {
        if (expectedHex == null || expectedHex.isBlank()) {
            if (actual.length == 0) {
                return;
            }
            throw new IllegalArgumentException(field + " expected value is missing");
        }
        byte[] expected = HexUtil.decodeHexString(expectedHex);
        if (!java.util.Arrays.equals(actual, expected)) {
            throw new IllegalArgumentException(field + " mismatch");
        }
    }

    private static byte[] bytes(DataItem item, String field) {
        if (item == SimpleValue.NULL) {
            return new byte[0];
        }
        if (!(item instanceof ByteString byteString)) {
            throw new IllegalArgumentException(field + " must be a byte string");
        }
        return byteString.getBytes();
    }

    private static long uint(DataItem item, String field) {
        try {
            return CborSerializationUtil.toBigInteger(item).longValueExact();
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be an unsigned integer", e);
        }
    }

    private static void requireLength(String field, byte[] bytes, int expected) {
        if (bytes.length != expected) {
            throw new IllegalArgumentException(field + " must be " + expected + " bytes");
        }
    }

    private static void requireVrfCert(DataItem item, String field) {
        if (!(item instanceof Array cert)) {
            throw new IllegalArgumentException(field + " must be a VRF cert array");
        }
        List<DataItem> items = cert.getDataItems();
        if (items.size() != 2) {
            throw new IllegalArgumentException(field + " must have output and proof");
        }
        requireLength(field + " output", bytes(items.get(0), field + " output"), VRF_OUTPUT_SIZE);
        requireLength(field + " proof", bytes(items.get(1), field + " proof"), VRF_PROOF_SIZE);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "structural"
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private record HeaderView(byte[] headerArrayBytes, Array body, byte[] headerBodyBytes, byte[] signature) {
    }

    private record BodyView(
            long blockNumber,
            long slot,
            byte[] previousHash,
            byte[] issuerVkey,
            byte[] vrfVkey,
            long blockBodySize,
            byte[] blockBodyHash,
            byte[] opcertKesVkey,
            long opcertCounter,
            long opcertKesPeriod,
            byte[] opcertColdSignature
    ) {
        static BodyView from(Array body) {
            List<DataItem> items = body.getDataItems();
            if (items.size() == 10 && items.get(8) instanceof Array) {
                return postBabbage(items);
            }
            if (items.size() == 15) {
                return preBabbage(items);
            }
            throw new IllegalArgumentException("unsupported Shelley header body size: " + items.size());
        }

        private static BodyView postBabbage(List<DataItem> items) {
            requireVrfCert(items.get(5), "vrf-result");
            List<DataItem> opcert = opcertItems(items.get(8));
            requireProtocolVersion(items.get(9));
            return common(
                    items,
                    6,
                    7,
                    opcert);
        }

        private static BodyView preBabbage(List<DataItem> items) {
            requireVrfCert(items.get(5), "nonce-vrf");
            requireVrfCert(items.get(6), "leader-vrf");
            requireProtocolVersion(items.get(13), items.get(14));
            return common(
                    items,
                    7,
                    8,
                    List.of(items.get(9), items.get(10), items.get(11), items.get(12)));
        }

        private static BodyView common(List<DataItem> items,
                                       int bodySizeIndex,
                                       int bodyHashIndex,
                                       List<DataItem> opcert) {
            byte[] previousHash = bytes(items.get(2), "previous hash");
            if (previousHash.length > 0) {
                requireLength("previous hash", previousHash, HASH_SIZE);
            }
            byte[] issuerVkey = bytes(items.get(3), "issuer vkey");
            byte[] vrfVkey = bytes(items.get(4), "vrf vkey");
            byte[] bodyHash = bytes(items.get(bodyHashIndex), "block body hash");
            byte[] opcertKesVkey = bytes(opcert.get(0), "opcert KES vkey");
            byte[] opcertColdSignature = bytes(opcert.get(3), "opcert cold signature");

            requireLength("issuer vkey", issuerVkey, VKEY_SIZE);
            requireLength("vrf vkey", vrfVkey, VKEY_SIZE);
            requireLength("block body hash", bodyHash, HASH_SIZE);
            requireLength("opcert KES vkey", opcertKesVkey, VKEY_SIZE);
            requireLength("opcert cold signature", opcertColdSignature, COLD_SIGNATURE_SIZE);

            return new BodyView(
                    uint(items.get(0), "block number"),
                    uint(items.get(1), "slot"),
                    previousHash,
                    issuerVkey,
                    vrfVkey,
                    uint(items.get(bodySizeIndex), "block body size"),
                    bodyHash,
                    opcertKesVkey,
                    uint(opcert.get(1), "opcert counter"),
                    uint(opcert.get(2), "opcert KES period"),
                    opcertColdSignature);
        }

        private static List<DataItem> opcertItems(DataItem item) {
            if (!(item instanceof Array opcert)) {
                throw new IllegalArgumentException("operational certificate must be a CBOR array");
            }
            List<DataItem> items = opcert.getDataItems();
            if (items.size() != 4) {
                throw new IllegalArgumentException("operational certificate must have 4 fields");
            }
            return items;
        }

        private static void requireProtocolVersion(DataItem item) {
            if (!(item instanceof Array protocolVersion)) {
                throw new IllegalArgumentException("protocol version must be a CBOR array");
            }
            List<DataItem> items = protocolVersion.getDataItems();
            if (items.size() != 2) {
                throw new IllegalArgumentException("protocol version must have major and minor");
            }
            requireProtocolVersion(items.get(0), items.get(1));
        }

        private static void requireProtocolVersion(DataItem major, DataItem minor) {
            uint(major, "protocol major");
            uint(minor, "protocol minor");
        }
    }
}
