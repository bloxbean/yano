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
    private final long maxLovelacePerTx;

    CardanoPaymentExecutor(BackendService backendService, Account account, Network network,
                           long metadataLabel, long maxLovelacePerTx) {
        this.backendService = backendService;
        this.account = account;
        this.network = network;
        this.metadataLabel = metadataLabel;
        this.maxLovelacePerTx = maxLovelacePerTx;
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
    public synchronized EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect)
            throws Exception {
        // synchronized: single-flight per payer wallet — parallel payments
        // from one account coin-select the same UTxOs and collide (M4 review)
        // Re-poll path (Submitted persisted earlier): probe by tx hash — NEVER
        // rebuild/resubmit here, that is the double-payment bug (ADR-010 F11)
        if (ctx.submittedRef().length > 0) {
            return pollSubmitted(new String(ctx.submittedRef(), StandardCharsets.UTF_8));
        }
        PaymentCommand command = PaymentCommand.decode(effect.payload());
        if (command == null) {
            return EffectExecution.failed("undecodable cardano.payment payload", false);
        }
        if (maxLovelacePerTx > 0 && command.lovelace() > maxLovelacePerTx) {
            // Blast-radius cap (final review): a compromised/buggy machine
            // cannot drain the hot wallet in one payment. Definitive — an
            // operator must raise the cap or handle the payment out of band.
            return EffectExecution.failed("payment of " + command.lovelace()
                    + " lovelace exceeds max-lovelace-per-tx (" + maxLovelacePerTx + ")", false);
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
            // ALL submit failures are retryable (M4 review): UTxO-contention
            // errors (BadInputsUTxO etc.) succeed on retry with fresh inputs,
            // and a spurious definitive FAILED would go on-chain for a payment
            // a later attempt could make. The attempt cap parks true failures
            // for the operator instead.
            return EffectExecution.failed("submit failed: " + submission.getResponse(), true);
        }
        String txHash = submission.getValue();
        // completeAndWait returns isSuccessful()=true even when the WAIT timed
        // out (TxResult.getTxStatus()=TIMEOUT/PENDING, verified against CCL
        // 0.8.0-pre4) — only CONFIRMED may confirm; anything else re-polls by
        // hash on later attempts (M4 review: false-confirmation fix)
        if (submission instanceof com.bloxbean.cardano.client.quicktx.TxResult txResult
                && txResult.getTxStatus() != com.bloxbean.cardano.client.quicktx.TxStatus.CONFIRMED) {
            log.info("cardano.payment {} submitted, awaiting confirmation: {}",
                    effect.effectId().canonical(), txHash);
            return EffectExecution.submitted(txHash.getBytes(StandardCharsets.UTF_8));
        }
        log.info("cardano.payment {} confirmed: {}", effect.effectId().canonical(), txHash);
        return EffectExecution.confirmed(txHash.getBytes(StandardCharsets.UTF_8));
    }

    /** Probe a previously submitted tx by hash; confirm only on chain presence. */
    private EffectExecution pollSubmitted(String txHash) {
        try {
            var result = backendService.getTransactionService().getTransaction(txHash);
            if (result != null && result.isSuccessful()) {
                log.info("cardano.payment confirmed on re-poll: {}", txHash);
                return EffectExecution.confirmed(txHash.getBytes(StandardCharsets.UTF_8));
            }
            // Not on chain yet — keep waiting (deterministic effect expiry is
            // the eventual escape hatch; we never rebuild a second tx)
            return EffectExecution.submitted(txHash.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return EffectExecution.submitted(txHash.getBytes(StandardCharsets.UTF_8));
        }
    }

    Network network() {
        return network;
    }
}
