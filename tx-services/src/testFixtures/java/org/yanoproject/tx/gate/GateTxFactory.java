package org.yanoproject.tx.gate;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.function.TxBuilder;
import com.bloxbean.cardano.client.function.TxSigner;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.util.function.Supplier;

/**
 * Builds and signs QuickTx transactions offline against a {@link GateWallet}, so a transaction can spend the
 * outputs of transactions that are still in the mempool. Nothing is submitted here.
 */
public final class GateTxFactory {

    /** A built transaction; {@link #commit()} applies it to the wallet once it is expected to be admitted. */
    public final class Built {
        private final Transaction transaction;
        private final byte[] cbor;
        private final String hash;
        private final GateWallet wallet;

        private Built(Transaction transaction, GateWallet wallet) {
            this.transaction = transaction;
            this.wallet = wallet;
            try {
                this.cbor = transaction.serialize();
            } catch (Exception e) {
                throw new IllegalStateException("cannot serialise a built transaction", e);
            }
            this.hash = TransactionUtil.getTxHash(cbor);
        }

        public byte[] cbor() {
            return cbor.clone();
        }

        public String hash() {
            return hash;
        }

        public Transaction transaction() {
            return transaction;
        }

        public Built commit() {
            wallet.apply(transaction, hash);
            return this;
        }
    }

    private final Supplier<ProtocolParams> params;

    public GateTxFactory(Supplier<ProtocolParams> params) {
        this.params = params;
    }

    public Built build(GateWallet wallet, Tx tx, TxSigner signer) {
        return build(wallet, tx, signer, null);
    }

    /**
     * @param preBalance a builder step applied before fee calculation and balancing (for example a
     *                   {@code currentTreasuryValue}), or {@code null}
     */
    public Built build(GateWallet wallet, Tx tx, TxSigner signer, TxBuilder preBalance) {
        ProtocolParams current = params.get();
        QuickTxBuilder builder = new QuickTxBuilder(wallet, () -> current, null);
        QuickTxBuilder.TxContext context = builder.compose(tx).withSigner(signer);
        if (preBalance != null) {
            context = context.preBalanceTx(preBalance);
        }
        return new Built(context.buildAndSign(), wallet);
    }
}
