package org.yanoproject.runtime.producer;

import org.yanoproject.runtime.blockproducer.EpochNonceState;
import org.yanoproject.runtime.blockproducer.BlockBodySizeLimitSupplier;
import org.yanoproject.runtime.blockproducer.NonceStateStore;
import org.yanoproject.runtime.blockproducer.ProtocolVersionSupplier;
import org.yanoproject.runtime.blockproducer.SignedBlockBuilder;
import org.yanoproject.runtime.blockproducer.SlotLeaderCheck;

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
        return create(keyMaterial, slotsPerKESPeriod, maxKESEvolutions, epochNonceState, nonceStore,
                protocolVersionSupplier, BlockBodySizeLimitSupplier.unbounded(), activeSlotsCoeff);
    }

    public static SlotLeaderSigningComponents create(SlotLeaderKeyMaterial keyMaterial,
                                                     long slotsPerKESPeriod,
                                                     long maxKESEvolutions,
                                                     EpochNonceState epochNonceState,
                                                     NonceStateStore nonceStore,
                                                     ProtocolVersionSupplier protocolVersionSupplier,
                                                     BlockBodySizeLimitSupplier blockBodySizeLimitSupplier,
                                                     double activeSlotsCoeff) {
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        Objects.requireNonNull(epochNonceState, "epochNonceState");
        Objects.requireNonNull(protocolVersionSupplier, "protocolVersionSupplier");
        Objects.requireNonNull(blockBodySizeLimitSupplier, "blockBodySizeLimitSupplier");

        var signedBlockBuilder = new SignedBlockBuilder(
                keyMaterial.keys(),
                slotsPerKESPeriod,
                maxKESEvolutions,
                epochNonceState,
                nonceStore,
                protocolVersionSupplier,
                blockBodySizeLimitSupplier);
        var slotLeaderCheck = new SlotLeaderCheck(
                keyMaterial.keys().getVrfSkey(),
                BigDecimal.valueOf(activeSlotsCoeff),
                signedBlockBuilder.getBlockSigner());
        return new SlotLeaderSigningComponents(signedBlockBuilder, slotLeaderCheck);
    }
}
