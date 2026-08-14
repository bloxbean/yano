package com.bloxbean.cardano.yano.runtime.appchain.hostbridge;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.api.plugin.domain.L1TransactionBuilderService;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.runtime.appchain.NodeUtxoSupplier;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Host-owned QuickTx adapter with no signing or submission capability. */
public final class DefaultL1TransactionBuilderService
        implements L1TransactionBuilderService {
    private static final TransactionProcessor NO_SUBMIT =
            new TransactionProcessor() {
                @Override
                public com.bloxbean.cardano.client.api.model.Result<String>
                submitTransaction(byte[] cborData) {
                    throw new UnsupportedOperationException(
                            "unsigned transaction builder cannot submit");
                }

                @Override
                public com.bloxbean.cardano.client.api.model.Result<List<
                        com.bloxbean.cardano.client.api.model.EvaluationResult>>
                evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
                    throw new UnsupportedOperationException(
                            "unsigned payment builder cannot evaluate scripts");
                }
            };

    private final NodeUtxoSupplier utxos;
    private final Supplier<ProtocolParams> protocolParams;
    private final LongSupplier tipSlot;

    public DefaultL1TransactionBuilderService(
            Supplier<UtxoState> utxoState,
            Supplier<ProtocolParams> protocolParams,
            LongSupplier tipSlot
    ) {
        this.utxos = new NodeUtxoSupplier(
                Objects.requireNonNull(utxoState, "utxoState"));
        this.protocolParams = Objects.requireNonNull(
                protocolParams, "protocolParams");
        this.tipSlot = Objects.requireNonNull(tipSlot, "tipSlot");
    }

    @Override
    public long tipSlot() {
        return Math.max(0, tipSlot.getAsLong());
    }

    @Override
    public SpendableInput selectSpendableInput(String sourceAddress) {
        String address = requireAddress(sourceAddress);
        Utxo selected = utxos.getPage(address, 40, 0, null).stream()
                .filter(value -> value.getAmount() != null)
                .filter(value -> value.getAmount().stream().anyMatch(amount ->
                        "lovelace".equals(amount.getUnit())
                                && amount.getQuantity() != null
                                && amount.getQuantity().signum() > 0))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "source address has no spendable L1 UTxO"));
        return new SpendableInput(
                selected.getTxHash(), selected.getOutputIndex(),
                lovelace(selected).longValueExact());
    }

    @Override
    public UnsignedTransaction buildPayment(PaymentPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Utxo selected = utxos.getTxOutput(
                        plan.input().transactionId(), plan.input().outputIndex())
                .filter(value -> plan.sourceAddress().equals(value.getAddress()))
                .orElseThrow(() -> new IllegalStateException(
                        "selected L1 input is unavailable or belongs to another address"));
        if (!lovelace(selected).equals(BigInteger.valueOf(plan.input().lovelace()))) {
            throw new IllegalStateException("selected L1 input changed before build");
        }
        ProtocolParamsSupplier params = () -> Objects.requireNonNull(
                protocolParams.get(), "protocol parameters are unavailable");
        try {
            Tx tx = new Tx()
                    .collectFrom(List.of(selected))
                    .payToContract(
                            plan.destinationAddress(),
                            Amount.lovelace(BigInteger.valueOf(plan.lovelace())),
                            PlutusData.deserialize(plan.inlineDatum()))
                    .from(plan.sourceAddress());
            Transaction unsigned = new QuickTxBuilder(utxos, params, NO_SUBMIT)
                    .compose(tx)
                    .additionalSignersCount(1)
                    .validTo(plan.ttlSlot())
                    .build();
            byte[] cbor = unsigned.serialize();
            return new UnsignedTransaction(
                    cbor,
                    TransactionUtil.getTxHash(cbor),
                    unsigned.getBody().getFee().longValueExact(),
                    plan.ttlSlot());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot build unsigned L1 payment", failure);
        }
    }

    private static BigInteger lovelace(Utxo value) {
        return value.getAmount().stream()
                .filter(amount -> "lovelace".equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .filter(Objects::nonNull)
                .findFirst().orElse(BigInteger.ZERO);
    }

    private static String requireAddress(String value) {
        String normalized = Objects.requireNonNull(value, "sourceAddress").trim();
        if (normalized.isEmpty()
                || normalized.length() > L1TransactionBuilderService.MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("invalid sourceAddress");
        }
        return normalized;
    }
}
