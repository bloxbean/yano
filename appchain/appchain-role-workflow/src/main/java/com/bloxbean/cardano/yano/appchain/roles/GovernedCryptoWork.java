package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;

import java.nio.ByteBuffer;

/** Shared, block-scoped governed-signature work fence stored in actor state. */
public final class GovernedCryptoWork {
    private static final int ENCODED_BYTES = Long.BYTES + Integer.BYTES;

    private GovernedCryptoWork() {
    }

    public static boolean reserve(
            AppStateWriter actorState,
            long height,
            int units,
            int maximumUnits
    ) {
        if (height < 1 || units < 0 || maximumUnits < 1) {
            throw new IllegalArgumentException("invalid governed crypto-work reservation");
        }
        byte[] key = RoleWorkflowKeys.cryptoWork();
        byte[] encoded = actorState.get(key).orElse(null);
        int used = 0;
        if (encoded != null) {
            if (encoded.length != ENCODED_BYTES) {
                throw new IllegalStateException("corrupt governed crypto-work counter");
            }
            ByteBuffer value = ByteBuffer.wrap(encoded);
            long recordedHeight = value.getLong();
            int recordedUnits = value.getInt();
            if (recordedHeight < 1 || recordedUnits < 0
                    || recordedUnits > maximumUnits) {
                throw new IllegalStateException("corrupt governed crypto-work counter");
            }
            if (recordedHeight == height) {
                used = recordedUnits;
            } else if (recordedHeight > height) {
                throw new IllegalStateException("governed crypto-work height moved backwards");
            }
        }
        if (units > maximumUnits - used) {
            return false;
        }
        actorState.put(key, ByteBuffer.allocate(ENCODED_BYTES)
                .putLong(height).putInt(used + units).array());
        return true;
    }
}
