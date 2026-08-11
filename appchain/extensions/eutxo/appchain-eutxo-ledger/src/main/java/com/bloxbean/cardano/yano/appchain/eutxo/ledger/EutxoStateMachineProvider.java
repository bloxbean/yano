package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentProvider;

/** Service-loaded provider for {@code state:eutxo-ledger}. */
public final class EutxoStateMachineProvider implements AppStateMachineProvider {
    private final EutxoValidityCommitmentProvider packagedValidityProvider;

    public EutxoStateMachineProvider() {
        this(null);
    }

    public EutxoStateMachineProvider(
            EutxoValidityCommitmentProvider packagedValidityProvider
    ) {
        this.packagedValidityProvider = packagedValidityProvider;
    }

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
            case "yano-eutxo-v3-bridge-settlement" -> EutxoProfile.V3;
            case "yano-eutxo-v3-bridge-settlement-devnet" -> EutxoProfile.V3_DEVNET;
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
        // ADR-UTXO-009 §13.2: the relaxed settlement profile exists ONLY for
        // demos. Refuse to construct it anywhere but devnet, so a chain
        // holding real funds can never run a sub-production fallback floor.
        if (profile.devnetOnly() && !"devnet".equals(network)) {
            throw new IllegalArgumentException(
                    "profile " + profile.id() + " is devnet-only but the chain"
                            + " is configured for network '" + network + "'");
        }
        EutxoValidityCommitmentEngine validity =
                EutxoValidityEngines.discover(
                        context.chainId(), profile, context.settings(),
                        packagedValidityProvider);
        KeyPaymentTransitionEngine.DomainPolicy domainPolicy =
                validity == null ? null : new KeyPaymentTransitionEngine.DomainPolicy(
                        context.chainId(), network, validity);
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
                network,
                context.membershipView().orElse(null),
                initialBridgeParams(context.settings()));
    }

    /**
     * Genesis values for the governed bridge parameters (ADR-UTXO-009 tier
     * 2). Config supplies only the INITIAL record; later values activate
     * through governance, never through config.
     */
    private static com.bloxbean.cardano.yano.appchain.eutxo.contracts
            .EutxoBridgeParams initialBridgeParams(
            java.util.Map<String, String> settings) {
        var defaults = com.bloxbean.cardano.yano.appchain.eutxo.contracts
                .EutxoBridgeParams.defaults();
        String prefix = "machines.eutxo.bridge.params.";
        return new com.bloxbean.cardano.yano.appchain.eutxo.contracts
                .EutxoBridgeParams(
                com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoBridgeParams.VERSION,
                longSetting(settings, prefix + "fee-flat-lovelace",
                        defaults.feeFlatLovelace()),
                (int) longSetting(settings, prefix + "fee-basis-points",
                        defaults.feeBasisPoints()),
                longSetting(settings, prefix + "min-withdrawal-lovelace",
                        defaults.minWithdrawalLovelace()),
                (int) longSetting(settings, prefix + "soft-batch-cap",
                        defaults.softBatchCap()),
                longSetting(settings, prefix + "rooting-blocks",
                        defaults.rootingBlocks()),
                longSetting(settings, prefix + "rooting-seconds",
                        defaults.rootingSeconds()),
                longSetting(settings, prefix + "fallback-delay-slots",
                        defaults.fallbackDelaySlots()),
                0L);
    }

    private static long longSetting(
            java.util.Map<String, String> settings, String key, long fallback) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }
}
