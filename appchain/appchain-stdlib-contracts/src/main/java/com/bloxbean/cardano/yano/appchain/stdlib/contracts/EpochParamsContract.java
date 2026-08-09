package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Stable wire, key, and query contract for the ADR-028 epoch-params component. */
public final class EpochParamsContract {
    public static final String STATE_MACHINE_ID = "epoch-params";
    public static final String OBSERVER_TYPE = "l1-epoch-params-v1";
    public static final String DEFAULT_OBSERVER_ID = "epoch-params";
    public static final String QUERY_PATH = "params";
    public static final String LATEST_QUERY_PATH = "params/latest";
    public static final String PROOF_SUBJECT = "cardano-history/epoch-params-v1";
    private static final byte[] PREFIX = "params/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LATEST_KEY = "params/latest".getBytes(StandardCharsets.US_ASCII);

    private EpochParamsContract() {
    }

    public record Claim(long effectiveEpoch, byte[] canonicalParamsCbor) {
        public Claim {
            if (effectiveEpoch < 0 || canonicalParamsCbor == null
                    || canonicalParamsCbor.length == 0
                    || canonicalParamsCbor.length > 8 * 1024) {
                throw new IllegalArgumentException("invalid epoch-params claim");
            }
            canonicalParamsCbor = canonicalParamsCbor.clone();
        }
        @Override public byte[] canonicalParamsCbor() { return canonicalParamsCbor.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Claim that && effectiveEpoch == that.effectiveEpoch
                    && Arrays.equals(canonicalParamsCbor, that.canonicalParamsCbor);
        }
        @Override public int hashCode() {
            return 31 * Long.hashCode(effectiveEpoch) + Arrays.hashCode(canonicalParamsCbor);
        }
    }

    public static byte[] encodeClaim(Claim claim) {
        Array array = new Array();
        array.add(new UnsignedInteger(claim.effectiveEpoch()));
        array.add(new ByteString(claim.canonicalParamsCbor()));
        return StdlibContractCbor.encode(array);
    }

    public static Claim decodeClaim(byte[] bytes) {
        Array array = StdlibContractCbor.decodeArray(bytes, 2);
        return new Claim(StdlibContractCbor.uint(array.getDataItems().get(0)),
                StdlibContractCbor.bytes(array.getDataItems().get(1)));
    }

    public static byte[] stateKey(long epoch) {
        if (epoch < 0) throw new IllegalArgumentException("epoch must be nonnegative");
        return ("params/" + epoch).getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] latestKey() { return LATEST_KEY.clone(); }
    public static byte[] encodeEpoch(long epoch) {
        if (epoch < 0) throw new IllegalArgumentException("epoch must be nonnegative");
        return ByteBuffer.allocate(Long.BYTES).putLong(epoch).array();
    }
    public static long decodeEpoch(byte[] bytes) {
        if (bytes == null || bytes.length != Long.BYTES) {
            throw new IllegalArgumentException("invalid epoch value");
        }
        long epoch = ByteBuffer.wrap(bytes).getLong();
        if (epoch < 0) throw new IllegalArgumentException("invalid epoch value");
        return epoch;
    }
    public static byte[] query(long epoch) { return encodeEpoch(epoch); }
    public static boolean owns(byte[] key) {
        if (key == null || key.length < PREFIX.length) return false;
        for (int i = 0; i < PREFIX.length; i++) if (key[i] != PREFIX[i]) return false;
        return true;
    }
}
