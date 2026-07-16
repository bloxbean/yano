package com.bloxbean.cardano.yano.api.appchain.anchor;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Strict public codec for the ADR-008.4 script-anchor datum ABI v1.
 *
 * <p>The wire form is {@code Constr(0, [1, chain-id, height, block-hash,
 * state-root, member-keys, threshold])}. Decoding rejects non-canonical CBOR,
 * unsupported versions, malformed UTF-8, unsorted/duplicate members, and all
 * field profiles that cannot be a valid v1 anchor.</p>
 */
public record AnchorDatumV1(String chainId,
                            long height,
                            byte[] blockHash,
                            byte[] stateRoot,
                            List<byte[]> memberKeys,
                            int threshold) {
    public static final long ABI_VERSION = 1;
    /** Maximum canonical v1 inline datum size accepted by the portable codec. */
    public static final int MAX_DATUM_BYTES = 8 * 1024;
    private static final int HASH_BYTES = 32;

    public AnchorDatumV1 {
        byte[] chainBytes = validatedChainBytes(chainId);
        if (chainBytes.length == 0 || height < 0
                || blockHash == null || blockHash.length != HASH_BYTES
                || stateRoot == null || stateRoot.length != HASH_BYTES
                || memberKeys == null || memberKeys.isEmpty()
                || memberKeys.size() > AppChainConfig.MAX_MEMBERS
                || threshold < 1 || threshold > memberKeys.size()) {
            throw invalid();
        }
        blockHash = blockHash.clone();
        stateRoot = stateRoot.clone();
        memberKeys = canonicalMembers(memberKeys, false);
    }

    @Override
    public byte[] blockHash() {
        return blockHash.clone();
    }

    @Override
    public byte[] stateRoot() {
        return stateRoot.clone();
    }

    @Override
    public List<byte[]> memberKeys() {
        return deepCopy(memberKeys);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AnchorDatumV1 that)
                || height != that.height || threshold != that.threshold
                || !chainId.equals(that.chainId)
                || !Arrays.equals(blockHash, that.blockHash)
                || !Arrays.equals(stateRoot, that.stateRoot)
                || memberKeys.size() != that.memberKeys.size()) {
            return false;
        }
        for (int index = 0; index < memberKeys.size(); index++) {
            if (!Arrays.equals(memberKeys.get(index), that.memberKeys.get(index))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(chainId, height, threshold);
        result = 31 * result + Arrays.hashCode(blockHash);
        result = 31 * result + Arrays.hashCode(stateRoot);
        for (byte[] member : memberKeys) {
            result = 31 * result + Arrays.hashCode(member);
        }
        return result;
    }

    @Override
    public String toString() {
        return "AnchorDatumV1[chainId=" + chainId + ", height=" + height
                + ", blockHash=" + HexFormat.of().formatHex(blockHash)
                + ", stateRoot=" + HexFormat.of().formatHex(stateRoot)
                + ", memberKeys=" + memberKeysHex()
                + ", threshold=" + threshold + "]";
    }

    /** Encodes this datum as canonical inline-datum CBOR bytes. */
    public byte[] encode() {
        ListPlutusData members = ListPlutusData.builder().build();
        for (byte[] member : memberKeys) {
            members.add(BytesPlutusData.of(member));
        }
        return ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        BigIntPlutusData.of(ABI_VERSION),
                        BytesPlutusData.of(validatedChainBytes(chainId)),
                        BigIntPlutusData.of(height),
                        BytesPlutusData.of(blockHash),
                        BytesPlutusData.of(stateRoot),
                        members,
                        BigIntPlutusData.of(threshold)))
                .build()
                .serializeToBytes();
    }

    /** Decodes and validates canonical inline-datum CBOR bytes. */
    public static AnchorDatumV1 decode(byte[] cbor) {
        if (cbor == null || cbor.length == 0 || cbor.length > MAX_DATUM_BYTES) {
            throw invalid();
        }
        if (!CborStructurePreflight.accepts(cbor, MAX_DATUM_BYTES, 4, 1_024)) {
            throw invalid();
        }
        try {
            PlutusData data = PlutusData.deserialize(cbor);
            if (!(data instanceof ConstrPlutusData constr)
                    || constr.getAlternative() != 0) {
                throw invalid();
            }
            List<PlutusData> fields = constr.getData().getPlutusDataList();
            if (fields.size() != 7 || asLong(fields.get(0)) != ABI_VERSION) {
                throw invalid();
            }
            String chainId = decodeChainId(asBytes(fields.get(1)));
            long height = asLong(fields.get(2));
            byte[] blockHash = asBytes(fields.get(3));
            byte[] stateRoot = asBytes(fields.get(4));
            List<PlutusData> encodedMembers = asList(fields.get(5)).getPlutusDataList();
            if (encodedMembers.isEmpty()
                    || encodedMembers.size() > AppChainConfig.MAX_MEMBERS) {
                throw invalid();
            }
            List<byte[]> members = new ArrayList<>(encodedMembers.size());
            for (PlutusData encodedMember : encodedMembers) {
                members.add(asBytes(encodedMember));
            }
            canonicalMembers(members, true);
            long threshold = asLong(fields.get(6));
            if (threshold > Integer.MAX_VALUE) {
                throw invalid();
            }
            AnchorDatumV1 datum = new AnchorDatumV1(chainId, height, blockHash,
                    stateRoot, members, (int) threshold);
            if (!Arrays.equals(cbor, datum.encode())) {
                throw invalid();
            }
            return datum;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    /**
     * Exact state-thread token unit used by new v1 script anchors: the 28-byte
     * policy id followed by the chain-id UTF-8 bytes truncated to 32 bytes.
     */
    public static String threadTokenUnit(String threadPolicyId, String chainId) {
        if (threadPolicyId == null || !threadPolicyId.matches("[0-9a-f]{56}")) {
            throw invalid();
        }
        byte[] chainBytes = validatedChainBytes(chainId);
        byte[] tokenName = chainBytes.length <= HASH_BYTES
                ? chainBytes : Arrays.copyOf(chainBytes, HASH_BYTES);
        return threadPolicyId + HexFormat.of().formatHex(tokenName);
    }

    /** Member keys as canonical lower-case hex in ABI order. */
    public List<String> memberKeysHex() {
        return memberKeys.stream().map(HexFormat.of()::formatHex).toList();
    }

    private static List<byte[]> canonicalMembers(List<byte[]> members,
                                                  boolean requireAlreadySorted) {
        List<byte[]> copied = new ArrayList<>(members.size());
        for (byte[] member : members) {
            if (member == null || member.length != HASH_BYTES) {
                throw invalid();
            }
            copied.add(member.clone());
        }
        List<byte[]> sorted = new ArrayList<>(copied);
        sorted.sort(Arrays::compareUnsigned);
        for (int index = 1; index < sorted.size(); index++) {
            if (Arrays.equals(sorted.get(index - 1), sorted.get(index))) {
                throw invalid();
            }
        }
        if (requireAlreadySorted) {
            for (int index = 0; index < copied.size(); index++) {
                if (!Arrays.equals(copied.get(index), sorted.get(index))) {
                    throw invalid();
                }
            }
        }
        return List.copyOf(sorted);
    }

    private static List<byte[]> deepCopy(List<byte[]> values) {
        return values.stream().map(byte[]::clone).toList();
    }

    private static long asLong(PlutusData data) {
        if (data instanceof BigIntPlutusData integer) {
            return integer.getValue().longValueExact();
        }
        throw invalid();
    }

    private static byte[] asBytes(PlutusData data) {
        if (data instanceof BytesPlutusData bytes) {
            return bytes.getValue();
        }
        throw invalid();
    }

    private static ListPlutusData asList(PlutusData data) {
        if (data instanceof ListPlutusData list) {
            return list;
        }
        throw invalid();
    }

    private static byte[] validatedChainBytes(String chainId) {
        if (chainId == null || chainId.isBlank() || chainId.indexOf('\0') >= 0) {
            throw invalid();
        }
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(chainId));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException malformed) {
            throw invalid();
        }
        if (bytes.length == 0 || bytes.length > AppChainConfig.MAX_CHAIN_ID_BYTES) {
            throw invalid();
        }
        return bytes;
    }

    private static String decodeChainId(byte[] bytes) {
        if (bytes == null || bytes.length == 0
                || bytes.length > AppChainConfig.MAX_CHAIN_ID_BYTES) {
            throw invalid();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid script-anchor datum v1");
    }
}
