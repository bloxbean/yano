package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;

/**
 * Governed bridge-parameter change command (ADR-UTXO-009 §8/§9). Rides the
 * reserved topic as a privileged system submission; the machine accumulates
 * approvals from distinct membership-epoch members on the EXACT command
 * bytes and schedules the parameters at {@code height + max(1, lag)}.
 */
public final class EutxoBridgeParamsGovernanceV1 {
    public static final String TOPIC = "~governance/eutxo-bridge-params";
    public static final int VERSION = 1;
    public static final int MAX_COMMAND_BYTES = 512;
    public static final long MAX_ACTIVATION_LAG = 100_000L;

    private EutxoBridgeParamsGovernanceV1() {
    }

    /** {@code params.effectiveHeight} must be zero — the height is assigned at activation. */
    public record Command(
            int version,
            EutxoBridgeParams params,
            long activationLag
    ) {
        public Command {
            if (version != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported bridge params command version");
            }
            java.util.Objects.requireNonNull(params, "params");
            if (params.effectiveHeight() != 0) {
                throw new IllegalArgumentException(
                        "command params must not pre-assign an effective height");
            }
            if (activationLag < 0 || activationLag > MAX_ACTIVATION_LAG) {
                throw new IllegalArgumentException(
                        "activation lag is outside 0-" + MAX_ACTIVATION_LAG);
            }
        }

        public byte[] encode() {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                new CborEncoder(out).encode(new CborBuilder()
                        .addArray()
                        .add(new UnsignedInteger(version))
                        .add(new ByteString(params.encode()))
                        .add(new UnsignedInteger(activationLag))
                        .end()
                        .build());
                return out.toByteArray();
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "cannot encode bridge params command", failure);
            }
        }

        /** Approval accumulation key: hash of the exact command bytes. */
        public String digestHex() {
            return HexFormat.of().formatHex(
                    Blake2bUtil.blake2bHash256(encode()));
        }
    }

    public static Command decode(byte[] bytes) {
        if (bytes == null || bytes.length < 1 || bytes.length > MAX_COMMAND_BYTES) {
            throw new IllegalArgumentException(
                    "bridge params command must contain 1-"
                            + MAX_COMMAND_BYTES + " bytes");
        }
        try {
            List<DataItem> items = new CborDecoder(
                    new ByteArrayInputStream(bytes)).decode();
            if (items.size() != 1 || !(items.getFirst() instanceof Array array)
                    || array.getDataItems().size() != 3) {
                throw new IllegalArgumentException(
                        "bridge params command must be a 3-field CBOR array");
            }
            List<DataItem> fields = array.getDataItems();
            if (!(fields.get(0) instanceof UnsignedInteger version)
                    || !(fields.get(1) instanceof ByteString params)
                    || !(fields.get(2) instanceof UnsignedInteger lag)) {
                throw new IllegalArgumentException(
                        "bridge params command fields are malformed");
            }
            return new Command(
                    version.getValue().intValueExact(),
                    EutxoBridgeParams.decode(params.getBytes()),
                    lag.getValue().longValueExact());
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "malformed bridge params command", failure);
        }
    }
}
