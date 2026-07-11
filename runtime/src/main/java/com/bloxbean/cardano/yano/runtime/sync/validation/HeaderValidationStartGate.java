package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationStartConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Defers configured header validation until the selected era/checkpoint start.
 */
public final class HeaderValidationStartGate implements HeaderValidator {
    private static final Logger log = LoggerFactory.getLogger(HeaderValidationStartGate.class);

    private final HeaderValidator delegate;
    private final UpstreamValidationStartConfig start;
    private final EraStartSlotProvider eraStartSlotProvider;
    private final AtomicBoolean loggedDeferred = new AtomicBoolean();
    private final AtomicBoolean loggedActive = new AtomicBoolean();

    private HeaderValidationStartGate(HeaderValidator delegate,
                                      UpstreamValidationStartConfig start,
                                      EraStartSlotProvider eraStartSlotProvider) {
        this.delegate = delegate != null ? delegate : HeaderValidator.none();
        this.start = start != null ? start : UpstreamValidationStartConfig.builder().build();
        this.eraStartSlotProvider = eraStartSlotProvider != null
                ? eraStartSlotProvider : era -> OptionalLong.empty();
    }

    public static HeaderValidator wrap(HeaderValidator delegate,
                                       UpstreamValidationConfig config,
                                       EraStartSlotProvider eraStartSlotProvider) {
        UpstreamValidationStartConfig start = config != null ? config.getStart() : null;
        if (start == null || "immediate".equals(start.normalizedMode())) {
            return delegate != null ? delegate : HeaderValidator.none();
        }
        return new HeaderValidationStartGate(delegate, start, eraStartSlotProvider);
    }

    @Override
    public HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes) {
        StartDecision decision = startDecision(blockHeader);
        if (!decision.validate()) {
            if (loggedDeferred.compareAndSet(false, true)) {
                log.info("Shelley+ header validation deferred until {}", decision.reason());
            }
            return HeaderValidationResult.accepted("deferred-" + start.normalizedMode());
        }
        if (!decision.accepted()) {
            return HeaderValidationResult.rejected(
                    delegate.snapshot().level(),
                    "validation-start",
                    decision.reason());
        }
        if (loggedActive.compareAndSet(false, true)) {
            log.info("Shelley+ header validation active from {}", decision.reason());
        }
        return delegate.validateShelley(blockHeader, originalHeaderBytes);
    }

    @Override
    public HeaderValidationSnapshot snapshot() {
        return delegate.snapshot();
    }

    private StartDecision startDecision(BlockHeader blockHeader) {
        if (blockHeader == null || blockHeader.getHeaderBody() == null) {
            return StartDecision.reject("header unavailable for validation-start policy");
        }

        long slot = blockHeader.getHeaderBody().getSlot();
        String hash = blockHeader.getHeaderBody().getBlockHash();
        String mode = start.normalizedMode();

        if ("checkpoint".equals(mode) || start.hasCheckpoint()) {
            if (slot < start.getSlot()) {
                return StartDecision.defer("checkpoint slot " + start.getSlot());
            }
            if (slot == start.getSlot()
                    && hash != null
                    && !hash.equalsIgnoreCase(start.getHash())) {
                return StartDecision.reject("checkpoint hash mismatch at slot " + start.getSlot());
            }
            return StartDecision.validate("checkpoint slot " + start.getSlot());
        }

        if ("era".equals(mode)) {
            OptionalLong eraStartSlot = eraStartSlotProvider.startSlot(start.normalizedEra());
            if (eraStartSlot.isEmpty()) {
                return StartDecision.defer(start.normalizedEra() + " era boundary");
            }
            if (slot < eraStartSlot.getAsLong()) {
                return StartDecision.defer(start.normalizedEra() + " era slot " + eraStartSlot.getAsLong());
            }
            return StartDecision.validate(start.normalizedEra() + " era slot " + eraStartSlot.getAsLong());
        }

        return StartDecision.validate(mode.toLowerCase(Locale.ROOT));
    }

    public interface EraStartSlotProvider {
        OptionalLong startSlot(String era);
    }

    private record StartDecision(boolean validate, boolean accepted, String reason) {
        static StartDecision defer(String reason) {
            return new StartDecision(false, true, reason);
        }

        static StartDecision reject(String reason) {
            return new StartDecision(true, false, reason);
        }

        static StartDecision validate(String reason) {
            return new StartDecision(true, true, reason);
        }
    }
}
