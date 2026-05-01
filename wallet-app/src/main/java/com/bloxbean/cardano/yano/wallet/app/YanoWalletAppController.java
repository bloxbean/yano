package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.wallet.bridge.BridgeApprovalHandler;
import com.bloxbean.cardano.yano.wallet.bridge.InMemoryBridgeSessionRegistry;
import com.bloxbean.cardano.yano.wallet.bridge.LocalBridgeHttpServer;
import com.bloxbean.cardano.yano.wallet.bridge.LocalCip30BridgeService;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransaction;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxDraft;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxService;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickTxPayment;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletBalance;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletBalanceService;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.core.wallet.UnlockedWallet;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletAccountView;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddressService;
import com.bloxbean.cardano.yano.wallet.ui.WalletRuntimeController;
import com.bloxbean.cardano.yano.wallet.yano.YanoPendingTransactionListener;
import com.bloxbean.cardano.yano.wallet.yano.YanoPendingTransactionReconciler;
import com.bloxbean.cardano.yano.wallet.yano.YanoProtocolParamsSupplier;
import com.bloxbean.cardano.yano.wallet.yano.YanoTransactionProcessor;
import com.bloxbean.cardano.yano.wallet.yano.YanoUtxoSupplier;
import com.bloxbean.cardano.yano.wallet.yano.runtime.DelegatingYanoWalletNodeRuntime;
import com.bloxbean.cardano.yano.wallet.yano.runtime.EmbeddedYanoRuntimeFactory;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class YanoWalletAppController implements WalletRuntimeController, AutoCloseable {
    private static final EpochSlotCalc PREPROD_EPOCH_CALC = new EpochSlotCalc(432_000, 21_600, 86_400);
    private static final String PREPROD_NETWORK_ID = "preprod";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yano-wallet-runtime");
        thread.setDaemon(true);
        return thread;
    });
    private final PendingTransactionStore pendingTransactions;
    private final StoredWalletRepository preprodWalletRepository;
    private final InMemoryBridgeSessionRegistry bridgeSessionRegistry = new InMemoryBridgeSessionRegistry();

    private DelegatingYanoWalletNodeRuntime runtime;
    private YanoUtxoSupplier utxoSupplier;
    private Wallet wallet;
    private StoredWallet activeStoredWallet;
    private WalletBalance walletBalance;
    private QuickAdaTxDraft lastDraft;
    private Path chainstatePath;
    private boolean runtimeSyncMode;
    private LocalBridgeHttpServer bridgeHttpServer;
    private final ActiveWalletBridgeBackend bridgeBackend = new ActiveWalletBridgeBackend(
            () -> wallet,
            () -> utxoSupplier,
            () -> runtime != null && runtimeSyncMode ? new YanoTransactionProcessor(runtime.nodeAPI()) : null,
            this::recordBridgeSubmittedTransaction,
            20,
            100);

    public YanoWalletAppController() {
        this(
                new FilePendingTransactionStore(defaultPendingTransactionFile()),
                new FileStoredWalletRepository(defaultNetworkDataDir(PREPROD_NETWORK_ID), com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork.PREPROD));
    }

    YanoWalletAppController(PendingTransactionStore pendingTransactions, StoredWalletRepository preprodWalletRepository) {
        this.pendingTransactions = pendingTransactions;
        this.preprodWalletRepository = preprodWalletRepository;
    }

    public LocalCip30BridgeService bridgeService(BridgeApprovalHandler approvalHandler) {
        return new LocalCip30BridgeService(bridgeSessionRegistry, bridgeBackend, approvalHandler);
    }

    @Override
    public CompletableFuture<BridgeSnapshot> startBridge(
            BridgeConnectionPrompt connectionPrompt,
            BridgeTransactionApprovalPrompt transactionApprovalPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            if (bridgeHttpServer != null) {
                return bridgeSnapshot("Local bridge is already running");
            }
            if (connectionPrompt == null) {
                throw new IllegalArgumentException("connectionPrompt is required");
            }
            if (transactionApprovalPrompt == null) {
                throw new IllegalArgumentException("transactionApprovalPrompt is required");
            }

            BridgeApprovalHandler approvalHandler = request -> transactionApprovalPrompt.approve(
                    new BridgeTransactionApprovalSnapshot(
                            request.session().origin(),
                            request.method().cip30Name(),
                            request.txCborHex(),
                            request.partialSign()));
            LocalCip30BridgeService bridgeService = bridgeService(approvalHandler);
            bridgeHttpServer = LocalBridgeHttpServer.start(
                    bridgeService,
                    (origin, permissions) -> connectionPrompt.allow(
                            origin,
                            permissions.stream().map(Enum::name).sorted().toList()));
            return bridgeSnapshot("Local CIP-30 bridge started");
        }, executor);
    }

    @Override
    public CompletableFuture<BridgeSnapshot> stopBridge() {
        return CompletableFuture.supplyAsync(() -> {
            closeBridgeOnly();
            return bridgeSnapshot("Local CIP-30 bridge stopped");
        }, executor);
    }

    @Override
    public CompletableFuture<BridgeSnapshot> refreshBridgeStatus() {
        return CompletableFuture.supplyAsync(() -> bridgeSnapshot(null), executor);
    }

    @Override
    public CompletableFuture<RuntimeSnapshot> openPreprodChainstate(Path chainstatePath, Path networkConfigDir) {
        return CompletableFuture.supplyAsync(() -> {
            validateRuntimePaths(chainstatePath, networkConfigDir);

            closeRuntimeOnly();
            runtime = EmbeddedYanoRuntimeFactory.preprodReadOnly(chainstatePath, networkConfigDir);
            registerPendingTransactionListener();
            utxoSupplier = new YanoUtxoSupplier(runtime.utxoState());
            this.chainstatePath = chainstatePath.toAbsolutePath().normalize();
            runtimeSyncMode = false;
            reconcilePendingTransactionsFromUtxoState();
            refreshWalletBalanceIfRestored();

            return runtimeSnapshot("preprod read-only chainstate", "Preprod chainstate opened");
        }, executor);
    }

    @Override
    public CompletableFuture<RuntimeSnapshot> startPreprodSync(Path chainstatePath, Path networkConfigDir) {
        return CompletableFuture.supplyAsync(() -> {
            validateRuntimePaths(chainstatePath, networkConfigDir);

            closeRuntimeOnly();
            runtime = EmbeddedYanoRuntimeFactory.preprodSync(chainstatePath, networkConfigDir);
            registerPendingTransactionListener();
            runtime.start();
            utxoSupplier = new YanoUtxoSupplier(runtime.utxoState());
            this.chainstatePath = chainstatePath.toAbsolutePath().normalize();
            runtimeSyncMode = true;
            reconcilePendingTransactionsFromUtxoState();
            refreshWalletBalanceIfRestored();

            return runtimeSnapshot("preprod sync", "Preprod sync started from wallet chainstate");
        }, executor);
    }

    @Override
    public CompletableFuture<RuntimeSnapshot> stopRuntime() {
        return CompletableFuture.supplyAsync(() -> {
            closeRuntimeOnly();
            return new RuntimeSnapshot(
                    false,
                    false,
                    "stopped",
                    chainstatePath == null ? null : chainstatePath.toString(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Yano wallet runtime stopped");
        }, executor);
    }

    @Override
    public CompletableFuture<RuntimeSnapshot> refreshRuntimeStatus() {
        return CompletableFuture.supplyAsync(() -> {
            if (runtime == null) {
                return new RuntimeSnapshot(
                        false,
                        false,
                        "stopped",
                        chainstatePath == null ? null : chainstatePath.toString(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Yano wallet runtime is stopped");
            }
            reconcilePendingTransactionsFromUtxoState();
            expirePendingTransactionsAtLocalTip();
            return runtimeSnapshot(runtimeSyncMode ? "preprod sync" : "preprod read-only chainstate", null);
        }, executor);
    }

    @Override
    public CompletableFuture<WalletSnapshot> restorePreprodWallet(String mnemonic) {
        return CompletableFuture.supplyAsync(() -> {
            if (utxoSupplier == null) {
                throw new IllegalStateException("Open preprod chainstate before restoring a wallet");
            }
            if (mnemonic == null || mnemonic.isBlank()) {
                throw new IllegalArgumentException("Mnemonic is required");
            }

            wallet = Wallet.createFromMnemonic(Networks.preprod(), mnemonic.trim());
            activeStoredWallet = null;
            walletBalance = scanWalletBalance();
            return toWalletSnapshot("Wallet restored from mnemonic in memory");
        }, executor);
    }

    @Override
    public CompletableFuture<List<StoredWalletSnapshot>> listStoredWallets() {
        return CompletableFuture.supplyAsync(() -> preprodWalletRepository.list().stream()
                .map(this::toStoredWalletSnapshot)
                .toList(), executor);
    }

    @Override
    public CompletableFuture<String> generatePreprodMnemonic() {
        return CompletableFuture.supplyAsync(preprodWalletRepository::generateMnemonic, executor);
    }

    @Override
    public CompletableFuture<WalletSnapshot> importPreprodWallet(String name, String mnemonic, String passphrase) {
        return CompletableFuture.supplyAsync(() -> {
            StoredWallet stored = preprodWalletRepository.importMnemonic(
                    name,
                    com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork.PREPROD,
                    mnemonic,
                    toPassphrase(passphrase));
            activate(preprodWalletRepository.unlock(stored.id(), toPassphrase(passphrase)));
            return toWalletSnapshot("Wallet imported into encrypted vault");
        }, executor);
    }

    @Override
    public CompletableFuture<WalletSnapshot> unlockStoredWallet(String walletId, String passphrase) {
        return CompletableFuture.supplyAsync(() -> {
            activate(preprodWalletRepository.unlock(walletId, toPassphrase(passphrase)));
            return toWalletSnapshot("Wallet unlocked from encrypted vault");
        }, executor);
    }

    @Override
    public CompletableFuture<WalletSnapshot> createAccountForActiveWallet(String accountName) {
        return CompletableFuture.supplyAsync(() -> {
            if (activeStoredWallet == null) {
                throw new IllegalStateException("Unlock an encrypted wallet before creating another account");
            }
            if (wallet == null) {
                throw new IllegalStateException("Unlock a wallet before creating another account");
            }
            String mnemonic = wallet.getMnemonic();
            if (mnemonic == null || mnemonic.isBlank()) {
                throw new IllegalStateException("Active wallet cannot derive another account from memory");
            }

            StoredWallet account = preprodWalletRepository.createAccount(
                    activeStoredWallet.seedId(),
                    accountName,
                    com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork.PREPROD,
                    mnemonic);
            activeStoredWallet = account;
            wallet = Wallet.createFromMnemonic(Networks.preprod(), mnemonic, account.accountIndex());
            walletBalance = utxoSupplier == null
                    ? new WalletBalance(BigInteger.ZERO, 0, 0, List.of())
                    : scanWalletBalance();
            return toWalletSnapshot("Account " + account.accountIndex() + " created under encrypted wallet");
        }, executor);
    }

    @Override
    public CompletableFuture<WalletSnapshot> refreshWallet() {
        return CompletableFuture.supplyAsync(() -> {
            if (wallet == null) {
                throw new IllegalStateException("Restore wallet before refreshing wallet state");
            }
            if (utxoSupplier == null) {
                throw new IllegalStateException("Open or sync preprod chainstate before refreshing wallet state");
            }

            walletBalance = scanWalletBalance();
            return toWalletSnapshot("Wallet balance refreshed from Yano state");
        }, executor);
    }

    @Override
    public CompletableFuture<AccountSnapshot> refreshActiveWalletAccount(int receiveAddressCount) {
        return CompletableFuture.supplyAsync(() -> {
            if (wallet == null) {
                throw new IllegalStateException("Unlock or restore a wallet before refreshing account details");
            }
            if (receiveAddressCount <= 0) {
                throw new IllegalArgumentException("receiveAddressCount must be positive");
            }
            WalletAccountView accountView = new WalletAddressService().accountView(
                    activeWalletProfile(),
                    wallet,
                    receiveAddressCount);
            return new AccountSnapshot(
                    accountView.walletId(),
                    accountView.name(),
                    accountView.networkId(),
                    accountView.accountIndex(),
                    accountView.stakeAddress(),
                    accountView.drepId(),
                    accountView.receiveAddresses().stream()
                            .map(address -> new AddressSnapshot(
                                    address.accountIndex(),
                                    address.addressIndex(),
                                    address.role(),
                                    address.derivationPath(),
                                    address.baseAddress(),
                                    address.enterpriseAddress()))
                            .toList(),
                    "Account details refreshed");
        }, executor);
    }

    @Override
    public CompletableFuture<QuickTxSnapshot> buildSelfPaymentDraft(BigInteger lovelace) {
        return buildAdaSendDraft(wallet == null ? null : wallet.getBaseAddressString(0), lovelace);
    }

    @Override
    public CompletableFuture<QuickTxSnapshot> buildAdaSendDraft(String receiverAddress, BigInteger lovelace) {
        return buildAssetSendDraft(receiverAddress, lovelace, null, null, null);
    }

    @Override
    public CompletableFuture<QuickTxSnapshot> buildAssetSendDraft(
            String receiverAddress,
            BigInteger lovelace,
            String assetUnit,
            BigInteger assetQuantity,
            String metadataMessage) {
        return CompletableFuture.supplyAsync(() -> {
            if (runtime == null || utxoSupplier == null) {
                throw new IllegalStateException("Open preprod chainstate before building a transaction");
            }
            if (wallet == null) {
                throw new IllegalStateException("Restore wallet before building a transaction");
            }

            int currentEpoch = currentEpoch();
            var protocolParamsSupplier = new YanoProtocolParamsSupplier(
                    runtime.ledgerStateProvider(),
                    () -> currentEpoch,
                    runtime.protocolParameters());
            QuickAdaTxDraft draft = new QuickAdaTxService().buildSignedDraft(
                    wallet,
                    utxoSupplier,
                    protocolParamsSupplier,
                    new YanoTransactionProcessor(runtime.nodeAPI()),
                    receiverAddress,
                    lovelace,
                    assetAmount(assetUnit, assetQuantity),
                    metadataMessage);
            lastDraft = draft;

            boolean selfPayment = wallet.getBaseAddressString(0).equals(receiverAddress);
            return new QuickTxSnapshot(
                    draft.txHash(),
                    draft.cborHex(),
                    draft.lovelace(),
                    draft.fee(),
                    draft.assetSummary(),
                    draft.metadataSummary(),
                    draft.inputCount(),
                    draft.outputCount(),
                    selfPayment
                            ? "Signed self-payment draft built but not submitted"
                            : draft.assetSummary().isBlank()
                            ? "Signed ADA transaction draft built but not submitted"
                            : "Signed ADA/native asset transaction draft built but not submitted");
        }, executor);
    }

    @Override
    public CompletableFuture<QuickTxSnapshot> buildMultiAssetSendDraft(
            List<SendPaymentRequest> payments,
            String metadataMessage) {
        return CompletableFuture.supplyAsync(() -> {
            if (runtime == null || utxoSupplier == null) {
                throw new IllegalStateException("Open preprod chainstate before building a transaction");
            }
            if (wallet == null) {
                throw new IllegalStateException("Restore wallet before building a transaction");
            }

            int currentEpoch = currentEpoch();
            var protocolParamsSupplier = new YanoProtocolParamsSupplier(
                    runtime.ledgerStateProvider(),
                    () -> currentEpoch,
                    runtime.protocolParameters());
            QuickAdaTxDraft draft = new QuickAdaTxService().buildSignedDraft(
                    wallet,
                    utxoSupplier,
                    protocolParamsSupplier,
                    new YanoTransactionProcessor(runtime.nodeAPI()),
                    toQuickTxPayments(payments),
                    metadataMessage);
            lastDraft = draft;

            return new QuickTxSnapshot(
                    draft.txHash(),
                    draft.cborHex(),
                    draft.lovelace(),
                    draft.fee(),
                    draft.assetSummary(),
                    draft.metadataSummary(),
                    draft.inputCount(),
                    draft.outputCount(),
                    draft.assetSummary().isBlank()
                            ? "Signed multi-output ADA transaction draft built but not submitted"
                            : "Signed multi-output ADA/native asset transaction draft built but not submitted");
        }, executor);
    }

    @Override
    public CompletableFuture<SubmitTxSnapshot> submitLastDraft() {
        return CompletableFuture.supplyAsync(() -> {
            if (runtime == null) {
                throw new IllegalStateException("Start or open Yano runtime before submitting a transaction");
            }
            if (!runtimeSyncMode) {
                throw new IllegalStateException("Start preprod sync before submitting so the transaction can be forwarded upstream");
            }
            if (lastDraft == null) {
                throw new IllegalStateException("Build and sign a transaction draft before submitting");
            }

            try {
                String submittedTxHash = runtime.submitTransaction(HexUtil.decodeHexString(lastDraft.cborHex()));
                PendingTransaction pending = PendingTransaction
                        .fromDraft(lastDraft, currentWalletId(), PREPROD_NETWORK_ID)
                        .markPending(Instant.now().toEpochMilli());
                pendingTransactions.save(pending);
                return new SubmitTxSnapshot(
                        submittedTxHash,
                        pending.status().name(),
                        "Transaction submitted to local Yano runtime and is pending block inclusion");
            } catch (RuntimeException e) {
                PendingTransaction failed = PendingTransaction
                        .fromDraft(lastDraft, currentWalletId(), PREPROD_NETWORK_ID)
                        .markFailed(e.getMessage() == null ? e.toString() : e.getMessage());
                pendingTransactions.save(failed);
                throw e;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<PendingTxSnapshot>> refreshPendingTransactions() {
        return CompletableFuture.supplyAsync(() -> {
            reconcilePendingTransactionsFromUtxoState();
            expirePendingTransactionsAtLocalTip();
            return pendingTransactionsForCurrentWallet().stream()
                    .map(this::toPendingTxSnapshot)
                    .toList();
        }, executor);
    }

    @Override
    public void close() {
        closeBridgeOnly();
        closeRuntimeOnly();
        executor.shutdownNow();
    }

    private WalletSnapshot toWalletSnapshot(String message) {
        WalletBalance balance = walletBalance == null
                ? new WalletBalance(BigInteger.ZERO, 0, 0, List.of())
                : walletBalance;
        return new WalletSnapshot(
                wallet.getBaseAddressString(0),
                balance.lovelace(),
                balance.addressCount(),
                balance.utxoCount(),
                balance.utxos().stream()
                        .map(utxo -> new UtxoSnapshot(
                                utxo.txHash() + "#" + utxo.outputIndex(),
                                utxo.address(),
                                utxo.lovelace(),
                                utxo.assetCount()))
                        .toList(),
                balance.assets().stream()
                        .map(asset -> new AssetSnapshot(asset.unit(), asset.quantity()))
                        .toList(),
                message);
    }

    private StoredWalletSnapshot toStoredWalletSnapshot(StoredWallet wallet) {
        return new StoredWalletSnapshot(
                wallet.id(),
                wallet.seedId(),
                wallet.name(),
                wallet.networkId(),
                wallet.accountIndex(),
                wallet.baseAddress(),
                wallet.stakeAddress(),
                wallet.drepId());
    }

    private RuntimeSnapshot runtimeSnapshot(String mode, String message) {
        var tip = runtime.localTip();
        NodeStatus status = runtime.status();
        Long localSlot = status != null && status.getLocalTipSlot() != null
                ? status.getLocalTipSlot()
                : tip != null ? tip.getSlot() : null;
        Long localBlock = status != null && status.getLocalTipBlockNumber() != null
                ? status.getLocalTipBlockNumber()
                : tip != null ? tip.getBlockNumber() : null;
        boolean syncing = status != null ? status.isSyncing() : runtime.isSyncing();
        String statusMessage = message != null
                ? message
                : status != null ? status.getStatusSummary() : "Runtime status refreshed";

        return new RuntimeSnapshot(
                runtime.isRunning(),
                syncing,
                mode,
                chainstatePath == null ? null : chainstatePath.toString(),
                localSlot,
                localBlock,
                status == null ? null : status.getRemoteTipSlot(),
                status == null ? null : status.getRemoteTipBlockNumber(),
                status == null ? null : status.calculateSyncProgress(),
                statusMessage);
    }

    private void validateRuntimePaths(Path chainstatePath, Path networkConfigDir) {
        if (!Files.isDirectory(chainstatePath)) {
            throw new IllegalArgumentException("Chainstate directory does not exist: " + chainstatePath);
        }
        if (!Files.isDirectory(networkConfigDir)) {
            throw new IllegalArgumentException("Network config directory does not exist: " + networkConfigDir);
        }
    }

    private WalletBalance scanWalletBalance() {
        return new WalletBalanceService().scan(wallet, utxoSupplier);
    }

    private List<Amount> assetAmount(String assetUnit, BigInteger assetQuantity) {
        if (assetUnit == null || assetUnit.isBlank()) {
            return List.of();
        }
        if (assetQuantity == null || assetQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Asset quantity must be positive");
        }
        return List.of(Amount.asset(assetUnit.trim(), assetQuantity));
    }

    private List<QuickTxPayment> toQuickTxPayments(List<SendPaymentRequest> payments) {
        if (payments == null || payments.isEmpty()) {
            throw new IllegalArgumentException("Add at least one recipient before building a transaction");
        }
        return payments.stream()
                .map(payment -> new QuickTxPayment(
                        payment.receiverAddress(),
                        payment.lovelace(),
                        payment.assets().stream()
                                .map(asset -> Amount.asset(asset.unit(), asset.quantity()))
                                .toList()))
                .toList();
    }

    private void refreshWalletBalanceIfRestored() {
        if (wallet != null && utxoSupplier != null) {
            walletBalance = scanWalletBalance();
        }
    }

    private int currentEpoch() {
        var tip = runtime.localTip();
        if (tip == null) {
            throw new IllegalStateException("Cannot resolve protocol params without a local tip");
        }
        return PREPROD_EPOCH_CALC.slotToEpoch(tip.getSlot());
    }

    private void registerPendingTransactionListener() {
        runtime.registerListeners(new YanoPendingTransactionListener(pendingTransactions, PREPROD_NETWORK_ID));
    }

    private void reconcilePendingTransactionsFromUtxoState() {
        if (runtime == null) {
            return;
        }
        new YanoPendingTransactionReconciler(pendingTransactions, PREPROD_NETWORK_ID)
                .reconcile(runtime.utxoState());
    }

    private List<PendingTransaction> pendingTransactionsForCurrentWallet() {
        if (wallet == null) {
            return pendingTransactions.listByNetwork(PREPROD_NETWORK_ID);
        }
        return pendingTransactions.list(currentWalletId(), PREPROD_NETWORK_ID);
    }

    private PendingTxSnapshot toPendingTxSnapshot(PendingTransaction transaction) {
        String message = transaction.lastError();
        if (message == null && transaction.confirmedSlot() != null) {
            message = "Confirmed in slot " + transaction.confirmedSlot();
        }
        return new PendingTxSnapshot(
                transaction.txHash(),
                transaction.status().name(),
                transaction.lovelace(),
                transaction.fee(),
                transaction.toAddress(),
                transaction.createdAtEpochMillis(),
                transaction.submittedAtEpochMillis(),
                transaction.confirmedSlot(),
                transaction.confirmedBlock(),
                message);
    }

    private void recordBridgeSubmittedTransaction(String txHash, String txCborHex) {
        if (wallet == null || txHash == null || txHash.isBlank()) {
            return;
        }

        BigInteger fee = BigInteger.ZERO;
        Long ttlSlot = null;
        try {
            Transaction transaction = Transaction.deserialize(HexUtil.decodeHexString(txCborHex));
            fee = transaction.getBody().getFee() == null ? BigInteger.ZERO : transaction.getBody().getFee();
            ttlSlot = transaction.getBody().getTtl() > 0 ? transaction.getBody().getTtl() : null;
        } catch (Exception ignored) {
            // Keep the bridge submission visible even if inspection fails.
        }

        PendingTransaction pending = new PendingTransaction(
                txHash,
                txCborHex,
                Instant.now().toEpochMilli(),
                Instant.now().toEpochMilli(),
                com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStatus.PENDING,
                currentWalletId(),
                PREPROD_NETWORK_ID,
                BigInteger.ZERO,
                fee,
                wallet.getBaseAddressString(0),
                "CIP-30 submit",
                ttlSlot,
                null,
                null,
                null,
                null);
        pendingTransactions.save(pending);
    }

    private BridgeSnapshot bridgeSnapshot(String message) {
        boolean running = bridgeHttpServer != null;
        return new BridgeSnapshot(
                running,
                running ? bridgeHttpServer.endpointUri().toString() : null,
                bridgeSessionRegistry.size(),
                message != null
                        ? message
                        : running ? "Local CIP-30 bridge running" : "Local CIP-30 bridge stopped");
    }

    private void expirePendingTransactionsAtLocalTip() {
        if (runtime == null || runtime.localTip() == null) {
            return;
        }
        long currentSlot = runtime.localTip().getSlot();
        pendingTransactions.listByNetwork(PREPROD_NETWORK_ID).stream()
                .filter(transaction -> transaction.isExpiredAt(currentSlot))
                .forEach(transaction -> pendingTransactions.save(transaction.markExpired(currentSlot)));
    }

    private String currentWalletId() {
        if (activeStoredWallet != null) {
            return activeStoredWallet.id();
        }
        if (wallet == null) {
            throw new IllegalStateException("Restore wallet before accessing wallet transaction state");
        }
        return wallet.getBaseAddressString(0);
    }

    private void activate(UnlockedWallet unlockedWallet) {
        activeStoredWallet = unlockedWallet.profile();
        wallet = unlockedWallet.wallet();
        walletBalance = utxoSupplier == null
                ? new WalletBalance(BigInteger.ZERO, 0, 0, List.of())
                : scanWalletBalance();
    }

    private StoredWallet activeWalletProfile() {
        if (activeStoredWallet != null) {
            return activeStoredWallet;
        }
        return new StoredWallet(
                wallet.getBaseAddressString(0),
                wallet.getBaseAddressString(0),
                "In-memory wallet",
                PREPROD_NETWORK_ID,
                0,
                wallet.getBaseAddressString(0),
                wallet.getStakeAddress(),
                wallet.getAccountAtIndex(0).drepId(),
                "memory",
                java.time.Instant.now(),
                java.time.Instant.now());
    }

    private char[] toPassphrase(String passphrase) {
        if (passphrase == null) {
            return new char[0];
        }
        return passphrase.toCharArray();
    }

    private static Path defaultPendingTransactionFile() {
        return defaultNetworkDataDir(PREPROD_NETWORK_ID).resolve("wallet").resolve("pending-transactions.json");
    }

    private static Path defaultNetworkDataDir(String networkId) {
        return Path.of(
                System.getProperty("user.home"),
                ".yano-wallet",
                "networks",
                networkId);
    }

    private void closeRuntimeOnly() {
        if (runtime != null) {
            try {
                runtime.stop();
            } catch (Exception ignored) {
                // Best-effort cleanup for the desktop shell.
            }
            runtime = null;
            utxoSupplier = null;
            runtimeSyncMode = false;
        }
    }

    private void closeBridgeOnly() {
        if (bridgeHttpServer != null) {
            bridgeHttpServer.close();
            bridgeHttpServer = null;
        }
    }
}
