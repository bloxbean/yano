package com.bloxbean.cardano.yano.appchain.effects.cardano;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * {@code cardano.payment} executor (ADR app-layer/010 FX-M4, §8.1): builds,
 * signs and submits an L1 payment for an approved effect, stamps the
 * deterministic effect id into tx metadata (the on-chain idempotency
 * breadcrumb), then polls the tx by hash until it is on chain.
 * <p>
 * Payload (CBOR map or JSON): {@code {"to": bech32-address, "lovelace": uint,
 * "memo"?: tstr}}. Config ({@code effects.executors.cardano.*}):
 * {@code backend-url} (Blockfrost-compatible base — typically THIS node's
 * REST per ADR-018), {@code signing-mnemonic} or {@code signing-account-key}
 * (bech32/hex — secrets live HERE, never in payloads, ADR-010 F11),
 * {@code network} (mainnet|preprod|preview|custom protocol magic),
 * {@code metadata-label} (default 21042).
 * <p>
 * Lifecycle: one attempt builds+signs+submits and BLOCKS until the tx is on
 * chain ({@code completeAndWait}), then returns {@code Confirmed(txHash)} —
 * the bounded worker pool absorbs the wait. The structural duplicate window
 * (crash between submit and the runtime persisting the outcome) is the
 * documented at-least-once residue (ADR-010 §11): reconcile via the metadata
 * label, which carries the effect id on every payment this executor makes.
 */
public class CardanoPaymentExecutor implements AppEffectExecutor {

    static final String TYPE = "cardano.payment";

    private static final Logger log = LoggerFactory.getLogger(CardanoPaymentExecutor.class);

    private final BackendService backendService;
    private final Account account;
    private final Network network;
    private final long metadataLabel;

    CardanoPaymentExecutor(BackendService backendService, Account account, Network network,
                           long metadataLabel) {
        this.backendService = backendService;
        this.account = account;
        this.network = network;
        this.metadataLabel = metadataLabel;
    }

    @Override
    public String id() {
        return "cardano";
    }

    @Override
    public boolean supports(String effectType) {
        return TYPE.equals(effectType);
    }

    @Override
    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) throws Exception {
        PaymentCommand command = PaymentCommand.decode(effect.payload());
        if (command == null) {
            return EffectExecution.failed("undecodable cardano.payment payload", false);
        }

        Metadata metadata = MetadataBuilder.createMetadata();
        MetadataMap entry = MetadataBuilder.createMap();
        entry.put("fx", effect.effectId().canonical());
        entry.put("key", HexUtil.encodeHexString(effect.idHash()));
        if (command.memo() != null && !command.memo().isBlank()) {
            entry.put("memo", command.memo());
        }
        metadata.put(BigInteger.valueOf(metadataLabel), entry);

        Tx tx = new Tx()
                .payToAddress(command.to(), Amount.lovelace(BigInteger.valueOf(command.lovelace())))
                .attachMetadata(metadata)
                .from(account.baseAddress());

        Result<String> submission = new QuickTxBuilder(backendService)
                .compose(tx)
                .withSigner(com.bloxbean.cardano.client.function.helper.SignerProviders
                        .signerFrom(account))
                .completeAndWait(status -> log.debug("cardano.payment {}: {}",
                        effect.effectId().canonical(), status));

        if (!submission.isSuccessful()) {
            String response = String.valueOf(submission.getResponse());
            // Value/UTxO errors are definitive for THIS payload; transport
            // errors are retryable
            boolean definitive = response.contains("ValueNotConserved")
                    || response.contains("BadInputsUTxO")
                    || response.contains("OutsideValidityInterval");
            return EffectExecution.failed("submit failed: " + response, !definitive);
        }
        String txHash = submission.getValue();
        log.info("cardano.payment {} submitted: {}", effect.effectId().canonical(), txHash);
        return EffectExecution.confirmed(txHash.getBytes(StandardCharsets.UTF_8));
    }

    Network network() {
        return network;
    }
}
