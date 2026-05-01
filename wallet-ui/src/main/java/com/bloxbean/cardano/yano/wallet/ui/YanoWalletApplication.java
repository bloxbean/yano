package com.bloxbean.cardano.yano.wallet.ui;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

public class YanoWalletApplication extends Application {
    private static final double DEFAULT_WIDTH = 1_360;
    private static final double DEFAULT_HEIGHT = 940;
    private static final double MIN_WIDTH = 1_120;
    private static final double MIN_HEIGHT = 760;
    private static final BigInteger SELF_PAYMENT_LOVELACE = BigInteger.valueOf(1_000_000L);
    private static final DateTimeFormatter TX_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static WalletRuntimeController controller = WalletRuntimeController.unavailable();

    private final ObjectProperty<WalletNetwork> selectedNetwork = new SimpleObjectProperty<>(WalletNetwork.PREPROD);
    private final BooleanProperty nodeRunning = new SimpleBooleanProperty(false);
    private final BooleanProperty bridgeRunning = new SimpleBooleanProperty(false);
    private final DoubleProperty syncProgress = new SimpleDoubleProperty(1.0);
    private final StringProperty walletState = new SimpleStringProperty("No wallet");
    private final StringProperty selectedView = new SimpleStringProperty("Launch");
    private final StringProperty runtimeMode = new SimpleStringProperty("Offline");
    private final StringProperty chainstateState = new SimpleStringProperty("Not opened");
    private final StringProperty tipState = new SimpleStringProperty("Not connected");
    private final StringProperty balanceState = new SimpleStringProperty("0.000000 ADA");
    private final StringProperty availableState = new SimpleStringProperty("0 lovelace");
    private final StringProperty activeAccountState = new SimpleStringProperty("0");
    private final StringProperty walletAddressState = new SimpleStringProperty("-");
    private final StringProperty stakeAddressState = new SimpleStringProperty("-");
    private final StringProperty drepIdState = new SimpleStringProperty("-");
    private final StringProperty utxoCountState = new SimpleStringProperty("0");
    private final StringProperty txDraftState = new SimpleStringProperty("No draft");
    private final StringProperty txFeeState = new SimpleStringProperty("-");
    private final StringProperty pendingTxState = new SimpleStringProperty("No pending tx");
    private final StringProperty bridgeStatusState = new SimpleStringProperty("Stopped");
    private final StringProperty bridgeEndpointState = new SimpleStringProperty("-");
    private final StringProperty bridgeSessionsState = new SimpleStringProperty("0");
    private final StringProperty bridgeLastEventState = new SimpleStringProperty("No browser activity");
    private final ObservableList<UtxoRow> walletUtxos = FXCollections.observableArrayList();
    private final ObservableList<PendingTxRow> pendingTransactions = FXCollections.observableArrayList();
    private final ObservableList<StoredWalletRow> storedWallets = FXCollections.observableArrayList();
    private final ObservableList<AddressRow> walletAddresses = FXCollections.observableArrayList();
    private final ObservableList<AssetOption> availableAssets = FXCollections.observableArrayList();

    private Label networkChip;
    private Label nodeChip;
    private Label walletChip;
    private Label bridgeChip;
    private Button headerSyncButton;
    private ProgressBar syncBar;
    private Label syncPercent;
    private Label dataDirLabel;
    private ComboBox<StoredWalletRow> walletSelector;
    private StackPane content;
    private Timeline syncTimeline;
    private int receiveAddressCount = 10;
    private BigInteger currentLovelace = BigInteger.ZERO;

    public static void setController(WalletRuntimeController runtimeController) {
        controller = Objects.requireNonNull(runtimeController, "runtimeController is required");
    }

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(header());
        root.setLeft(sidebar());

