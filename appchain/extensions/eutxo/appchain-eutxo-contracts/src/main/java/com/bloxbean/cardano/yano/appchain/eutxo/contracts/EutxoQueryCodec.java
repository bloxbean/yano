package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/** Frozen query paths and bounded request/response codecs. */
public final class EutxoQueryCodec {
    public static final String OUTPOINT_PATH = "utxos/outpoint";
    public static final String ADDRESS_PATH = "utxos/address";
    public static final String TRANSACTION_PATH = "transactions/receipt";
    public static final String ATTEMPT_PATH = "attempts/receipt";
    public static final String DEPOSIT_PATH = "bridge/deposits/record";
    public static final String RESERVE_PATH = "bridge/reserve";
    public static final String WITHDRAWAL_PATH = "bridge/withdrawals/record";
    public static final String VALIDITY_TRANSITION_PATH =
            "validity/transitions/finalized";
    public static final String L2_PARAMETERS_PATH = "protocol-parameters";
    public static final String PROFILE_PATH = "profile";

    private EutxoQueryCodec() {
    }

    public static byte[] outpointRequest(EutxoOutpoint outpoint) {
        return Objects.requireNonNull(outpoint, "outpoint")
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    public static EutxoOutpoint decodeOutpointRequest(byte[] bytes) {
        return EutxoOutpoint.parse(boundedUtf8(bytes, 80, "outpoint"));
    }

    public static byte[] addressRequest(String address) {
        String normalized = Objects.requireNonNull(address, "address").trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException("address must contain 1-256 characters");
        }
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    public static String decodeAddressRequest(byte[] bytes) {
        return boundedUtf8(bytes, 256, "address");
    }

    public static byte[] transactionRequest(String transactionId) {
        return new EutxoOutpoint(transactionId, 0).transactionId()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static String decodeTransactionRequest(byte[] bytes) {
        return new EutxoOutpoint(boundedUtf8(bytes, 64, "transaction id"), 0)
                .transactionId();
    }

    public static byte[] attemptRequest(byte[] appMessageId) {
        Objects.requireNonNull(appMessageId, "appMessageId");
        if (appMessageId.length != 32) {
            throw new IllegalArgumentException("app message id must contain 32 bytes");
        }
        return appMessageId.clone();
    }

    public static byte[] decodeAttemptRequest(byte[] bytes) {
        return attemptRequest(bytes);
    }

    public static byte[] optionalRecord(EutxoRecord record) {
        return EutxoCbor.encodeOptionalRecord(record);
    }

    public static EutxoRecord decodeOptionalRecord(byte[] bytes) {
        return EutxoCbor.decodeOptionalRecord(bytes);
    }

    public static byte[] records(List<EutxoRecord> records) {
        return EutxoCbor.encodeRecords(records);
    }

    public static List<EutxoRecord> decodeRecords(byte[] bytes) {
        return EutxoCbor.decodeRecords(bytes);
    }

    public static byte[] optionalReceipt(EutxoReceipt receipt) {
        return EutxoCbor.encodeOptionalReceipt(receipt);
    }

    public static EutxoReceipt decodeOptionalReceipt(byte[] bytes) {
        return EutxoCbor.decodeOptionalReceipt(bytes);
    }

    public static byte[] depositRequest(EutxoOutpoint acceptedOutpoint) {
        return outpointRequest(acceptedOutpoint);
    }

    public static EutxoOutpoint decodeDepositRequest(byte[] bytes) {
        return decodeOutpointRequest(bytes);
    }

    public static byte[] reserveRequest(String assetId) {
        String normalized = Objects.requireNonNull(assetId, "assetId").trim();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw new IllegalArgumentException("asset id must contain 1-120 characters");
        }
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    public static String decodeReserveRequest(byte[] bytes) {
        return boundedUtf8(bytes, 120, "asset id");
    }

    public static byte[] optionalDepositRecord(EutxoDepositRecord record) {
        return EutxoCbor.encodeOptionalDepositRecord(record);
    }

    public static EutxoDepositRecord decodeOptionalDepositRecord(byte[] bytes) {
        return EutxoCbor.decodeOptionalDepositRecord(bytes);
    }

    public static byte[] optionalReserve(EutxoReserve reserve) {
        return EutxoCbor.encodeOptionalReserve(reserve);
    }

    public static EutxoReserve decodeOptionalReserve(byte[] bytes) {
        return EutxoCbor.decodeOptionalReserve(bytes);
    }

    public static byte[] withdrawalRequest(String claimId) {
        return transactionRequest(claimId);
    }

    public static String decodeWithdrawalRequest(byte[] bytes) {
        return decodeTransactionRequest(bytes);
    }

    public static byte[] optionalWithdrawalRecord(EutxoWithdrawalRecord record) {
        return EutxoCbor.encodeOptionalWithdrawalRecord(record);
    }

    public static EutxoWithdrawalRecord decodeOptionalWithdrawalRecord(byte[] bytes) {
        return EutxoCbor.decodeOptionalWithdrawalRecord(bytes);
    }

    public static byte[] validityTransitionRequest(
            long appHeight,
            int ordinal
    ) {
        if (appHeight < 1 || ordinal < 0) {
            throw new IllegalArgumentException(
                    "invalid validity transition position");
        }
        return ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                .putLong(appHeight).putInt(ordinal).array();
    }

    public static Position decodeValidityTransitionRequest(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != Long.BYTES + Integer.BYTES) {
            throw new IllegalArgumentException(
                    "invalid validity transition request");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes);
        Position position = new Position(
                input.getLong(), input.getInt());
        if (position.appHeight() < 1 || position.ordinal() < 0) {
            throw new IllegalArgumentException(
                    "invalid validity transition position");
        }
        return position;
    }

    public static byte[] optionalValidityTransition(
            EutxoValidityTransition transition
    ) {
        if (transition == null) {
            return new byte[]{0};
        }
        byte[] encoded = transition.canonicalBytes();
        return ByteBuffer.allocate(1 + Integer.BYTES + encoded.length)
                .put((byte) 1).putInt(encoded.length).put(encoded).array();
    }

    public static EutxoValidityTransition decodeOptionalValidityTransition(
            byte[] bytes
    ) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 1 && bytes[0] == 0) {
            return null;
        }
        if (bytes.length < 1 + Integer.BYTES || bytes[0] != 1) {
            throw new IllegalArgumentException(
                    "invalid optional validity transition");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes);
        input.get();
        int length = input.getInt();
        if (length < 1 || length != input.remaining()) {
            throw new IllegalArgumentException(
                    "invalid optional validity transition length");
        }
        byte[] encoded = new byte[length];
        input.get(encoded);
        return EutxoValidityTransition.decode(encoded);
    }

    public static byte[] l2Parameters(EutxoL2ParameterSnapshot snapshot) {
        return Objects.requireNonNull(snapshot, "snapshot").encode();
    }

    public static EutxoL2ParameterSnapshot decodeL2Parameters(byte[] bytes) {
        return EutxoL2ParameterSnapshot.decode(bytes);
    }

    public record Position(long appHeight, int ordinal) {
    }

    private static String boundedUtf8(byte[] bytes, int maximum, String field) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > maximum) {
            throw new IllegalArgumentException(field + " request is empty or too large");
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(value.getBytes(StandardCharsets.UTF_8), bytes)) {
            throw new IllegalArgumentException(field + " request is not canonical UTF-8");
        }
        return value;
    }
}
