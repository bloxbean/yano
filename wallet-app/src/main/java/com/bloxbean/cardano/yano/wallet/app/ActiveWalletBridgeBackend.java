package com.bloxbean.cardano.yano.wallet.app;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.model.WalletUtxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.cip1852.DerivationPath;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeError;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeException;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeMethod;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeSignTxResult;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeWalletBackend;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class ActiveWalletBridgeBackend implements BridgeWalletBackend {
    private static final int DEFAULT_GAP_LIMIT = 20;
    private static final int DEFAULT_MAX_ADDRESSES = 100;

    private final Supplier<Wallet> walletSupplier;
    private final Supplier<UtxoSupplier> utxoSupplierSupplier;
    private final Supplier<TransactionProcessor> transactionProcessorSupplier;
    private final BiConsumer<String, String> submittedTransactionHandler;
    private final int gapLimit;
    private final int maxAddresses;

    public ActiveWalletBridgeBackend(
            Supplier<Wallet> walletSupplier,
            Supplier<UtxoSupplier> utxoSupplierSupplier,
            Supplier<TransactionProcessor> transactionProcessorSupplier) {
        this(walletSupplier, utxoSupplierSupplier, transactionProcessorSupplier, (txHash, txCborHex) -> {
        }, DEFAULT_GAP_LIMIT, DEFAULT_MAX_ADDRESSES);
    }

    ActiveWalletBridgeBackend(
            Supplier<Wallet> walletSupplier,
            Supplier<UtxoSupplier> utxoSupplierSupplier,
            Supplier<TransactionProcessor> transactionProcessorSupplier,
            BiConsumer<String, String> submittedTransactionHandler,
            int gapLimit,
            int maxAddresses) {
        this.walletSupplier = Objects.requireNonNull(walletSupplier, "walletSupplier is required");
        this.utxoSupplierSupplier = Objects.requireNonNull(utxoSupplierSupplier, "utxoSupplierSupplier is required");
        this.transactionProcessorSupplier = Objects.requireNonNull(transactionProcessorSupplier, "transactionProcessorSupplier is required");
        this.submittedTransactionHandler = Objects.requireNonNull(submittedTransactionHandler, "submittedTransactionHandler is required");
        if (gapLimit <= 0) {
            throw new IllegalArgumentException("gapLimit must be positive");
        }
        if (maxAddresses <= 0) {
            throw new IllegalArgumentException("maxAddresses must be positive");
        }
        this.gapLimit = gapLimit;
        this.maxAddresses = maxAddresses;
    }

    @Override
    public int networkId() {
        return wallet(BridgeMethod.GET_NETWORK_ID).getNetwork().getNetworkId();
    }

    @Override
    public String balanceCborHex() {
        Value balance = walletUtxos(BridgeMethod.GET_BALANCE).stream()
                .map(Utxo::toValue)
                .reduce(Value.fromCoin(BigInteger.ZERO), Value::add);
        return serializeValue(balance, BridgeMethod.GET_BALANCE);
    }

    @Override
    public List<String> utxosCborHex() {
        return walletUtxos(BridgeMethod.GET_UTXOS).stream()
                .map(utxo -> serializeUtxo(utxo, BridgeMethod.GET_UTXOS))
                .toList();
    }

    @Override
    public String changeAddressHex() {
        Wallet wallet = wallet(BridgeMethod.GET_CHANGE_ADDRESS);
        String changeAddress = wallet.getBaseAddressString(0);
        return HexUtil.encodeHexString(new Address(changeAddress).getBytes());
    }

    @Override
    public List<String> rewardAddressHexes() {
        Wallet wallet = wallet(BridgeMethod.GET_REWARD_ADDRESSES);
        return List.of(HexUtil.encodeHexString(new Address(wallet.getAccount(wallet.getAccountNo(), 0).stakeAddress()).getBytes()));
    }

    @Override
    public BridgeSignTxResult signTx(String txCborHex, boolean partialSign) {
        Wallet wallet = wallet(BridgeMethod.SIGN_TX);
        Transaction transaction = deserializeTransaction(txCborHex, BridgeMethod.SIGN_TX);
        Set<WalletUtxo> signingUtxos = signingUtxos(transaction);
        if (signingUtxos.isEmpty()) {
            throw new BridgeException(
                    BridgeError.INVALID_REQUEST,
                    BridgeMethod.SIGN_TX,
                    "Transaction does not spend any active wallet UTXOs");
        }
        Transaction signed = wallet.sign(transaction, signingUtxos);
        try {
            return new BridgeSignTxResult(HexUtil.encodeHexString(
                    CborSerializationUtil.serialize(signed.getWitnessSet().serialize())));
        } catch (Exception e) {
            throw new BridgeException(BridgeError.BACKEND_ERROR, BridgeMethod.SIGN_TX, "Unable to serialize witness set", e);
        }
    }

    @Override
    public String submitTx(String txCborHex) {
        TransactionProcessor transactionProcessor = transactionProcessor(BridgeMethod.SUBMIT_TX);
        try {
            Result<String> result = transactionProcessor.submitTransaction(HexUtil.decodeHexString(txCborHex));
            if (!result.isSuccessful()) {
                throw new BridgeException(BridgeError.BACKEND_ERROR, BridgeMethod.SUBMIT_TX, result.getResponse());
            }
            String txHash = result.getValue() != null ? result.getValue() : result.getResponse();
            submittedTransactionHandler.accept(txHash, txCborHex);
            return txHash;
        } catch (BridgeException e) {
            throw e;
        } catch (Exception e) {
            throw new BridgeException(BridgeError.BACKEND_ERROR, BridgeMethod.SUBMIT_TX, "Unable to submit transaction", e);
        }
    }

    private Set<WalletUtxo> signingUtxos(Transaction transaction) {
        Set<String> requiredOutpoints = new HashSet<>();
        Stream.concat(
                        transaction.getBody().getInputs().stream(),
                        transaction.getBody().getCollateral().stream())
                .map(this::outpoint)
                .forEach(requiredOutpoints::add);

        Set<WalletUtxo> signingUtxos = new HashSet<>();
        for (WalletUtxo utxo : walletUtxos(BridgeMethod.SIGN_TX)) {
            if (requiredOutpoints.contains(outpoint(utxo))) {
                signingUtxos.add(utxo);
            }
        }
        return signingUtxos;
    }

    private List<WalletUtxo> walletUtxos(BridgeMethod method) {
        Wallet wallet = wallet(method);
        UtxoSupplier utxoSupplier = utxoSupplier(method);
        int unusedStreak = 0;
        Set<WalletUtxo> walletUtxos = new HashSet<>();

        for (int index = 0; index < maxAddresses && unusedStreak < gapLimit; index++) {
            Address address = wallet.getBaseAddress(index);
            List<Utxo> utxos = utxoSupplier.getAll(address.getAddress());
            if (utxos == null || utxos.isEmpty()) {
                unusedStreak++;
                continue;
            }

            unusedStreak = 0;
            DerivationPath derivationPath = address.getDerivationPath()
                    .orElseThrow(() -> new BridgeException(
                            BridgeError.BACKEND_ERROR,
                            method,
                            "Wallet address has no derivation path"));
            for (Utxo utxo : utxos) {
                WalletUtxo walletUtxo = WalletUtxo.from(utxo);
                walletUtxo.setDerivationPath(derivationPath);
                walletUtxos.add(walletUtxo);
            }
        }

        return walletUtxos.stream().toList();
    }

    private String serializeUtxo(Utxo utxo, BridgeMethod method) {
        try {
            Array txUnspentOutput = new Array();
            txUnspentOutput.add(new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex()).serialize());
            TransactionOutput output = TransactionOutput.builder()
                    .address(utxo.getAddress())
                    .value(utxo.toValue())
                    .datumHash(utxo.getDataHash() == null || utxo.getDataHash().isBlank()
                            ? null
                            : HexUtil.decodeHexString(utxo.getDataHash()))
                    .build();
            txUnspentOutput.add(output.serialize());
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(txUnspentOutput));
        } catch (Exception e) {
            throw new BridgeException(BridgeError.BACKEND_ERROR, method, "Unable to serialize wallet UTXO", e);
        }
    }

    private String serializeValue(Value value, BridgeMethod method) {
        try {
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(value.serialize()));
        } catch (Exception e) {
            throw new BridgeException(BridgeError.BACKEND_ERROR, method, "Unable to serialize wallet balance", e);
        }
    }

    private Transaction deserializeTransaction(String txCborHex, BridgeMethod method) {
        try {
            return Transaction.deserialize(HexUtil.decodeHexString(txCborHex));
        } catch (Exception e) {
            throw new BridgeException(BridgeError.INVALID_REQUEST, method, "Unable to deserialize transaction CBOR", e);
        }
    }

    private Wallet wallet(BridgeMethod method) {
        Wallet wallet = walletSupplier.get();
        if (wallet == null) {
            throw new BridgeException(BridgeError.WALLET_NOT_READY, method, "No wallet is unlocked");
        }
        return wallet;
    }

    private UtxoSupplier utxoSupplier(BridgeMethod method) {
        UtxoSupplier utxoSupplier = utxoSupplierSupplier.get();
        if (utxoSupplier == null) {
            throw new BridgeException(BridgeError.WALLET_NOT_READY, method, "Yano state is not available");
        }
        return utxoSupplier;
    }

    private TransactionProcessor transactionProcessor(BridgeMethod method) {
        TransactionProcessor transactionProcessor = transactionProcessorSupplier.get();
        if (transactionProcessor == null) {
            throw new BridgeException(BridgeError.WALLET_NOT_READY, method, "Transaction submission is not available");
        }
        return transactionProcessor;
    }

    private String outpoint(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }

    private String outpoint(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }
}
