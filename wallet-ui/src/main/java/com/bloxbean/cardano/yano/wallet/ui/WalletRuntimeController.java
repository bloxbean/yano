package com.bloxbean.cardano.yano.wallet.ui;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface WalletRuntimeController {
    CompletableFuture<RuntimeSnapshot> openPreprodChainstate(Path chainstatePath, Path networkConfigDir);

    CompletableFuture<RuntimeSnapshot> startPreprodSync(Path chainstatePath, Path networkConfigDir);

    CompletableFuture<RuntimeSnapshot> stopRuntime();

    CompletableFuture<RuntimeSnapshot> refreshRuntimeStatus();

    CompletableFuture<WalletSnapshot> restorePreprodWallet(String mnemonic);

    CompletableFuture<List<StoredWalletSnapshot>> listStoredWallets();

    CompletableFuture<String> generatePreprodMnemonic();

    CompletableFuture<WalletSnapshot> importPreprodWallet(String name, String mnemonic, String passphrase);

    CompletableFuture<WalletSnapshot> unlockStoredWallet(String walletId, String passphrase);

    CompletableFuture<WalletSnapshot> createAccountForActiveWallet(String accountName);

    CompletableFuture<WalletSnapshot> refreshWallet();

    default CompletableFuture<AccountSnapshot> refreshActiveWalletAccount() {
        return refreshActiveWalletAccount(10);
    }

    CompletableFuture<AccountSnapshot> refreshActiveWalletAccount(int receiveAddressCount);

    CompletableFuture<QuickTxSnapshot> buildSelfPaymentDraft(BigInteger lovelace);

    CompletableFuture<QuickTxSnapshot> buildAdaSendDraft(String receiverAddress, BigInteger lovelace);

    CompletableFuture<QuickTxSnapshot> buildAssetSendDraft(
            String receiverAddress,
            BigInteger lovelace,
            String assetUnit,
            BigInteger assetQuantity,
            String metadataMessage);

    CompletableFuture<QuickTxSnapshot> buildMultiAssetSendDraft(
            List<SendPaymentRequest> payments,
            String metadataMessage);

    CompletableFuture<SubmitTxSnapshot> submitLastDraft();

    CompletableFuture<List<PendingTxSnapshot>> refreshPendingTransactions();

    CompletableFuture<BridgeSnapshot> startBridge(
            BridgeConnectionPrompt connectionPrompt,
            BridgeTransactionApprovalPrompt transactionApprovalPrompt);

    CompletableFuture<BridgeSnapshot> stopBridge();

    CompletableFuture<BridgeSnapshot> refreshBridgeStatus();

    record RuntimeSnapshot(
            boolean opened,
            boolean syncing,
            String mode,
            String chainstatePath,
            Long tipSlot,
            Long tipBlock,
            Long remoteTipSlot,
            Long remoteTipBlock,
            Double syncProgress,
            String message) {
    }

    record WalletSnapshot(
            String address,
            BigInteger lovelace,
            int scannedAddresses,
            int utxoCount,
            List<UtxoSnapshot> utxos,
            List<AssetSnapshot> assets,
            String message) {
        public WalletSnapshot {
            lovelace = lovelace == null ? BigInteger.ZERO : lovelace;
            utxos = utxos == null ? List.of() : List.copyOf(utxos);
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    record UtxoSnapshot(
            String outpoint,
            String address,
            BigInteger lovelace,
            int assetCount) {
    }

    record AssetSnapshot(
            String unit,
            BigInteger quantity) {
        public AssetSnapshot {
            quantity = quantity == null ? BigInteger.ZERO : quantity;
        }
    }

    record StoredWalletSnapshot(
            String walletId,
            String seedId,
            String name,
            String networkId,
            int accountIndex,
            String baseAddress,
            String stakeAddress,
            String drepId) {
    }

    record AccountSnapshot(
            String walletId,
            String name,
            String networkId,
            int accountIndex,
            String stakeAddress,
            String drepId,
            List<AddressSnapshot> receiveAddresses,
            String message) {
        public AccountSnapshot {
            receiveAddresses = receiveAddresses == null ? List.of() : List.copyOf(receiveAddresses);
        }
    }

    record AddressSnapshot(
            int accountIndex,
            int addressIndex,
            String role,
            String derivationPath,
            String baseAddress,
            String enterpriseAddress) {
    }

    record QuickTxSnapshot(
            String txHash,
            String cborHex,
            BigInteger lovelace,
            BigInteger fee,
            String assetSummary,
            String metadataSummary,
            int inputCount,
            int outputCount,
            String message) {
    }

    record SendPaymentRequest(
            String receiverAddress,
            BigInteger lovelace,
            List<AssetTransferRequest> assets) {
        public SendPaymentRequest {
            lovelace = lovelace == null ? BigInteger.ZERO : lovelace;
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    record AssetTransferRequest(
            String unit,
            BigInteger quantity) {
        public AssetTransferRequest {
            quantity = quantity == null ? BigInteger.ZERO : quantity;
        }
    }

    record SubmitTxSnapshot(
            String txHash,
            String status,
            String message) {
    }

    record PendingTxSnapshot(
            String txHash,
            String status,
            BigInteger lovelace,
            BigInteger fee,
            String toAddress,
            Long createdAtEpochMillis,
            Long submittedAtEpochMillis,
            Long confirmedSlot,
            Long confirmedBlock,
            String message) {
    }

    record BridgeSnapshot(
            boolean running,
            String endpoint,
            int sessionCount,
            String message) {
    }

    record BridgeTransactionApprovalSnapshot(
            String origin,
            String method,
            String txCborHex,
            boolean partialSign) {
    }

    @FunctionalInterface
    interface BridgeConnectionPrompt {
        boolean allow(String origin, List<String> permissions);
    }

    @FunctionalInterface
    interface BridgeTransactionApprovalPrompt {
        boolean approve(BridgeTransactionApprovalSnapshot request);
    }

    static WalletRuntimeController unavailable() {
        return new WalletRuntimeController() {
            @Override
            public CompletableFuture<RuntimeSnapshot> openPreprodChainstate(Path chainstatePath, Path networkConfigDir) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<RuntimeSnapshot> startPreprodSync(Path chainstatePath, Path networkConfigDir) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<RuntimeSnapshot> stopRuntime() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<RuntimeSnapshot> refreshRuntimeStatus() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<WalletSnapshot> restorePreprodWallet(String mnemonic) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<List<StoredWalletSnapshot>> listStoredWallets() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<String> generatePreprodMnemonic() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<WalletSnapshot> importPreprodWallet(String name, String mnemonic, String passphrase) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<WalletSnapshot> unlockStoredWallet(String walletId, String passphrase) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<WalletSnapshot> createAccountForActiveWallet(String accountName) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<WalletSnapshot> refreshWallet() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<AccountSnapshot> refreshActiveWalletAccount(int receiveAddressCount) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<QuickTxSnapshot> buildSelfPaymentDraft(BigInteger lovelace) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<QuickTxSnapshot> buildAdaSendDraft(String receiverAddress, BigInteger lovelace) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<QuickTxSnapshot> buildAssetSendDraft(String receiverAddress, BigInteger lovelace, String assetUnit, BigInteger assetQuantity, String metadataMessage) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<QuickTxSnapshot> buildMultiAssetSendDraft(List<SendPaymentRequest> payments, String metadataMessage) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<SubmitTxSnapshot> submitLastDraft() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<List<PendingTxSnapshot>> refreshPendingTransactions() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<BridgeSnapshot> startBridge(
                    BridgeConnectionPrompt connectionPrompt,
                    BridgeTransactionApprovalPrompt transactionApprovalPrompt) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<BridgeSnapshot> stopBridge() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }

            @Override
            public CompletableFuture<BridgeSnapshot> refreshBridgeStatus() {
                return CompletableFuture.failedFuture(new IllegalStateException("Wallet runtime controller is not configured"));
            }
        };
    }
}
