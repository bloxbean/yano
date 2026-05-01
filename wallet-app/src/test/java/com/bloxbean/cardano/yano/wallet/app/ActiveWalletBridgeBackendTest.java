package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.cip.cip30.CIP30UtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeError;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeException;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeMethod;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveWalletBridgeBackendTest {
    private static final String MNEMONIC =
            "drive useless envelope shine range ability time copper alarm museum near flee wrist live type device meadow allow churn purity wisdom praise drop code";
    private static final String INPUT_HASH = "a".repeat(64);

    @Test
    void servesCip30ReadMethodsFromActiveWalletUtxos() throws Exception {
        Wallet wallet = Wallet.createFromMnemonic(Networks.preprod(), MNEMONIC);
        Utxo utxo = walletUtxo(wallet, 0, INPUT_HASH, 0, BigInteger.valueOf(3_000_000));
        ActiveWalletBridgeBackend backend = backend(wallet, new FakeUtxoSupplier(List.of(utxo)));

        assertThat(backend.networkId()).isZero();
        assertThat(backend.balanceCborHex()).isNotBlank();
        assertThat(backend.changeAddressHex()).matches("[0-9a-f]+");
        assertThat(new Address(HexUtil.decodeHexString(backend.changeAddressHex())).toBech32())
                .isEqualTo(wallet.getBaseAddressString(0));
        assertThat(backend.rewardAddressHexes()).hasSize(1).allSatisfy(address -> assertThat(address).matches("[0-9a-f]+"));

        List<String> cborUtxos = backend.utxosCborHex();

        assertThat(cborUtxos).hasSize(1);
        assertThat(new CIP30UtxoSupplier(cborUtxos).getAll(wallet.getBaseAddressString(0))).hasSize(1);
    }

    @Test
    void signsTransactionInputsOwnedByActiveWallet() throws Exception {
        Wallet wallet = Wallet.createFromMnemonic(Networks.preprod(), MNEMONIC);
        Utxo utxo = walletUtxo(wallet, 0, INPUT_HASH, 0, BigInteger.valueOf(3_000_000));
        ActiveWalletBridgeBackend backend = backend(wallet, new FakeUtxoSupplier(List.of(utxo)));
        String txCbor = unsignedTx(INPUT_HASH, 0, wallet.getBaseAddressString(1));

        String witnessSet = backend.signTx(txCbor, true).witnessSetCborHex();

        assertThat(witnessSet).isNotBlank();
        assertThat(witnessSet).matches("[0-9a-f]+");
    }

    @Test
    void rejectsSigningWhenTransactionDoesNotSpendWalletUtxos() throws Exception {
        Wallet wallet = Wallet.createFromMnemonic(Networks.preprod(), MNEMONIC);
        Utxo utxo = walletUtxo(wallet, 0, INPUT_HASH, 0, BigInteger.valueOf(3_000_000));
        ActiveWalletBridgeBackend backend = backend(wallet, new FakeUtxoSupplier(List.of(utxo)));
        String txCbor = unsignedTx("b".repeat(64), 0, wallet.getBaseAddressString(1));

        assertBridgeError(
                () -> backend.signTx(txCbor, true),
                BridgeError.INVALID_REQUEST,
                BridgeMethod.SIGN_TX);
    }

    @Test
    void submitsTransactionThroughProcessor() {
        Wallet wallet = Wallet.createFromMnemonic(Networks.preprod(), MNEMONIC);
        AtomicReference<String> submittedHash = new AtomicReference<>();
        AtomicReference<String> submittedCbor = new AtomicReference<>();
        ActiveWalletBridgeBackend backend = new ActiveWalletBridgeBackend(
                () -> wallet,
                () -> new FakeUtxoSupplier(List.of()),
                FakeTransactionProcessor::new,
                (txHash, txCborHex) -> {
                    submittedHash.set(txHash);
                    submittedCbor.set(txCborHex);
                },
                3,
                10);

        assertThat(backend.submitTx("84a400")).isEqualTo("c".repeat(64));
        assertThat(submittedHash.get()).isEqualTo("c".repeat(64));
        assertThat(submittedCbor.get()).isEqualTo("84a400");
    }

    @Test
    void reportsWalletNotReadyWhenNoWalletIsUnlocked() {
        ActiveWalletBridgeBackend backend = backend(null, new FakeUtxoSupplier(List.of()));

        assertBridgeError(
                backend::networkId,
                BridgeError.WALLET_NOT_READY,
                BridgeMethod.GET_NETWORK_ID);
    }

    private ActiveWalletBridgeBackend backend(Wallet wallet, UtxoSupplier utxoSupplier) {
        return new ActiveWalletBridgeBackend(
                () -> wallet,
                () -> utxoSupplier,
                FakeTransactionProcessor::new,
                (txHash, txCborHex) -> {
                },
                3,
                10);
    }

    private Utxo walletUtxo(Wallet wallet, int addressIndex, String txHash, int outputIndex, BigInteger lovelace) {
        return Utxo.builder()
                .txHash(txHash)
                .outputIndex(outputIndex)
                .address(wallet.getBaseAddressString(addressIndex))
                .amount(List.of(Amount.lovelace(lovelace)))
                .build();
    }

    private String unsignedTx(String txHash, int outputIndex, String receiver) throws Exception {
        Transaction transaction = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput(txHash, outputIndex)))
                        .outputs(List.of(new TransactionOutput(receiver, Value.fromCoin(BigInteger.valueOf(1_000_000)))))
                        .fee(BigInteger.valueOf(170_000))
                        .build())
                .witnessSet(new TransactionWitnessSet())
                .build();
        return transaction.serializeToHex();
    }

    private void assertBridgeError(Runnable action, BridgeError error, BridgeMethod method) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BridgeException.class)
                .satisfies(throwable -> {
                    BridgeException bridgeException = (BridgeException) throwable;
                    assertThat(bridgeException.error()).isEqualTo(error);
                    assertThat(bridgeException.method()).isEqualTo(method);
                });
    }

    private static class FakeUtxoSupplier implements UtxoSupplier {
        private final List<Utxo> utxos;

        private FakeUtxoSupplier(List<Utxo> utxos) {
            this.utxos = utxos;
        }

        @Override
        public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
            if (page != 0) {
                return List.of();
            }
            return utxos.stream()
                    .filter(utxo -> utxo.getAddress().equals(address))
                    .toList();
        }

        @Override
        public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
            return utxos.stream()
                    .filter(utxo -> utxo.getTxHash().equals(txHash) && utxo.getOutputIndex() == outputIndex)
                    .findFirst();
        }
    }

    private static class FakeTransactionProcessor implements TransactionProcessor {
        @Override
        public Result<String> submitTransaction(byte[] cborData) {
            return new SubmissionResult();
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
            return new UnsupportedEvaluationResult();
        }
    }

    private static class UnsupportedEvaluationResult extends Result<List<EvaluationResult>> {
        private UnsupportedEvaluationResult() {
            super(false, "not supported");
        }
    }

    private static class SubmissionResult extends Result<String> {
        private SubmissionResult() {
            super(true, "c".repeat(64));
        }
    }
}
