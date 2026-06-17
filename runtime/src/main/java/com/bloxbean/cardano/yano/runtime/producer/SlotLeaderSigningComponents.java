package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateStore;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolVersionSupplier;
import com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilder;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderCheck;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Signed block-building components that must share the same block signer.
 */
public record SlotLeaderSigningComponents(
        SignedBlockBuilder signedBlockBuilder,
        SlotLeaderCheck slotLeaderCheck) {

    public SlotLeaderSigningComponents {
        Objects.requireNonNull(signedBlockBuilder, "signedBlockBuilder");
        Objects.requireNonNull(slotLeaderCheck, "slotLeaderCheck");
    }

    public static SlotLeaderSigningComponents create(SlotLeaderKeyMaterial keyMaterial,
                                                     long slotsPerKESPeriod,
                                                     long maxKESEvolutions,
                                                     EpochNonceState epochNonceState,
                                                     NonceStateStore nonceStore,
                                                     ProtocolVersionSupplier protocolVersionSupplier,
                                                     double activeSlotsCoeff) {
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        Objects.requireNonNull(epochNonceState, "epochNonceState");
        Objects.requireNonNull(protocolVersionSupplier, "protocolVersionSupplier");

        var signedBlockBuilder = new SignedBlockBuilder(
                keyMaterial.keys(),
                slotsPerKESPeriod,
                maxKESEvolutions,
                epochNonceState,
                nonceStore,
                protocolVersionSupplier);
        var slotLeaderCheck = new SlotLeaderCheck(
                keyMaterial.keys().getVrfSkey(),
                BigDecimal.valueOf(activeSlotsCoeff),
                signedBlockBuilder.getBlockSigner());
        return new SlotLeaderSigningComponents(signedBlockBuilder, slotLeaderCheck);
    }
}
