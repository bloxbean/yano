package com.bloxbean.cardano.yano.appchain.effects.cardano;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ServiceLoader factory for the Cardano payment executor (ADR-010 FX-M4).
 * Activated when {@code yano.app-chain.effects.executors.cardano.*} is
 * configured:
 * <pre>
 *   effects.executors.cardano.backend-url       Blockfrost-compatible base URL
 *                                               (typically this node's REST, ADR-018)
 *   effects.executors.cardano.signing-mnemonic  wallet mnemonic (or…)
 *   effects.executors.cardano.signing-account-key  bech32/hex account private key
 *   effects.executors.cardano.network           mainnet|preprod|preview (default preprod)
 *   effects.executors.cardano.metadata-label    default 21042
 * </pre>
 * Secrets live in executor config on the executor node only — never in
 * effect payloads (ADR-010 F11).
 */
public class CardanoEffectExecutorFactory implements AppEffectExecutorFactory {

    private static final Logger log = LoggerFactory.getLogger(CardanoEffectExecutorFactory.class);

    @Override
    public String scheme() {
        return "cardano";
    }

    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        String backendUrl = config.getOrDefault("backend-url", "").trim();
        String mnemonic = config.getOrDefault("signing-mnemonic", "").trim();
        String accountKey = config.getOrDefault("signing-account-key", "").trim();
        if (backendUrl.isEmpty() || (mnemonic.isEmpty() && accountKey.isEmpty())) {
            log.warn("cardano effect executor for '{}' not started: backend-url and a signing "
                    + "credential (signing-mnemonic or signing-account-key) are required", chainId);
            return List.of();
        }
        Network network = switch (config.getOrDefault("network", "preprod")
                .trim().toLowerCase(Locale.ROOT)) {
            case "mainnet" -> Networks.mainnet();
            case "preview" -> Networks.preview();
            case "preprod" -> Networks.preprod();
            default -> throw new IllegalArgumentException(
                    "effects.executors.cardano.network must be mainnet|preprod|preview");
        };
        Account account = !mnemonic.isEmpty()
                ? new Account(network, mnemonic)
                : Account.createFromAccountKey(network,
                        com.bloxbean.cardano.client.util.HexUtil.decodeHexString(accountKey));
        long metadataLabel = Long.parseLong(config.getOrDefault("metadata-label", "21042").trim());
        long maxLovelacePerTx = Long.parseLong(
                config.getOrDefault("max-lovelace-per-tx", "0").trim()); // 0 = uncapped
        BFBackendService backendService = new BFBackendService(
                backendUrl.endsWith("/") ? backendUrl : backendUrl + "/", "");
        if (maxLovelacePerTx <= 0) {
            log.warn("cardano effect executor for '{}' has NO per-tx amount cap — set "
                    + "effects.executors.cardano.max-lovelace-per-tx and fund the payer wallet "
                    + "conservatively (a buggy/compromised machine could otherwise drain it)", chainId);
        }
        log.info("cardano effect executor ready for '{}': payer={}, label={}, maxPerTx={}",
                chainId, account.baseAddress(), metadataLabel,
                maxLovelacePerTx > 0 ? maxLovelacePerTx : "uncapped");
        return List.of(new CardanoPaymentExecutor(backendService, account, network, metadataLabel,
                maxLovelacePerTx));
    }
}
