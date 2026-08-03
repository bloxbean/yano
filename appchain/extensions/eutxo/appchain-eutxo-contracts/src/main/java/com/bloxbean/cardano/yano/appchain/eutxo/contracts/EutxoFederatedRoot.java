package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Threshold-controlled app-chain MPF root accepted by the Cardano bridge. */
public record EutxoFederatedRoot(
        int abiVersion,
        String chainId,
        long bridgeEpoch,
        long height,
        byte[] stateRoot,
        List<byte[]> memberKeys,
        int threshold,
        long generation
) {
    public static final int ABI_VERSION = 1;
    public static final int MAX_MEMBERS = 32;

    public EutxoFederatedRoot {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported federated root ABI");
        }
        chainId = Objects.requireNonNull(chainId, "chainId").trim();
        int chainBytes = chainId.getBytes(StandardCharsets.UTF_8).length;
        if (chainBytes < 1 || chainBytes > 128) {
            throw new IllegalArgumentException(
                    "root chain id must contain 1-128 UTF-8 bytes");
        }
        if (bridgeEpoch < 0 || height < 0 || generation < 0) {
            throw new IllegalArgumentException(
                    "root epoch, height, and generation cannot be negative");
        }
        stateRoot = exact(stateRoot, 32, "state root");
        memberKeys = Objects.requireNonNull(memberKeys, "memberKeys").stream()
                .map(key -> exact(key, 32, "member key"))
                .toList();
        if (memberKeys.isEmpty() || memberKeys.size() > MAX_MEMBERS
                || threshold < 1 || threshold > memberKeys.size()) {
            throw new IllegalArgumentException(
                    "federated root member profile is out of bounds");
        }
        for (int i = 1; i < memberKeys.size(); i++) {
            if (Arrays.compareUnsigned(
                    memberKeys.get(i - 1), memberKeys.get(i)) >= 0) {
                throw new IllegalArgumentException(
                        "federated root member keys must be sorted and unique");
            }
        }
    }

    public PlutusData toPlutusData() {
        List<PlutusData> keys = memberKeys.stream()
                .map(BytesPlutusData::of)
                .map(PlutusData.class::cast)
                .toList();
        return ConstrPlutusData.of(
                0,
                BigIntPlutusData.of(abiVersion),
                BytesPlutusData.of(chainId.getBytes(StandardCharsets.UTF_8)),
                BigIntPlutusData.of(bridgeEpoch),
                BigIntPlutusData.of(height),
                BytesPlutusData.of(stateRoot),
                new ListPlutusData(keys, false),
                BigIntPlutusData.of(threshold),
                BigIntPlutusData.of(generation));
    }

    public byte[] encode() {
        return toPlutusData().serializeToBytes();
    }

    public static EutxoFederatedRoot decode(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        try {
            PlutusData decoded = PlutusData.deserialize(cbor);
            if (!(decoded instanceof ConstrPlutusData constructor)
                    || constructor.getAlternative() != 0
                    || !Arrays.equals(cbor, decoded.serializeToBytes())) {
                throw new IllegalArgumentException(
                        "federated root must use canonical constructor zero");
            }
            List<PlutusData> fields =
                    constructor.getData().getPlutusDataList();
            if (fields.size() != 8
                    || !(fields.get(5) instanceof ListPlutusData members)) {
                throw new IllegalArgumentException(
                        "federated root must contain eight canonical fields");
            }
            List<byte[]> memberKeys = members.getPlutusDataList().stream()
                    .map(value -> bytes(value, "member key"))
                    .toList();
            return new EutxoFederatedRoot(
                    integer(fields.get(0), "ABI version").intValueExact(),
                    string(fields.get(1), "chain id"),
                    integer(fields.get(2), "bridge epoch").longValueExact(),
                    integer(fields.get(3), "height").longValueExact(),
                    bytes(fields.get(4), "state root"),
                    memberKeys,
                    integer(fields.get(6), "threshold").intValueExact(),
                    integer(fields.get(7), "generation").longValueExact());
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid federated root datum", failure);
        }
    }

    public boolean accepts(EutxoProofWithdrawal withdrawal) {
        Objects.requireNonNull(withdrawal, "withdrawal");
        EutxoWithdrawalCommitment claim = withdrawal.commitment();
        return Arrays.equals(
                chainId.getBytes(StandardCharsets.UTF_8), claim.chainId())
                && bridgeEpoch == claim.bridgeEpoch()
                && height == withdrawal.proof().committedHeight()
                && Arrays.equals(stateRoot, withdrawal.proof().stateRoot());
    }

    @Override
    public byte[] stateRoot() {
        return stateRoot.clone();
    }

    @Override
    public List<byte[]> memberKeys() {
        return memberKeys.stream().map(byte[]::clone).toList();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoFederatedRoot root
                && abiVersion == root.abiVersion
                && bridgeEpoch == root.bridgeEpoch
                && height == root.height
                && threshold == root.threshold
                && generation == root.generation
                && chainId.equals(root.chainId)
                && Arrays.equals(stateRoot, root.stateRoot)
                && arrayListEquals(memberKeys, root.memberKeys);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                abiVersion,
                chainId,
                bridgeEpoch,
                height,
                threshold,
                generation);
        result = 31 * result + Arrays.hashCode(stateRoot);
        for (byte[] memberKey : memberKeys) {
            result = 31 * result + Arrays.hashCode(memberKey);
        }
        return result;
    }

    private static boolean arrayListEquals(
            List<byte[]> left,
            List<byte[]> right
    ) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!Arrays.equals(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] bytes(PlutusData data, String field) {
        if (!(data instanceof BytesPlutusData value)) {
            throw new IllegalArgumentException(
                    field + " must be Plutus bytes");
        }
        return value.getValue();
    }

    private static BigInteger integer(PlutusData data, String field) {
        if (!(data instanceof BigIntPlutusData value)) {
            throw new IllegalArgumentException(
                    field + " must be a Plutus integer");
        }
        return value.getValue();
    }

    private static String string(PlutusData data, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes(data, field)))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    field + " must be canonical UTF-8", failure);
        }
    }

    private static byte[] exact(byte[] value, int length, String field) {
        byte[] copy = Objects.requireNonNull(value, field).clone();
        if (copy.length != length) {
            throw new IllegalArgumentException(
                    field + " must contain " + length + " bytes");
        }
        return copy;
    }
}
