package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentEngine;

/** Service-loaded provider for {@code state:eutxo-ledger}. */
public final class EutxoStateMachineProvider implements AppStateMachineProvider {

    @Override
    public String id() {
        return EutxoStateMachine.ID;
    }

    @Override
    public AppStateMachine create() {
        EutxoProfile profile = EutxoProfile.V2;
        return new EutxoStateMachine(
                profile,
                EutxoGenesis.from(java.util.Map.of()),
                new KeyPaymentTransitionEngine(profile),
                EutxoBridgeConfig.disabled());
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        String configuredProfile = context.settings()
                .getOrDefault("machines.eutxo.profile", EutxoProfile.V2.id());
        EutxoProfile profile = switch (configuredProfile) {
            case "yano-eutxo-v1" -> EutxoProfile.V1;
            case "yano-eutxo-v2-plutus-v3" -> EutxoProfile.V2;
            default -> throw new IllegalArgumentException(
                    "unsupported EUTxO profile: " + configuredProfile);
        };
        String expectedDigest =
                context.settings().get("machines.eutxo.expected-profile-digest");
        if (expectedDigest != null && !expectedDigest.isBlank()
                && !profile.digestHex().equals(expectedDigest.trim())) {
            throw new IllegalArgumentException(
                    "configured EUTxO profile digest does not match " + profile.id());
        }
        String network = context.settings().getOrDefault(
                "machines.eutxo.network", "devnet");
        EutxoValidityCommitmentEngine validity =
                EutxoValidityEngines.discover(
                        context.chainId(), profile, context.settings());
        KeyPaymentTransitionEngine.DomainPolicy domainPolicy =
                validity == null ? null : new KeyPaymentTransitionEngine.DomainPolicy(
                        context.chainId(), network, validity.profileDigest());
        return new EutxoStateMachine(
                profile,
                EutxoGenesis.from(context.settings()),
                new KeyPaymentTransitionEngine(
                        profile,
                        CryptoConfiguration.INSTANCE.getSigningProvider(),
                        profile.scriptsEnabled()
                                ? new ScalusPlutusV3Evaluator() : null,
                        domainPolicy),
                EutxoBridgeConfig.from(context.chainId(), context.settings()),
                validity,
                context.chainId(),
                network);
    }
}
