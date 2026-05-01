package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.exception.ApiRuntimeException;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolParamsMapper;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;

public class YanoProtocolParamsSupplier implements ProtocolParamsSupplier {
    private final Function<Integer, Optional<LedgerStateProvider.ProtocolParamsSnapshot>> snapshotSupplier;
    private final IntSupplier currentEpochSupplier;
    private final String fallbackNodeProtocolParamsJson;

    public YanoProtocolParamsSupplier(LedgerStateProvider ledgerStateProvider, IntSupplier currentEpochSupplier) {
        this(ledgerStateProvider, currentEpochSupplier, null);
    }

    public YanoProtocolParamsSupplier(
            LedgerStateProvider ledgerStateProvider,
            IntSupplier currentEpochSupplier,
            String fallbackNodeProtocolParamsJson) {
        this(
                ledgerStateProvider == null ? epoch -> Optional.empty() : ledgerStateProvider::getProtocolParameters,
                currentEpochSupplier,
                fallbackNodeProtocolParamsJson);
    }

    YanoProtocolParamsSupplier(
            Function<Integer, Optional<LedgerStateProvider.ProtocolParamsSnapshot>> snapshotSupplier,
            IntSupplier currentEpochSupplier,
            String fallbackNodeProtocolParamsJson) {
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier is required");
        this.currentEpochSupplier = Objects.requireNonNull(currentEpochSupplier, "currentEpochSupplier is required");
        this.fallbackNodeProtocolParamsJson = fallbackNodeProtocolParamsJson;
    }

    @Override
    public ProtocolParams getProtocolParams() {
        int epoch = currentEpochSupplier.getAsInt();
        if (epoch < 0) {
            throw new ApiRuntimeException("Protocol parameters require a non-negative epoch; got " + epoch);
        }

        RuntimeException snapshotError = null;
        try {
            Optional<LedgerStateProvider.ProtocolParamsSnapshot> snapshot = snapshotSupplier.apply(epoch);
            if (snapshot.isPresent()) {
                return ProtocolParamsMapper.fromSnapshot(snapshot.get());
            }
        } catch (RuntimeException e) {
            snapshotError = e;
        }

        if (fallbackNodeProtocolParamsJson != null && !fallbackNodeProtocolParamsJson.isBlank()) {
            try {
                return ProtocolParamsMapper.fromNodeProtocolParam(fallbackNodeProtocolParamsJson);
            } catch (IOException e) {
                throw new ApiRuntimeException("Unable to parse fallback Yano protocol parameters", e);
            }
        }

        if (snapshotError != null) {
            throw new ApiRuntimeException("Unable to read Yano protocol parameters", snapshotError);
        }

        throw new ApiRuntimeException("Yano protocol parameters are unavailable for epoch " + epoch);
    }
}