        content = new StackPane();
        content.getStyleClass().add("content-wrap");
        root.setCenter(content);
        showLanding();

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());

        stage.setTitle("Yano Wallet");
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setScene(scene);
        stage.show();

        selectedNetwork.addListener((obs, oldValue, newValue) -> updateStatus());
        nodeRunning.addListener((obs, oldValue, newValue) -> updateStatus());
        bridgeRunning.addListener((obs, oldValue, newValue) -> updateStatus());
        walletState.addListener((obs, oldValue, newValue) -> updateStatus());
        syncProgress.addListener((obs, oldValue, newValue) -> updateStatus());
        updateStatus();
        loadStoredWallets();
    }

    @Override
    public void stop() throws Exception {
        stopRuntimePolling();
        if (controller instanceof AutoCloseable closeable) {
            closeable.close();
        }
        super.stop();
    }

    private HBox header() {
        Label title = new Label("Yano Wallet");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Full-node wallet and local developer runtime");
        subtitle.getStyleClass().add("app-subtitle");

        VBox titles = new VBox(2, title, subtitle);
        titles.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        networkChip = chip("");
        nodeChip = chip("");
        walletChip = chip("");
        bridgeChip = chip("");

        headerSyncButton = new Button("Start Sync");
        headerSyncButton.getStyleClass().addAll("primary-button");
        headerSyncButton.setOnAction(event -> toggleSync(headerSyncButton));

        HBox header = new HBox(14, titles, spacer, networkChip, nodeChip, walletChip, bridgeChip, headerSyncButton);
        header.getStyleClass().add("top-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private VBox sidebar() {
        VBox nav = new VBox(8);
        nav.getStyleClass().add("sidebar");
        nav.setPadding(new Insets(20, 14, 20, 14));

        Label section = new Label("Workspace");
        section.getStyleClass().add("nav-section");

        nav.getChildren().add(section);
        nav.getChildren().add(navButton("Launch", this::showLanding));
        nav.getChildren().add(navButton("Overview", this::showOverview));
        nav.getChildren().add(navButton("Send", this::showSend));
        nav.getChildren().add(navButton("UTXOs", this::showUtxos));
        nav.getChildren().add(navButton("Addresses", this::showAddresses));
        nav.getChildren().add(navButton("History", this::showHistory));
        nav.getChildren().add(navButton("Developer", this::showDeveloper));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label runtime = new Label("Wallet runtime");
        runtime.getStyleClass().add("runtime-label");
        Label version = new Label("MVP preprod build");
        version.getStyleClass().add("runtime-version");

        nav.getChildren().addAll(spacer, new Separator(), runtime, version);
        return nav;
    }

    private Button navButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(event -> {
            selectedView.set(text);
            action.run();
        });
        selectedView.addListener((obs, oldValue, newValue) -> {
            if (text.equals(newValue)) {
                if (!button.getStyleClass().contains("selected")) {
                    button.getStyleClass().add("selected");
                }
            } else {
                button.getStyleClass().remove("selected");
            }
        });
        if (text.equals(selectedView.get())) {
            button.getStyleClass().add("selected");
        }
        return button;
    }

    private void showLanding() {
        selectedView.set("Launch");

        Button openPreprod = primaryButton("Open Wallet Chainstate", () -> openPreprodChainstate());
        Button startSync = button("Start Sync", this::startPreprodSync);
        Button restore = button("Import Wallet", this::showImportWalletDialog);
        Button quickDraft = button("Build Self-Payment Draft", this::buildSelfPaymentDraft);
        Button submit = button("Submit Signed Draft", this::showSubmitDraftDialog);

        VBox actions = new VBox(10, openPreprod, startSync, restore, quickDraft, submit);
        actions.getStyleClass().add("landing-actions");

        Label title = new Label("Yano Wallet");
        title.getStyleClass().add("landing-title");
        title.setWrapText(true);

        Label subtitle = new Label("A full-node Cardano wallet and local developer runtime built in Java.");
        subtitle.getStyleClass().add("landing-copy");
        subtitle.setWrapText(true);

        VBox brand = new VBox(18, logoMark(), title, subtitle, actions);
        brand.getStyleClass().add("landing-left");
        brand.setAlignment(Pos.TOP_LEFT);

        GridPane statusGrid = new GridPane();
        statusGrid.getStyleClass().add("hero-grid");
        statusGrid.setHgap(12);
        statusGrid.setVgap(12);
        statusGrid.add(statusTile("Runtime", runtimeMode), 0, 0);
        statusGrid.add(statusTile("Chainstate", chainstateState), 1, 0);
        statusGrid.add(statusTile("Tip", tipState), 0, 1);
        statusGrid.add(statusTile("Wallet", walletState), 1, 1);
        statusGrid.add(statusTile("Balance", balanceState), 0, 2);
        statusGrid.add(statusTile("QuickTx", txDraftState), 1, 2);

        VBox addressBox = new VBox(10,
                panelHeader("Current Address"),
                boundMonoValue(walletAddressState),
                copyableMetric("Stake", stakeAddressState),
                copyableMetric("DRep", drepIdState),
                metric("UTXOs", utxoCountState),
                metric("Draft fee", txFeeState),
                metric("Pending", pendingTxState));
        addressBox.getStyleClass().add("address-band");

        VBox heroPanel = new VBox(16,
                panelHeader("Preprod Runtime"),
                statusGrid,
                addressBox);
        heroPanel.getStyleClass().add("hero-panel");

        HBox landing = new HBox(22, brand, heroPanel);
        landing.getStyleClass().add("landing");
        HBox.setHgrow(heroPanel, Priority.ALWAYS);

        setPage(landing);
    }

    private void showOverview() {
        selectedView.set("Overview");

        VBox page = page("Overview", "Runtime, wallet, UTXO, and bridge status");
        GridPane grid = twoColumnGrid();
        grid.add(runtimePanel(), 0, 0);
        grid.add(syncPanel(), 1, 0);
        grid.add(walletPanel(), 0, 1);
        grid.add(balancePanel(), 1, 1);
        grid.add(utxoPreviewPanel(), 0, 2);
        grid.add(bridgePanel(), 1, 2);

        page.getChildren().add(grid);
        setPage(page);
    }

    private void showSend() {
        selectedView.set("Send");

        TextField receiver = new TextField();
        receiver.setPromptText("addr...");
        TextField amount = new TextField();
        amount.setPromptText("Optional ADA");
        ComboBox<AssetOption> assetSelector = new ComboBox<>(availableAssets);
        assetSelector.setMaxWidth(Double.MAX_VALUE);
        assetSelector.setPromptText("Native asset");
        TextField assetQuantity = new TextField();
        assetQuantity.setPromptText("0");
        ObservableList<AssetTransferDraft> recipientAssets = FXCollections.observableArrayList();
        ListView<AssetTransferDraft> recipientAssetList = new ListView<>(recipientAssets);
        recipientAssetList.setPrefHeight(92);

        ObservableList<PaymentDraftRow> sendPayments = FXCollections.observableArrayList();
        TableView<PaymentDraftRow> paymentTable = new TableView<>(sendPayments);
        paymentTable.setPlaceholder(new Label("No recipients added"));
        paymentTable.setPrefHeight(210);
        paymentTable.getStyleClass().add("data-table");
        paymentTable.getColumns().add(column("Address", 260, PaymentDraftRow::shortAddress));
        paymentTable.getColumns().add(column("ADA", 110, PaymentDraftRow::ada));
        paymentTable.getColumns().add(column("Assets", 260, PaymentDraftRow::assetSummary));

        TextArea note = new TextArea();
        note.setPromptText("Metadata message");
        note.setPrefRowCount(3);

        Button addAsset = button("Add Asset", () -> {
            AssetOption selectedAsset = assetSelector.getSelectionModel().getSelectedItem();
            if (selectedAsset == null) {
                showInfoDialog("Asset", "Select an available asset first");
                return;
            }
            BigInteger quantity;
            try {
                quantity = parseOptionalPositiveInteger(assetQuantity.getText());
            } catch (RuntimeException e) {
                showInfoDialog("Asset quantity", e.getMessage());
                return;
            }
            if (quantity == null || quantity.signum() <= 0) {
                showInfoDialog("Asset quantity", "Asset quantity is required");
                return;
            }
            if (selectedAsset.available().signum() > 0 && quantity.compareTo(selectedAsset.available()) > 0) {
                showInfoDialog("Asset quantity", "Quantity exceeds available balance");
                return;
            }
            recipientAssets.add(new AssetTransferDraft(selectedAsset.unit(), quantity));
            assetQuantity.clear();
        });
        Button removeAsset = button("Remove Asset", () -> {
            AssetTransferDraft selectedAsset = recipientAssetList.getSelectionModel().getSelectedItem();
            if (selectedAsset != null) {
                recipientAssets.remove(selectedAsset);
            }
        });
        Button addRecipient = primaryButton("Add Recipient", () -> {
            if (receiver.getText() == null || receiver.getText().isBlank()) {
                showInfoDialog("Recipient", "Receiver address is required");
                return;
            }
            BigInteger lovelace;
            try {
                lovelace = parseOptionalAdaToLovelace(amount.getText());
            } catch (RuntimeException e) {
                showInfoDialog("Amount", e.getMessage());
                return;
            }
            if (lovelace.signum() == 0 && recipientAssets.isEmpty()) {
                showInfoDialog("Recipient", "Add ADA or at least one native asset");
                return;
            }
            sendPayments.add(new PaymentDraftRow(
                    receiver.getText().trim(),
                    lovelace,
                    List.copyOf(recipientAssets)));
            receiver.clear();
            amount.clear();
            recipientAssets.clear();
        });
        Button removeRecipient = button("Remove Recipient", () -> {
            PaymentDraftRow selectedPayment = paymentTable.getSelectionModel().getSelectedItem();
            if (selectedPayment != null) {
                sendPayments.remove(selectedPayment);
            }
        });
        Button review = primaryButton("Build Signed Draft", () -> buildMultiSendDraft(sendPayments, note.getText()));
        Button submit = button("Submit Signed Draft", this::showSubmitDraftDialog);

        VBox form = panel("Compose Transaction",
                field("To address", receiver),
                field("ADA", amount),
                field("Available asset", assetSelector),
                field("Asset quantity", assetQuantity),
                actionRow(addAsset, removeAsset),
                field("Assets for this recipient", recipientAssetList),
                actionRow(addRecipient, removeRecipient),
                field("Recipients", paymentTable),
                field("Metadata", note),
                actionRow(review, submit));

        TableView<AssetOption> assetTable = new TableView<>(availableAssets);
        assetTable.setPlaceholder(new Label("No native assets in the active account"));
        assetTable.setPrefHeight(180);
        assetTable.getStyleClass().add("data-table");
        assetTable.getColumns().add(column("Asset", 260, AssetOption::shortUnit));
        assetTable.getColumns().add(column("Available", 150, AssetOption::quantity));

        VBox summary = panel("Approval Boundary",
                metric("Signing state", walletState),
                metric("Account", activeAccountState),
                metric("Network", selectedNetwork.get().id()),
                metric("Runtime", runtimeMode),
                metric("Last draft", txDraftState),
                metric("Draft fee", txFeeState),
                metric("Pending", pendingTxState),
                field("Available native assets", assetTable));

        GridPane grid = twoColumnGrid();
        grid.add(form, 0, 0);
        grid.add(summary, 1, 0);

        VBox page = page("Send", "Build, inspect, approve, and submit multi-output ADA/native asset transactions");
        page.getChildren().add(grid);
        setPage(page);
    }

    private void showUtxos() {
        selectedView.set("UTXOs");

        TableView<UtxoRow> table = new TableView<>(walletUtxos);
        table.setPlaceholder(new Label("No UTXOs indexed"));
        table.getStyleClass().add("data-table");
        table.getColumns().add(outpointColumn("Outpoint", 220, UtxoRow::outpoint));
        table.getColumns().add(copyableColumn("Address", 360, UtxoRow::address));
        table.getColumns().add(column("Lovelace", 140, UtxoRow::lovelace));
        table.getColumns().add(column("Assets", 120, UtxoRow::assets));

        VBox page = page("UTXOs", "Current wallet UTXO set from Yano state");
        page.getChildren().add(panel("UTXO List", table));
        setPage(page);
    }

    private void showAddresses() {
        selectedView.set("Addresses");

        VBox page = page("Addresses", "Current account receive, stake, and DRep identifiers");
        page.getChildren().add(accountAddressesPanel());
        setPage(page);
        refreshActiveWalletAccount();
    }

    private void showHistory() {
        selectedView.set("History");

        TableView<PendingTxRow> table = new TableView<>(pendingTransactions);
        table.setPlaceholder(new Label("No wallet history indexed"));
        table.getStyleClass().add("data-table");
        table.getColumns().add(column("Time", 150, PendingTxRow::time));
        table.getColumns().add(column("Type", 100, PendingTxRow::type));
        table.getColumns().add(column("Amount", 150, PendingTxRow::amount));
        table.getColumns().add(column("Fee", 140, PendingTxRow::fee));
        table.getColumns().add(column("Status", 120, PendingTxRow::status));
        table.getColumns().add(txHashColumn("Tx hash", 300, PendingTxRow::txHash));
        table.getColumns().add(column("Block", 120, PendingTxRow::block));

        VBox page = page("History", "Wallet-local transaction index");
        page.getChildren().add(panel("Transactions", table));
        setPage(page);
        refreshPendingTransactions();
    }

    private void showDeveloper() {
        selectedView.set("Developer");

        VBox bridge = panel("Local Bridge",
                metric("Bind address", "127.0.0.1"),
                metric("Endpoint", bridgeEndpointState),
                metric("Sessions", bridgeSessionsState),
                metric("Last request", bridgeLastEventState),
                metric("CIP-30", "getBalance, getUtxos, signTx, submitTx"),
                actionRow(button("Toggle Bridge", this::toggleBridge)));

        VBox runtime = panel("Runtime",
                metric("Mode", runtimeMode.get()),
                metric("Network", selectedNetwork.get().id()),
                metric("Chainstate", chainstateState.get()),
                metric("Local tip", tipState.get()),
                metric("QuickTx", "Draft, sign, and explicit submit"));

        GridPane grid = twoColumnGrid();
        grid.add(bridge, 0, 0);
        grid.add(runtime, 1, 0);

        VBox page = page("Developer", "Local wallet API and runtime controls");
        page.getChildren().add(grid);
        setPage(page);
    }

    private VBox runtimePanel() {
        ToggleGroup networkGroup = new ToggleGroup();
        HBox networks = new HBox(8);
        networks.getStyleClass().add("segmented");

        for (WalletNetwork network : WalletNetwork.values()) {
            ToggleButton button = new ToggleButton(network.id());
            button.setToggleGroup(networkGroup);
            button.getStyleClass().add("segment");
            button.setSelected(network == selectedNetwork.get());
            button.setOnAction(event -> selectedNetwork.set(network));
            networks.getChildren().add(button);
        }

        dataDirLabel = new Label();
        dataDirLabel.getStyleClass().add("path-label");

        return panel("Yano Runtime",
                metric("Mode", runtimeMode.get()),
                metric("Node", nodeRunning.get() ? "Ready" : "Stopped"),
                metric("Chainstate", chainstateState.get()),
                field("Network", networks),
                field("Data directory", dataDirLabel));
    }

    private VBox syncPanel() {
        syncBar = new ProgressBar();
        syncBar.setMaxWidth(Double.MAX_VALUE);
        syncPercent = new Label();
        syncPercent.getStyleClass().add("large-number");

        Button start = button(nodeRunning.get() && runtimeMode.get().contains("sync") ? "Stop Sync" : "Start Sync",
                () -> toggleSync(null));

        return panel("Sync",
                metric("Status", nodeRunning.get() ? "At tip or ready" : "Stopped"),
                field("Progress", syncBar),
                field("Complete", syncPercent),
                metric("Local tip", tipState.get()),
                actionRow(start));
    }

    private VBox walletPanel() {
        walletSelector = new ComboBox<>(storedWallets);
        walletSelector.setMaxWidth(Double.MAX_VALUE);
        walletSelector.setPromptText("Select wallet");

        Button unlock = button("Unlock", () -> {
            StoredWalletRow selected = walletSelector.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showInfoDialog("Wallet", "Select a wallet first");
                return;
            }
            showUnlockWalletDialog(selected);
        });
        Button create = button("Create", this::showCreateWalletDialog);
        Button restore = button("Import", this::showImportWalletDialog);
        Button createAccount = button("Add Account", this::showCreateAccountDialog);
        Button refresh = button("Refresh", () -> {
            loadStoredWallets();
            refreshActiveWallet();
            refreshActiveWalletAccount();
        });

        return panel("Wallet",
                metric("State", walletState),
                metric("Account", activeAccountState),
                metric("Vault", "Encrypted per wallet"),
                copyableMetric("Address", walletAddressState),
                copyableMetric("Stake", stakeAddressState),
                copyableMetric("DRep", drepIdState),
                field("Stored wallets", walletSelector),
                actionRow(unlock, create, restore, createAccount, refresh));
    }

    private VBox balancePanel() {
        Label balance = new Label();
        balance.textProperty().bind(balanceState);
        balance.getStyleClass().add("balance");

        return panel("Balance",
                balance,
                metric("Available", availableState),
                metric("Pending", pendingTxState),
                metric("UTXOs", utxoCountState));
    }

    private VBox accountAddressesPanel() {
        TableView<AddressRow> table = new TableView<>(walletAddresses);
        table.setPlaceholder(new Label("No receive addresses loaded"));
        table.setPrefHeight(210);
        table.getColumns().add(column("Index", 70, AddressRow::index));
        table.getColumns().add(column("Role", 90, AddressRow::role));
        table.getColumns().add(column("Path", 160, AddressRow::path));
        table.getColumns().add(copyableColumn("Base address", 340, AddressRow::baseAddress));
        table.getColumns().add(copyableColumn("Enterprise", 300, AddressRow::enterpriseAddress));

        Button showMore = button("Show 10 More", () -> {
            receiveAddressCount += 10;
            refreshActiveWalletAccount();
        });
        Button firstTen = button("First 10", () -> {
            receiveAddressCount = 10;
            refreshActiveWalletAccount();
        });

        return panel("Receive Addresses",
                copyableMetric("Stake", stakeAddressState),
                copyableMetric("DRep", drepIdState),
                table,
                actionRow(showMore, firstTen));
    }

    private VBox utxoPreviewPanel() {
        TableView<UtxoRow> table = new TableView<>(walletUtxos);
        table.setPlaceholder(new Label("No UTXOs indexed"));
        table.setPrefHeight(190);
        table.getColumns().add(outpointColumn("Outpoint", 180, UtxoRow::outpoint));
        table.getColumns().add(column("Lovelace", 120, UtxoRow::lovelace));
        table.getColumns().add(column("Assets", 100, UtxoRow::assets));

        return panel("UTXOs", table);
    }

    private VBox bridgePanel() {
        Button toggle = button("Start Bridge", this::toggleBridge);
        bridgeRunning.addListener((obs, oldValue, newValue) -> toggle.setText(newValue ? "Stop Bridge" : "Start Bridge"));

        return panel("CIP-30 Bridge",
                metric("Status", bridgeStatusState),
                metric("Scope", "Loopback only"),
                metric("Endpoint", bridgeEndpointState),
                metric("Sessions", bridgeSessionsState),
                metric("Last request", bridgeLastEventState),
                actionRow(toggle));
    }

    private void openPreprodChainstate() {
        Path chainstate = preprodWalletChainstatePath();
        Path config = resolvePath("config/network/preprod");

        CompletableFuture<WalletRuntimeController.RuntimeSnapshot> future =
                controller.openPreprodChainstate(chainstate, config);

        runTask("Open Preprod", future, snapshot -> {
            applyRuntimeSnapshot(snapshot);
            showInfoDialog("Preprod Chainstate", snapshot.message());
        });
    }

    private void startPreprodSync() {
        CompletableFuture<WalletRuntimeController.RuntimeSnapshot> future =
                controller.startPreprodSync(preprodWalletChainstatePath(), resolvePath("config/network/preprod"));

        runTask("Start Sync", future, snapshot -> {
            applyRuntimeSnapshot(snapshot);
            startRuntimePolling();
            showInfoDialog("Preprod Sync", snapshot.message());
        });
    }

    private void stopRuntime() {
        CompletableFuture<WalletRuntimeController.RuntimeSnapshot> future = controller.stopRuntime();

        runTask("Stop Runtime", future, snapshot -> {
            stopRuntimePolling();
            applyRuntimeSnapshot(snapshot);
            showInfoDialog("Yano Runtime", snapshot.message());
        });
    }

    private void showMnemonicDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(content.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Restore Wallet");

        TextArea mnemonic = new TextArea();
        mnemonic.setPromptText("24-word recovery phrase");
        mnemonic.setPrefRowCount(4);
        mnemonic.setWrapText(true);

        Button cancel = button("Cancel", () -> {
            mnemonic.clear();
            dialog.close();
        });
        Button restore = new Button("Restore");
        restore.getStyleClass().add("primary-button");
        restore.setOnAction(event -> {
            String phrase = mnemonic.getText();
            mnemonic.clear();
            dialog.close();
            restorePreprodWallet(phrase);
        });

        VBox box = new VBox(14,
                dialogTitle("Restore Wallet"),
                field("Recovery phrase", mnemonic),
                actionRow(restore, cancel));
        box.getStyleClass().add("dialog");
        Scene scene = new Scene(box, 520, 300);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private void showCreateWalletDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(content.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Create Wallet");

        TextField name = new TextField("Preprod Wallet");
        TextArea mnemonic = new TextArea();
        mnemonic.setEditable(false);
        mnemonic.setWrapText(true);
        mnemonic.setPrefRowCount(5);
        mnemonic.getStyleClass().add("mono-value");
        PasswordField passphrase = new PasswordField();
        PasswordField confirm = new PasswordField();

        Button generate = button("Generate Recovery Phrase", () ->
                runTask("Generate Wallet", controller.generatePreprodMnemonic(), mnemonic::setText));
        Button cancel = button("Cancel", () -> {
            mnemonic.clear();
            passphrase.clear();
            confirm.clear();
            dialog.close();
        });
        Button save = new Button("Save Encrypted Wallet");
        save.getStyleClass().add("primary-button");
        save.setOnAction(event -> {
            if (!passphrase.getText().equals(confirm.getText())) {
                showInfoDialog("Wallet", "Passphrases do not match");
                return;
            }
            String phrase = mnemonic.getText();
            String walletName = name.getText();
            String password = passphrase.getText();
            mnemonic.clear();
            passphrase.clear();
            confirm.clear();
            dialog.close();
            importPreprodWallet(walletName, phrase, password);
        });

        VBox box = new VBox(14,
                dialogTitle("Create Wallet"),
                field("Name", name),
                field("Recovery phrase", mnemonic),
                field("Passphrase", passphrase),
                field("Confirm", confirm),
                actionRow(generate, save, cancel));
        box.getStyleClass().add("dialog");
        Scene scene = new Scene(box, 620, 520);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private void showImportWalletDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(content.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Import Wallet");

        TextField name = new TextField("Imported Wallet");
        TextArea mnemonic = new TextArea();
        mnemonic.setPromptText("24-word recovery phrase");
        mnemonic.setPrefRowCount(4);
        mnemonic.setWrapText(true);
        PasswordField passphrase = new PasswordField();
        PasswordField confirm = new PasswordField();

        Button cancel = button("Cancel", () -> {
            mnemonic.clear();
            passphrase.clear();
            confirm.clear();
            dialog.close();
        });
        Button restore = new Button("Import");
        restore.getStyleClass().add("primary-button");
        restore.setOnAction(event -> {
            if (!passphrase.getText().equals(confirm.getText())) {
                showInfoDialog("Wallet", "Passphrases do not match");
                return;
            }
            String phrase = mnemonic.getText();
            String walletName = name.getText();
            String password = passphrase.getText();
            mnemonic.clear();
            passphrase.clear();
            confirm.clear();
            dialog.close();
            importPreprodWallet(walletName, phrase, password);
        });

        VBox box = new VBox(14,
                dialogTitle("Import Wallet"),
                field("Name", name),
                field("Recovery phrase", mnemonic),
                field("Passphrase", passphrase),
                field("Confirm", confirm),
                actionRow(restore, cancel));
        box.getStyleClass().add("dialog");
        Scene scene = new Scene(box, 620, 460);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private void showCreateAccountDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(content.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Add Account");

        TextField name = new TextField(nextAccountName());
        Label detail = new Label("The new account is derived from the active encrypted wallet seed and becomes the active account after creation.");
        detail.setWrapText(true);
        detail.getStyleClass().add("dialog-copy");

        Button cancel = button("Cancel", dialog::close);
        Button create = new Button("Create Account");
        create.getStyleClass().add("primary-button");
        create.setOnAction(event -> {
            String accountName = name.getText();
            dialog.close();
            createAccountForActiveWallet(accountName);
        });

        VBox box = new VBox(14,
                dialogTitle("Add Account"),
                detail,
                field("Account name", name),
                actionRow(create, cancel));
        box.getStyleClass().add("dialog");
        Scene scene = new Scene(box, 520, 280);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private void showUnlockWalletDialog(StoredWalletRow walletRow) {
        Stage dialog = new Stage();
        dialog.initOwner(content.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Unlock Wallet");

        PasswordField passphrase = new PasswordField();
        Button cancel = button("Cancel", () -> {
            passphrase.clear();
            dialog.close();
        });
        Button unlock = new Button("Unlock");
        unlock.getStyleClass().add("primary-button");
        unlock.setOnAction(event -> {
            String password = passphrase.getText();
            passphrase.clear();
            dialog.close();
            unlockStoredWallet(walletRow.walletId(), password);
        });

        VBox box = new VBox(14,
                dialogTitle(walletRow.name()),
                field("Passphrase", passphrase),
                actionRow(unlock, cancel));
        box.getStyleClass().add("dialog");
        Scene scene = new Scene(box, 420, 240);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private void restorePreprodWallet(String mnemonic) {
        CompletableFuture<WalletRuntimeController.WalletSnapshot> future =
                controller.restorePreprodWallet(mnemonic);

        runTask("Restore Wallet", future, snapshot -> {
            applyWalletSnapshot(snapshot);
            refreshActiveWalletAccount();
            showInfoDialog("Wallet Restored", snapshot.message());
        });
    }

    private void importPreprodWallet(String name, String mnemonic, String passphrase) {
        CompletableFuture<WalletRuntimeController.WalletSnapshot> future =
                controller.importPreprodWallet(name, mnemonic, passphrase);

        runTask("Import Wallet", future, snapshot -> {
            applyWalletSnapshot(snapshot);
            loadStoredWallets();
            refreshActiveWalletAccount();
            showInfoDialog("Wallet Imported", snapshot.message());
        });
    }

    private void unlockStoredWallet(String walletId, String passphrase) {
        CompletableFuture<WalletRuntimeController.WalletSnapshot> future =
                controller.unlockStoredWallet(walletId, passphrase);

        runTask("Unlock Wallet", future, snapshot -> {
            applyWalletSnapshot(snapshot);
            refreshActiveWalletAccount();
            showInfoDialog("Wallet Unlocked", snapshot.message());
        });
    }

    private void createAccountForActiveWallet(String accountName) {
        CompletableFuture<WalletRuntimeController.WalletSnapshot> future =
                controller.createAccountForActiveWallet(accountName);

        runTask("Add Account", future, snapshot -> {
            applyWalletSnapshot(snapshot);
            loadStoredWallets();
            refreshActiveWalletAccount();
            showInfoDialog("Account Created", snapshot.message());
        });
    }

    private void buildSelfPaymentDraft() {
        CompletableFuture<WalletRuntimeController.QuickTxSnapshot> future =
                controller.buildSelfPaymentDraft(SELF_PAYMENT_LOVELACE);

        runTask("Build Draft", future, snapshot -> {
            txDraftState.set(abbreviate(snapshot.txHash(), 20));
            txFeeState.set(formatLovelace(snapshot.fee()));
            showInfoDialog(
                    "Signed Draft",
                    "Tx hash: " + snapshot.txHash()
                            + "\nFee: " + formatLovelace(snapshot.fee())
                            + assetLine(snapshot.assetSummary())
                            + metadataLine(snapshot.metadataSummary())
                            + "\nInputs: " + snapshot.inputCount()
                            + "\nOutputs: " + snapshot.outputCount()
                            + "\nStatus: " + snapshot.message());
        });
    }

    private void buildSendDraft(
            String receiverAddress,
            String adaText,
            String assetUnit,
            String assetQuantityText,
            String metadataMessage) {
        BigInteger lovelace;
        try {
            lovelace = parseAdaToLovelace(adaText);
        } catch (RuntimeException e) {
            showInfoDialog("Amount", e.getMessage());
            return;
        }
        BigInteger assetQuantity;
        try {
            assetQuantity = parseOptionalPositiveInteger(assetQuantityText);
        } catch (RuntimeException e) {
            showInfoDialog("Asset quantity", e.getMessage());
            return;
        }

        CompletableFuture<WalletRuntimeController.QuickTxSnapshot> future =
                controller.buildAssetSendDraft(receiverAddress, lovelace, assetUnit, assetQuantity, metadataMessage);

        runTask("Build Draft", future, snapshot -> {
            txDraftState.set(abbreviate(snapshot.txHash(), 20));
            txFeeState.set(formatLovelace(snapshot.fee()));
            showInfoDialog(
                    "Signed Draft",
                    "Tx hash: " + snapshot.txHash()
                            + "\nAmount: " + formatLovelace(snapshot.lovelace())
                            + "\nFee: " + formatLovelace(snapshot.fee())
                            + assetLine(snapshot.assetSummary())
                            + metadataLine(snapshot.metadataSummary())
                            + "\nInputs: " + snapshot.inputCount()
                            + "\nOutputs: " + snapshot.outputCount()
                            + "\nStatus: " + snapshot.message());
        });
    }

    private void buildMultiSendDraft(List<PaymentDraftRow> payments, String metadataMessage) {
        if (payments == null || payments.isEmpty()) {
            showInfoDialog("Recipients", "Add at least one recipient before building a transaction");
            return;
        }
        String validationError = validateRequestedOutputs(payments);
        if (validationError != null) {
            showInfoDialog("Transaction outputs", validationError);
            return;
        }
        List<WalletRuntimeController.SendPaymentRequest> request = payments.stream()
                .map(payment -> new WalletRuntimeController.SendPaymentRequest(
                        payment.address(),
                        payment.lovelace(),
                        payment.assets().stream()
                                .map(asset -> new WalletRuntimeController.AssetTransferRequest(
                                        asset.unit(),
                                        asset.quantity()))
                                .toList()))
                .toList();

        CompletableFuture<WalletRuntimeController.QuickTxSnapshot> future =
                controller.buildMultiAssetSendDraft(request, metadataMessage);

        runTask("Build Draft", future, snapshot -> {
            txDraftState.set(abbreviate(snapshot.txHash(), 20));
            txFeeState.set(formatLovelace(snapshot.fee()));
            showInfoDialog(
                    "Signed Draft",
                    "Tx hash: " + snapshot.txHash()
                            + "\nAmount: " + formatLovelace(snapshot.lovelace())
                            + "\nFee: " + formatLovelace(snapshot.fee())
                            + assetLine(snapshot.assetSummary())
                            + metadataLine(snapshot.metadataSummary())
                            + "\nInputs: " + snapshot.inputCount()
                            + "\nOutputs: " + snapshot.outputCount()
                            + "\nStatus: " + snapshot.message());
        });
    }

    private void showSubmitDraftDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(content.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Submit Transaction");

        Label label = new Label("Submit the currently signed transaction draft through the local Yano runtime?");
        label.setWrapText(true);
        Label draft = new Label(txDraftState.get());
        draft.getStyleClass().add("mono-value");
        draft.setWrapText(true);

        Button cancel = button("Cancel", dialog::close);
        Button submit = new Button("Submit");
        submit.getStyleClass().add("primary-button");
        submit.setOnAction(event -> {
            dialog.close();
            submitLastDraft();
        });

        VBox box = new VBox(16, dialogTitle("Submit Transaction"), label, draft, actionRow(submit, cancel));
        box.getStyleClass().add("dialog");
        Scene scene = new Scene(box, 520, 260);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private void submitLastDraft() {
        CompletableFuture<WalletRuntimeController.SubmitTxSnapshot> future = controller.submitLastDraft();

        runTask("Submit Transaction", future, snapshot -> {
            pendingTxState.set(snapshot.status() + " " + abbreviate(snapshot.txHash(), 12));
            refreshPendingTransactions();
            showInfoDialog("Submitted", snapshot.message() + "\nTx hash: " + snapshot.txHash());
        });
    }

    private void toggleBridge() {
        CompletableFuture<WalletRuntimeController.BridgeSnapshot> future = bridgeRunning.get()
                ? controller.stopBridge()
                : controller.startBridge(this::approveBridgeConnection, this::approveBridgeTransaction);
        runTask("Local Bridge", future, snapshot -> {
            applyBridgeSnapshot(snapshot);
            showInfoDialog("Local Bridge", snapshot.message());
        });
    }

    private void applyBridgeSnapshot(WalletRuntimeController.BridgeSnapshot snapshot) {
        bridgeRunning.set(snapshot.running());
        bridgeStatusState.set(snapshot.running() ? "Running" : "Stopped");
        bridgeEndpointState.set(snapshot.endpoint() == null || snapshot.endpoint().isBlank() ? "-" : snapshot.endpoint());
        bridgeSessionsState.set(Integer.toString(snapshot.sessionCount()));
    }

    private boolean approveBridgeConnection(String origin, List<String> permissions) {
        setBridgeLastEvent("Connection request from " + nullSafe(origin));
        boolean approved = bridgeDecision(
                "Bridge Connection",
                "Origin\n" + nullSafe(origin) + "\n\nPermissions\n" + String.join(", ", permissions));
        setBridgeLastEvent((approved ? "Approved " : "Rejected ") + nullSafe(origin));
        refreshBridgeStatusSoon();
        return approved;
    }

    private boolean approveBridgeTransaction(WalletRuntimeController.BridgeTransactionApprovalSnapshot request) {
        setBridgeLastEvent(request.method() + " request from " + nullSafe(request.origin()));
        boolean approved = bridgeDecision(
                "Bridge Transaction",
                bridgeTransactionDetail(request));
        setBridgeLastEvent((approved ? "Approved " : "Rejected ") + request.method() + " from " + nullSafe(request.origin()));
        refreshBridgeStatusSoon();
        return approved;
    }

    private void setBridgeLastEvent(String event) {
        Runnable update = () -> bridgeLastEventState.set(event == null || event.isBlank() ? "No browser activity" : event);
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private void refreshBridgeStatusSoon() {
        Runnable schedule = () -> {
            Timeline delay = new Timeline(new KeyFrame(Duration.millis(300), event ->
                    controller.refreshBridgeStatus().whenComplete((snapshot, error) -> {
                        if (error == null && snapshot != null) {
                            Platform.runLater(() -> applyBridgeSnapshot(snapshot));
                        }
                    })));
            delay.play();
        };
        if (Platform.isFxApplicationThread()) {
            schedule.run();
        } else {
            Platform.runLater(schedule);
        }
    }

    private boolean bridgeDecision(String title, String detail) {
        if (Platform.isFxApplicationThread()) {
            return showBridgeDecisionDialog(title, detail);
        }
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        Platform.runLater(() -> decision.complete(showBridgeDecisionDialog(title, detail)));
        return decision.join();
    }

    private boolean showBridgeDecisionDialog(String title, String detail) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(title);
        dialog.setAlwaysOnTop(true);

        AtomicBoolean approved = new AtomicBoolean(false);
        TextArea details = new TextArea(detail);
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(14);
        details.getStyleClass().add("dialog-copy");

        Button approve = primaryButton("Approve", () -> {
            approved.set(true);
            dialog.close();
        });
        Button reject = button("Reject", dialog::close);

        VBox box = new VBox(16, dialogTitle(title), details, actionRow(approve, reject));
        box.getStyleClass().add("dialog");
        Scene scene = new Scene(box, 680, 500);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.setOnShown(event -> {
            dialog.toFront();
            dialog.requestFocus();
        });
        dialog.showAndWait();
        return approved.get();
    }

    private String bridgeTransactionDetail(WalletRuntimeController.BridgeTransactionApprovalSnapshot request) {
        StringBuilder detail = new StringBuilder();
        detail.append("Origin\n").append(nullSafe(request.origin()));
        detail.append("\n\nMethod\n").append(request.method());
        detail.append("\n\nPartial sign\n").append(request.partialSign());

        try {
            Transaction transaction = Transaction.deserialize(HexUtil.decodeHexString(request.txCborHex()));
            TransactionBody body = transaction.getBody();
            detail.append("\n\nTransaction summary");
            detail.append("\nFee: ").append(formatLovelace(body.getFee()));
            detail.append("\nInputs: ").append(size(body.getInputs()));
            detail.append("\nCollateral inputs: ").append(size(body.getCollateral()));
            detail.append("\nReference inputs: ").append(size(body.getReferenceInputs()));
            if (body.getTtl() > 0) {
                detail.append("\nValid until slot: ").append(String.format("%,d", body.getTtl()));
            }
            if (transaction.getAuxiliaryData() != null || body.getAuxiliaryDataHash() != null) {
                detail.append("\nMetadata: present");
            }
            appendAdvancedTransactionFlags(detail, body);
            appendOutputs(detail, body.getOutputs());
        } catch (Exception e) {
            detail.append("\n\nTransaction summary\nUnable to decode transaction CBOR: ")
                    .append(e.getMessage());
        }

        detail.append("\n\nTransaction CBOR\n").append(abbreviate(request.txCborHex(), 48));
        return detail.toString();
    }

    private void appendAdvancedTransactionFlags(StringBuilder detail, TransactionBody body) {
        List<String> flags = new ArrayList<>();
        if (size(body.getCerts()) > 0) {
            flags.add(size(body.getCerts()) + " certificate(s)");
        }
        if (size(body.getWithdrawals()) > 0) {
            flags.add(size(body.getWithdrawals()) + " withdrawal(s)");
        }
        if (size(body.getMint()) > 0) {
            flags.add("mint/burn: " + multiAssetSummary(body.getMint(), 5));
        }
        if (body.getVotingProcedures() != null) {
            flags.add("governance vote(s)");
        }
        if (size(body.getProposalProcedures()) > 0) {
            flags.add(size(body.getProposalProcedures()) + " governance proposal(s)");
        }
        if (body.getDonation() != null) {
            flags.add("treasury donation: " + formatLovelace(body.getDonation()));
        }
        if (!flags.isEmpty()) {
            detail.append("\nAdvanced fields: ").append(String.join("; ", flags));
        }
    }

    private void appendOutputs(StringBuilder detail, List<TransactionOutput> outputs) {
        detail.append("\n\nOutputs");
        if (outputs == null || outputs.isEmpty()) {
            detail.append("\n- none");
            return;
        }

        int limit = Math.min(outputs.size(), 8);
        for (int i = 0; i < limit; i++) {
            TransactionOutput output = outputs.get(i);
            detail.append("\n- ").append(abbreviate(output.getAddress(), 18));
            detail.append("\n  ").append(valueSummary(output.getValue()));
            if (output.getDatumHash() != null) {
                detail.append("\n  datum hash: ").append(abbreviate(HexUtil.encodeHexString(output.getDatumHash()), 16));
            }
            if (output.getInlineDatum() != null) {
                detail.append("\n  inline datum: present");
            }
            if (output.getScriptRef() != null) {
                detail.append("\n  reference script: present");
            }
        }
        if (outputs.size() > limit) {
            detail.append("\n- ").append(outputs.size() - limit).append(" more output(s)");
        }
    }

    private String valueSummary(Value value) {
        if (value == null) {
            return "value: unavailable";
        }

        List<String> parts = new ArrayList<>();
        parts.add(formatLovelace(value.getCoin()));
        String assets = multiAssetSummary(value.getMultiAssets(), 6);
        if (!assets.isBlank()) {
            parts.add(assets);
        }
        return String.join("; ", parts);
    }

    private String multiAssetSummary(List<MultiAsset> multiAssets, int limit) {
        if (multiAssets == null || multiAssets.isEmpty()) {
            return "";
        }

        List<String> assets = new ArrayList<>();
        for (MultiAsset multiAsset : multiAssets) {
            if (multiAsset.getAssets() == null) {
                continue;
            }
            for (Asset asset : multiAsset.getAssets()) {
                if (assets.size() >= limit) {
                    assets.add("...");
                    return String.join(", ", assets);
                }
                assets.add(abbreviate(multiAsset.getPolicyId() + "." + asset.getNameAsHex(), 12)
                        + ":" + asset.getValue());
            }
        }
        return String.join(", ", assets);
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private <T> void runTask(String title, CompletableFuture<T> future, Consumer<T> onSuccess) {
        if (title.contains("Draft")) {
            txDraftState.set("Building draft...");
        }
        future.whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                showInfoDialog(title, cause.getMessage() == null ? cause.toString() : cause.getMessage());
                if (title.contains("Draft")) {
                    txDraftState.set("Draft failed");
                }
                return;
            }
            onSuccess.accept(result);
        }));
    }

    private void applyRuntimeSnapshot(WalletRuntimeController.RuntimeSnapshot snapshot) {
        selectedNetwork.set(WalletNetwork.PREPROD);
        runtimeMode.set(snapshot.mode());
        chainstateState.set(snapshot.chainstatePath() == null ? "Not opened" : snapshot.chainstatePath());
        tipState.set(formatTip(snapshot.tipSlot(), snapshot.tipBlock(), snapshot.remoteTipSlot()));
        nodeRunning.set(snapshot.opened());
        if (snapshot.syncProgress() == null) {
            syncProgress.set(snapshot.syncing() ? -1.0 : snapshot.opened() ? 1.0 : 0.0);
        } else {
            syncProgress.set(Math.max(0.0, Math.min(1.0, snapshot.syncProgress() / 100.0)));
        }
        updateStatus();
    }

    private void applyWalletSnapshot(WalletRuntimeController.WalletSnapshot snapshot) {
        if (walletState.get().startsWith("No") || "Locked".equals(walletState.get())) {
            walletState.set("Restored in memory");
        }
        currentLovelace = snapshot.lovelace();
        walletAddressState.set(snapshot.address());
        balanceState.set(formatAda(snapshot.lovelace()));
        availableState.set(formatLovelace(snapshot.lovelace()));
        utxoCountState.set(Integer.toString(snapshot.utxoCount()));
        walletUtxos.setAll(snapshot.utxos().stream()
                .map(utxo -> new UtxoRow(
                        utxo.outpoint(),
                        utxo.address(),
                        formatLovelace(utxo.lovelace()),
                        Integer.toString(utxo.assetCount())))
                .toList());
        availableAssets.setAll(snapshot.assets().stream()
                .map(asset -> new AssetOption(
                        asset.unit(),
                        asset.quantity(),
                        abbreviate(asset.unit(), 10) + "  " + asset.quantity()))
                .toList());
    }

    private void loadStoredWallets() {
        controller.listStoredWallets().whenComplete((wallets, error) -> Platform.runLater(() -> {
            if (error != null) {
                return;
            }
            storedWallets.setAll(wallets.stream()
                    .map(wallet -> new StoredWalletRow(
                            wallet.walletId(),
                            wallet.seedId(),
                            wallet.name(),
                            wallet.networkId(),
                            wallet.accountIndex(),
                            wallet.baseAddress(),
                            wallet.stakeAddress(),
                            wallet.drepId(),
                            wallet.name() + " · acct " + wallet.accountIndex() + " · " + abbreviate(wallet.baseAddress(), 8)))
                    .toList());
        }));
    }

    private void refreshActiveWalletAccount() {
        controller.refreshActiveWalletAccount(receiveAddressCount).whenComplete((account, error) -> Platform.runLater(() -> {
            if (error == null) {
                applyActiveWalletAccount(account);
            }
        }));
    }

    private void refreshActiveWallet() {
        controller.refreshWallet().whenComplete((snapshot, error) -> Platform.runLater(() -> {
            if (error == null) {
                applyWalletSnapshot(snapshot);
            }
        }));
    }

    private void applyActiveWalletAccount(WalletRuntimeController.AccountSnapshot account) {
        walletState.set(account.name());
        activeAccountState.set(Integer.toString(account.accountIndex()));
        stakeAddressState.set(account.stakeAddress() == null ? "-" : account.stakeAddress());
        drepIdState.set(account.drepId() == null ? "-" : account.drepId());
        walletAddresses.setAll(account.receiveAddresses().stream()
                .map(address -> new AddressRow(
                        Integer.toString(address.addressIndex()),
                        address.role(),
                        address.derivationPath(),
                        address.baseAddress(),
                        address.enterpriseAddress()))
                .toList());
        if (!account.receiveAddresses().isEmpty()) {
            walletAddressState.set(account.receiveAddresses().getFirst().baseAddress());
        }
    }

    private void refreshPendingTransactions() {
        controller.refreshPendingTransactions().whenComplete((transactions, error) -> Platform.runLater(() -> {
            if (error == null) {
                applyPendingTransactions(transactions);
            }
        }));
    }

    private void applyPendingTransactions(java.util.List<WalletRuntimeController.PendingTxSnapshot> transactions) {
        pendingTransactions.setAll(transactions.stream()
                .map(tx -> new PendingTxRow(
                        formatTxTime(tx.submittedAtEpochMillis() != null
                                ? tx.submittedAtEpochMillis()
                                : tx.createdAtEpochMillis()),
                        "ADA send",
                        formatLovelace(tx.lovelace()),
                        formatLovelace(tx.fee()),
                        tx.status(),
                        tx.txHash(),
                        tx.confirmedBlock() == null ? "-" : String.format("%,d", tx.confirmedBlock())))
                .toList());

        long active = transactions.stream()
                .filter(tx -> "PENDING".equals(tx.status())
                        || "SUBMITTED".equals(tx.status())
                        || "ROLLED_BACK".equals(tx.status()))
                .count();
        if (active == 0) {
            pendingTxState.set(transactions.isEmpty() ? "No pending tx" : "0 pending");
        } else {
            pendingTxState.set(active == 1 ? "1 pending" : active + " pending");
        }
    }

    private void startRuntimePolling() {
        stopRuntimePolling();
        syncTimeline = new Timeline(new KeyFrame(Duration.seconds(4), event -> pollRuntime()));
        syncTimeline.setCycleCount(Timeline.INDEFINITE);
        syncTimeline.play();
        pollRuntime();
    }

    private void stopRuntimePolling() {
        if (syncTimeline != null) {
            syncTimeline.stop();
            syncTimeline = null;
        }
    }

    private void pollRuntime() {
        controller.refreshRuntimeStatus().whenComplete((snapshot, error) -> Platform.runLater(() -> {
            if (error == null) {
                applyRuntimeSnapshot(snapshot);
            }
        }));

        if (!walletState.get().startsWith("No")) {
            controller.refreshWallet().whenComplete((snapshot, error) -> Platform.runLater(() -> {
                if (error == null) {
                    applyWalletSnapshot(snapshot);
                }
            }));
        }

        refreshPendingTransactions();
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private Path resolvePath(String path) {
        Path direct = Path.of(path);
        if (Files.exists(direct)) {
            return direct;
        }

        Path underNodeApp = Path.of("node-app").resolve(path);
        if (Files.exists(underNodeApp)) {
            return underNodeApp;
        }

        Path siblingNodeApp = Path.of("..", "node-app").resolve(path);
        if (Files.exists(siblingNodeApp)) {
            return siblingNodeApp;
        }

        return direct;
    }

    private Path preprodWalletChainstatePath() {
        Path walletPath = Path.of(
                System.getProperty("user.home"),
                ".yano-wallet",
                "networks",
                "preprod",
                "yano",
                "chainstate");
        if (Files.exists(walletPath)) {
            return walletPath;
        }
        return resolvePath("chainstate");
    }

    private VBox page(String title, String subtitle) {
        Label heading = new Label(title);
        heading.getStyleClass().add("page-title");
        Label subheading = new Label(subtitle);
        subheading.getStyleClass().add("page-subtitle");
        VBox page = new VBox(18, heading, subheading);
        page.getStyleClass().add("page");
        return page;
    }

    private GridPane twoColumnGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(18);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(50);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(50);
        grid.getColumnConstraints().addAll(left, right);
        return grid;
    }

    private VBox panel(String title, javafx.scene.Node... children) {
        Label heading = new Label(title);
        heading.getStyleClass().add("panel-title");
        VBox panel = new VBox(13, heading);
        panel.getStyleClass().add("panel");
        panel.getChildren().addAll(children);
        return panel;
    }

    private Label panelHeader(String text) {
        Label heading = new Label(text);
        heading.getStyleClass().add("panel-title");
        return heading;
    }

    private VBox statusTile(String label, StringProperty valueProperty) {
        Label key = new Label(label);
        key.getStyleClass().add("status-label");

        Label value = new Label();
        value.textProperty().bind(valueProperty);
        value.setWrapText(true);
        value.getStyleClass().add("status-value");

        VBox tile = new VBox(8, key, value);
        tile.getStyleClass().add("status-tile");
        tile.setMinHeight(92);
        return tile;
    }

    private HBox metric(String label, String value) {
        Label key = new Label(label);
        key.getStyleClass().add("metric-key");
        Label val = new Label(value);
        val.getStyleClass().add("metric-value");
        val.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, key, spacer, val);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("metric-row");
        return row;
    }

    private HBox metric(String label, StringProperty valueProperty) {
        Label key = new Label(label);
        key.getStyleClass().add("metric-key");
        Label val = new Label();
        val.textProperty().bind(valueProperty);
        val.getStyleClass().add("metric-value");
        val.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, key, spacer, val);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("metric-row");
        return row;
    }

    private HBox copyableMetric(String label, StringProperty valueProperty) {
        Label key = new Label(label);
        key.getStyleClass().add("metric-key");
        TextField value = copyableTextField("");
        value.textProperty().bind(valueProperty);
        value.getStyleClass().add("metric-value");
        Button copy = button("Copy", () -> copyToClipboard(valueProperty.get()));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox row = new HBox(12, key, spacer, value, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("metric-row");
        return row;
    }

    private javafx.scene.Node boundMonoValue(StringProperty valueProperty) {
        TextField field = copyableTextField("");
        field.textProperty().bind(valueProperty);
        field.getStyleClass().add("mono-value");
        return field;
    }

    private TextField copyableTextField(String value) {
        TextField field = new TextField(value == null ? "" : value);
        field.setEditable(false);
        field.setFocusTraversable(true);
        field.getStyleClass().add("copyable-value");
        field.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                field.selectAll();
            }
        });
        return field;
    }

    private void copyToClipboard(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void openTxInExplorer(String txHash) {
        String url = cardanoscanTxUrl(txHash);
        if (url != null) {
            getHostServices().showDocument(url);
        }
    }

    private String cardanoscanTxUrl(String txHash) {
        if (txHash == null || txHash.isBlank() || "-".equals(txHash)) {
            return null;
        }
        String baseUrl = switch (selectedNetwork.get()) {
            case MAINNET -> "https://cardanoscan.io/transaction/";
            case PREPROD -> "https://preprod.cardanoscan.io/transaction/";
            case PREVIEW -> "https://preview.cardanoscan.io/transaction/";
            case DEVNET -> null;
        };
        return baseUrl == null ? null : baseUrl + txHash;
    }

    private VBox field(String label, javafx.scene.Node control) {
        Label key = new Label(label);
        key.getStyleClass().add("field-label");
        return new VBox(6, key, control);
    }

    private HBox actionRow(javafx.scene.Node... actions) {
        HBox row = new HBox(10, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("action-row");
        return row;
    }

    private Button primaryButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("secondary-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Label chip(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("chip");
        return label;
    }

    private Label dialogTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-title");
        return label;
    }

    private Pane logoMark() {
        Pane canvas = new Pane();
        canvas.getStyleClass().add("logo-canvas");
        canvas.setMinSize(168, 168);
        canvas.setPrefSize(168, 168);
        canvas.setMaxSize(168, 168);

        Circle ring = new Circle(84, 84, 58);
        ring.getStyleClass().add("logo-ring");
        Circle orbit = new Circle(84, 84, 72);
        orbit.getStyleClass().add("logo-orbit");
        Circle core = new Circle(84, 84, 26);
        core.getStyleClass().add("logo-core");

        double[][] points = {
                {84, 20}, {132, 42}, {144, 102}, {96, 144}, {34, 118}, {28, 54}
        };
        canvas.getChildren().addAll(orbit, ring);
        for (double[] point : points) {
            Line line = new Line(84, 84, point[0], point[1]);
            line.getStyleClass().add("logo-spoke");
            Circle node = new Circle(point[0], point[1], 7);
            node.getStyleClass().add("logo-node");
            canvas.getChildren().addAll(line, node);
        }
        canvas.getChildren().add(core);
        return canvas;
    }

    private <T> TableColumn<T, String> column(String title, double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        return column;
    }

    private <T> TableColumn<T, String> column(String title, double width, Function<T, String> valueMapper) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(valueMapper.apply(cell.getValue())));
        return column;
    }

    private <T> TableColumn<T, String> copyableColumn(String title, double width, Function<T, String> valueMapper) {
        TableColumn<T, String> column = column(title, width, valueMapper);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final TextField text = copyableTextField("");

            {
                text.setMaxWidth(Double.MAX_VALUE);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                text.setText(item);
                setText(null);
                setGraphic(text);
            }
        });
        return column;
    }

    private <T> TableColumn<T, String> outpointColumn(String title, double width, Function<T, String> valueMapper) {
        TableColumn<T, String> column = column(title, width, valueMapper);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();
            private final Button copy = button("Copy", () -> copyToClipboard(link.getUserData() == null ? "" : link.getUserData().toString()));
            private final HBox row = new HBox(8, link, copy);

            {
                row.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(link, Priority.ALWAYS);
                link.setMaxWidth(Double.MAX_VALUE);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank() || "-".equals(item)) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String txHash = item.contains("#") ? item.substring(0, item.indexOf('#')) : item;
                link.setUserData(item);
                link.setText(abbreviate(item, 18));
                link.setDisable(cardanoscanTxUrl(txHash) == null);
                link.setOnAction(event -> openTxInExplorer(txHash));
                setText(null);
                setGraphic(row);
            }
        });
        return column;
    }

    private <T> TableColumn<T, String> txHashColumn(String title, double width, Function<T, String> valueMapper) {
        TableColumn<T, String> column = column(title, width, valueMapper);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();
            private final Button copy = button("Copy", () -> copyToClipboard(link.getUserData() == null ? "" : link.getUserData().toString()));
            private final HBox row = new HBox(8, link, copy);

            {
                row.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(link, Priority.ALWAYS);
                link.setMaxWidth(Double.MAX_VALUE);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank() || "-".equals(item)) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String txHash = item.contains("#") ? item.substring(0, item.indexOf('#')) : item;
                link.setUserData(txHash);
                link.setText(abbreviate(item, 18));
                link.setDisable(cardanoscanTxUrl(txHash) == null);
                link.setOnAction(event -> openTxInExplorer(txHash));
                setText(null);
                setGraphic(row);
            }
        });
        return column;
    }

    private void setPage(javafx.scene.Node page) {
        ScrollPane scrollPane = new ScrollPane(page);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("page-scroll");
        content.getChildren().setAll(scrollPane);
        updateStatus();
    }

    private void toggleSync(Button source) {
        if (nodeRunning.get() && runtimeMode.get().contains("sync")) {
            stopRuntime();
            return;
        }

        startPreprodSync();
    }

    private void showWalletDialog(String title) {
        Stage dialog = new Stage();
        dialog.initOwner(content.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(title);

        PasswordField passphrase = new PasswordField();
        passphrase.setPromptText("Passphrase");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Confirm passphrase");

        Button cancel = button("Cancel", dialog::close);
        Button save = new Button(title);
        save.getStyleClass().add("primary-button");
        save.setOnAction(event -> {
            walletState.set("Locked");
            dialog.close();
        });

        VBox box = new VBox(14,
                dialogTitle(title),
                field("Passphrase", passphrase),
                field("Confirm", confirm),
                actionRow(save, cancel));
        box.getStyleClass().add("dialog");
        Scene scene = new Scene(box, 420, 260);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private void showInfoDialog(String title, String message) {
        Stage dialog = new Stage();
        dialog.initOwner(content.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(title);

        TextArea label = new TextArea(message == null ? "" : message);
        label.setEditable(false);
        label.setWrapText(true);
        label.setPrefRowCount(6);
        label.getStyleClass().add("dialog-copy");
        Button close = button("Close", dialog::close);
        VBox box = new VBox(16, dialogTitle(title), label, actionRow(close));
        box.getStyleClass().add("dialog");

        Scene scene = new Scene(box, 520, 260);
        scene.getStylesheets().add(Objects.requireNonNull(
                YanoWalletApplication.class.getResource("yano-wallet.css")).toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }

    private void updateStatus() {
        if (networkChip == null) {
            return;
        }

        networkChip.setText(selectedNetwork.get().id());
        nodeChip.setText(nodeRunning.get() ? "Runtime ready" : "Runtime stopped");
        walletChip.setText(walletState.get());
        bridgeChip.setText(bridgeRunning.get() ? "Bridge running" : "Bridge stopped");

        networkChip.getStyleClass().removeAll("danger-chip", "ok-chip");
        networkChip.getStyleClass().add(selectedNetwork.get().production() ? "danger-chip" : "ok-chip");

        nodeChip.getStyleClass().removeAll("ok-chip", "warn-chip");
        nodeChip.getStyleClass().add(nodeRunning.get() ? "ok-chip" : "warn-chip");

        walletChip.getStyleClass().removeAll("ok-chip", "warn-chip");
        walletChip.getStyleClass().add(walletState.get().startsWith("No") ? "warn-chip" : "ok-chip");

        bridgeChip.getStyleClass().removeAll("ok-chip", "warn-chip");
        bridgeChip.getStyleClass().add(bridgeRunning.get() ? "ok-chip" : "warn-chip");

        if (syncBar != null) {
            syncBar.setProgress(syncProgress.get());
        }
        if (syncPercent != null) {
            syncPercent.setText(syncProgress.get() < 0
                    ? "Syncing"
                    : String.format("%.0f%%", syncProgress.get() * 100));
        }
        if (dataDirLabel != null) {
            dataDirLabel.setText(chainstateState.get().equals("Not opened")
                    ? "~/.yano-wallet/networks/" + selectedNetwork.get().id()
                    : chainstateState.get());
        }
        if (headerSyncButton != null) {
            headerSyncButton.setText(nodeRunning.get() && runtimeMode.get().contains("sync") ? "Stop Sync" : "Start Sync");
        }
    }

    private String formatAda(BigInteger lovelace) {
        BigDecimal ada = new BigDecimal(lovelace == null ? BigInteger.ZERO : lovelace)
                .movePointLeft(6)
                .setScale(6, RoundingMode.DOWN);
        return ada.toPlainString() + " ADA";
    }

    private static String formatLovelace(BigInteger lovelace) {
        return String.format("%,d lovelace", lovelace == null ? BigInteger.ZERO : lovelace);
    }

    private String formatTip(Long slot, Long block, Long remoteSlot) {
        if (slot == null) {
            return "No local tip";
        }
        String blockText = block == null ? "unknown block" : "block " + String.format("%,d", block);
        String remoteText = remoteSlot == null ? "" : " | remote " + String.format("%,d", remoteSlot);
        return "slot " + String.format("%,d", slot) + " | " + blockText + remoteText;
    }

    private String formatTxTime(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return "-";
        }
        return TX_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private BigInteger parseAdaToLovelace(String adaText) {
        if (adaText == null || adaText.isBlank()) {
            throw new IllegalArgumentException("Amount is required");
        }

        BigDecimal ada = new BigDecimal(adaText.trim());
        if (ada.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        return ada.movePointRight(6).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    private BigInteger parseOptionalAdaToLovelace(String adaText) {
        if (adaText == null || adaText.isBlank()) {
            return BigInteger.ZERO;
        }

        BigDecimal ada = new BigDecimal(adaText.trim());
        if (ada.signum() < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
        return ada.movePointRight(6).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    private BigInteger parseOptionalPositiveInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        BigInteger parsed = new BigInteger(value.trim());
        if (parsed.signum() <= 0) {
            throw new IllegalArgumentException("Asset quantity must be positive");
        }
        return parsed;
    }

    private String assetLine(String assetSummary) {
        return assetSummary == null || assetSummary.isBlank() ? "" : "\nAssets: " + assetSummary;
    }

    private String metadataLine(String metadataSummary) {
        return metadataSummary == null || metadataSummary.isBlank() ? "" : "\nMetadata: " + metadataSummary;
    }

    private static String abbreviate(String text, int visible) {
        if (text == null || text.isBlank() || "-".equals(text)) {
            return "-";
        }
        if (text.length() <= visible * 2 + 3) {
            return text;
        }
        return text.substring(0, visible) + "..." + text.substring(text.length() - visible);
    }

    private String nullSafe(String text) {
        return text == null || text.isBlank() ? "-" : text;
    }

    private String validateRequestedOutputs(List<PaymentDraftRow> payments) {
        BigInteger requestedLovelace = payments.stream()
                .map(PaymentDraftRow::lovelace)
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (requestedLovelace.compareTo(currentLovelace) > 0) {
            return "Requested ADA exceeds the active account balance before fees";
        }

        Map<String, BigInteger> availableByUnit = new LinkedHashMap<>();
        availableAssets.forEach(asset -> availableByUnit.put(asset.unit(), asset.available()));
        Map<String, BigInteger> requestedByUnit = new LinkedHashMap<>();
        payments.stream()
                .flatMap(payment -> payment.assets().stream())
                .forEach(asset -> requestedByUnit.merge(asset.unit(), asset.quantity(), BigInteger::add));

        return requestedByUnit.entrySet().stream()
                .filter(entry -> entry.getValue().compareTo(availableByUnit.getOrDefault(entry.getKey(), BigInteger.ZERO)) > 0)
                .map(entry -> "Requested asset quantity exceeds balance for " + abbreviate(entry.getKey(), 12))
                .findFirst()
                .orElse(null);
    }

    private String nextAccountName() {
        try {
            return "Account " + (Integer.parseInt(activeAccountState.get()) + 1);
        } catch (NumberFormatException e) {
            return "Account";
        }
    }

    private record UtxoRow(String outpoint, String address, String lovelace, String assets) {
    }

    private record PendingTxRow(
            String time,
            String type,
            String amount,
            String fee,
            String status,
            String txHash,
            String block) {
    }

    private record StoredWalletRow(
            String walletId,
            String seedId,
            String name,
            String networkId,
            int accountIndex,
            String baseAddress,
            String stakeAddress,
            String drepId,
            String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record AddressRow(
            String index,
            String role,
            String path,
            String baseAddress,
            String enterpriseAddress) {
    }

    private record AssetOption(
            String unit,
            BigInteger available,
            String label) {
        private String shortUnit() {
            return abbreviate(unit, 12);
        }

        private String quantity() {
            return available == null ? "0" : available.toString();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record AssetTransferDraft(
            String unit,
            BigInteger quantity) {
        @Override
        public String toString() {
            return abbreviate(unit, 10) + ":" + quantity;
        }
    }

    private record PaymentDraftRow(
            String address,
            BigInteger lovelace,
            List<AssetTransferDraft> assets) {
        private PaymentDraftRow {
            lovelace = lovelace == null ? BigInteger.ZERO : lovelace;
            assets = assets == null ? List.of() : List.copyOf(assets);
        }

        private String shortAddress() {
            return abbreviate(address, 14);
        }

        private String ada() {
            return formatLovelace(lovelace);
        }

        private String assetSummary() {
            if (assets.isEmpty()) {
                return "-";
            }
            return assets.stream()
                    .map(AssetTransferDraft::toString)
                    .collect(java.util.stream.Collectors.joining(", "));
        }
    }
}
