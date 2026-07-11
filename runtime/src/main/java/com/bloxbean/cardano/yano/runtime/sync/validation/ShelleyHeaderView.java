package com.bloxbean.cardano.yano.runtime.sync.validation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

/**
 * Parsed Shelley+ header data shared by staged validators.
 */
public record ShelleyHeaderView(
        byte[] headerArrayBytes,
        byte[] headerBodyBytes,
        byte[] signature,
        long blockNumber,
        long slot,
        byte[] previousHash,
        byte[] issuerVkey,
        byte[] vrfVkey,
        byte[] nonceVrfOutput,
        byte[] nonceVrfProof,
        byte[] leaderVrfOutput,
        byte[] leaderVrfProof,
        long blockBodySize,
        byte[] blockBodyHash,
        byte[] opcertKesVkey,
        long opcertCounter,
        long opcertKesPeriod,
        byte[] opcertColdSignature,
        long protocolMajor,
        long protocolMinor
) {
    private static final int HASH_SIZE = 32;
    private static final int VKEY_SIZE = 32;
    private static final int VRF_OUTPUT_SIZE = 64;
    private static final int VRF_PROOF_SIZE = 80;
    private static final int KES_SIGNATURE_SIZE = 448;
    private static final int COLD_SIGNATURE_SIZE = 64;

    public ShelleyHeaderView {
        headerArrayBytes = headerArrayBytes.clone();
        headerBodyBytes = headerBodyBytes.clone();
        signature = signature.clone();
        previousHash = previousHash.clone();
        issuerVkey = issuerVkey.clone();
        vrfVkey = vrfVkey.clone();
        nonceVrfOutput = nonceVrfOutput.clone();
        nonceVrfProof = nonceVrfProof.clone();
        leaderVrfOutput = leaderVrfOutput.clone();
        leaderVrfProof = leaderVrfProof.clone();
        blockBodyHash = blockBodyHash.clone();
        opcertKesVkey = opcertKesVkey.clone();
        opcertColdSignature = opcertColdSignature.clone();
    }

    public static ShelleyHeaderView from(BlockHeader blockHeader, byte[] originalHeaderBytes) {
        if (blockHeader == null || blockHeader.getHeaderBody() == null) {
            throw new HeaderValidationFailure("shape", "missing decoded Shelley header body");
        }
        if (originalHeaderBytes == null || originalHeaderBytes.length == 0) {
            throw new HeaderValidationFailure("shape", "missing original header bytes");
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
            throw new HeaderValidationFailure("header-hash", "computed block hash does not match decoded header hash");
        }

        if (blockHeader.getBodySignature() != null) {
            requireHexEquals("body-signature", header.signature(), blockHeader.getBodySignature());
        }

        return new ShelleyHeaderView(
                header.headerArrayBytes(),
                header.headerBodyBytes(),
                header.signature(),
                body.blockNumber(),
                body.slot(),
                body.previousHash(),
                body.issuerVkey(),
                body.vrfVkey(),
                body.nonceVrfOutput(),
                body.nonceVrfProof(),
                body.leaderVrfOutput(),
                body.leaderVrfProof(),
                body.blockBodySize(),
                body.blockBodyHash(),
                body.opcertKesVkey(),
                body.opcertCounter(),
                body.opcertKesPeriod(),
                body.opcertColdSignature(),
                body.protocolMajor(),
                body.protocolMinor());
    }

    @Override
    public byte[] headerArrayBytes() {
        return headerArrayBytes.clone();
    }

    @Override
    public byte[] headerBodyBytes() {
        return headerBodyBytes.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    @Override
    public byte[] previousHash() {
        return previousHash.clone();
    }

    @Override
    public byte[] issuerVkey() {
        return issuerVkey.clone();
    }

    @Override
    public byte[] vrfVkey() {
        return vrfVkey.clone();
    }

    @Override
    public byte[] nonceVrfOutput() {
        return nonceVrfOutput.clone();
    }

    @Override
    public byte[] nonceVrfProof() {
        return nonceVrfProof.clone();
    }

    @Override
    public byte[] leaderVrfOutput() {
        return leaderVrfOutput.clone();
    }

    @Override
    public byte[] leaderVrfProof() {
        return leaderVrfProof.clone();
    }

    @Override
    public byte[] blockBodyHash() {
        return blockBodyHash.clone();
    }

    @Override
    public byte[] opcertKesVkey() {
        return opcertKesVkey.clone();
    }

    @Override
    public byte[] opcertColdSignature() {
        return opcertColdSignature.clone();
    }

    public boolean hasSeparateNonceVrf() {
        return nonceVrfOutput.length > 0 || nonceVrfProof.length > 0;
    }

    private static HeaderView extractHeader(byte[] originalHeaderBytes) {
        DataItem root = CborSerializationUtil.deserializeOne(originalHeaderBytes);
        if (root instanceof ByteString byteString) {
            return fromHeaderArrayBytes(byteString.getBytes());
        }
        if (root instanceof Array rootArray) {
            List<DataItem> items = rootArray.getDataItems();
            if (items.size() == 2 && items.get(1) instanceof ByteString wrappedHeader
                    && !(items.get(0) instanceof Array)) {
                return fromHeaderArrayBytes(wrappedHeader.getBytes());
            }
            return fromHeaderArray(rootArray, CborSerializationUtil.serialize(rootArray));
        }
        throw new HeaderValidationFailure("shape",
                "unsupported Shelley header CBOR root: " + root.getClass().getSimpleName());
    }

    private static HeaderView fromHeaderArrayBytes(byte[] headerArrayBytes) {
        DataItem inner = CborSerializationUtil.deserializeOne(headerArrayBytes);
        if (!(inner instanceof Array headerArray)) {
            throw new HeaderValidationFailure("shape", "Shelley header payload is not a CBOR array");
        }
        return fromHeaderArray(headerArray, headerArrayBytes);
    }

    private static HeaderView fromHeaderArray(Array headerArray, byte[] headerArrayBytes) {
        List<DataItem> items = headerArray.getDataItems();
        if (items.size() != 2) {
            throw new HeaderValidationFailure("shape", "Shelley header array must have 2 items");
        }
        if (!(items.get(0) instanceof Array body)) {
            throw new HeaderValidationFailure("shape", "Shelley header body is not a CBOR array");
        }
        byte[] signature = bytes(items.get(1), "KES signature");
        requireLength("KES signature", signature, KES_SIGNATURE_SIZE);
        return new HeaderView(headerArrayBytes, body, CborSerializationUtil.serialize(body), signature);
    }

    private static void requireEquals(String field, long actual, long expected) {
        if (actual != expected) {
            throw new HeaderValidationFailure(field, field + " mismatch");
        }
    }

    private static void requireHexEquals(String field, byte[] actual, String expectedHex) {
        if (expectedHex == null || expectedHex.isBlank()) {
            if (actual.length == 0) {
                return;
            }
            throw new HeaderValidationFailure(field, field + " expected value is missing");
        }
        byte[] expected = HexUtil.decodeHexString(expectedHex);
        if (!java.util.Arrays.equals(actual, expected)) {
            throw new HeaderValidationFailure(field, field + " mismatch");
        }
    }

    private static byte[] bytes(DataItem item, String field) {
        if (item == SimpleValue.NULL) {
            return new byte[0];
        }
        if (!(item instanceof ByteString byteString)) {
            throw new HeaderValidationFailure(field, field + " must be a byte string");
        }
        return byteString.getBytes();
    }

    private static long uint(DataItem item, String field) {
        try {
            return CborSerializationUtil.toBigInteger(item).longValueExact();
        } catch (Exception e) {
            throw new HeaderValidationFailure(field, field + " must be an unsigned integer");
        }
    }

    private static void requireLength(String field, byte[] bytes, int expected) {
        if (bytes.length != expected) {
            throw new HeaderValidationFailure(field, field + " must be " + expected + " bytes");
        }
    }

    private static VrfCert vrfCert(DataItem item, String field) {
        if (!(item instanceof Array cert)) {
            throw new HeaderValidationFailure(field, field + " must be a VRF cert array");
        }
        List<DataItem> items = cert.getDataItems();
        if (items.size() != 2) {
            throw new HeaderValidationFailure(field, field + " must have output and proof");
        }
        byte[] output = bytes(items.get(0), field + " output");
        byte[] proof = bytes(items.get(1), field + " proof");
        requireLength(field + " output", output, VRF_OUTPUT_SIZE);
        requireLength(field + " proof", proof, VRF_PROOF_SIZE);
        return new VrfCert(output, proof);
    }

    private static ProtocolVersionView requireProtocolVersion(DataItem major) {
        if (!(major instanceof Array protocolVersion)) {
            throw new HeaderValidationFailure("protocol-version", "protocol version must be a CBOR array");
        }
        List<DataItem> items = protocolVersion.getDataItems();
        if (items.size() != 2) {
            throw new HeaderValidationFailure("protocol-version", "protocol version must have major and minor");
        }
        return requireProtocolVersion(items.get(0), items.get(1));
    }

    private static ProtocolVersionView requireProtocolVersion(DataItem major, DataItem minor) {
        return new ProtocolVersionView(
                uint(major, "protocol major"),
                uint(minor, "protocol minor"));
    }

    private record HeaderView(byte[] headerArrayBytes, Array body, byte[] headerBodyBytes, byte[] signature) {
    }

    private record VrfCert(byte[] output, byte[] proof) {
    }

    private record ProtocolVersionView(long major, long minor) {
    }

    private record BodyView(
            long blockNumber,
            long slot,
            byte[] previousHash,
            byte[] issuerVkey,
            byte[] vrfVkey,
            byte[] nonceVrfOutput,
            byte[] nonceVrfProof,
            byte[] leaderVrfOutput,
            byte[] leaderVrfProof,
            long blockBodySize,
            byte[] blockBodyHash,
            byte[] opcertKesVkey,
            long opcertCounter,
            long opcertKesPeriod,
            byte[] opcertColdSignature,
            long protocolMajor,
            long protocolMinor
    ) {
        static BodyView from(Array body) {
            List<DataItem> items = body.getDataItems();
            if (items.size() == 10 && items.get(8) instanceof Array) {
                return postBabbage(items);
            }
            if (items.size() == 15) {
                return preBabbage(items);
            }
            throw new HeaderValidationFailure("shape", "unsupported Shelley header body size: " + items.size());
        }

        private static BodyView postBabbage(List<DataItem> items) {
            VrfCert leader = vrfCert(items.get(5), "vrf-result");
            List<DataItem> opcert = opcertItems(items.get(8));
            ProtocolVersionView protocolVersion = requireProtocolVersion(items.get(9));
            return common(items, 6, 7, opcert, null, leader, protocolVersion);
        }

        private static BodyView preBabbage(List<DataItem> items) {
            VrfCert nonce = vrfCert(items.get(5), "nonce-vrf");
            VrfCert leader = vrfCert(items.get(6), "leader-vrf");
            ProtocolVersionView protocolVersion = requireProtocolVersion(items.get(13), items.get(14));
            return common(
                    items,
                    7,
                    8,
                    List.of(items.get(9), items.get(10), items.get(11), items.get(12)),
                    nonce,
                    leader,
                    protocolVersion);
        }

        private static BodyView common(List<DataItem> items,
                                       int bodySizeIndex,
                                       int bodyHashIndex,
                                       List<DataItem> opcert,
                                       VrfCert nonceVrf,
                                       VrfCert leaderVrf,
                                       ProtocolVersionView protocolVersion) {
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
                    nonceVrf != null ? nonceVrf.output() : new byte[0],
                    nonceVrf != null ? nonceVrf.proof() : new byte[0],
                    leaderVrf.output(),
                    leaderVrf.proof(),
                    uint(items.get(bodySizeIndex), "block body size"),
                    bodyHash,
                    opcertKesVkey,
                    uint(opcert.get(1), "opcert counter"),
                    uint(opcert.get(2), "opcert KES period"),
                    opcertColdSignature,
                    protocolVersion.major(),
                    protocolVersion.minor());
        }

        private static List<DataItem> opcertItems(DataItem item) {
            if (!(item instanceof Array opcert)) {
                throw new HeaderValidationFailure("opcert", "operational certificate must be a CBOR array");
            }
            List<DataItem> items = opcert.getDataItems();
            if (items.size() != 4) {
                throw new HeaderValidationFailure("opcert", "operational certificate must have 4 fields");
            }
            return items;
        }
    }
}
